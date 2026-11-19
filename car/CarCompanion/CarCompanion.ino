/*
 * Co-Pilot: a push-to-talk AI voice assistant for the car, on an ESP32-S3.
 *
 *   Hold the button and speak -> Groq Whisper turns it into text
 *   -> Claude answers (with web search for weather, news, opening times...)
 *   -> Groq Orpheus speaks the answer through a small amplifier and speaker.
 *
 * It goes online through your phone's hotspot. Everything is set up from a web page the
 * ESP32 shows on its own Wi-Fi ("CoPilot-Setup"), so no keys live in this code.
 * Wiring and setup: car/README.md.
 *
 * Board: ESP32-S3 with PSRAM (e.g. ESP32-S3-DevKitC-1 N16R8). Arduino core for ESP32 3.x.
 * Library: ArduinoJson 7.
 */
#include <WiFi.h>
#include <WiFiMulti.h>
#include <WiFiClientSecure.h>
#include <HTTPClient.h>
#include <WebServer.h>
#include <Preferences.h>
#include <LittleFS.h>
#include <ArduinoJson.h>
#include <ESP_I2S.h>
#include <time.h>
#include <vector>
#include "certs.h"

// ---------------------------------------------------------------- wiring
// INMP441 microphone (L/R pin to GND)
const int MIC_SCK = 5;
const int MIC_WS  = 6;
const int MIC_SD  = 4;
// MAX98357A amplifier
const int SPK_BCLK = 15;
const int SPK_LRC  = 16;
const int SPK_DIN  = 7;
// Push-to-talk button: between this pin and GND
const int BUTTON = 1;

// ---------------------------------------------------------------- tuning
const char* ASSISTANT_NAME = "Co-Pilot";
const char* CLAUDE_MODEL = "claude-opus-5-5";
const char* GROQ_CHAT_MODEL = "openai/gpt-oss-20b";     // used only if Claude can't answer
const int REC_RATE = 16000;
const int MAX_REC_SECONDS = 20;
const int MIC_SHIFT = 14;           // 24-bit mic -> 16-bit; lower = louder (try 13 in a noisy car)
const int MAX_TURNS = 16;           // remembered messages (8 back-and-forths)
const uint32_t NEW_TRIP_MS = 20UL * 60 * 1000;   // forget the conversation after 20 min of quiet
const char* SETUP_AP = "CoPilot-Setup";
const char* SETUP_PASS = "copilot123";

struct Zone { const char* label; const char* posix; const char* iana; };
const Zone ZONES[] = {
  {"UK & Ireland",   "GMT0BST,M3.5.0/1,M10.5.0",     "Europe/London"},
  {"Central Europe", "CET-1CEST,M3.5.0,M10.5.0/3",   "Europe/Paris"},
  {"US Eastern",     "EST5EDT,M3.2.0,M11.1.0",       "America/New_York"},
  {"US Central",     "CST6CDT,M3.2.0,M11.1.0",       "America/Chicago"},
  {"US Mountain",    "MST7MDT,M3.2.0,M11.1.0",       "America/Denver"},
  {"US Pacific",     "PST8PDT,M3.2.0,M11.1.0",       "America/Los_Angeles"},
  {"Sydney",         "AEST-10AEDT,M10.1.0,M4.1.0/3", "Australia/Sydney"},
};
const int ZONE_COUNT = sizeof(ZONES) / sizeof(ZONES[0]);
const char* VOICES[] = {"troy", "austin", "daniel", "autumn", "diana", "hannah"};
const int VOICE_COUNT = sizeof(VOICES) / sizeof(VOICES[0]);

// ---------------------------------------------------------------- settings (saved in flash)
struct Settings {
  String ssid1, pass1, ssid2, pass2;
  String claudeKey, groqKey;
  String voice = "troy";
  String city, country = "GB";
  int zone = 0;
  int volume = 70;
} cfg;
Preferences prefs;

void loadSettings() {
  prefs.begin("copilot", true);
  cfg.ssid1 = prefs.getString("ssid1", "");
  cfg.pass1 = prefs.getString("pass1", "");
  cfg.ssid2 = prefs.getString("ssid2", "");
  cfg.pass2 = prefs.getString("pass2", "");
  cfg.claudeKey = prefs.getString("claude", "");
  cfg.groqKey = prefs.getString("groq", "");
  cfg.voice = prefs.getString("voice", "troy");
  cfg.city = prefs.getString("city", "");
  cfg.country = prefs.getString("country", "GB");
  cfg.zone = constrain(prefs.getInt("zone", 0), 0, ZONE_COUNT - 1);
  cfg.volume = constrain(prefs.getInt("volume", 70), 5, 100);
  prefs.end();
}

void saveVolume() {
  prefs.begin("copilot", false);
  prefs.putInt("volume", cfg.volume);
  prefs.end();
}

// ---------------------------------------------------------------- status light (on-board RGB LED)
void led(uint8_t r, uint8_t g, uint8_t b) {
#ifdef RGB_BUILTIN
  rgbLedWrite(RGB_BUILTIN, r / 8, g / 8, b / 8);   // dim: it's in a car at night
#endif
}

// ---------------------------------------------------------------- audio out
I2SClass spk;
I2SClass mic;
uint32_t spkRate = 0;
volatile bool spkAbort = false;     // set when the driver presses the button mid-sentence

void speakerRate(uint32_t rate) {
  if (rate == spkRate) return;
  spk.end();
  spk.setPins(SPK_BCLK, SPK_LRC, SPK_DIN);
  if (!spk.begin(I2S_MODE_STD, rate, I2S_DATA_BIT_WIDTH_16BIT, I2S_SLOT_MODE_MONO)) {
    Serial.println("Speaker failed to start");
    spkRate = 0;
    return;
  }
  spkRate = rate;
}

float gain() { float v = cfg.volume / 100.0f; return v * v; }   // ears hear volume roughly squared

void silence(int ms) {
  static int16_t zeros[256] = {0};
  int n = spkRate * ms / 1000;
  while (n > 0) { int c = min(n, 256); spk.write((uint8_t*)zeros, c * 2); n -= c; }
}

void playTone(float freq, int ms, float level = 0.35f) {
  speakerRate(24000);
  const int total = spkRate * ms / 1000, fade = spkRate / 200;
  int16_t buf[256];
  float phase = 0, step = 2 * PI * freq / spkRate;
  for (int i = 0; i < total;) {
    int c = min(total - i, 256);
    for (int k = 0; k < c; k++, i++) {
      float env = min(1.0f, min((float)i / fade, (float)(total - i) / fade));
      buf[k] = (int16_t)(sinf(phase) * 32767 * level * gain() * env);
      phase += step;
    }
    spk.write((uint8_t*)buf, c * 2);
  }
  silence(15);           // end on silence so the amplifier doesn't repeat the last buffer
}

void beepListen()  { playTone(880, 70); playTone(1320, 90); }
void beepDone()    { playTone(1320, 60); playTone(880, 80); silence(40); }
void beepError()   { playTone(330, 150); silence(80); playTone(262, 250); silence(40); }

/** Plays a WAV as it arrives (from the internet or a file). Only 16-bit PCM, which is what Orpheus sends. */
class WavPlayer : public Stream {
 public:
  uint8_t head[512];
  size_t headLen = 0;
  bool playing = false, failed = false;
  int channels = 1;
  uint8_t carry = 0; bool hasCarry = false;

  void reset() { headLen = 0; playing = false; failed = false; channels = 1; hasCarry = false; }

  size_t write(uint8_t b) override { return write(&b, 1); }
  size_t write(const uint8_t* data, size_t len) override {
    if (spkAbort || failed) return 0;              // stops the download too
    if (!playing) {
      size_t take = min(len, sizeof(head) - headLen);
      memcpy(head + headLen, data, take);
      headLen += take;
      int start = parseHeader();
      if (start < 0) {                              // header not complete yet
        if (headLen == sizeof(head)) { failed = true; return 0; }
        return len;
      }
      playing = true;
      pcm(head + start, headLen - start);
      if (take < len) pcm(data + take, len - take);
      return len;
    }
    pcm(data, len);
    return spkAbort ? 0 : len;
  }
  int available() override { return 0; }
  int read() override { return -1; }
  int peek() override { return -1; }

  void finish() { if (playing && !spkAbort) silence(120); }   // let the last syllable out of the DMA buffer

 private:
  int parseHeader() {
    if (headLen < 12) return -1;
    if (memcmp(head, "RIFF", 4) || memcmp(head + 8, "WAVE", 4)) { failed = true; return -1; }
    size_t pos = 12;
    uint32_t rate = 24000; int bits = 16;
    while (pos + 8 <= headLen) {
      uint32_t len = head[pos + 4] | head[pos + 5] << 8 | head[pos + 6] << 16 | (uint32_t)head[pos + 7] << 24;
      if (!memcmp(head + pos, "fmt ", 4)) {
        if (pos + 24 > headLen) return -1;
        channels = head[pos + 10] | head[pos + 11] << 8;
        rate = head[pos + 12] | head[pos + 13] << 8 | head[pos + 14] << 16 | (uint32_t)head[pos + 15] << 24;
        bits = head[pos + 22] | head[pos + 23] << 8;
      } else if (!memcmp(head + pos, "data", 4)) {
        if (bits != 16 || channels < 1 || channels > 2 || rate < 8000 || rate > 48000) { failed = true; return -1; }
        speakerRate(rate);
        return pos + 8;
      }
      if (len > 0x10000) { failed = true; return -1; }
      pos += 8 + len + (len & 1);
    }
    return -1;
  }

  void pcm(const uint8_t* data, size_t len) {
    static int16_t out[256];
    int n = 0;
    float g = gain();
    const int frame = 2 * channels;
    // samples can be split between network packets: keep the odd byte for next time
    size_t i = 0;
    uint8_t tmp[4];
    auto flush = [&]() { spk.write((uint8_t*)out, n * 2); n = 0; };
    if (hasCarry && len > 0) {
      tmp[0] = carry; tmp[1] = data[0];
      int16_t s = (int16_t)(tmp[0] | tmp[1] << 8);
      if (channels == 1) out[n++] = (int16_t)(s * g);
      i = 1; hasCarry = false;
    }
    for (; i + frame <= len; i += frame) {
      int32_t s = (int16_t)(data[i] | data[i + 1] << 8);
      if (channels == 2) s = (s + (int16_t)(data[i + 2] | data[i + 3] << 8)) / 2;
      out[n++] = (int16_t)(s * g);
      if (n == 256) { flush(); if (digitalRead(BUTTON) == LOW) { spkAbort = true; return; } }
    }
    if (channels == 1 && i < len) { carry = data[i]; hasCarry = true; }
    if (n) flush();
  }
};
WavPlayer player;

// soft "thinking" ticks while waiting for an answer, so the driver knows it's working
volatile bool thinking = false, tickBusy = false;
void tickTask(void*) {
  for (;;) {
    vTaskDelay(pdMS_TO_TICKS(1400));
    if (!thinking) continue;
    tickBusy = true;
    if (thinking) { playTone(660, 35, 0.12f); silence(20); }
    tickBusy = false;
  }
}
void startThinking() { thinking = true; }
void stopThinking() { thinking = false; while (tickBusy) delay(5); }

// ---------------------------------------------------------------- HTTPS
WiFiClientSecure tls;

bool online() { return WiFi.status() == WL_CONNECTED; }

bool openHttps(HTTPClient& http, const char* url, uint16_t timeoutMs) {
  tls.setCACert(ROOT_CAS);
  http.setReuse(false);
  http.setConnectTimeout(8000);
  http.setTimeout(timeoutMs);
  return http.begin(tls, url);
}

String lastProblem;

String apiError(int code, const String& body) {
  JsonDocument d;
  String msg;
  if (!deserializeJson(d, body)) msg = d["error"]["message"] | "";
  String out = "HTTP " + String(code);
  if (msg.length()) out += ": " + msg;
  return out;
}

// ---------------------------------------------------------------- hearing (Groq Whisper)
int16_t* recBuf = nullptr;
size_t recLen = 0;

/** Records while the button is held. Returns the number of samples. */
size_t record() {
  recLen = 0;
  mic.setPins(MIC_SCK, MIC_WS, -1, MIC_SD);
  if (!mic.begin(I2S_MODE_STD, REC_RATE, I2S_DATA_BIT_WIDTH_32BIT, I2S_SLOT_MODE_MONO, I2S_STD_SLOT_LEFT)) {
    Serial.println("Mic failed to start");
    return 0;
  }
  int32_t raw[256];
  // the mic needs a moment to settle after starting
  for (int i = 0; i < REC_RATE / 20 / 256 + 1; i++) mic.readBytes((char*)raw, sizeof(raw));
  float prevX = 0, prevY = 0;
  const size_t maxLen = (size_t)REC_RATE * MAX_REC_SECONDS;
  while (digitalRead(BUTTON) == LOW && recLen < maxLen) {
    size_t n = mic.readBytes((char*)raw, sizeof(raw)) / 4;
    for (size_t i = 0; i < n && recLen < maxLen; i++) {
      float x = (float)(raw[i] >> MIC_SHIFT);
      float y = x - prevX + 0.995f * prevY;     // removes the mic's DC offset
      prevX = x; prevY = y;
      recBuf[recLen++] = (int16_t)constrain((int)y, -32768, 32767);
    }
  }
  mic.end();
  return recLen;
}

static const char* NOISE[] = {"thank you", "thank you.", "thanks for watching", "thanks for watching!",
                              "thank you for watching.", "you", "bye", "bye.", ".", "uh", "um", "okay", "okay."};

String transcribe() {
  if (cfg.groqKey.isEmpty()) { lastProblem = "No Groq key set"; return ""; }
  const String boundary = "----copilot" + String(millis());
  String pre;
  auto field = [&](const char* name, const String& value) {
    pre += "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n";
  };
  field("model", "whisper-large-v3-turbo");
  field("response_format", "json");
  field("temperature", "0");
  if (cfg.country == "GB" || cfg.country == "US" || cfg.country == "AU" || cfg.country == "IE") field("language", "en");
  pre += "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\nContent-Type: audio/wav\r\n\r\n";
  const String post = "\r\n--" + boundary + "--\r\n";

  const uint32_t data = recLen * 2;
  const size_t total = pre.length() + 44 + data + post.length();
  uint8_t* body = (uint8_t*)ps_malloc(total);
  if (!body) { lastProblem = "Out of memory"; return ""; }
  uint8_t* p = body;
  memcpy(p, pre.c_str(), pre.length()); p += pre.length();
  auto u32 = [&](uint32_t v) { for (int i = 0; i < 4; i++) *p++ = v >> (8 * i); };
  auto u16 = [&](uint16_t v) { *p++ = v; *p++ = v >> 8; };
  memcpy(p, "RIFF", 4); p += 4; u32(36 + data);
  memcpy(p, "WAVEfmt ", 8); p += 8; u32(16); u16(1); u16(1); u32(REC_RATE); u32(REC_RATE * 2); u16(2); u16(16);
  memcpy(p, "data", 4); p += 4; u32(data);
  memcpy(p, recBuf, data); p += data;
  memcpy(p, post.c_str(), post.length());

  HTTPClient http;
  String text;
  if (openHttps(http, "https://api.groq.com/openai/v1/audio/transcriptions", 20000)) {
    http.addHeader("Authorization", "Bearer " + cfg.groqKey);
    http.addHeader("Content-Type", "multipart/form-data; boundary=" + boundary);
    int code = http.POST(body, total);
    String res = http.getString();
    if (code == 200) {
      JsonDocument d;
      if (!deserializeJson(d, res)) text = String((const char*)(d["text"] | ""));
      text.trim();
    } else {
      lastProblem = "Hearing failed, " + apiError(code, res);
    }
    http.end();
  } else {
    lastProblem = "Couldn't reach Groq";
  }
  free(body);
  String low = text; low.toLowerCase();
  for (auto n : NOISE) if (low == n) return "";
  return text;
}

// ---------------------------------------------------------------- thinking (Claude, with Groq as a spare)
struct Turn { String role, text; };
std::vector<Turn> history;
uint32_t lastTalk = 0;

String timeNow() {
  struct tm t;
  if (!getLocalTime(&t, 50)) return "";
  char buf[48];
  strftime(buf, sizeof(buf), "%A %d %B %Y, %H:%M", &t);
  return buf;
}

String systemPrompt() {
  String s = String("You are ") + ASSISTANT_NAME + ", a voice assistant in a car, talking with the driver. "
    "Everything you write is read aloud by a text-to-speech voice, so answer in plain spoken sentences: "
    "no lists, headings, markdown, emojis, links or symbols, and write numbers the way you'd say them. "
    "Keep it short: one to three sentences unless the driver asks for more detail. "
    "The driver must keep their eyes on the road and hands on the wheel, so never ask them to read, look at a screen, "
    "type or tap anything; anything like that can wait until they've parked. "
    "If they sound tired or say they're sleepy, gently suggest a break at the next safe place. "
    "You can't control the car, the phone, the music or the sat-nav; if asked, say so in a few words. "
    "Use web search for anything current, such as weather, traffic news, opening times, prices, sport or news, "
    "and give the answer, not the sources. "
    "If you didn't understand, it may be road noise: just ask them to say it again.";
  if (cfg.city.length()) s += " The car is near " + cfg.city + ".";
  String now = timeNow();
  if (now.length()) s += " It is " + now + ".";
  return s;
}

void addMessages(JsonArray msgs, const String& userText) {
  for (auto& t : history) {
    JsonObject m = msgs.add<JsonObject>();
    m["role"] = t.role;
    m["content"] = t.text;
  }
  JsonObject m = msgs.add<JsonObject>();
  m["role"] = "user";
  m["content"] = userText;
}

String askClaude(const String& userText) {
  if (cfg.claudeKey.isEmpty()) return "";
  JsonDocument req;
  req["model"] = CLAUDE_MODEL;
  req["max_tokens"] = 4000;
  req["output_config"]["effort"] = "low";        // quick answers: the driver is waiting
  req["fallbacks"] = "default";                   // if declined, the API retries on another model
  req["system"] = systemPrompt();
  JsonObject search = req["tools"].to<JsonArray>().add<JsonObject>();
  search["type"] = "web_search_20260209";
  search["name"] = "web_search";
  search["max_uses"] = 2;
  JsonObject loc = search["user_location"].to<JsonObject>();
  loc["type"] = "approximate";
  if (cfg.city.length()) loc["city"] = cfg.city;
  if (cfg.country.length() == 2) loc["country"] = cfg.country;
  loc["timezone"] = ZONES[cfg.zone].iana;
  addMessages(req["messages"].to<JsonArray>(), userText);
  String body;
  serializeJson(req, body);

  HTTPClient http;
  if (!openHttps(http, "https://api.anthropic.com/v1/messages", 60000)) { lastProblem = "Couldn't reach Claude"; return ""; }
  http.addHeader("Content-Type", "application/json");
  http.addHeader("x-api-key", cfg.claudeKey);
  http.addHeader("anthropic-version", "2026-10-21");
  http.addHeader("anthropic-beta", "server-side-fallback-2026-07-01");
  int code = http.POST(body);
  String res = http.getString();
  http.end();
  if (code != 200) { lastProblem = "Claude " + apiError(code, res); return ""; }

  JsonDocument filter;
  filter["content"][0]["type"] = true;
  filter["content"][0]["text"] = true;
  filter["stop_reason"] = true;
  JsonDocument d;
  if (deserializeJson(d, res, DeserializationOption::Filter(filter))) { lastProblem = "Claude reply unreadable"; return ""; }
  if (d["stop_reason"] == "refusal") return "Sorry, that's not something I can help with.";
  String out;
  for (JsonObject block : d["content"].as<JsonArray>())
    if (block["type"] == "text") out += (const char*)(block["text"] | "");
  out.trim();
  if (out.isEmpty()) lastProblem = "Claude gave an empty answer";
  return out;
}

String askGroq(const String& userText) {
  if (cfg.groqKey.isEmpty()) return "";
  JsonDocument req;
  req["model"] = GROQ_CHAT_MODEL;
  req["max_completion_tokens"] = 800;
  req["reasoning_effort"] = "low";
  req["include_reasoning"] = false;
  JsonArray msgs = req["messages"].to<JsonArray>();
  JsonObject sys = msgs.add<JsonObject>();
  sys["role"] = "system";
  sys["content"] = systemPrompt() + " You can't search the web right now, so say if you don't know something current.";
  addMessages(msgs, userText);
  String body;
  serializeJson(req, body);

  HTTPClient http;
  if (!openHttps(http, "https://api.groq.com/openai/v1/chat/completions", 30000)) return "";
  http.addHeader("Content-Type", "application/json");
  http.addHeader("Authorization", "Bearer " + cfg.groqKey);
  int code = http.POST(body);
  String res = http.getString();
  http.end();
  if (code != 200) { lastProblem += " / Groq " + apiError(code, res); return ""; }
  JsonDocument d;
  if (deserializeJson(d, res)) return "";
  String out = String((const char*)(d["choices"][0]["message"]["content"] | ""));
  out.trim();
  return out;
}

String think(const String& userText) {
  String reply = askClaude(userText);
  if (reply.isEmpty()) reply = askGroq(userText);
  if (reply.length()) {
    history.push_back({"user", userText});
    history.push_back({"assistant", reply});
    while (history.size() > MAX_TURNS) history.erase(history.begin(), history.begin() + 2);
  }
  return reply;
}

// ---------------------------------------------------------------- speaking (Groq Orpheus)
bool ttsTo(const String& text, Stream* out) {
  if (cfg.groqKey.isEmpty()) { lastProblem = "No Groq key set"; return false; }
  JsonDocument req;
  req["model"] = "canopylabs/orpheus-v1-english";
  req["voice"] = cfg.voice;
  req["input"] = text;
  req["response_format"] = "wav";
  String body;
  serializeJson(req, body);
  HTTPClient http;
  if (!openHttps(http, "https://api.groq.com/openai/v1/audio/speech", 20000)) { lastProblem = "Couldn't reach Groq voice"; return false; }
  http.addHeader("Content-Type", "application/json");
  http.addHeader("Authorization", "Bearer " + cfg.groqKey);
  int code = http.POST(body);
  bool ok = false;
  if (code == 200) {
    int n = http.writeToStream(out);
    ok = n > 0 || spkAbort;
  } else {
    lastProblem = "Voice " + apiError(code, http.getString());
  }
  http.end();
  return ok;
}

/** Orpheus takes short inputs: cut the reply into pieces at sentence ends. */
std::vector<String> pieces(const String& text) {
  std::vector<String> out;
  const unsigned LIMIT = 180;
  String cur;
  int start = 0;
  for (int i = 0; i <= (int)text.length(); i++) {
    bool end = i == (int)text.length() ||
               ((text[i] == '.' || text[i] == '!' || text[i] == '?') && (i + 1 == (int)text.length() || text[i + 1] == ' '));
    if (!end) continue;
    String s = text.substring(start, min(i + 1, (int)text.length()));
    s.trim();
    start = i + 1;
    if (s.isEmpty()) continue;
    while (s.length() > LIMIT) {              // one very long sentence: break at a comma or space
      int cut = s.lastIndexOf(", ", LIMIT);
      if (cut < 40) cut = s.lastIndexOf(' ', LIMIT);
      if (cut < 1) cut = LIMIT;
      if (cur.length()) { out.push_back(cur); cur = ""; }
      out.push_back(s.substring(0, cut + 1));
      s = s.substring(cut + 1); s.trim();
    }
    if (cur.length() + 1 + s.length() > LIMIT && cur.length()) { out.push_back(cur); cur = ""; }
    if (cur.length()) cur += ' ';
    cur += s;
  }
  if (cur.length()) out.push_back(cur);
  return out;
}

bool playFile(const char* path) {
  File f = LittleFS.open(path, "r");
  if (!f) return false;
  player.reset();
  uint8_t buf[1024];
  int n;
  while (!spkAbort && (n = f.read(buf, sizeof(buf))) > 0) if (player.write(buf, n) == 0) break;
  player.finish();
  f.close();
  return player.playing;
}

// short phrases saved to flash the first time, so they still work with no signal
struct Phrase { const char* path; const char* text; };
const Phrase READY   = {"/ready.wav",   "Co-pilot here. Hold the button and ask me anything."};
const Phrase OFFLINE = {"/offline.wav", "I've lost my internet connection. Try again when there's signal."};
const Phrase TROUBLE = {"/trouble.wav", "Sorry, I couldn't get an answer just then. Please try again."};
const Phrase PARDON  = {"/pardon.wav",  "Sorry, I didn't catch that."};

void cachePhrase(const Phrase& p) {
  if (LittleFS.exists(p.path) || !online()) return;
  File f = LittleFS.open(p.path, "w");
  if (!f) return;
  bool ok = ttsTo(p.text, &f);
  size_t size = f.size();
  f.close();
  if (!ok || size < 1000) LittleFS.remove(p.path);
}

void sayPhrase(const Phrase& p) {
  spkAbort = false;
  if (!playFile(p.path)) beepError();
}

/** Speaks text; returns false if the voice failed before saying anything. */
bool speak(const String& text) {
  led(0, 255, 0);
  spkAbort = false;
  bool any = false;
  for (auto& piece : pieces(text)) {
    if (spkAbort) break;
    player.reset();
    if (ttsTo(piece, &player) && player.playing) any = true;
    else if (!spkAbort) break;
    player.finish();
  }
  return any || spkAbort;
}

// ---------------------------------------------------------------- setup page
WebServer server(80);

String esc(const String& s) {
  String o;
  for (char c : s) {
    if (c == '&') o += "&amp;"; else if (c == '<') o += "&lt;"; else if (c == '>') o += "&gt;";
    else if (c == '"') o += "&quot;"; else o += c;
  }
  return o;
}

void handleRoot() {
  String h = F("<!doctype html><html><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
               "<title>Co-Pilot setup</title><style>body{font-family:sans-serif;max-width:480px;margin:auto;padding:16px;background:#111;color:#eee}"
               "label{display:block;margin-top:14px;font-size:14px;color:#aaa}input,select{width:100%;padding:10px;font-size:16px;box-sizing:border-box;"
               "background:#222;color:#eee;border:1px solid #444;border-radius:6px}button{margin-top:22px;width:100%;padding:14px;font-size:18px;"
               "background:#2a7;color:#fff;border:0;border-radius:8px}h2{margin-top:28px}small{color:#888}</style></head><body>"
               "<h1>Co-Pilot setup</h1><form method=post action=/save>"
               "<h2>Internet</h2><small>Your phone's hotspot, and optionally your home Wi-Fi.</small>");
  h += "<label>Hotspot name</label><input name=ssid1 value=\"" + esc(cfg.ssid1) + "\">";
  h += "<label>Hotspot password</label><input name=pass1 type=password placeholder='" + String(cfg.pass1.length() ? "saved (leave blank to keep)" : "") + "'>";
  h += "<label>Second Wi-Fi name (optional)</label><input name=ssid2 value=\"" + esc(cfg.ssid2) + "\">";
  h += "<label>Second Wi-Fi password</label><input name=pass2 type=password placeholder='" + String(cfg.pass2.length() ? "saved (leave blank to keep)" : "") + "'>";
  h += F("<h2>Keys</h2><small>Claude does the thinking; Groq does the hearing and the voice (free tier is fine).</small>");
  h += "<label>Claude API key</label><input name=claude type=password placeholder='" + String(cfg.claudeKey.length() ? "saved (leave blank to keep)" : "sk-ant-...") + "'>";
  h += "<label>Groq API key</label><input name=groq type=password placeholder='" + String(cfg.groqKey.length() ? "saved (leave blank to keep)" : "gsk_...") + "'>";
  h += F("<h2>About you</h2><label>Voice</label><select name=voice>");
  for (int i = 0; i < VOICE_COUNT; i++)
    h += "<option" + String(cfg.voice == VOICES[i] ? " selected" : "") + ">" + VOICES[i] + "</option>";
  h += F("</select><label>Time zone</label><select name=zone>");
  for (int i = 0; i < ZONE_COUNT; i++)
    h += "<option value=" + String(i) + (cfg.zone == i ? " selected" : "") + ">" + ZONES[i].label + "</option>";
  h += "</select><label>Home town (for local weather and places)</label><input name=city value=\"" + esc(cfg.city) + "\">";
  h += "<label>Country code (GB, US, IE...)</label><input name=country maxlength=2 value=\"" + esc(cfg.country) + "\">";
  h += "<label>Volume (5-100)</label><input name=volume type=number min=5 max=100 value=" + String(cfg.volume) + ">";
  h += F("<button>Save and restart</button></form></body></html>");
  server.send(200, "text/html", h);
}

void handleSave() {
  prefs.begin("copilot", false);
  auto keep = [&](const char* arg, const char* key) {   // blank secret fields keep the saved value
    String v = server.arg(arg); v.trim();
    if (v.length()) prefs.putString(key, v);
  };
  String ssid1 = server.arg("ssid1"); ssid1.trim();
  String ssid2 = server.arg("ssid2"); ssid2.trim();
  prefs.putString("ssid1", ssid1);
  prefs.putString("ssid2", ssid2);
  keep("pass1", "pass1");
  keep("pass2", "pass2");
  if (ssid2.isEmpty()) prefs.putString("pass2", "");
  keep("claude", "claude");
  keep("groq", "groq");
  prefs.putString("voice", server.arg("voice"));
  prefs.putInt("zone", server.arg("zone").toInt());
  String city = server.arg("city"); city.trim();
  prefs.putString("city", city);
  String country = server.arg("country"); country.trim(); country.toUpperCase();
  prefs.putString("country", country);
  prefs.putInt("volume", constrain(server.arg("volume").toInt(), 5, 100));
  prefs.end();
  // the voice may have changed: say the saved phrases again in the new voice
  for (auto p : {READY.path, OFFLINE.path, TROUBLE.path, PARDON.path}) LittleFS.remove(p);
  server.send(200, "text/html", String(F("<meta name=viewport content='width=device-width'><body style='font-family:sans-serif;background:#111;color:#eee;padding:16px'>"
                                  "<h2>Saved.</h2>Co-Pilot is restarting. You can disconnect from CoPilot-Setup.</body>")));
  delay(1500);
  ESP.restart();
}

[[noreturn]] void setupMode() {
  Serial.println("Setup mode: join Wi-Fi 'CoPilot-Setup' (password copilot123) and open http://192.168.4.1");
  led(160, 0, 255);
  WiFi.mode(WIFI_AP);
  WiFi.softAP(SETUP_AP, SETUP_PASS);
  server.on("/", HTTP_GET, handleRoot);
  server.on("/save", HTTP_POST, handleSave);
  server.onNotFound(handleRoot);
  server.begin();
  playTone(523, 120); playTone(659, 120); playTone(784, 200);
  for (;;) { server.handleClient(); delay(2); }
}

// ---------------------------------------------------------------- main
WiFiMulti wifi;
bool greeted = false;
uint32_t lastWifiTry = 0;

void setup() {
  Serial.begin(115200);
  pinMode(BUTTON, INPUT_PULLUP);
  led(0, 0, 0);
  LittleFS.begin(true);
  loadSettings();
  speakerRate(24000);
  xTaskCreatePinnedToCore(tickTask, "ticks", 4096, nullptr, 1, nullptr, 0);

  recBuf = (int16_t*)ps_malloc((size_t)REC_RATE * MAX_REC_SECONDS * 2);
  if (!recBuf) {                       // no PSRAM: check the board settings (PSRAM: OPI)
    Serial.println("No PSRAM found");
    for (;;) { led(255, 0, 0); beepError(); delay(3000); }
  }

  // hold the button while powering on, or no Wi-Fi saved yet: open the setup page
  delay(50);
  bool held = digitalRead(BUTTON) == LOW;
  for (int i = 0; held && i < 30; i++) { delay(100); held = digitalRead(BUTTON) == LOW; }
  if (held || cfg.ssid1.isEmpty()) setupMode();

  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);
  wifi.addAP(cfg.ssid1.c_str(), cfg.pass1.c_str());
  if (cfg.ssid2.length()) wifi.addAP(cfg.ssid2.c_str(), cfg.pass2.c_str());
  configTzTime(ZONES[cfg.zone].posix, "pool.ntp.org", "time.google.com");
  led(0, 0, 40);
  Serial.println("Co-Pilot started");
}

bool handleLocalCommand(String said) {
  said.toLowerCase();
  if (said.indexOf("start over") >= 0 || said.indexOf("new conversation") >= 0 || said.indexOf("forget everything") >= 0) {
    history.clear();
    speak("Okay, fresh start.");
    return true;
  }
  bool up = said.indexOf("louder") >= 0 || said.indexOf("volume up") >= 0 || said.indexOf("turn it up") >= 0;
  bool down = said.indexOf("quieter") >= 0 || said.indexOf("volume down") >= 0 || said.indexOf("turn it down") >= 0;
  if ((up || down) && said.length() < 40) {
    cfg.volume = constrain(cfg.volume + (up ? 15 : -15), 5, 100);
    saveVolume();
    speak(up ? "Is this better?" : "Okay, quieter.");
    return true;
  }
  return false;
}

void logLine(const char* label, const String& text) {
  Serial.print(label);
  Serial.print(text);
  if (lastProblem.length()) { Serial.print("  ["); Serial.print(lastProblem); Serial.print("]"); }
  Serial.println();
}

void converse() {
  led(0, 80, 255);
  beepListen();
  size_t n = record();
  beepDone();
  if (n < REC_RATE * 4 / 10) { led(0, 0, 40); return; }          // a quick tap: nothing to hear

  if (!online()) { led(255, 0, 0); sayPhrase(OFFLINE); return; }
  if (millis() - lastTalk > NEW_TRIP_MS) history.clear();
  lastTalk = millis();

  led(255, 160, 0);
  startThinking();
  lastProblem = "";
  String said = transcribe();
  logLine("Heard: ", said);
  if (said.isEmpty()) {
    stopThinking();
    if (lastProblem.length()) sayPhrase(TROUBLE); else sayPhrase(PARDON);
    return;
  }
  if (handleLocalCommand(said)) { stopThinking(); return; }

  String reply = think(said);
  stopThinking();
  logLine("Reply: ", reply);
  if (reply.isEmpty()) { led(255, 0, 0); sayPhrase(TROUBLE); return; }
  if (!speak(reply)) { led(255, 0, 0); Serial.println(lastProblem); beepError(); }
  lastTalk = millis();
}

void loop() {
  if (!online() && millis() - lastWifiTry > 8000) {
    led(40, 0, 0);
    wifi.run(4000);
    lastWifiTry = millis();
    if (online()) Serial.println("Online via " + WiFi.SSID());
  }
  if (online() && !greeted) {
    greeted = true;
    led(0, 0, 40);
    for (auto& p : {READY, OFFLINE, TROUBLE, PARDON}) cachePhrase(p);
    sayPhrase(READY);
  }

  if (digitalRead(BUTTON) == LOW) {
    delay(25);                          // debounce
    if (digitalRead(BUTTON) == LOW) {
      spkAbort = false;
      converse();   // pressing again while it's talking stops it and listens straight away
      led(online() ? 0 : 40, 0, online() ? 40 : 0);
    }
  }
  delay(10);
}

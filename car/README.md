# Co-Pilot: an AI voice assistant for the car

A small ESP32 box for the dashboard. **Hold the button, ask a question, let go.** It answers out loud.

- **Hearing:** Groq Whisper (online speech-to-text)
- **Thinking:** Claude, with web search for anything current (weather, traffic news, opening times, scores).
  If Claude can't answer, it falls back to a Groq model.
- **Voice:** Groq Orpheus, played through a small amplifier and speaker
- **Internet:** your phone's hotspot (and optionally your home Wi-Fi when parked on the drive)

It's push-to-talk on purpose: no screen to look at, no wake word mis-firing on the radio, and it only
listens while you hold the button.

## What to buy (about £20 / $25)

| Part | Notes |
|------|-------|
| **ESP32-S3 DevKitC-1, N16R8** | Must be an S3 *with PSRAM* ("R8" = 8 MB PSRAM) |
| **INMP441** I2S microphone | Mount it facing the driver, away from the air vents |
| **MAX98357A** I2S amplifier | 3 W, drives the speaker directly |
| 4 Ω or 8 Ω speaker, 3 W | Or skip it and use a cheap 3.5 mm-to-aux lead from the amp (see below) |
| Momentary push button | A big one you can find without looking; mount it within reach |
| USB car charger + USB-C cable | Powers everything |

## Wiring

```
INMP441 mic          ESP32-S3
  VDD  ───────────── 3V3
  GND  ───────────── GND
  L/R  ───────────── GND
  SCK  ───────────── GPIO 5
  WS   ───────────── GPIO 6
  SD   ───────────── GPIO 4

MAX98357A amp        ESP32-S3
  VIN  ───────────── 5V
  GND  ───────────── GND
  BCLK ───────────── GPIO 15
  LRC  ───────────── GPIO 16
  DIN  ───────────── GPIO 7
  +/-  ───────────── speaker

Button: one leg to GPIO 1, the other to GND
```

Pins are listed at the top of `CarCompanion/CarCompanion.ino` if you need to change them.

## Putting the software on

**Easiest (no coding tools):**
1. Every push to `main` that touches `car/` builds the firmware on GitHub. Open **Releases** and download
   `CoPilot.bin` from the newest "Co-Pilot car AI build".
2. In Chrome or Edge on a computer, open **https://espressif.github.io/esptool-js/**, plug the ESP32 in by USB
   (the port labelled **USB** or **COM**), press **Connect**, and pick the port.
3. Set the flash address to **0x0**, choose `CoPilot.bin`, and press **Program**.

**With the Arduino IDE:** install the **esp32 by Espressif** boards (version 3.x) and the **ArduinoJson** library (7.x).
Open `car/CarCompanion/CarCompanion.ino` and choose board **ESP32S3 Dev Module** with
*PSRAM: OPI PSRAM*, *Flash Size: 16MB*, *Partition Scheme: Huge APP*, then Upload.

## First-time setup

1. Power it up. With nothing saved it plays three rising notes and makes its own Wi-Fi called
   **CoPilot-Setup** (password `copilot123`).
2. Join that Wi-Fi from your phone and open **http://192.168.4.1**.
3. Fill in:
   - your **phone hotspot** name and password (and your home Wi-Fi, if you like)
   - a **Claude API key** from console.anthropic.com (pay-as-you-go)
   - a **Groq API key** from console.groq.com (the free tier is enough). This is the same kind of key Pip uses.
   - the voice, your time zone, your home town and country code
4. Press **Save and restart**. When it gets online it says *"Co-pilot here…"*.

To change settings later, **hold the button while plugging it in** (keep holding for 3 seconds).

## Using it

- **Hold the button and talk**; let go when you're done. Short beeps mark the start and end.
- While it's thinking you'll hear a soft tick every second or so.
- **Press the button while it's talking** to stop it and ask something else.
- It remembers the conversation for follow-up questions, and forgets after 20 minutes of quiet (a new trip).
- Say **"start over"** to forget the conversation now.
- Say **"louder"** or **"quieter"** to change the volume.
- No signal (tunnels, the countryside): it says so, using a phrase it saved earlier.

The light on the board shows what it's doing: blue = listening, amber = thinking, green = talking,
red = a problem, purple = setup mode. You don't need to look at it while driving.

## Using the car's speakers instead

The MAX98357A is loud enough for a quiet cabin. For the car's own speakers, swap the amp for a
**PCM5102A** I2S DAC board (same three wires: BCK→15, LCK→16, DIN→7) and run a 3.5 mm lead into the aux socket.

## Costs

- **Claude:** each question costs a fraction of a penny. Questions that need a web search cost a little more.
- **Groq:** free tier. If the daily voice allowance runs out, the answer is still in the serial log, and it
  beeps instead of speaking until the allowance resets.

## Troubleshooting

Plug it into a computer and open a serial monitor at 115200 baud (Arduino IDE → Serial Monitor).
It prints what it heard, the reply, and any error, such as a rejected key.

- **Hears nothing or very quiet:** check the mic wiring, and that L/R is on GND. In a noisy car, change
  `MIC_SHIFT` from 14 to 13 for more gain.
- **"No PSRAM found" (red light, error beeps):** the board doesn't have PSRAM, or PSRAM isn't set to OPI.

## Safety

Keep it hands-free: mount the button where your hand naturally rests and never fiddle with the box while
moving. The assistant is told to keep answers short and never ask you to look at anything.

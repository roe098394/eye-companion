package com.eyecompanion

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Pip's little web page on the home Wi-Fi (http://<tablet>:8080): tell Pip things to know (with an optional
 * "until" date), see and edit the shopping list. Nothing leaves the house; facts live in Pip's settings.
 */
class Portal(private val prefs: SharedPreferences, private val host: Host) {
    /** What the page can see and do (all called from the web thread; the host hops to the main thread). */
    interface Host {
        fun status(): List<Pair<String, String>>        // label to value
        fun getShopping(): List<String>
        fun setShopping(v: List<String>)
        fun onNewFact(text: String)
        fun say(text: String): String                   // returns a note for the page
        fun action(name: String): String                 // sleep, wake, mute, unmute, volup, voldown
        fun settings(): List<Triple<String, String, List<Pair<String, String>>>>   // key, label, (value, label) choices
        fun setting(key: String): String
        fun setSetting(key: String, value: String)
        fun snapshot(): ByteArray?
    }
    data class Reminder(val id: Long, val text: String, val at: Long)
    data class Fact(val id: Long, val text: String, val until: Long, val added: Long)

    private var server: ServerSocket? = null
    val port = 8080

    // ---- facts
    fun facts(): List<Fact> {
        val arr = try { JSONArray(prefs.getString("portalFacts", "[]")) } catch (_: Exception) { JSONArray() }
        val now = System.currentTimeMillis()
        val out = ArrayList<Fact>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val f = Fact(o.optLong("id"), o.optString("text"), o.optLong("until"), o.optLong("added"))
            if (f.until == 0L || f.until > now) out.add(f)
        }
        if (out.size != arr.length()) save(out)            // forget the expired ones
        return out
    }
    private fun save(list: List<Fact>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("text", it.text).put("until", it.until).put("added", it.added)) }
        prefs.edit().putString("portalFacts", arr.toString()).apply()
    }
    /** For Pip's brain. */
    fun factsForBrain(): String {
        val f = facts(); if (f.isEmpty()) return ""
        val fmt = SimpleDateFormat("EEE d MMM", Locale.UK)
        return " Things the family have told you (true, use them when relevant): " + f.joinToString("; ") {
            it.text.trim().trimEnd('.') + (if (it.until > 0) " (until ${fmt.format(Date(it.until))})" else "")
        } + "."
    }

    // ---- reminders: Pip says them at the time
    fun reminders(): List<Reminder> {
        val arr = try { JSONArray(prefs.getString("portalReminders", "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { Reminder(it.optLong("id"), it.optString("text"), it.optLong("at")) }
    }
    private fun saveReminders(list: List<Reminder>) {
        val arr = JSONArray(); list.forEach { arr.put(JSONObject().put("id", it.id).put("text", it.text).put("at", it.at)) }
        prefs.edit().putString("portalReminders", arr.toString()).apply()
    }
    /** Reminders that are due now (removed from the list). */
    @Synchronized fun takeDue(): List<Reminder> {
        val now = System.currentTimeMillis()
        val all = reminders(); val due = all.filter { it.at <= now }
        if (due.isNotEmpty()) saveReminders(all - due.toSet())
        return due.filter { now - it.at < 3 * 3_600_000L }               // skip ones missed by hours (tablet was off)
    }

    /** 4-digit code for the camera, made once. */
    val pin: String get() = prefs.getString("portalPin", null) ?: (1000 + java.util.Random().nextInt(9000)).toString()
        .also { prefs.edit().putString("portalPin", it).apply() }

    // ---- the address to show in the menu
    fun address(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.hostAddress?.startsWith("192.168.") == true }
            ?.hostAddress?.let { "http://$it:$port" }
    } catch (_: Exception) { null }

    fun start() {
        if (server != null) return
        thread(name = "portal", isDaemon = true) {
            try {
                val s = ServerSocket(port); server = s
                while (!s.isClosed) {
                    val c = try { s.accept() } catch (_: Exception) { break }
                    thread(isDaemon = true) { try { handle(c) } catch (_: Exception) {} finally { try { c.close() } catch (_: Exception) {} } }
                }
            } catch (_: Exception) { server = null }
        }
    }
    fun stop() { try { server?.close() } catch (_: Exception) {}; server = null }

    private fun handle(c: Socket) {
        c.soTimeout = 8000
        val inp = BufferedReader(InputStreamReader(c.getInputStream(), Charsets.UTF_8))
        val first = inp.readLine() ?: return
        val (method, path) = first.split(" ").let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "/") }
        var len = 0
        while (true) {
            val h = inp.readLine() ?: break
            if (h.isEmpty()) break
            if (h.startsWith("Content-Length:", true)) len = h.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        val body = if (method == "POST" && len in 1..20_000) CharArray(len).let { buf -> var r = 0; while (r < len) { val k = inp.read(buf, r, len - r); if (k < 0) break; r += k }; String(buf, 0, r) } else ""
        val form = body.split("&").mapNotNull { kv -> kv.split("=", limit = 2).takeIf { it.size == 2 }?.let { URLDecoder.decode(it[0], "UTF-8") to URLDecoder.decode(it[1], "UTF-8") } }.toMap()
        val out = c.getOutputStream()
        fun send(code: String, type: String, text: String, extra: String = "") {
            val bytes = text.toByteArray(Charsets.UTF_8)
            out.write(("HTTP/1.1 $code\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\n$extra" +
                "Connection: close\r\n\r\n").toByteArray()); out.write(bytes); out.flush()
        }
        fun back() = send("303 See Other", "text/plain", "", "Location: /\r\n")
        when {
            method == "POST" && path == "/fact" -> {
                val text = form["text"]?.trim()?.take(300).orEmpty()
                if (text.isNotEmpty()) {
                    val until = form["until"]?.takeIf { it.isNotBlank() }?.let {
                        try { SimpleDateFormat("yyyy-MM-dd", Locale.UK).parse(it)?.time?.plus(86_400_000L - 1) } catch (_: Exception) { null }
                    } ?: 0L
                    val now = System.currentTimeMillis()
                    save(facts() + Fact(now, text, until, now)); host.onNewFact(text)
                }
                back()
            }
            method == "POST" && path == "/forget" -> { val id = form["id"]?.toLongOrNull(); save(facts().filterNot { it.id == id }); back() }
            method == "POST" && path == "/shop" -> {
                val items = form["item"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
                if (items.isNotEmpty()) host.setShopping((host.getShopping() + items).distinct().takeLast(25)); back()
            }
            method == "POST" && path == "/unshop" -> { val it0 = form["item"].orEmpty(); host.setShopping(host.getShopping().filterNot { it == it0 }); back() }
            method == "POST" && path == "/say" -> {
                val t = form["text"]?.trim()?.take(300).orEmpty()
                flash = if (t.isNotEmpty()) host.say(t) else ""; back()
            }
            method == "POST" && path == "/remind" -> {
                val t = form["text"]?.trim()?.take(300).orEmpty()
                val at = try { SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.UK).parse(form["at"].orEmpty())?.time } catch (_: Exception) { null }
                if (t.isNotEmpty() && at != null) {
                    synchronized(this) { saveReminders((reminders() + Reminder(System.currentTimeMillis(), t, at)).sortedBy { it.at }) }
                    flash = "Reminder set for ${SimpleDateFormat("EEE HH:mm", Locale.UK).format(Date(at))}."
                } else flash = "Pick a time for the reminder."
                back()
            }
            method == "POST" && path == "/unremind" -> { val id = form["id"]?.toLongOrNull(); synchronized(this) { saveReminders(reminders().filterNot { it.id == id }) }; back() }
            method == "POST" && path == "/do" -> { flash = host.action(form["a"].orEmpty()); back() }
            method == "POST" && path == "/set" -> { form["k"]?.let { k -> host.setSetting(k, form["v"].orEmpty()) }; flash = "Saved."; back() }
            method == "GET" && path.startsWith("/cam.jpg") -> {
                if (query(path)["pin"] != pin) { send("403 Forbidden", "text/plain", "Wrong code."); return }
                val jpg = host.snapshot()
                if (jpg == null) { send("503 Service Unavailable", "text/plain", "Camera busy."); return }
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpg.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
                out.write(jpg); out.flush()
            }
            method == "GET" && path.startsWith("/cam") -> send("200 OK", "text/html; charset=utf-8", camPage(query(path)["pin"].orEmpty()))
            method == "GET" && (path == "/" || path.startsWith("/?")) -> send("200 OK", "text/html; charset=utf-8", page())
            else -> send("404 Not Found", "text/plain", "Not here.")
        }
    }

    @Volatile private var flash = ""
    private fun query(path: String): Map<String, String> = path.substringAfter('?', "").split("&").mapNotNull { kv ->
        kv.split("=", limit = 2).takeIf { it.size == 2 }?.let { URLDecoder.decode(it[0], "UTF-8") to URLDecoder.decode(it[1], "UTF-8") } }.toMap()

    private fun camPage(code: String): String {
        val ok = code == pin
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Pip camera</title><style>body{margin:0;background:#0d1114;color:#e8f6fb;font:16px system-ui,sans-serif;text-align:center}
main{max-width:720px;margin:0 auto;padding:16px}img{width:100%;border-radius:14px;background:#161c21;min-height:200px}
input,button{font:inherit;padding:10px 14px;border-radius:10px;border:1px solid #2b3a42;background:#0d1418;color:#e8f6fb}
button{background:#5ce1ff;color:#062028;border:0;font-weight:600}a{color:#5ce1ff}p{color:#8aa3ad}</style></head><body><main>
<h2>📷 Pip's view</h2>
""" + (if (!ok) """<form method="get" action="/cam"><p>Enter the camera code (shown in Pip's menu under "Pip's web page").</p>
<input name="pin" inputmode="numeric" maxlength="4" autofocus> <button>Show</button></form>"""
        else """<img id="v" alt="Pip's camera"><p id="s">Refreshing every 3 seconds. Nothing is saved.</p>
<script>const v=document.getElementById('v');let busy=false;function load(){if(busy)return;busy=true;const i=new Image();
i.onload=()=>{v.src=i.src;busy=false};i.onerror=()=>{busy=false};i.src='/cam.jpg?pin=$code&t='+Date.now()}load();setInterval(load,3000);</script>""") +
"""<p><a href="/">← Back</a></p></main></body></html>"""
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun page(): String {
        val fmt = SimpleDateFormat("EEE d MMM", Locale.UK)
        val facts = facts().sortedByDescending { it.added }
        val shop = host.getShopping()
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Pip</title><style>
:root{--bg:#0d1114;--card:#161c21;--ink:#e8f6fb;--mute:#8aa3ad;--cyan:#5ce1ff;--red:#e8433a}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.45 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
main{max-width:620px;margin:0 auto;padding:18px 16px 40px}h1{margin:6px 0 2px;font-size:26px}h1 span{color:var(--cyan)}
.sub{color:var(--mute);margin:0 0 18px}.card{background:var(--card);border-radius:16px;padding:16px;margin:14px 0;border:1px solid #233038}
h2{font-size:17px;margin:0 0 10px;color:var(--cyan)}form.add{display:flex;flex-wrap:wrap;gap:8px}
textarea,input{font:inherit;color:var(--ink);background:#0d1418;border:1px solid #2b3a42;border-radius:10px;padding:10px}
textarea{width:100%;min-height:70px;resize:vertical}input[type=text]{flex:1;min-width:0}
button{font:inherit;border:0;border-radius:10px;padding:10px 16px;background:var(--cyan);color:#062028;font-weight:600;cursor:pointer}
button.x{background:none;color:var(--mute);padding:4px 8px;font-size:20px;line-height:1}button.x:hover{color:var(--red)}
ul{list-style:none;margin:0;padding:0}li{display:flex;align-items:flex-start;gap:8px;padding:10px 0;border-top:1px solid #222c33}
li:first-child{border-top:0}li .t{flex:1}li .m{color:var(--mute);font-size:13px}.row{display:flex;gap:8px;align-items:center;width:100%}
label{color:var(--mute);font-size:14px}.empty{color:var(--mute)}
</style></head><body><main>
<h1>👀 <span>Pip</span></h1>
${flash.takeIf { it.isNotEmpty() }?.let { f -> flash = ""; "<div class=\"card\" style=\"border-color:var(--cyan)\">${esc(f)}</div>" } ?: ""}
<div class="card"><h2>Right now</h2><table style="width:100%;border-collapse:collapse">${host.status().joinToString("") { (k, v) ->
    "<tr><td style=\"color:var(--mute);padding:4px 10px 4px 0;white-space:nowrap;vertical-align:top\">${esc(k)}</td><td style=\"padding:4px 0\">${esc(v)}</td></tr>" }}</table></div>
<div class="card"><h2>🗣️ Make Pip say something</h2>
<form class="add" method="post" action="/say"><input type="text" name="text" maxlength="300" placeholder="Dinner's ready!" required><button>Say it</button></form></div>
<div class="card"><h2>⏰ Reminders</h2>
<form class="add" method="post" action="/remind"><input type="text" name="text" maxlength="300" placeholder="Laura, get the chicken out" required>
<div class="row"><input type="datetime-local" name="at" required style="flex:1"><button>Remind</button></div></form>
${reminders().let { rs -> if (rs.isEmpty()) "" else "<ul style=\"margin-top:8px\">" + rs.joinToString("") { r ->
    "<li><div class=\"t\">${esc(r.text)}<div class=\"m\">${SimpleDateFormat("EEE d MMM, HH:mm", Locale.UK).format(Date(r.at))}</div></div>" +
    "<form method=\"post\" action=\"/unremind\"><input type=\"hidden\" name=\"id\" value=\"${r.id}\"><button class=\"x\" title=\"Delete\">×</button></form></li>" } + "</ul>" }}</div>
<div class="card"><h2>🎛️ Remote</h2><div style="display:flex;flex-wrap:wrap;gap:8px">${listOf("wake" to "☀️ Wake", "sleep" to "😴 Sleep",
    "mute" to "🤐 Zip", "unmute" to "🗣️ Unzip", "voldown" to "🔉 Quieter", "volup" to "🔊 Louder").joinToString("") { (a, l) ->
    "<form method=\"post\" action=\"/do\"><input type=\"hidden\" name=\"a\" value=\"$a\"><button>$l</button></form>" }}</div></div>
<div class="card"><h2>Tell Pip something</h2>
<form class="add" method="post" action="/fact">
<textarea name="text" maxlength="300" placeholder="e.g. Honey's at the vet on Tuesday. Brandon's started a new job. Laura's away until Sunday." required></textarea>
<div class="row"><label for="u">Forget it after (optional)</label><input id="u" type="date" name="until"><span style="flex:1"></span><button>Tell Pip</button></div>
</form></div>
<div class="card"><h2>What Pip knows from you (${facts.size})</h2>
${if (facts.isEmpty()) "<p class=\"empty\">Nothing yet.</p>" else "<ul>" + facts.joinToString("") { f ->
    "<li><div class=\"t\">${esc(f.text)}<div class=\"m\">added ${fmt.format(Date(f.added))}" +
    (if (f.until > 0) " · until ${fmt.format(Date(f.until))}" else "") + "</div></div>" +
    "<form method=\"post\" action=\"/forget\"><input type=\"hidden\" name=\"id\" value=\"${f.id}\"><button class=\"x\" title=\"Forget\">×</button></form></li>" } + "</ul>"}
</div>
<div class="card"><h2>🛒 Shopping list (${shop.size})</h2>
<form class="add" method="post" action="/shop"><input type="text" name="item" placeholder="Add items, comma separated" required><button>Add</button></form>
${if (shop.isEmpty()) "<p class=\"empty\" style=\"margin-top:12px\">Empty.</p>" else "<ul style=\"margin-top:8px\">" + shop.joinToString("") { s ->
    "<li><div class=\"t\">${esc(s)}</div><form method=\"post\" action=\"/unshop\"><input type=\"hidden\" name=\"item\" value=\"${esc(s)}\"><button class=\"x\" title=\"Remove\">×</button></form></li>" } + "</ul>"}
</div>
<div class="card"><h2>⚙️ Settings</h2>${host.settings().joinToString("") { (k, label, choices) ->
    val cur = host.setting(k)
    "<form method=\"post\" action=\"/set\" style=\"margin:8px 0\"><input type=\"hidden\" name=\"k\" value=\"$k\"><label>${esc(label)}</label>" +
    "<div class=\"row\" style=\"margin-top:4px\"><select name=\"v\" style=\"flex:1;font:inherit;color:var(--ink);background:#0d1418;border:1px solid #2b3a42;border-radius:10px;padding:10px\">" +
    choices.joinToString("") { (v, l) -> "<option value=\"$v\"${if (v == cur) " selected" else ""}>${esc(l)}</option>" } +
    "</select><button>Save</button></div></form>" }}</div>
<div class="card"><h2>📷 Camera</h2><p class="sub" style="margin:0 0 8px">See what Pip sees (needs the code from Pip's menu).</p>
<form method="get" action="/cam" class="add"><input type="text" name="pin" inputmode="numeric" maxlength="4" placeholder="Code"><button>Open</button></form></div>
<p class="sub" style="text-align:center">Only works on the home Wi-Fi. Nothing leaves the house.</p>
</main></body></html>"""
    }
}

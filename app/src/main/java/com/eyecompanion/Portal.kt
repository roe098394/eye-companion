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
class Portal(
    private val prefs: SharedPreferences,
    private val status: () -> String,                  // a line or two about Pip right now (HTML-escaped by us)
    private val getShopping: () -> List<String>,
    private val setShopping: (List<String>) -> Unit,
    private val onNewFact: (String) -> Unit,           // so Pip can react ("Ooh, noted!")
) {
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
                    save(facts() + Fact(now, text, until, now)); onNewFact(text)
                }
                back()
            }
            method == "POST" && path == "/forget" -> { val id = form["id"]?.toLongOrNull(); save(facts().filterNot { it.id == id }); back() }
            method == "POST" && path == "/shop" -> {
                val items = form["item"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
                if (items.isNotEmpty()) setShopping((getShopping() + items).distinct().takeLast(25)); back()
            }
            method == "POST" && path == "/unshop" -> { val it0 = form["item"].orEmpty(); setShopping(getShopping().filterNot { it == it0 }); back() }
            method == "GET" && (path == "/" || path.startsWith("/?")) -> send("200 OK", "text/html; charset=utf-8", page())
            else -> send("404 Not Found", "text/plain", "Not here.")
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun page(): String {
        val fmt = SimpleDateFormat("EEE d MMM", Locale.UK)
        val facts = facts().sortedByDescending { it.added }
        val shop = getShopping()
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
<h1>👀 <span>Pip</span></h1><p class="sub">${esc(status())}</p>
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
<p class="sub" style="text-align:center">Only works on the home Wi-Fi. Nothing leaves the house.</p>
</main></body></html>"""
    }
}

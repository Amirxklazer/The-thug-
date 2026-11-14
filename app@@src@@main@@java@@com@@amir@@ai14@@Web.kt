package com.amir.ai14

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/** Web access for the AI: search + read pages. No API key needed. */
object Web {
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    private fun get(url: String): String {
        var u = url
        for (i in 0 until 6) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", "text/html,application/json;q=0.9,*/*;q=0.8")
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: break
                u = URL(URL(u), loc).toString()
                continue
            }
            if (code !in 200..299) throw Exception("HTTP $code")
            val out = ByteArrayOutputStream()
            c.inputStream.use { ins ->
                val buf = ByteArray(8192)
                var n = ins.read(buf)
                while (n >= 0 && out.size() < 700000) { out.write(buf, 0, n); n = ins.read(buf) }
            }
            return out.toString("UTF-8")
        }
        throw Exception("too many redirects")
    }

    private fun ent(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'").replace("&nbsp;", " ")

    fun text(html: String): String {
        var s = html.replace(Regex("(?is)<(script|style|noscript|svg|head)[^>]*>.*?</\\1>"), " ")
        s = s.replace(Regex("(?i)<(br|/p|/div|/li|/h[1-6]|/tr)[^>]*>"), "\n").replace(Regex("<[^>]+>"), " ")
        return ent(s).replace(Regex("[ \\t\\r]+"), " ").replace(Regex("\\n\\s*\\n+"), "\n").trim()
    }

    fun search(q: String): String {
        val enc = URLEncoder.encode(q, "UTF-8")
        try {
            val h = get("https://html.duckduckgo.com/html/?q=$enc")
            val links = Regex("<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(h).toList()
            val snips = Regex("<a[^>]*class=\"result__snippet\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(h).toList()
            if (links.isNotEmpty()) {
                return links.take(7).mapIndexed { i, m ->
                    val href = ent(m.groupValues[1])
                    val real = Regex("uddg=([^&]+)").find(href)?.groupValues?.get(1)
                    val url = if (real != null) URLDecoder.decode(real, "UTF-8") else if (href.startsWith("//")) "https:$href" else href
                    val sn = snips.getOrNull(i)?.let { text(it.groupValues[1]) } ?: ""
                    "${i + 1}. ${text(m.groupValues[2])}\n   $url\n   $sn"
                }.joinToString("\n")
            }
        } catch (e: Exception) { /* fall through to the backup source */ }
        return try {
            val r = JSONObject(get("https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$enc&format=json&srlimit=6"))
            val arr = r.getJSONObject("query").getJSONArray("search")
            if (arr.length() == 0) "No results found."
            else (0 until arr.length()).joinToString("\n") { i ->
                val o = arr.getJSONObject(i); val t = o.getString("title")
                "${i + 1}. $t\n   https://en.wikipedia.org/wiki/${t.replace(' ', '_')}\n   ${text(o.optString("snippet"))}"
            }
        } catch (e: Exception) { "ERROR: web search unavailable (${e.message})" }
    }

    fun fetch(url: String): String {
        val u = if (url.startsWith("http")) url else "https://$url"
        val raw = get(u)
        val t = if (raw.trimStart().startsWith("{") || raw.trimStart().startsWith("[")) raw else text(raw)
        return t.take(9000).ifBlank { "(page had no readable text)" }
    }
}

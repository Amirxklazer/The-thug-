package com.amir.ai14

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class HttpErr(val code: Int, val retry: Int, msg: String) : Exception(msg)

object Api {
    const val GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta/openai"
    const val GROQ_BASE = "https://api.groq.com/openai/v1"

    private fun post(url: String, key: String?, body: JSONObject): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 30000
        c.readTimeout = 180000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        if (key != null) c.setRequestProperty("Authorization", "Bearer $key")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        if (code !in 200..299) {
            val retry = c.getHeaderField("retry-after")?.toDoubleOrNull()?.toInt() ?: 0
            val detail = try { JSONObject(txt).getJSONObject("error").getString("message") } catch (e: Exception) { txt.take(200) }
            throw HttpErr(
                code, retry,
                when (code) {
                    401, 403 -> "The API key was rejected. Check it in Settings, API keys. ($detail)"
                    404 -> "Model not found. Change the model in Settings, Models. ($detail)"
                    413 -> "That request is too big for this model's limits. Start a new chat or ask for less at once. ($detail)"
                    429 -> "Rate limit reached. ($detail)"
                    else -> "HTTP $code $detail"
                }
            )
        }
        return JSONObject(txt)
    }

    /** OpenAI-compatible chat (Groq, Gemini and Qwen all speak this). Retries rate limits. */
    fun chat(base: String, key: String, model: String, msgs: JSONArray, tools: JSONArray?, onWait: (String) -> Unit = {}): JSONObject {
        var attempt = 0
        while (true) {
            try {
                val b = JSONObject().put("model", model).put("messages", msgs)
                if (tools != null) b.put("tools", tools)
                val r = post(base.trimEnd('/') + "/chat/completions", key, b)
                return r.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            } catch (e: HttpErr) {
                if ((e.code == 429 || e.code >= 500) && attempt < 3) {
                    attempt++
                    val w = (if (e.retry > 0) e.retry else 6 * attempt).coerceIn(2, 30)
                    onWait("Busy, retrying in ${w}s")
                    Thread.sleep(w * 1000L)
                } else throw e
            }
        }
    }

    // Gemini native image generation
    fun image(key: String, model: String, prompt: String): ByteArray {
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("TEXT").put("IMAGE")))
        val r = post("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key", null, body)
        val parts = r.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
        for (i in 0 until parts.length()) {
            val d = parts.getJSONObject(i).optJSONObject("inlineData")
            if (d != null) return Base64.decode(d.getString("data"), Base64.DEFAULT)
        }
        throw Exception("No image returned by the model")
    }
}

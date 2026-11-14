package com.amir.ai14

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Api {
    const val GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta/openai"

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
        if (code !in 200..299) throw Exception("HTTP $code ${txt.take(300)}")
        return JSONObject(txt)
    }

    // OpenAI-compatible chat (works for both Gemini and Qwen/DashScope)
    fun chat(base: String, key: String, model: String, msgs: JSONArray, tools: JSONArray?): JSONObject {
        val b = JSONObject().put("model", model).put("messages", msgs)
        if (tools != null) b.put("tools", tools)
        val r = post(base.trimEnd('/') + "/chat/completions", key, b)
        return r.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
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

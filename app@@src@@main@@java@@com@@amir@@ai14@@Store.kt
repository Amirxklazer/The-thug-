package com.amir.ai14

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Settings are shared on the device. Sandbox, chats and memory.md are per user (uid). */
class Store(ctx: Context, val uid: String) {
    private val p = ctx.getSharedPreferences("ai14", 0)
    fun get(k: String, d: String = ""): String = p.getString(k, null)?.takeIf { it.isNotBlank() } ?: d
    fun put(k: String, v: String) = p.edit().putString(k, v.trim()).apply()

    private val base = File(ctx.filesDir, "users/$uid").apply { mkdirs() }
    val root = File(base, "sandbox").apply { mkdirs() }
    val chatsDir = File(base, "chats").apply { mkdirs() }
    val memFile = File(root, "memory.md")

    fun memory(): String = if (memFile.exists()) memFile.readText() else ""

    fun chats(): List<Pair<String, String>> = (chatsDir.listFiles() ?: emptyArray())
        .sortedByDescending { it.lastModified() }
        .map { f ->
            f.nameWithoutExtension to (try { JSONObject(f.readText()).optString("title", "Chat") } catch (e: Exception) { "Chat" })
        }
}

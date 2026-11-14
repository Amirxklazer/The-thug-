package com.amir.ai14

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

object Auth {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun sha(alg: String, s: String) = hex(MessageDigest.getInstance(alg).digest(s.toByteArray()))

    /** Same email = same data folder, whether you log in with email or Google. */
    fun uid(email: String) = sha("SHA-1", email.trim().lowercase()).take(12)

    private fun fb(path: String, key: String, body: JSONObject): JSONObject {
        val c = URL("https://identitytoolkit.googleapis.com/v1/accounts:$path?key=$key").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 20000; c.readTimeout = 30000
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        if (code !in 200..299) {
            val m = try { JSONObject(txt).getJSONObject("error").getString("message") } catch (e: Exception) { "HTTP $code" }
            throw Exception(
                when {
                    m.startsWith("EMAIL_EXISTS") -> "That email already has an account. Try Sign in."
                    m.startsWith("INVALID_LOGIN_CREDENTIALS") || m.startsWith("INVALID_PASSWORD") || m.startsWith("EMAIL_NOT_FOUND") -> "Wrong email or password."
                    m.startsWith("WEAK_PASSWORD") -> "Password must be at least 6 characters."
                    m.startsWith("INVALID_EMAIL") -> "That email looks invalid."
                    m.startsWith("OPERATION_NOT_ALLOWED") -> "Enable Email/Password in Firebase (Authentication > Sign-in method)."
                    m.contains("API key") || m.startsWith("API_KEY") -> "The Firebase API key is wrong."
                    else -> m
                }
            )
        }
        return JSONObject(txt)
    }

    /** Email login. Uses Firebase if a Web API key is saved, otherwise a local on-device account. */
    fun email(st: Store, email0: String, pw: String, create: Boolean) {
        val email = email0.trim().lowercase()
        require(email.contains("@") && email.contains(".")) { "Enter a valid email." }
        require(pw.length >= 6) { "Password must be at least 6 characters." }
        val fk = st.get("fbkey")
        if (fk.isNotBlank()) {
            fb(if (create) "signUp" else "signInWithPassword", fk, JSONObject().put("email", email).put("password", pw).put("returnSecureToken", true))
            return
        }
        val k = "acct_" + uid(email)
        val saved = st.get(k)
        if (create) {
            require(saved.isBlank()) { "That email already has an account here. Try Sign in." }
            val salt = hex(ByteArray(8).also { SecureRandom().nextBytes(it) })
            st.put(k, salt + ":" + sha("SHA-256", salt + pw))
        } else {
            require(saved.isNotBlank()) { "No account for that email. Tap Create account." }
            val parts = saved.split(":")
            require(sha("SHA-256", parts[0] + pw) == parts[1]) { "Wrong email or password." }
        }
    }
}

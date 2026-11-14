package com.amir.ai14

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.text.TextUtils
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipFile

/** Live colour palette (black + an accent). Changed from Settings → Theme. */
private object C {
    var bg = 0; var card = 0; var card2 = 0; var text = 0; var sub = 0; var hint = 0; var accent = 0
}

class MainActivity : Activity() {
    private lateinit var st: Store
    private lateinit var sb: Sandbox
    private val scope = MainScope()
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var modeBtn: TextView
    private lateinit var liveBtn: TextView
    private lateinit var attInfo: TextView

    private var cmode = ""            // "" = not picked yet, "agent", "coding"
    private var chatId = newId()
    private val ui = mutableListOf<Pair<String, String>>() // role (user/ai/file/note), text
    private val pending = mutableListOf<File>()
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var rec: SpeechRecognizer? = null
    private var live = false
    private var busy = false
    private var pendingMic: ((String) -> Unit)? = null

    // live mode overlay
    private var liveDlg: Dialog? = null
    private var liveStatus: TextView? = null
    private var liveSub: TextView? = null
    private var orb: View? = null
    private var pulse: ValueAnimator? = null
    private var liveState = ""

    private val DARK = AlertDialog.THEME_DEVICE_DEFAULT_DARK
    private val textExt = setOf("txt", "md", "kt", "kts", "java", "xml", "json", "html", "htm", "css", "js", "py", "csv", "yaml", "yml", "gradle", "sql", "svg", "log", "ini", "properties", "sh")
    private val imgExt = setOf("png", "jpg", "jpeg", "webp", "gif")

    private val themes = linkedMapOf(
        "Ember" to longArrayOf(0xFF0D0D0E, 0xFF1A1A1C, 0xFF29292C, 0xFFEDEDED, 0xFF9A9AA0, 0xFF6C6C72, 0xFFE8743B),
        "Ocean" to longArrayOf(0xFF0A0C10, 0xFF161A21, 0xFF232A35, 0xFFEAF0F7, 0xFF93A0B2, 0xFF667286, 0xFF4C8DFF),
        "Mint" to longArrayOf(0xFF0A0D0C, 0xFF151A18, 0xFF222B28, 0xFFE9F3EF, 0xFF92A39D, 0xFF65756F, 0xFF35D49A),
        "Violet" to longArrayOf(0xFF0C0B10, 0xFF18161F, 0xFF262330, 0xFFEEEAF7, 0xFF9E98B0, 0xFF6F6A82, 0xFF9B7BFF),
        "Rose" to longArrayOf(0xFF0E0B0C, 0xFF1C1618, 0xFF2C2326, 0xFFF5EBED, 0xFFA89A9E, 0xFF776A6E, 0xFFFF5C8A)
    )

    private val personas = linkedMapOf(
        "Friendly" to "Warm, upbeat and a little playful, like a smart friend who loves helping. Light emoji use.",
        "Pro" to "Precise, calm and professional. Straight to the point, no filler, no jokes unless invited.",
        "Hype" to "High-energy hype friend. Celebrate wins, motivate, punchy short lines, plenty of emojis.",
        "Mentor" to "Patient teacher. Explain the why in simple steps, give small examples, check the user understood, never condescending.",
        "Chill" to "Laid-back and casual. Short relaxed sentences, easy humor, zero stiffness.",
        "Genius" to "Brilliant nerdy deep thinker. Reason carefully, weigh tradeoffs, point out edge cases, dry wit.",
        "Sassy" to "Witty and a little sarcastic in a lovable way, but always genuinely helpful and never rude."
    )

    private fun applyTheme() {
        val t = themes[st.get("theme", "Ember")] ?: themes.getValue("Ember")
        C.bg = t[0].toInt(); C.card = t[1].toInt(); C.card2 = t[2].toInt(); C.text = t[3].toInt()
        C.sub = t[4].toInt(); C.hint = t[5].toInt(); C.accent = t[6].toInt()
        window.statusBarColor = C.bg; window.navigationBarColor = C.bg
    }

    private fun newId() = UUID.randomUUID().toString().take(8)
    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun round(c: Int, r: Int = 18) = GradientDrawable().apply { setColor(c); cornerRadius = dp(r).toFloat() }
    private fun isImg(f: File) = f.extension.lowercase() in imgExt
    private fun size(n: Long) = if (n < 1024) "$n B" else if (n < 1048576) "${n / 1024} KB" else "%.1f MB".format(n / 1048576.0)
    private fun pretty(x: File) = x.nameWithoutExtension.replace(Regex("[_\\-]+"), " ").trim().ifBlank { x.name }

    private fun chip(t: String, f: () -> Unit) = TextView(this).apply {
        text = t; textSize = 19f; gravity = Gravity.CENTER; setTextColor(C.text)
        setPadding(dp(11), dp(8), dp(11), dp(8)); setOnClickListener { f() }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        st = Store(this, "_")
        applyTheme()
        tts = TextToSpeech(this) { s ->
            ttsReady = s == TextToSpeech.SUCCESS
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { runOnUiThread { if (live) liveListen() } }
                override fun onError(id: String?) { runOnUiThread { if (live) liveListen() } }
            })
        }
        val uid = st.get("uid")
        if (uid.isBlank()) showLogin() else startApp(uid)
    }

    override fun onDestroy() {
        super.onDestroy()
        pulse?.cancel(); liveDlg?.dismiss()
        tts?.shutdown(); rec?.destroy(); scope.cancel()
    }

    private fun startApp(uid: String) {
        st = Store(this, uid); sb = Sandbox(this, st)
        applyTheme()
        ui.clear(); pending.clear()
        buildMain()
        newChat()
        if (st.get("ptoken").isBlank() && st.get("gkey").isBlank() && st.get("qkey").isBlank()) keysDialog()
    }

    private fun buildMain() {
        val rootV = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.bg) }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(6)) }
        modeBtn = chip("🔀 Mode") {
            if (ui.isEmpty()) showPicker()
            else { cmode = if (cmode == "coding") "agent" else "coding"; updMode(); toast(if (cmode == "coding") "Coding mode" else "Agent mode") }
        }.apply { textSize = 14f; background = round(C.card2, 14) }
        bar.addView(modeBtn)
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(chip("＋") { newChat() })
        bar.addView(chip("🕘") { history() })
        bar.addView(chip("📁") { files() })
        bar.addView(chip("🧠") { memoryDlg() })
        bar.addView(chip("⚙") { settings() })

        scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(12)) }
        scroll.addView(list)
        attInfo = TextView(this).apply {
            setTextColor(C.accent); textSize = 13f
            setPadding(dp(16), 0, dp(16), dp(4)); visibility = View.GONE
        }
        input = EditText(this).apply {
            hint = "Message 14AI…"; setHintTextColor(C.hint); setTextColor(C.text)
            background = round(C.card, 22); setPadding(dp(16), dp(10), dp(16), dp(10)); maxLines = 5
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        liveBtn = chip("🎧") { toggleLive() }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(8)) }
        row.addView(chip("📎") { pick() })
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(chip("🎤") { tts?.stop(); listen { t -> input.setText(t); input.setSelection(t.length) } })
        row.addView(liveBtn)
        row.addView(chip("➤") { send(input.text.toString(), live) }.apply { background = round(C.accent, 20); setTextColor(Color.WHITE) })
        rootV.addView(bar)
        rootV.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        rootV.addView(attInfo)
        rootV.addView(row)
        setContentView(rootV)
        updMode(); updLive()
    }

    private fun refreshUi() {
        buildMain()
        if (ui.isEmpty() && cmode == "") showPicker() else renderChat()
        updAtt()
    }

    // ───────────── login ─────────────
    private fun bigBtn(t: String, bg: Int, fg: Int, f: () -> Unit) = TextView(this).apply {
        text = t; textSize = 16f; gravity = Gravity.CENTER; setTextColor(fg); background = round(bg, 14)
        setPadding(dp(12), dp(14), dp(12), dp(14)); setOnClickListener { f() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
    }

    private fun styled(e: EditText) = e.apply {
        setTextColor(C.text); setHintTextColor(C.hint); background = round(C.card, 14)
        setPadding(dp(16), dp(14), dp(16), dp(14)); setSingleLine(true)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
    }

    private fun showLogin() {
        window.statusBarColor = C.bg; window.navigationBarColor = C.bg
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(28), dp(56), dp(28), dp(24)) }
        col.addView(TextView(this).apply { text = "14AI"; textSize = 46f; setTextColor(C.accent); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER })
        col.addView(TextView(this).apply { text = "Your personal AI agent ✨"; textSize = 15f; setTextColor(C.sub); gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(24)) })
        val em = styled(EditText(this).apply { hint = "Email"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS })
        val pw = styled(EditText(this).apply { hint = "Password (6+ characters)"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD })
        val err = TextView(this).apply { setTextColor(0xFFFF8A80.toInt()); textSize = 13f; gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) }
        val go: (Boolean) -> Unit = { create ->
            val e = em.text.toString(); val p = pw.text.toString()
            err.text = "⏳ One sec…"
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { Auth.email(st, e, p, create) }
                    enter(Auth.uid(e), e.trim().lowercase(), e.trim().substringBefore("@"), "email")
                } catch (x: Exception) { err.text = x.message ?: "Something went wrong" }
            }
        }
        col.addView(em); col.addView(pw); col.addView(err)
        col.addView(bigBtn("Sign in", C.accent, Color.WHITE) { go(false) })
        col.addView(bigBtn("Create account", C.card2, C.text) { go(true) })
        col.addView(TextView(this).apply { text = "Continue as guest"; textSize = 14f; gravity = Gravity.CENTER; setTextColor(C.sub); setPadding(0, dp(22), 0, dp(10)); setOnClickListener { enter("guest", "", "Guest", "guest") } })
        col.addView(TextView(this).apply { text = "🔑 Login setup (optional)"; textSize = 13f; gravity = Gravity.CENTER; setTextColor(C.accent); setPadding(0, dp(6), 0, dp(6)); setOnClickListener { authSetup() } })
        setContentView(ScrollView(this).apply { isFillViewport = true; setBackgroundColor(C.bg); addView(col) })
    }

    private fun enter(uid: String, email: String, name: String, method: String) {
        st.put("uid", uid); st.put("email", email); st.put("name", name); st.put("method", method)
        startApp(uid)
    }

    private fun signOut() {
        saveChat(); stopLive(); tts?.stop(); rec?.cancel()
        st.put("uid", ""); st = Store(this, "_"); showLogin()
    }

    private fun authSetup() = fieldsDialog(
        "🔑 Login setup",
        listOf(Triple("fbkey", "Firebase Web API key (optional)", "")),
        "Email login works without any setup as an account stored on this phone. Paste a Firebase Web API key to use real cloud accounts instead."
    )

    private fun paste(et: EditText) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        if (!t.isNullOrBlank()) et.setText(t) else toast("Clipboard is empty")
    }

    private fun fieldsDialog(title: String, items: List<Triple<String, String, String>>, note: String?, extra: String? = null, onExtra: (() -> Unit)? = null) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        if (note != null) box.addView(TextView(this).apply { text = note; textSize = 13f; setTextColor(0xFFB0B0B6.toInt()) })
        val ets = items.map { (k, l, d) ->
            box.addView(TextView(this).apply { text = l; textSize = 12f; setTextColor(0xFF9A9AA0.toInt()); setPadding(0, dp(12), 0, 0) })
            val et = EditText(this).apply { setText(st.get(k, d)); setTextColor(Color.WHITE); if (k != "custom") setSingleLine(true) }
            val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            r.addView(et, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(chip("📋") { paste(et) })
            box.addView(r)
            et
        }
        val b = AlertDialog.Builder(this, DARK).setTitle(title).setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ -> items.forEachIndexed { i, f -> st.put(f.first, ets[i].text.toString()) }; toast("Saved ✅") }
            .setNegativeButton("Close", null)
        if (extra != null) b.setNeutralButton(extra) { _, _ -> onExtra?.invoke() }
        b.show()
    }

    private fun keysDialog() = fieldsDialog(
        "🔑 Connect your AI",
        listOf(Triple("ptoken", "Puter token", ""), Triple("gkey", "Gemini API key (optional)", ""), Triple("qkey", "Qwen API key (optional)", "")),
        "Tap 📋 to paste from your clipboard. One is enough.\nPuter: puter.com/dashboard → Create token\nGemini: aistudio.google.com/apikey"
    )

    private fun settings() {
        val who = if (st.get("method") == "guest") "Guest" else st.get("name")
        val items = arrayOf(
            "🎨 Theme · ${st.get("theme", "Ember")}",
            "🎭 Personality · ${st.get("persona", "Friendly")}",
            "🔑 Keys & models",
            "🎙 Voice language · ${st.get("lang", "en-US")}",
            "✍ Custom instructions",
            "🔐 Login setup",
            "🚪 Sign out ($who)"
        )
        AlertDialog.Builder(this, DARK).setTitle("⚙ Settings").setItems(items) { _, i ->
            when (i) {
                0 -> pickTheme()
                1 -> pickPersona()
                2 -> fieldsDialog("🔑 Keys & models", listOf(
                    Triple("ptoken", "Puter token (used first when set)", ""),
                    Triple("pmodel", "Puter model (Agent)", "gpt-5.4-nano"),
                    Triple("pcmodel", "Puter model (Coding)", "gpt-5.4-nano"),
                    Triple("gkey", "Gemini API key", ""),
                    Triple("gmodel", "Gemini model (Agent)", "gemini-2.5-flash"),
                    Triple("qkey", "Qwen (DashScope) API key", ""),
                    Triple("cmodel", "Qwen coding model (Coding mode)", "qwen3-coder-plus"),
                    Triple("qmodel", "Qwen general model", "qwen-plus"),
                    Triple("qbase", "Qwen base URL (Singapore = intl)", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"),
                    Triple("imodel", "Image model (Gemini)", "gemini-2.5-flash-image")
                ), null)
                3 -> fieldsDialog("🎙 Voice language", listOf(Triple("lang", "en-US, fr-FR, ar-DZ…", "en-US")), "Used for the mic and for the AI's spoken voice.")
                4 -> fieldsDialog("✍ Custom instructions", listOf(Triple("custom", "Always follow these", "")), "Added to every chat.")
                5 -> authSetup()
                else -> signOut()
            }
        }.show()
    }

    private fun pickTheme() {
        val names = themes.keys.toList()
        AlertDialog.Builder(this, DARK).setTitle("🎨 Theme").setItems(names.toTypedArray()) { _, i ->
            st.put("theme", names[i]); applyTheme(); refreshUi(); toast("${names[i]} theme on")
        }.show()
    }

    private fun pickPersona() {
        val names = personas.keys.toList()
        val shown = names.map { "$it  ·  ${personas.getValue(it).substringBefore('.')}" }
        AlertDialog.Builder(this, DARK).setTitle("🎭 Personality").setItems(shown.toTypedArray()) { _, i ->
            st.put("persona", names[i]); toast("${names[i]} personality on")
        }.show()
    }

    // ───────────── chat UI ─────────────
    private fun scrollDown() { scroll.post { scroll.fullScroll(View.FOCUS_DOWN) } }

    private fun addBubble(role: String, text: String, save: Boolean = true) {
        if (save) ui.add(role to text)
        val tv = TextView(this).apply {
            this.text = text; textSize = 16f; setTextColor(C.text); setTextIsSelectable(true)
            setLineSpacing(0f, 1.12f)
            if (role == "user") { background = round(C.card2); setPadding(dp(14), dp(10), dp(14), dp(10)) }
            if (role == "note") { textSize = 13f; setTextColor(C.sub) }
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(dp(14), dp(6), dp(14), dp(6)); lp.gravity = if (role == "user") Gravity.END else Gravity.START
        list.addView(tv, lp); scrollDown()
    }

    // ── mode picker (start of every new chat) ──
    private fun modeCard(icon: String, title: String, desc: String, f: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = round(C.card, 20); setPadding(dp(18), dp(16), dp(18), dp(16))
        addView(TextView(context).apply { text = "$icon  $title"; textSize = 19f; setTextColor(C.accent); typeface = Typeface.DEFAULT_BOLD })
        addView(TextView(context).apply { text = desc; textSize = 14f; setTextColor(C.sub); setPadding(0, dp(4), 0, 0) })
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(14), dp(6), dp(14), dp(6)) }
        setOnClickListener { f() }
    }

    private fun showPicker() {
        list.removeAllViews()
        list.addView(TextView(this).apply { text = "What are we doing?"; textSize = 26f; setTextColor(C.text); typeface = Typeface.DEFAULT_BOLD; setPadding(dp(18), dp(28), dp(18), dp(2)) })
        list.addView(TextView(this).apply { text = "Pick a mode for this chat"; textSize = 14f; setTextColor(C.sub); setPadding(dp(18), 0, dp(18), dp(14)) })
        list.addView(modeCard("🤖", "Agent", "Talk normally, search the web, make files, images, PDFs and run Python.") { choose("agent") })
        list.addView(modeCard("💻", "Coding", "Builds full apps. Searches the web and docs first, then ships a flat zip with build.yml.") { choose("coding") })
    }

    private fun choose(m: String) {
        cmode = m; updMode(); list.removeAllViews()
        val p = st.get("persona", "Friendly")
        addBubble("note", if (m == "coding") "💻 Coding mode · $p · tell me what to build and I'll research it first." else "🤖 Agent mode · $p · ask me anything or tell me to make something.", false)
        input.requestFocus()
    }

    private fun updMode() { modeBtn.text = when (cmode) { "agent" -> "🤖 Agent"; "coding" -> "💻 Coding"; else -> "🔀 Mode" } }

    // ── Claude-style file cards ──
    private fun kindOf(x: File): String = when (x.extension.lowercase()) {
        in imgExt -> "Image"
        "zip", "jar" -> "Archive"
        "pdf", "docx", "xlsx", "pptx", "epub" -> "Document"
        "html", "htm" -> "Web page"
        "md", "txt", "csv", "log" -> "Text"
        in textExt -> "Code"
        else -> "File"
    }

    private fun iconOf(x: File): String = when (kindOf(x)) {
        "Image" -> "🖼"; "Archive" -> "📦"; "Document" -> "📄"; "Web page" -> "🌐"; "Text" -> "📝"; "Code" -> "💻"; else -> "📎"
    }

    private fun thumb(f: File, px: Int): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (minOf(o.outWidth, o.outHeight) / (s * 2) >= px) s *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
    } catch (e: Exception) { null }

    private fun addCard(rel: String, persist: Boolean = true) {
        val x = sb.f(rel)
        if (!x.exists()) return
        if (persist) ui.add("file" to rel)
        val bm = if (isImg(x)) thumb(x, dp(64)) else null
        val tile: View = if (bm != null) ImageView(this).apply { setImageBitmap(bm); scaleType = ImageView.ScaleType.CENTER_CROP }
        else TextView(this).apply { text = iconOf(x); textSize = 26f; gravity = Gravity.CENTER; setBackgroundColor(C.card2) }
        tile.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(14).toFloat()) }
        }
        tile.clipToOutline = true
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(6), 0) }
        info.addView(TextView(this).apply { text = pretty(x); textSize = 16f; setTextColor(C.text); maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        info.addView(TextView(this).apply { text = "${kindOf(x)} · ${x.extension.uppercase()} · ${size(x.length())}"; textSize = 13f; setTextColor(C.sub); setPadding(0, dp(3), 0, 0) })
        val card = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; background = round(C.card, 20); setPadding(dp(12), dp(12), dp(6), dp(12)); setOnClickListener { preview(rel) } }
        card.addView(tile, LinearLayout.LayoutParams(dp(64), dp(64)))
        card.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(chip("⬇") { save(rel) })
        card.addView(chip("📤") { share(rel) })
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(dp(12), dp(5), dp(12), dp(5))
        list.addView(card, lp); scrollDown()
    }

    private fun renderChat() {
        list.removeAllViews()
        ui.toList().forEach { (r, t) -> if (r == "file") addCard(t, false) else addBubble(r, t, false) }
    }

    private fun updLive() { liveBtn.text = if (live) "🔴" else "🎧"; if (live) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    private fun updAtt() { attInfo.visibility = if (pending.isEmpty()) View.GONE else View.VISIBLE; attInfo.text = "📎 " + pending.joinToString(", ") { it.name } }

    // ───────────── chats / history ─────────────
    private fun saveChat() {
        if (ui.none { it.first == "user" }) return
        val title = ui.first { it.first == "user" }.second.lines().first().take(40)
        val arr = JSONArray(); ui.forEach { arr.put(JSONArray().put(it.first).put(it.second)) }
        File(st.chatsDir, "$chatId.json").writeText(JSONObject().put("title", title).put("cm", cmode).put("ui", arr).toString())
    }

    private fun newChat() {
        saveChat(); chatId = newId(); ui.clear(); pending.clear(); updAtt(); cmode = ""; updMode(); showPicker()
    }

    private fun history() {
        val cs = st.chats()
        if (cs.isEmpty()) { toast("No saved chats yet"); return }
        AlertDialog.Builder(this, DARK).setTitle("🕘 Chats")
            .setItems(cs.map { it.second }.toTypedArray()) { _, i ->
                val id = cs[i].first
                AlertDialog.Builder(this, DARK).setItems(arrayOf("Open", "Delete")) { _, j ->
                    if (j == 0) {
                        saveChat()
                        val o = JSONObject(File(st.chatsDir, "$id.json").readText())
                        chatId = id; ui.clear()
                        cmode = o.optString("cm", "agent").ifBlank { "agent" }; updMode()
                        val a = o.getJSONArray("ui")
                        for (k in 0 until a.length()) ui.add(a.getJSONArray(k).getString(0) to a.getJSONArray(k).getString(1))
                        renderChat()
                    } else {
                        File(st.chatsDir, "$id.json").delete(); toast("Deleted")
                    }
                }.show()
            }.show()
    }

    // ───────────── files / memory ─────────────
    private fun files() {
        val fs = sb.root.walkTopDown().filter { it.isFile }.toList()
        if (fs.isEmpty()) { toast("Sandbox is empty"); return }
        AlertDialog.Builder(this, DARK).setTitle("📁 Sandbox (${fs.size})")
            .setItems(fs.map { "${sb.rel(it)}  ·  ${size(it.length())}" }.toTypedArray()) { _, i ->
                val rel = sb.rel(fs[i])
                AlertDialog.Builder(this, DARK).setTitle(rel).setItems(arrayOf("👁 Preview", "⬇ Save to Downloads", "📤 Share", "🗑 Delete")) { _, j ->
                    when (j) { 0 -> preview(rel); 1 -> save(rel); 2 -> share(rel); else -> { sb.f(rel).delete(); toast("Deleted") } }
                }.show()
            }.show()
    }

    private fun memoryDlg() {
        val et = EditText(this).apply { setText(st.memory()); setTextColor(Color.WHITE); minLines = 8; gravity = Gravity.TOP; hint = "Nothing yet. The AI saves lasting facts here (memory.md)." }
        AlertDialog.Builder(this, DARK).setTitle("🧠 memory.md").setView(et)
            .setPositiveButton("Save") { _, _ -> st.memFile.writeText(et.text.toString()); toast("Memory saved ✅") }
            .setNegativeButton("Close", null).show()
    }

    // ───────────── preview / save / share ─────────────
    private fun preview(rel: String) {
        val x = sb.f(rel); val ext = x.extension.lowercase()
        val body: View = when {
            ext in imgExt || ext == "svg" || ext == "html" || ext == "htm" -> WebView(this).apply {
                setBackgroundColor(Color.parseColor("#111111"))
                settings.javaScriptEnabled = true; settings.allowFileAccess = true
                settings.builtInZoomControls = true; settings.displayZoomControls = false
                settings.useWideViewPort = true; settings.loadWithOverviewMode = true
                if (ext in imgExt) loadDataWithBaseURL("file://${x.parent}/", "<body style='margin:0;background:#111'><img src='${x.name}' style='max-width:100%'></body>", "text/html", "utf-8", null)
                else loadUrl("file://${x.path}")
            }
            ext in textExt -> ScrollView(this).apply {
                addView(TextView(context).apply { text = x.readText().take(30000); typeface = Typeface.MONOSPACE; textSize = 12f; setTextColor(C.text); setTextIsSelectable(true); setPadding(dp(16), dp(16), dp(16), dp(16)) })
            }
            ext == "zip" || ext == "jar" -> ScrollView(this).apply {
                val names = try { ZipFile(x).use { z -> z.entries().toList().map { it.name } } } catch (e: Exception) { emptyList() }
                addView(TextView(context).apply {
                    text = "📦 ${names.size} entries\n\n" + names.take(120).joinToString("\n") + if (names.size > 120) "\n…" else ""
                    typeface = Typeface.MONOSPACE; textSize = 12f; setTextColor(C.text); setPadding(dp(16), dp(16), dp(16), dp(16))
                })
            }
            else -> TextView(this).apply { text = "${iconOf(x)}\nNo preview for .$ext files\nUse ⬇ to save or 📤 to share"; gravity = Gravity.CENTER; textSize = 15f; setTextColor(C.sub) }
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.bg) }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(10), dp(8), dp(10)) }
        lateinit var dlg: Dialog
        top.addView(chip("✕") { dlg.dismiss() })
        top.addView(TextView(this).apply { text = pretty(x); textSize = 17f; setTextColor(C.text); typeface = Typeface.DEFAULT_BOLD; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(chip("⬇") { save(rel) })
        top.addView(chip("📤") { share(rel) })
        col.addView(top)
        col.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        dlg = Dialog(this, android.R.style.Theme_Material_NoActionBar_Fullscreen).apply { setContentView(col); show() }
    }

    private fun share(rel: String) {
        try {
            val x = sb.f(rel)
            val uri = Uri.Builder().scheme("content").authority("com.amir.ai14.share").path(rel).build()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(x.extension.lowercase()) ?: "application/octet-stream"
            val i = Intent(Intent.ACTION_SEND).apply {
                type = mime; putExtra(Intent.EXTRA_STREAM, uri); clipData = ClipData.newRawUri(x.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(i, "Share ${x.name}").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) { toast("Share failed: ${e.message}") }
    }

    private fun save(rel: String) {
        val x = sb.f(rel)
        try {
            val ext = x.extension.lowercase()
            val mime = if (ext in setOf("kt", "kts", "gradle", "java")) "application/octet-stream"
            else MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, x.name); put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/14AI")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
                contentResolver.openOutputStream(uri)!!.use { o -> x.inputStream().use { it.copyTo(o) } }
            } else {
                val d = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "14AI").apply { mkdirs() }
                x.copyTo(File(d, x.name), true)
            }
            toast("Saved to Downloads/14AI ✅")
        } catch (e: Exception) { toast("Save failed: ${e.message}") }
    }

    // ───────────── attachments ─────────────
    private fun pick() {
        startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true); addCategory(Intent.CATEGORY_OPENABLE)
        }, 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(rq: Int, rs: Int, d: Intent?) {
        super.onActivityResult(rq, rs, d)
        if (rq != 1 || rs != RESULT_OK || d == null) return
        val uris = mutableListOf<Uri>()
        val cd = d.clipData
        if (cd != null) { for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri) } else { d.data?.let { uris.add(it) } }
        uris.forEach { u ->
            try {
                var name = "file_${System.currentTimeMillis()}"
                contentResolver.query(u, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) { val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) name = c.getString(i) }
                }
                val dest = File(sb.root, "uploads/" + name.replace("/", "_")).apply { parentFile?.mkdirs() }
                contentResolver.openInputStream(u)!!.use { ins -> dest.outputStream().use { ins.copyTo(it) } }
                pending.add(dest)
            } catch (e: Exception) { toast("Couldn't add file: ${e.message}") }
        }
        updAtt()
    }

    // ───────────── voice + live mode ─────────────
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            if (live) liveListen() else pendingMic?.let { listen(it) }
        } else { toast("Mic permission is needed for voice 🎤"); stopLive() }
    }

    private fun listen(onText: (String) -> Unit) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingMic = onText
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7); return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { toast("No speech recognizer on this phone. Install Google app / Speech Services."); stopLive(); return }
        rec?.destroy()
        rec = SpeechRecognizer.createSpeechRecognizer(this).also { r ->
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) { input.hint = "🎙 Listening…" }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {
                    val o = orb
                    if (live && o != null && liveState.startsWith("Listening")) {
                        val s = 1f + (v.coerceIn(0f, 10f) / 10f) * 0.45f
                        o.animate().scaleX(s).scaleY(s).setDuration(90).start()
                    }
                }
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(t: Int, p: Bundle?) {}
                override fun onPartialResults(p: Bundle?) {
                    p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
                        input.setText(it); if (live) liveSub?.text = "You: $it"
                    }
                }
                override fun onResults(p: Bundle?) {
                    input.hint = "Message 14AI…"
                    val t = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!t.isNullOrBlank()) onText(t) else if (live && !busy) relisten()
                }
                override fun onError(e: Int) {
                    input.hint = "Message 14AI…"
                    if (live && !busy && (e == 6 || e == 7 || e == 8)) relisten()
                    else if (live && e != 5) { stopLive(); toast("Voice error $e") }
                }
            })
            r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, st.get("lang", "en-US"))
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            })
        }
    }

    private fun liveListen() { if (live && !busy) { setLive("Listening…"); listen { t -> input.setText(""); send(t, true) } } }
    private fun relisten() { Handler(Looper.getMainLooper()).postDelayed({ liveListen() }, 500) }

    private fun setLive(s: String, sub: String? = null) {
        liveState = s
        liveStatus?.text = s
        if (sub != null) liveSub?.text = sub
        pulse?.cancel()
        val o = orb ?: return
        if (s.startsWith("Thinking") || s.startsWith("Speaking")) {
            pulse = ValueAnimator.ofFloat(1f, 1.2f).apply {
                duration = if (s.startsWith("Thinking")) 700L else 450L
                repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
                addUpdateListener { o.scaleX = it.animatedValue as Float; o.scaleY = it.animatedValue as Float }
                start()
            }
        } else { o.animate().scaleX(1f).scaleY(1f).setDuration(120).start() }
    }

    private fun showLive() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setBackgroundColor(C.bg); setPadding(dp(24), dp(56), dp(24), dp(28)) }
        liveStatus = TextView(this).apply { textSize = 24f; setTextColor(C.text); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; text = "Listening…" }
        orb = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(C.accent) }
            setOnClickListener { orbTap() }
        }
        liveSub = TextView(this).apply { textSize = 15f; setTextColor(C.sub); gravity = Gravity.CENTER; setLineSpacing(0f, 1.15f); text = "Talk to me. Tap the orb to interrupt." }
        val mid = FrameLayout(this).apply { addView(orb, FrameLayout.LayoutParams(dp(170), dp(170), Gravity.CENTER)) }
        col.addView(liveStatus)
        col.addView(mid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        col.addView(ScrollView(this).apply { addView(liveSub) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)))
        col.addView(bigBtn("End live mode", C.card2, C.text) { stopLive() })
        liveDlg = Dialog(this, android.R.style.Theme_Material_NoActionBar_Fullscreen).apply {
            setContentView(col); setOnCancelListener { stopLive() }; show()
        }
    }

    private fun orbTap() {
        if (liveState.startsWith("Speaking")) { tts?.stop(); liveListen() }
    }

    private fun toggleLive() { if (live) stopLive() else { live = true; updLive(); showLive(); liveListen() } }

    private fun stopLive() {
        live = false
        pulse?.cancel(); pulse = null
        tts?.stop(); rec?.cancel()
        liveDlg?.dismiss(); liveDlg = null; liveStatus = null; liveSub = null; orb = null
        if (::liveBtn.isInitialized) updLive()
    }

    private fun speak(t: String) {
        val clean = t.replace(Regex("```[\\s\\S]*?```"), " code block. ").replace(Regex("[*_#`>|]"), "")
            .replace(Regex("[\\p{So}\\p{Cn}\\uFE0F]"), "").take(3500)
        if (!ttsReady || clean.isBlank()) { liveListen(); return }
        tts?.language = Locale.forLanguageTag(st.get("lang", "en-US"))
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "u1")
    }

    // ───────────── sending + agent loop ─────────────
    private fun send(text0: String, spoken: Boolean) {
        if (busy) return
        val text = text0.trim()
        if (text.isEmpty() && pending.isEmpty()) return
        if (cmode == "") { cmode = "agent"; updMode(); list.removeAllViews() }
        busy = true; input.setText("")
        val atts = pending.toList(); pending.clear(); updAtt()
        val hist = ui.filter { it.first == "user" || it.first == "ai" }.takeLast(20)
        addBubble("user", text + if (atts.isNotEmpty()) "\n📎 " + atts.joinToString { it.name } else "")
        if (live) setLive("Thinking…", "You: $text")
        scope.launch {
            val reply = try { withContext(Dispatchers.IO) { agent(text, atts, spoken, hist) } } catch (e: Exception) { "⚠️ ${e.message}" }
            addBubble("ai", reply)
            sb.produced.take(12).forEach { addCard(it) }
            saveChat(); busy = false
            if (spoken && live) { setLive("Speaking…", reply); speak(reply) } else if (live) liveListen()
        }
    }

    private fun pick(img: Boolean): String {
        val g = st.get("gkey").isNotBlank(); val q = st.get("qkey").isNotBlank(); val pt = st.get("ptoken").isNotBlank()
        if (pt) return "puter"
        if (cmode == "coding") return if (q) "qwen" else "gemini"
        if (img) return "gemini"
        return if (g) "gemini" else "qwen"
    }

    private fun call(p: String, msgs: JSONArray, tools: JSONArray?): JSONObject {
        val gem = p == "gemini"; val pu = p == "puter"
        val key = st.get(if (pu) "ptoken" else if (gem) "gkey" else "qkey")
        if (key.isBlank()) throw Exception("Add your ${if (pu) "Puter" else if (gem) "Gemini" else "Qwen"} key in ⚙ Settings → Keys & models")
        val base = if (pu) "https://api.puter.com/puterai/openai/v1" else if (gem) Api.GEMINI_BASE else st.get("qbase", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1")
        val model = if (pu) (if (cmode == "coding") st.get("pcmodel", "gpt-5.4-nano") else st.get("pmodel", "gpt-5.4-nano"))
        else if (gem) st.get("gmodel", "gemini-2.5-flash")
        else if (cmode == "coding") st.get("cmodel", "qwen3-coder-plus") else st.get("qmodel", "qwen-plus")
        return Api.chat(base, key, model, msgs, tools)
    }

    private fun sysPrompt(spoken: Boolean): String {
        val day = SimpleDateFormat("EEEE d MMMM yyyy", Locale.US).format(Date())
        val pName = st.get("persona", "Friendly")
        val persona = personas[pName] ?: personas.getValue("Friendly")
        val who = if (st.get("method") == "guest") "" else "The user is ${st.get("name")} (${st.get("email")})."
        val modeBlock = if (cmode == "coding") """CODING MODE: you are a senior software engineer.
- Before building ANY app or project, call web_search at least twice (current library versions, official docs, best practice for what was asked) and fetch_url the best page. Then build.
- Write complete, working files. No TODO placeholders, no cut-off code. Re-read your code for compile errors before zipping, because you cannot compile here.
- Pick the project and file names yourself and keep the structure clean (README.txt included)."""
        else """AGENT MODE: you are a general assistant. Chat normally about anything, use tools only when they help. For big coding jobs you can still build them, but Coding mode is better at it."""
        return """You are 14AI, a personal AI agent living inside an Android app. Today is $day. $who
PERSONALITY ($pName): $persona Stay in this personality always, but never let it make you less accurate or less useful.
$modeBlock
You own a private sandbox folder and tools. When the user wants a file (code, image, document, archive, anything), CREATE it with tools, then say briefly what you made. The app shows nice file cards (thumbnail, share and download buttons) for created files automatically, so never paste whole files into chat.
WEB: you can search the internet with web_search(query) and read pages with fetch_url(url). Use them whenever facts may be recent or you are unsure: news, versions, prices, docs, people, sports, anything the user asks you to look up. Search first, read the best results, then answer. Mention source names briefly, no long URL dumps.
FILE NAMES: YOU always choose every file name yourself: short, clear, descriptive, lowercase with underscores (home_dashboard.png, budget_report.pdf, Calculator_v1.0.zip). Never ask the user what to call a file and never use generic names like output.png or file1.
How to make formats:
- Text formats (.kt .java .xml .json .html .css .js .py .md .csv .svg .yaml .gradle ...): write_file.
- Images (.png .jpg .webp): DRAW them with run_python using Pillow (PIL) and numpy, saving into the sandbox (gradients, shapes, text, patterns, charts, filters). Fonts: ImageFont.truetype('/system/fonts/Roboto-Regular.ttf', size) with ImageFont.load_default() as fallback. You can also use HTML/SVG + render_html_to_image (best for UI mockups, 1280x800), or generate_image for photo-style art.
- PDF: make_pdf for plain text, or build HTML and render it to an image.
- Office files (.docx .xlsx .pptx), .epub, .jar, .zip: write the internal XML/files with write_file, then zip_folder with the right extension.
- Android projects (DELIVERY RULE): build the project as a normal folder (app/src/main/..., build.gradle.kts, settings.gradle.kts, gradle.properties, README.txt) in ONE sandbox folder, then call flat_zip(folder, output). It makes the FLAT zip for you: no folders at all, every file at the zip root, plus a build.yml that sorts the files back into Android folders on GitHub Actions. NEVER use zip_folder for Android projects and never write build.yml yourself. Name the zip like ProjectName_v1.0.zip. Add mockup PNG screens when asked.
- Other small binaries: write_base64.
run_python runs real Python 3.12 with only Pillow and numpy (no internet, no pip, avoid infinite loops). You cannot compile Kotlin/Java; be honest about that.
STYLE: the chat shows plain text, so do not use markdown symbols (no **, no #, no backticks). Short paragraphs, dashes for lists.
MEMORY: memory.md holds lasting facts about the user. When you learn something lasting (name, preferences, projects, goals), call memory_save. Use memory_forget when asked to forget. Current memory:
${st.memory().ifBlank { "(empty)" }.takeLast(6000)}
Reply in the user's language.
${if (spoken) "LIVE VOICE MODE: your reply is spoken aloud. Use short natural sentences, no markdown, no code, no emojis. Think first, then answer briefly." else ""}
${st.get("custom")}"""
    }

    private fun userMsg(text: String, atts: List<File>): JSONObject {
        var t = text
        val imgs = mutableListOf<JSONObject>()
        atts.forEach { f ->
            val r = sb.rel(f)
            if (isImg(f)) { imgs.add(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", dataUri(f)))); t += "\n[attached image: $r]" }
            else if (f.extension.lowercase() in textExt && f.length() < 200000) t += "\n[attached file: $r]\n" + f.readText().take(12000)
            else t += "\n[attached file saved at $r. Use tools to read or unzip it]"
        }
        val m = JSONObject().put("role", "user")
        if (imgs.isEmpty()) return m.put("content", t)
        val parts = JSONArray().put(JSONObject().put("type", "text").put("text", t))
        imgs.forEach { parts.put(it) }
        return m.put("content", parts)
    }

    private fun dataUri(f: File): String {
        val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bo)
        var s = 1
        while (maxOf(bo.outWidth, bo.outHeight) / s > 1280) s *= 2
        val bm = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
        val out = ByteArrayOutputStream()
        bm.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private suspend fun agent(text: String, atts: List<File>, spoken: Boolean, hist: List<Pair<String, String>>): String {
        sb.produced.clear()
        val prov = pick(atts.any { isImg(it) })
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", sysPrompt(spoken)))
        hist.forEach { msgs.put(JSONObject().put("role", if (it.first == "user") "user" else "assistant").put("content", it.second)) }
        msgs.put(userMsg(text, atts))
        var answer = ""
        var finished = false
        val maxSteps = if (cmode == "coding") 30 else 18
        for (step in 1..maxSteps) {
            val m = call(prov, msgs, Sandbox.specs())
            val calls = m.optJSONArray("tool_calls")
            val content = if (m.isNull("content")) "" else m.optString("content")
            if (calls == null || calls.length() == 0) { answer = content; finished = true; break }
            msgs.put(if (prov == "gemini") m else JSONObject().put("role", "assistant").put("content", content).put("tool_calls", calls))
            for (i in 0 until calls.length()) {
                val c = calls.getJSONObject(i); val fn = c.getJSONObject("function")
                val args = JSONObject(fn.optString("arguments").ifBlank { "{}" })
                val nm = fn.getString("name")
                val label = when (nm) {
                    "web_search" -> "🔎 searching: ${args.optString("query")}"
                    "fetch_url" -> "🌐 reading ${args.optString("url").take(60)}"
                    "run_python" -> "🐍 running python"
                    else -> "🔧 $nm ${args.optString("path", args.optString("output", ""))}"
                }
                withContext(Dispatchers.Main) { addBubble("note", label, false); if (live) liveSub?.text = label }
                val res = sb.run(nm, args)
                msgs.put(JSONObject().put("role", "tool").put("tool_call_id", c.optString("id")).put("content", res.take(8000)))
            }
        }
        if (!finished) answer = "I hit my step limit. Check 📁 to see what I made so far, then tell me to continue."
        if (answer.isBlank()) answer = "Done ✅"
        return answer
    }
}

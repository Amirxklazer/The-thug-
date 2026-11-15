package com.amir.ai14

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
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
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Base64
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.MimeTypeMap
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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

class MainActivity : Activity() {
    private lateinit var st: Store
    private lateinit var sb: Sandbox
    private val scope = MainScope()

    private lateinit var mainView: FrameLayout
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: PasteEdit
    private lateinit var titleV: TextView
    private lateinit var chipV: TextView
    private lateinit var sendBtn: FrameLayout
    private lateinit var sendIcon: ImageView
    private lateinit var micIcon: ImageView
    private lateinit var attScroll: HorizontalScrollView
    private lateinit var attStrip: LinearLayout
    private lateinit var drawer: LinearLayout
    private lateinit var scrim: View
    private lateinit var recents: LinearLayout
    private var drawerOpen = false
    private var drawerW = 0
    private var onBack: (() -> Unit)? = null
    private var themeDirty = false

    private var cmode = ""                 // "" = not picked yet, "agent", "coding"
    private var chatId = newId()
    private val ui = mutableListOf<Pair<String, String>>() // role: user / ai / att / file, text
    private val pending = mutableListOf<File>()
    private var camFile: File? = null
    private var stepsBody: LinearLayout? = null
    private var stepsHead: TextView? = null
    private var stepsN = 0

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var rec: SpeechRecognizer? = null
    private var live = false
    private var busy = false
    private var listening = false
    private var pendingMic: ((String) -> Unit)? = null

    // live mode overlay
    private var liveDlg: Dialog? = null
    private var liveStatus: TextView? = null
    private var liveSub: TextView? = null
    private var wave: WaveView? = null
    private var liveState = ""

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val textExt = setOf("txt", "md", "kt", "kts", "java", "xml", "json", "html", "htm", "css", "js", "py", "csv", "yaml", "yml", "gradle", "sql", "svg", "log", "ini", "properties", "sh")
    private val imgExt = setOf("png", "jpg", "jpeg", "webp", "gif")

    private val personas = linkedMapOf(
        "Friendly" to "Warm, upbeat and a little playful, like a smart friend who loves helping.",
        "Pro" to "Precise, calm and professional. Straight to the point, no filler, no jokes unless invited.",
        "Hype" to "High-energy hype friend. Celebrate wins, motivate, punchy short lines.",
        "Mentor" to "Patient teacher. Explain the why in simple steps, give small examples, never condescending.",
        "Chill" to "Laid-back and casual. Short relaxed sentences, easy humor, zero stiffness.",
        "Genius" to "Brilliant nerdy deep thinker. Reason carefully, weigh tradeoffs, point out edge cases, dry wit.",
        "Sassy" to "Witty and a little sarcastic in a lovable way, but always genuinely helpful and never rude."
    )

    // ───────────── small helpers ─────────────
    private fun newId() = UUID.randomUUID().toString().take(8)
    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
    private fun isImg(f: File) = f.extension.lowercase() in imgExt
    private fun size(n: Long) = if (n < 1024) "$n B" else if (n < 1048576) "${n / 1024} KB" else "%.1f MB".format(n / 1048576.0)
    private fun pretty(x: File) = x.nameWithoutExtension.replace(Regex("[_\\-]+"), " ").trim().ifBlank { x.name }
    private fun cap(k: String) = st.get("cap_$k", "1") == "1"
    private fun id(n: String) = resources.getIdentifier(n, "drawable", packageName)
    private fun lp(w: Int = MATCH, h: Int = WRAP, wt: Float = 0f) = LinearLayout.LayoutParams(w, h, wt)
    private fun fl(w: Int, h: Int, g: Int) = FrameLayout.LayoutParams(w, h, g)
    private fun gap(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }
    private fun box(fill: Int, stroke: Int = 0) = GradientDrawable().apply { setColor(fill); if (stroke != 0) setStroke(dp(1).coerceAtLeast(1), stroke) }

    private fun face(): Typeface = when (st.get("font", "Serif")) {
        "Sans" -> Typeface.DEFAULT
        "Mono" -> Typeface.MONOSPACE
        else -> Typeface.SERIF
    }

    private fun tv(t: String, sz: Float = 16f, col: Int = C.text, bold: Boolean = false, serif: Boolean = false) = TextView(this).apply {
        text = t; textSize = sz; setTextColor(col)
        typeface = if (serif) Typeface.create(Typeface.SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        else if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun ic(n: String, tint: Int = C.text, s: Int = 22) = ImageView(this).apply {
        setImageResource(id(n)); setColorFilter(tint); scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = LinearLayout.LayoutParams(dp(s), dp(s))
    }

    private fun View.onTap(f: () -> Unit) {
        setOnClickListener {
            if (st.get("haptic", "1") == "1") performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            f()
        }
    }

    private fun iconBtn(n: String, tint: Int = C.text, f: () -> Unit) = FrameLayout(this).apply {
        addView(ic(n, tint), fl(dp(22), dp(22), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        onTap(f)
    }

    private fun btn(t: String, bg: Int, fg: Int, f: () -> Unit) = TextView(this).apply {
        text = t; textSize = 16f; gravity = Gravity.CENTER; setTextColor(fg); setBackgroundColor(bg)
        typeface = Typeface.DEFAULT_BOLD; setPadding(dp(12), dp(15), dp(12), dp(15))
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) }
        onTap(f)
    }

    private fun toast(s: String) {
        val t = TextView(this).apply {
            text = s; textSize = 14f; setTextColor(C.text); setBackgroundColor(C.card2)
            setPadding(dp(18), dp(12), dp(18), dp(12)); elevation = dp(8).toFloat()
        }
        addContentView(t, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(110) })
        t.postDelayed({ (t.parent as? ViewGroup)?.removeView(t) }, 2200)
    }

    private fun toggle(on0: Boolean, f: (Boolean) -> Unit): View {
        var on = on0
        val track = FrameLayout(this)
        val knob = View(this)
        track.addView(knob, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER_VERTICAL or Gravity.START).apply { setMargins(dp(3), 0, dp(3), 0) })
        fun paint() {
            track.setBackgroundColor(if (on) C.accent else C.card2)
            knob.setBackgroundColor(if (on) Color.WHITE else C.sub)
            (knob.layoutParams as FrameLayout.LayoutParams).gravity = Gravity.CENTER_VERTICAL or (if (on) Gravity.END else Gravity.START)
            knob.requestLayout()
        }
        paint()
        track.onTap { on = !on; paint(); f(on) }
        track.layoutParams = LinearLayout.LayoutParams(dp(46), dp(26))
        return track
    }

    private fun row(icon: String?, title: String, sub: String? = null, tint: Int = C.text, trailing: View? = null, chevron: Boolean = trailing == null, onClick: (() -> Unit)? = null): View {
        val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(C.card); setPadding(dp(16), dp(14), dp(14), dp(14)) }
        if (icon != null) r.addView(ic(icon, tint, 22), lp(dp(22), dp(22)).apply { rightMargin = dp(16) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv(title, 17f, tint))
        if (sub != null) col.addView(tv(sub, 14f, C.sub).apply { setPadding(0, dp(2), 0, 0) })
        r.addView(col, lp(0, WRAP, 1f))
        if (trailing != null) r.addView(trailing) else if (chevron) r.addView(ic("ic_chev", C.hint, 18))
        if (onClick != null) r.onTap(onClick)
        r.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(2) }
        return r
    }

    private fun sheet(title: String?, build: (LinearLayout, () -> Unit) -> Unit) {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.sheet); setPadding(dp(14), dp(10), dp(14), dp(18)) }
        col.addView(View(this).apply { setBackgroundColor(C.card2) }, lp(dp(40), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(14) })
        if (title != null) col.addView(tv(title, 21f, C.text, serif = true).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(12)) })
        build(col) { d.dismiss() }
        d.setContentView(ScrollView(this).apply { addView(col) })
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(MATCH, WRAP)
            setWindowAnimations(android.R.style.Animation_InputMethod)
        }
        d.show()
    }

    private fun opt(col: LinearLayout, title: String, sub: String?, selected: Boolean, f: () -> Unit) {
        col.addView(row(null, title, sub, if (selected) C.accent else C.text, trailing = if (selected) ic("ic_check", C.accent, 20) else View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)) }, onClick = f))
    }

    private fun confirm(msg: String, okLabel: String, f: () -> Unit) = sheet(null) { col, close ->
        col.addView(tv(msg, 18f, C.text, serif = true).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(4), dp(8), dp(10)) })
        col.addView(btn(okLabel, C.accent, Color.WHITE) { close(); f() })
        col.addView(btn("Cancel", C.card2, C.text) { close() })
    }

    private fun hideKb() { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(window.decorView.windowToken, 0) }

    // ───────────── theme ─────────────
    private fun applyTheme() {
        val m = st.get("cm", "Dark")
        val dark = when (m) {
            "Light" -> false
            "Dark" -> true
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        C.dark = dark
        if (dark) {
            C.bg = 0xFF121212.toInt(); C.card = 0xFF1E1E1E.toInt(); C.card2 = 0xFF2B2B2B.toInt(); C.sheet = 0xFF1A1A1A.toInt()
            C.text = 0xFFEDEDED.toInt(); C.sub = 0xFF9A9A9A.toInt(); C.hint = 0xFF6F6F6F.toInt(); C.line = 0xFF2E2E2E.toInt()
        } else {
            C.bg = 0xFFFAF9F5.toInt(); C.card = 0xFFFFFFFF.toInt(); C.card2 = 0xFFEFEDE6.toInt(); C.sheet = 0xFFFFFFFF.toInt()
            C.text = 0xFF1F1E1D.toInt(); C.sub = 0xFF6B6A68.toInt(); C.hint = 0xFF9C9A95.toInt(); C.line = 0xFFE3E0D8.toInt()
        }
        C.accent = (0xFF000000 or st.get("accent", "D97757").toLong(16)).toInt()
        window.statusBarColor = C.bg
        window.navigationBarColor = C.bg
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (dark) 0 else (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
    }

    // ───────────── lifecycle ─────────────
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        st = Store(this, "_")
        applyTheme()
        drawerW = (resources.displayMetrics.widthPixels * 0.84f).toInt()
        tts = TextToSpeech(this) { s ->
            ttsReady = s == TextToSpeech.SUCCESS
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { runOnUiThread { if (live) liveListen() } }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { runOnUiThread { if (live) liveListen() } }
            })
        }
        val uid = st.get("uid")
        if (uid.isBlank()) showLogin() else startApp(uid)
    }

    override fun onDestroy() {
        super.onDestroy()
        wave?.run(false); liveDlg?.dismiss()
        tts?.shutdown(); rec?.destroy(); scope.cancel()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val b = onBack
        if (drawerOpen) closeDrawer() else if (b != null) b() else super.onBackPressed()
    }

    private fun startApp(uid: String) {
        st = Store(this, uid); sb = Sandbox(this, st)
        applyTheme()
        ui.clear(); pending.clear(); cmode = ""
        buildMain()
        newChat()
        if (st.get("grkey").isBlank() && st.get("gkey").isBlank() && st.get("qkey").isBlank()) keysPage { backToMain() }
    }

    private fun enter(uid: String, email: String, name: String, method: String) {
        st.put("uid", uid); st.put("email", email); st.put("name", name); st.put("method", method)
        startApp(uid)
    }

    private fun signOut() {
        saveChat(); stopLive()
        st.put("uid", ""); st = Store(this, "_"); showLogin()
    }

    // ───────────── screens ─────────────
    private fun showScreen(title: String, body: View, back: () -> Unit, right: View? = null) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.bg) }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        bar.addView(iconBtn("ic_back") { back() })
        bar.addView(tv(title, 20f, C.text, serif = true).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, lp(0, WRAP, 1f))
        bar.addView(right ?: View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)) })
        col.addView(bar)
        col.addView(body, lp(MATCH, 0, 1f))
        onBack = back
        setContentView(col)
    }

    private fun backToMain() {
        onBack = null
        if (themeDirty) { themeDirty = false; buildMain(); renderChat() }
        setContentView(mainView)
    }

    private fun scrolled(v: View) = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(v) }

    // ───────────── main screen ─────────────
    private fun buildMain() {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.bg) }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        titleV = tv("14AI", 18f, C.text, serif = true).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        bar.addView(iconBtn("ic_menu") { openDrawer() })
        bar.addView(titleV, lp(0, WRAP, 1f))
        bar.addView(iconBtn("ic_artifact") { filesScreen() })
        bar.addView(iconBtn("ic_new") { newChat() })
        bar.addView(iconBtn("ic_more") { chatMenu() })

        scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(16)) }
        scroll.addView(list)

        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = box(C.card, C.line); setPadding(dp(12), dp(10), dp(12), dp(10)) }
        attStrip = LinearLayout(this)
        attScroll = HorizontalScrollView(this).apply { visibility = View.GONE; isHorizontalScrollBarEnabled = false; addView(attStrip) }
        card.addView(attScroll, lp().apply { bottomMargin = dp(8) })
        input = PasteEdit(this).apply {
            hint = "Message 14AI"; setHintTextColor(C.hint); setTextColor(C.text); textSize = 17f
            background = null; maxLines = 6; setPadding(dp(2), dp(4), dp(2), dp(4))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            onImages = { us -> addUris(us) }
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { updSend() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        card.addView(input, lp())
        val row2 = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val plus = FrameLayout(this).apply { setBackgroundColor(C.card2); addView(ic("ic_plus", C.text, 22), fl(dp(22), dp(22), Gravity.CENTER)); onTap { addSheet() } }
        row2.addView(plus, lp(dp(40), dp(40)))
        chipV = tv("", 14f, C.text).apply {
            setBackgroundColor(C.card2); gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), 0, dp(10), 0)
            compoundDrawablePadding = dp(6)
            val d = getDrawable(id("ic_chev_down"))!!
            d.mutate().setTint(C.sub); d.setBounds(0, 0, dp(16), dp(16))
            setCompoundDrawables(null, null, d, null)
            onTap { modelSheet() }
        }
        row2.addView(chipV, lp(WRAP, dp(40)).apply { leftMargin = dp(10) })
        row2.addView(View(this), lp(0, 1, 1f))
        micIcon = ic("ic_mic", C.text, 22)
        row2.addView(FrameLayout(this).apply { addView(micIcon, fl(dp(22), dp(22), Gravity.CENTER)); onTap { micTap() } }, lp(dp(44), dp(40)))
        sendIcon = ic("ic_wave", C.bg, 22)
        sendBtn = FrameLayout(this).apply { addView(sendIcon, fl(dp(22), dp(22), Gravity.CENTER)); onTap { sendOrLive() } }
        row2.addView(sendBtn, lp(dp(44), dp(40)).apply { leftMargin = dp(6) })
        card.addView(row2, lp().apply { topMargin = dp(6) })
        val wrap = FrameLayout(this).apply { setPadding(dp(10), dp(4), dp(10), dp(10)); addView(card) }

        content.addView(bar)
        content.addView(scroll, lp(MATCH, 0, 1f))
        content.addView(wrap)

        mainView = FrameLayout(this)
        mainView.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
        scrim = View(this).apply { setBackgroundColor(0x99000000.toInt()); visibility = View.GONE; alpha = 0f; setOnClickListener { closeDrawer() } }
        mainView.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
        buildDrawer()
        mainView.addView(drawer, FrameLayout.LayoutParams(drawerW, MATCH))
        drawer.translationX = -drawerW.toFloat()
        drawerOpen = false
        setContentView(mainView)
        updChip(); updSend(); refreshAtt()
    }

    private fun buildDrawer() {
        drawer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.bg); setPadding(dp(18), dp(36), dp(18), dp(14)); isClickable = true }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(ic("ic_logo", C.accent, 30))
        head.addView(tv("14AI", 30f, C.text, serif = true).apply { setPadding(dp(10), 0, 0, 0) })
        drawer.addView(head, lp().apply { bottomMargin = dp(18) })
        fun nav(icon: String, label: String, f: () -> Unit) {
            val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(14), dp(4), dp(14)); onTap { closeDrawer(); f() } }
            r.addView(ic(icon, C.sub, 24), lp(dp(24), dp(24)).apply { rightMargin = dp(18) })
            r.addView(tv(label, 19f, C.text))
            drawer.addView(r)
        }
        nav("ic_chat", "Chats") { chatsScreen() }
        nav("ic_artifact", "Files") { filesScreen() }
        nav("ic_memory", "Memory") { memoryScreen() }
        nav("ic_sliders", "Settings") { settingsScreen() }
        drawer.addView(View(this).apply { setBackgroundColor(C.line) }, lp(MATCH, 1).apply { topMargin = dp(10); bottomMargin = dp(10) })
        drawer.addView(tv("Recents", 15f, C.sub).apply { setPadding(dp(4), dp(8), 0, dp(8)) })
        recents = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        drawer.addView(scrolled(recents), lp(MATCH, 0, 1f))
        val bottom = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, 0) }
        val nm = st.get("name").ifBlank { "14" }
        val av = tv(nm.take(1).uppercase(), 18f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER; setBackgroundColor(C.accent) }
        bottom.addView(av, lp(dp(48), dp(48)))
        bottom.addView(View(this), lp(0, 1, 1f))
        val nb = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(C.text); setPadding(dp(18), dp(14), dp(22), dp(14)); onTap { closeDrawer(); newChat() } }
        nb.addView(ic("ic_plus", C.bg, 20))
        nb.addView(tv("New chat", 17f, C.bg, bold = true).apply { setPadding(dp(8), 0, 0, 0) })
        bottom.addView(nb)
        drawer.addView(bottom)
    }

    private fun fillRecents() {
        recents.removeAllViews()
        st.chats().forEach { (cid, title) ->
            val t = tv(title, 16f, C.text).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(12), dp(12), dp(12), dp(12))
                if (cid == chatId) setBackgroundColor(C.card2)
                onTap { closeDrawer(); openChat(cid) }
                setOnLongClickListener { chatOptions(cid, title); true }
            }
            recents.addView(t, lp())
        }
    }

    private fun openDrawer() {
        fillRecents(); hideKb()
        scrim.visibility = View.VISIBLE
        scrim.animate().alpha(1f).setDuration(160).start()
        drawer.animate().translationX(0f).setDuration(200).start()
        drawerOpen = true
    }

    private fun closeDrawer() {
        scrim.animate().alpha(0f).setDuration(160).withEndAction { scrim.visibility = View.GONE }.start()
        drawer.animate().translationX(-drawerW.toFloat()).setDuration(200).start()
        drawerOpen = false
    }

    private fun updChip() {
        chipV.text = (if (cmode == "coding") "Coding" else "Agent") + "  " + modelFor(pickProv(), false).substringAfterLast('/').take(20)
    }

    private fun updSend() {
        val has = input.text.isNotBlank() || pending.isNotEmpty()
        sendIcon.setImageResource(id(if (has) "ic_send" else "ic_wave"))
        sendIcon.setColorFilter(if (has) Color.WHITE else C.bg)
        sendBtn.setBackgroundColor(if (has) C.accent else C.text)
    }

    private fun sendOrLive() { if (input.text.isNotBlank() || pending.isNotEmpty()) send(input.text.toString(), false) else toggleLive() }

    private fun setMicOn(on: Boolean) { listening = on; micIcon.setColorFilter(if (on) C.accent else C.text) }

    private fun micTap() {
        if (listening) { rec?.cancel(); setMicOn(false); return }
        tts?.stop(); setMicOn(true)
        listen { t -> setMicOn(false); input.setText(t); input.setSelection(t.length) }
    }

    // ───────────── sheets from the main screen ─────────────
    private fun addSheet(): Unit = sheet(null) { col, close ->
        col.addView(tv("Add to chat", 21f, C.text, serif = true).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(14)) })
        val tiles = LinearLayout(this)
        fun tile(icon: String, label: String, f: () -> Unit) {
            val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setBackgroundColor(C.card); setPadding(0, dp(16), 0, dp(14)); onTap { close(); f() } }
            t.addView(ic(icon, C.text, 26), lp(dp(26), dp(26)))
            t.addView(tv(label, 14f, C.text).apply { setPadding(0, dp(8), 0, 0); gravity = Gravity.CENTER })
            tiles.addView(t, lp(0, WRAP, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        tile("ic_camera", "Camera") { camera() }
        tile("ic_photo", "Photos") { pickFiles("image/*") }
        tile("ic_upload", "Files") { pickFiles("*/*") }
        tile("ic_paste", "Paste") { pasteImages() }
        col.addView(tiles, lp())
        col.addView(gap(10))
        col.addView(row("ic_code", "Mode", if (cmode == "coding") "Coding" else "Agent") { close(); modelSheet() })
        col.addView(row("ic_web", "Web search", trailing = toggle(cap("web")) { st.put("cap_web", if (it) "1" else "0") }))
        col.addView(row("ic_persona", "Personality", st.get("persona", "Friendly")) { close(); personaSheet() })
        col.addView(row("ic_memory", "Memory", trailing = toggle(cap("mem")) { st.put("cap_mem", if (it) "1" else "0") }))
    }

    private fun modelSheet(): Unit = sheet("Mode and model") { col, close ->
        opt(col, "Agent", "Chat, search the web, make files, images and PDFs", cmode != "coding") { setMode("agent"); close() }
        opt(col, "Coding", "Research first, then build complete projects", cmode == "coding") { setMode("coding"); close() }
        col.addView(tv("Provider", 15f, C.sub).apply { setPadding(0, dp(14), 0, dp(6)) })
        listOf("Auto", "Groq", "Gemini", "Qwen").forEach { p ->
            opt(col, p, when (p) { "Auto" -> "Groq first, then Gemini, then Qwen"; "Groq" -> "Fast open models. Best for coding"; "Gemini" -> "Google Gemini"; else -> "Alibaba Qwen" }, st.get("prov", "Auto") == p) { st.put("prov", p); updChip(); close() }
        }
    }

    private fun personaSheet(): Unit = sheet("Personality") { col, close ->
        personas.keys.forEach { n ->
            opt(col, n, personas.getValue(n).substringBefore('.'), st.get("persona", "Friendly") == n) { st.put("persona", n); close(); toast("$n personality on") }
        }
    }

    private fun setMode(m: String) {
        cmode = m; updChip()
        if (ui.isEmpty()) choose(m)
    }

    private fun chatMenu(): Unit = sheet(null) { col, close ->
        col.addView(row("ic_memory", "Memory") { close(); memoryScreen() })
        col.addView(row("ic_artifact", "Files") { close(); filesScreen() })
        col.addView(row("ic_copy", "Copy chat") {
            close()
            val txt = ui.filter { it.first == "user" || it.first == "ai" }.joinToString("\n\n") { (if (it.first == "user") "You: " else "14AI: ") + it.second }
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("chat", txt)); toast("Chat copied")
        })
        col.addView(row("ic_trash", "Delete chat", tint = C.accent) {
            close(); confirm("Delete this chat?", "Delete") { File(st.chatsDir, "$chatId.json").delete(); chatId = newId(); ui.clear(); newChat() }
        })
    }

    private fun chatOptions(cid: String, title: String): Unit = sheet(title.take(40)) { col, close ->
        col.addView(row("ic_chat", "Open") { close(); closeDrawer(); openChat(cid) })
        col.addView(row("ic_trash", "Delete", tint = C.accent) { close(); File(st.chatsDir, "$cid.json").delete(); if (cid == chatId) newChat(); fillRecents(); toast("Deleted") })
    }

    // ───────────── empty state ─────────────
    private fun modeCard(icon: String, title: String, desc: String, f: () -> Unit): View {
        val c = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; background = box(C.card, C.line); setPadding(dp(16), dp(16), dp(16), dp(16)); onTap(f) }
        val t = FrameLayout(this).apply { setBackgroundColor(C.accent); addView(ic(icon, Color.WHITE, 24), fl(dp(24), dp(24), Gravity.CENTER)) }
        c.addView(t, lp(dp(48), dp(48)).apply { rightMargin = dp(16) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv(title, 19f, C.text, serif = true, bold = true))
        col.addView(tv(desc, 14f, C.sub).apply { setPadding(0, dp(3), 0, 0) })
        c.addView(col, lp(0, WRAP, 1f))
        c.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) }
        return c
    }

    private fun emptyState() {
        list.removeAllViews()
        val b = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(22), dp(56), dp(22), 0) }
        b.addView(ic("ic_logo", C.accent, 56), lp(dp(56), dp(56)))
        b.addView(tv("What are we working on?", 28f, C.text, serif = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(6)) })
        b.addView(tv("Pick a mode to start.", 15f, C.sub).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(14)) })
        b.addView(modeCard("ic_chat", "Agent", "Chat about anything, search the web, make files, images and PDFs, run Python.") { choose("agent") })
        b.addView(modeCard("ic_code", "Coding", "Research first, then build complete apps and ship a flat zip with build.yml.") { choose("coding") })
        list.addView(b)
    }

    private fun choose(m: String) {
        cmode = m; updChip(); list.removeAllViews()
        addBubble("note", if (m == "coding") "Coding mode. Tell me what to build and I will research it first." else "Agent mode. Ask me anything or tell me to make something.", false)
        input.requestFocus()
    }

    // ───────────── chat rendering ─────────────
    private fun scrollDown() { scroll.post { scroll.fullScroll(View.FOCUS_DOWN) } }

    private fun copyText(s: String) { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("code", s)); toast("Copied") }

    private fun codeBlock(code: String, lang: String): View {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = box(C.card, C.line) }
        val h = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(2), dp(2), dp(2)) }
        h.addView(tv(lang.ifBlank { "code" }, 13f, C.sub), lp(0, WRAP, 1f))
        h.addView(iconBtn("ic_copy", C.sub) { copyText(code) })
        c.addView(h)
        val t = TextView(this).apply { text = code; typeface = Typeface.MONOSPACE; textSize = 12.5f; setTextColor(C.text); setPadding(dp(12), dp(4), dp(12), dp(12)); setTextIsSelectable(true) }
        c.addView(HorizontalScrollView(this).apply { addView(t) })
        c.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6); bottomMargin = dp(6) }
        return c
    }

    private fun aiView(text: String): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        text.split("```").forEachIndexed { i, p ->
            if (i % 2 == 0) {
                val t = p.trim('\n')
                if (t.isNotBlank()) col.addView(TextView(this).apply {
                    this.text = mdSpans(t); textSize = 16.5f; setTextColor(C.text); typeface = face(); setLineSpacing(0f, 1.18f); setTextIsSelectable(true)
                })
            } else {
                val hasNl = p.contains('\n')
                val lang = if (hasNl) p.substringBefore('\n').trim().take(14) else ""
                col.addView(codeBlock((if (hasNl) p.substringAfter('\n') else p).trimEnd(), lang))
            }
        }
        return col
    }

    private fun thumb(f: File, px: Int): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (minOf(o.outWidth, o.outHeight) / (s * 2) >= px) s *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
    } catch (e: Exception) { null }

    private fun attRow(text: String): View {
        val r = LinearLayout(this)
        text.lines().filter { it.isNotBlank() }.forEach { rel ->
            val x = try { sb.f(rel) } catch (e: Exception) { null }
            val bm = if (x != null && x.exists() && isImg(x)) thumb(x, dp(72)) else null
            val v: View = if (bm != null) ImageView(this).apply { setImageBitmap(bm); scaleType = ImageView.ScaleType.CENTER_CROP }
            else LinearLayout(this).apply {
                gravity = Gravity.CENTER; orientation = LinearLayout.VERTICAL; setBackgroundColor(C.card2); setPadding(dp(4), dp(4), dp(4), dp(4))
                addView(ic("ic_file", C.sub, 22), lp(dp(22), dp(22)))
                addView(tv(rel.substringAfterLast('/').take(10), 10f, C.sub).apply { gravity = Gravity.CENTER; maxLines = 1 })
            }
            if (x != null) v.onTap { preview(rel) }
            r.addView(v, lp(dp(72), dp(72)).apply { leftMargin = dp(6) })
        }
        return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(r) }
    }

    private fun addBubble(role: String, text: String, save: Boolean = true) {
        if (save) ui.add(role to text)
        when (role) {
            "user" -> {
                val b = tv(text, 16.5f, C.text).apply { typeface = face(); setBackgroundColor(C.card2); setPadding(dp(14), dp(10), dp(14), dp(10)); setTextIsSelectable(true) }
                list.addView(b, lp(WRAP, WRAP).apply { gravity = Gravity.END; setMargins(dp(56), dp(8), dp(14), dp(8)) })
            }
            "ai" -> {
                val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                c.addView(aiView(text))
                c.addView(ic("ic_logo", C.accent, 20), lp(dp(20), dp(20)).apply { topMargin = dp(8) })
                list.addView(c, lp().apply { setMargins(dp(16), dp(10), dp(16), dp(10)) })
            }
            "att" -> list.addView(attRow(text), lp(WRAP, WRAP).apply { gravity = Gravity.END; setMargins(dp(56), dp(8), dp(14), dp(0)) })
            else -> list.addView(tv(text, 13.5f, C.sub), lp().apply { setMargins(dp(16), dp(4), dp(16), dp(4)) })
        }
        scrollDown()
    }

    private fun stepLine(label: String) {
        if (stepsBody == null) {
            val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
            val ht = tv("", 14f, C.sub)
            val chev = ic("ic_chev", C.sub, 16)
            head.addView(ic("ic_terminal", C.sub, 16), lp(dp(16), dp(16)).apply { rightMargin = dp(8) })
            head.addView(ht)
            head.addView(chev, lp(dp(16), dp(16)).apply { leftMargin = dp(4) })
            val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(dp(40), 0, dp(16), dp(6)) }
            head.onTap {
                val open = body.visibility != View.VISIBLE
                body.visibility = if (open) View.VISIBLE else View.GONE
                chev.rotation = if (open) 90f else 0f
            }
            list.addView(head, lp(WRAP, WRAP))
            list.addView(body, lp())
            stepsBody = body; stepsHead = ht
        }
        stepsN++
        stepsHead?.text = if (stepsN == 1) "Ran 1 step" else "Ran $stepsN steps"
        stepsBody?.addView(tv(label, 13f, C.hint))
        scrollDown()
    }

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
        "Image" -> "ic_photo"; "Archive" -> "ic_archive"; "Document" -> "ic_file"; "Web page" -> "ic_web"; "Text" -> "ic_note"; "Code" -> "ic_code"; else -> "ic_file"
    }

    private fun fileCard(rel: String, deletable: Boolean = false, onDeleted: () -> Unit = {}): View? {
        val x = try { sb.f(rel) } catch (e: Exception) { return null }
        if (!x.exists()) return null
        val bm = if (isImg(x)) thumb(x, dp(72)) else null
        val tile: View = if (bm != null) ImageView(this).apply { setImageBitmap(bm); scaleType = ImageView.ScaleType.CENTER_CROP }
        else FrameLayout(this).apply { setBackgroundColor(C.card2); addView(ic(iconOf(x), C.accent, 26), fl(dp(26), dp(26), Gravity.CENTER)) }
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(6), 0) }
        info.addView(tv(pretty(x), 16f, C.text).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        info.addView(tv("${kindOf(x)} \u00b7 ${x.extension.uppercase()} \u00b7 ${size(x.length())}", 13f, C.sub).apply { setPadding(0, dp(3), 0, 0) })
        val card = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; background = box(C.card, C.line); setPadding(dp(10), dp(10), dp(4), dp(10)); onTap { preview(rel) } }
        card.addView(tile, lp(dp(64), dp(64)))
        card.addView(info, lp(0, WRAP, 1f))
        card.addView(iconBtn("ic_download", C.sub) { save(rel) })
        card.addView(iconBtn("ic_share", C.sub) { share(rel) })
        if (deletable) card.addView(iconBtn("ic_trash", C.sub) { confirm("Delete ${x.name}?", "Delete") { x.delete(); onDeleted() } })
        return card
    }

    private fun renderChat() {
        list.removeAllViews(); stepsBody = null
        if (ui.isEmpty()) { if (cmode == "") emptyState() }
        else ui.toList().forEach { (r, t) ->
            if (r == "file") fileCard(t)?.let { c -> list.addView(c, lp().apply { setMargins(dp(14), dp(5), dp(14), dp(5)) }) }
            else addBubble(r, t, false)
        }
        titleV.text = chatTitle(); updChip()
    }

    // ───────────── chats ─────────────
    private fun chatTitle(): String =
        ui.firstOrNull { it.first == "user" }?.second?.lines()?.firstOrNull()?.take(40)
            ?: ui.firstOrNull { it.first == "att" }?.second?.lines()?.firstOrNull()?.substringAfterLast('/')?.take(40)
            ?: "14AI"

    private fun saveChat() {
        if (ui.none { it.first == "user" || it.first == "att" }) return
        val arr = JSONArray(); ui.forEach { arr.put(JSONArray().put(it.first).put(it.second)) }
        File(st.chatsDir, "$chatId.json").writeText(JSONObject().put("title", chatTitle()).put("cm", cmode).put("ui", arr).toString())
    }

    private fun newChat() {
        saveChat(); chatId = newId(); ui.clear(); pending.clear(); refreshAtt(); updSend()
        cmode = ""; stepsBody = null
        emptyState(); titleV.text = "14AI"; updChip()
        if (onBack != null) backToMain()
    }

    private fun openChat(cid: String) {
        try {
            saveChat()
            val o = JSONObject(File(st.chatsDir, "$cid.json").readText())
            chatId = cid; ui.clear()
            cmode = o.optString("cm", "agent").ifBlank { "agent" }
            val a = o.getJSONArray("ui")
            for (k in 0 until a.length()) ui.add(a.getJSONArray(k).getString(0) to a.getJSONArray(k).getString(1))
            backToMain(); renderChat()
        } catch (e: Exception) { toast("Couldn't open that chat") }
    }

    private fun chatsScreen() {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(20)) }
        fun fill() {
            body.removeAllViews()
            val cs = st.chats()
            if (cs.isEmpty()) body.addView(tv("No chats yet.", 15f, C.sub).apply { setPadding(dp(8), dp(24), 0, 0) })
            cs.forEach { (cid, title) ->
                val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; background = box(C.card, C.line); setPadding(dp(16), 0, dp(4), 0); onTap { openChat(cid) } }
                r.addView(tv(title, 16.5f, C.text).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER_VERTICAL }, lp(0, dp(56), 1f))
                r.addView(iconBtn("ic_trash", C.sub) { confirm("Delete this chat?", "Delete") { File(st.chatsDir, "$cid.json").delete(); if (cid == chatId) { chatId = newId(); ui.clear() }; fill() } })
                body.addView(r, lp().apply { bottomMargin = dp(8) })
            }
        }
        fill()
        showScreen("Chats", scrolled(body), { backToMain() })
    }

    private fun filesScreen() {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(20)) }
        fun fill() {
            body.removeAllViews()
            val fs = sb.root.walkTopDown().filter { it.isFile && sb.rel(it) != "memory.md" }.sortedByDescending { it.lastModified() }.toList()
            if (fs.isEmpty()) body.addView(tv("No files yet. Ask 14AI to make something.", 15f, C.sub).apply { setPadding(dp(8), dp(24), dp(8), 0) })
            fs.forEach { f -> fileCard(sb.rel(f), true) { fill() }?.let { body.addView(it, lp().apply { bottomMargin = dp(8) }) } }
        }
        fill()
        showScreen("Files", scrolled(body), { backToMain() })
    }

    private fun memoryScreen() {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(20)) }
        body.addView(tv("14AI saves lasting facts about you here (memory.md). Edit it freely.", 14f, C.sub).apply { setPadding(0, 0, 0, dp(10)) })
        val et = EditText(this).apply {
            setText(st.memory()); setTextColor(C.text); setHintTextColor(C.hint); hint = "Nothing saved yet."
            background = box(C.card, C.line); setPadding(dp(14), dp(12), dp(14), dp(12)); minLines = 12; gravity = Gravity.TOP; textSize = 15f
        }
        body.addView(et, lp())
        body.addView(btn("Save", C.accent, Color.WHITE) { st.memFile.writeText(et.text.toString()); toast("Memory saved"); backToMain() })
        showScreen("Memory", scrolled(body), { backToMain() })
    }
    // ───────────── settings ─────────────
    private fun paste(et: EditText) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        if (!t.isNullOrBlank()) et.setText(t) else toast("Clipboard is empty")
    }

    private fun formPage(title: String, items: List<Triple<String, String, String>>, note: String? = null, multi: Set<String> = emptySet(), header: ((LinearLayout) -> Unit)? = null, back: () -> Unit) {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(28)) }
        if (note != null) body.addView(tv(note, 14f, C.sub).apply { setPadding(0, 0, 0, dp(6)) })
        header?.invoke(body)
        val ets = items.map { (k, l, d) ->
            body.addView(tv(l, 13f, C.sub).apply { setPadding(0, dp(14), 0, dp(6)) })
            val et = EditText(this).apply {
                setText(st.get(k, "")); hint = d; setTextColor(C.text); setHintTextColor(C.hint); background = null; textSize = 15f
                setPadding(dp(12), dp(10), dp(8), dp(10))
                if (k in multi) { minLines = 6; gravity = Gravity.TOP } else setSingleLine(true)
            }
            val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; background = box(C.card, C.line) }
            r.addView(et, lp(0, WRAP, 1f))
            r.addView(iconBtn("ic_paste", C.sub) { paste(et) })
            body.addView(r, lp())
            et
        }
        body.addView(gap(18))
        body.addView(btn("Save", C.accent, Color.WHITE) {
            items.forEachIndexed { i, f -> st.put(f.first, ets[i].text.toString()) }
            toast("Saved"); updChip(); back()
        })
        showScreen(title, scrolled(body), back)
    }

    private fun keysSummary(): String {
        val n = mutableListOf<String>()
        if (st.get("grkey").isNotBlank()) n.add("Groq")
        if (st.get("gkey").isNotBlank()) n.add("Gemini")
        if (st.get("qkey").isNotBlank()) n.add("Qwen")
        return if (n.isEmpty()) "No key yet" else n.joinToString(", ")
    }

    private fun keysPage(back: () -> Unit) = formPage(
        "API keys",
        listOf(
            Triple("grkey", "Groq API key (recommended)", "gsk_..."),
            Triple("gkey", "Gemini API key (optional)", "AIza..."),
            Triple("qkey", "Qwen API key (optional)", "sk-...")
        ),
        "Tap the paste button next to a field to paste from your clipboard. One key is enough.\nGroq: console.groq.com/keys\nGemini: aistudio.google.com/apikey",
        back = back
    )

    private fun modelsPage(): Unit = formPage(
        "Models",
        listOf(
            Triple("grmodel", "Groq model, Agent", "openai/gpt-oss-120b"),
            Triple("grcmodel", "Groq model, Coding", "moonshotai/kimi-k2-instruct-0905"),
            Triple("grvmodel", "Groq model, images", "meta-llama/llama-4-scout-17b-16e-instruct"),
            Triple("gmodel", "Gemini model", "gemini-2.5-flash"),
            Triple("qmodel", "Qwen model, Agent", "qwen-plus"),
            Triple("cmodel", "Qwen model, Coding", "qwen3-coder-plus"),
            Triple("qbase", "Qwen base URL", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"),
            Triple("imodel", "Image model (Gemini)", "gemini-2.5-flash-image")
        ),
        "Leave a field empty to use its default. If a model stops working, paste a newer model name here.",
        header = { body ->
            body.addView(row("ic_model", "AI provider", st.get("prov", "Auto")) {
                sheet("Provider") { col, close ->
                    listOf("Auto", "Groq", "Gemini", "Qwen").forEach { p -> opt(col, p, null, st.get("prov", "Auto") == p) { st.put("prov", p); close(); modelsPage() } }
                }
            })
        },
        back = { settingsScreen() }
    )

    private fun capPage() {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(28)) }
        fun cp(k: String, icon: String, title: String, sub: String) =
            body.addView(row(icon, title, sub, trailing = toggle(cap(k)) { st.put("cap_$k", if (it) "1" else "0") }))
        cp("web", "ic_web", "Web search", "Search the internet and read pages")
        cp("py", "ic_terminal", "Python", "Run Python to draw images and process files")
        cp("img", "ic_photo", "Image generation", "Create photo-style images with Gemini")
        cp("mem", "ic_memory", "Memory", "Remember lasting facts in memory.md")
        showScreen("Capabilities", scrolled(body), { settingsScreen() })
    }

    private fun privacyPage() {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(28)) }
        body.addView(row("ic_trash", "Delete all chats", "Removes every saved conversation") {
            confirm("Delete all chats?", "Delete") { st.chatsDir.listFiles()?.forEach { it.delete() }; chatId = newId(); ui.clear(); toast("Chats deleted") }
        })
        body.addView(row("ic_trash", "Clear memory", "Erases memory.md") {
            confirm("Clear memory?", "Clear") { st.memFile.delete(); toast("Memory cleared") }
        })
        body.addView(row("ic_trash", "Delete all files", "Empties the sandbox") {
            confirm("Delete every file in the sandbox?", "Delete") { sb.root.listFiles()?.forEach { if (it.name != "memory.md") it.deleteRecursively() }; toast("Files deleted") }
        })
        showScreen("Privacy", scrolled(body), { settingsScreen() })
    }

    private fun colorBlock(): View {
        val b = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.card); setPadding(dp(16), dp(14), dp(16), dp(16)) }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(ic("ic_moon", C.text, 22), lp(dp(22), dp(22)).apply { rightMargin = dp(16) })
        head.addView(tv("Color mode", 17f, C.text))
        b.addView(head)
        val cur = st.get("cm", "Dark")
        val tiles = LinearLayout(this).apply { setPadding(0, dp(12), 0, 0) }
        listOf("Light", "Dark", "System").forEach { k ->
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                onTap { st.put("cm", k); applyTheme(); themeDirty = true; settingsScreen() }
            }
            c.addView(PreviewView(this, k, cur == k, C.accent), lp(MATCH, dp(70)))
            c.addView(tv(k, 15f, if (cur == k) C.accent else C.sub).apply { setPadding(0, dp(6), 0, 0) })
            tiles.addView(c, lp(0, WRAP, 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
        b.addView(tiles)
        val sw = LinearLayout(this).apply { setPadding(0, dp(16), 0, 0); gravity = Gravity.CENTER_VERTICAL }
        sw.addView(tv("Accent", 15f, C.sub), lp(0, WRAP, 1f))
        listOf("D97757", "4C8DFF", "35D49A", "9B7BFF", "FF5C8A").forEach { hx ->
            val s = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#$hx")); onTap { st.put("accent", hx); applyTheme(); themeDirty = true; settingsScreen() } }
            if (C.accent == (0xFF000000 or hx.toLong(16)).toInt()) s.addView(ic("ic_check", Color.WHITE, 18), fl(dp(18), dp(18), Gravity.CENTER))
            sw.addView(s, lp(dp(32), dp(32)).apply { leftMargin = dp(8) })
        }
        b.addView(sw)
        return b
    }

    private fun fontSheet(): Unit = sheet("Font style") { col, close ->
        listOf("Serif", "Sans", "Mono").forEach { f ->
            opt(col, f, null, st.get("font", "Serif") == f) { st.put("font", f); close(); renderChat(); settingsScreen() }
        }
    }

    private fun profilePage(): Unit = formPage(
        "Profile",
        listOf(Triple("name", "Name", "Your name")),
        if (st.get("method") == "guest") "Guest account on this phone." else "Signed in as ${st.get("email")}",
        back = { settingsScreen() }
    )

    private fun settingsScreen() {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(6), dp(14), dp(28)) }
        fun group(vararg v: View) { v.forEach { body.addView(it) }; body.addView(gap(12)) }
        val who = if (st.get("method") == "guest") "Guest" else st.get("name").ifBlank { st.get("email") }
        group(row("ic_user", "Profile", who) { profilePage() })
        group(
            row("ic_key", "API keys", keysSummary()) { keysPage { settingsScreen() } },
            row("ic_model", "Models", st.get("prov", "Auto")) { modelsPage() }
        )
        val nCap = listOf("web", "py", "img", "mem").count { cap(it) }
        group(
            row("ic_bolt", "Capabilities", "$nCap enabled") { capPage() },
            row("ic_persona", "Personality", st.get("persona", "Friendly")) { personaSheet() },
            row("ic_edit", "Custom instructions", if (st.get("custom").isBlank()) "None" else "On") {
                formPage("Custom instructions", listOf(Triple("custom", "Always follow these", "Example: answer in French")), "Added to every chat.", setOf("custom")) { settingsScreen() }
            }
        )
        group(
            colorBlock(),
            row("ic_font", "Font style", st.get("font", "Serif")) { fontSheet() },
            row("ic_wave", "Voice and dictation", st.get("lang", "en-US")) {
                formPage("Voice and dictation", listOf(Triple("lang", "Language (en-US, fr-FR, ar-DZ)", "en-US")), "Used for the mic and for 14AI's spoken voice.") { settingsScreen() }
            }
        )
        group(
            row("ic_haptic", "Haptic feedback", trailing = toggle(st.get("haptic", "1") == "1") { st.put("haptic", if (it) "1" else "0") }),
            row("ic_shield", "Privacy") { privacyPage() }
        )
        group(row("ic_logout", "Log out", tint = C.accent, chevron = false) { confirm("Log out of 14AI?", "Log out") { signOut() } })
        showScreen("Settings", scrolled(body), { backToMain() })
    }

    // ───────────── login ─────────────
    private fun field(h: String, type: Int) = EditText(this).apply {
        hint = h; setHintTextColor(C.hint); setTextColor(C.text); background = box(C.card, C.line)
        setPadding(dp(16), dp(14), dp(16), dp(14)); inputType = type; setSingleLine(true)
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) }
    }

    private fun showLogin() {
        onBack = null
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(28), dp(64), dp(28), dp(28)); gravity = Gravity.CENTER_HORIZONTAL }
        col.addView(ic("ic_logo", C.accent, 72), lp(dp(72), dp(72)))
        col.addView(tv("14AI", 40f, C.text, serif = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) })
        col.addView(tv("Your personal AI agent", 16f, C.sub).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(28)) })
        val em = field("Email", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val pw = field("Password (6+ characters)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val err = tv("", 13f, 0xFFFF8A80.toInt()).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) }
        val go: (Boolean) -> Unit = { create ->
            val e = em.text.toString(); val p = pw.text.toString()
            err.text = "One moment"
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { Auth.email(st, e, p, create) }
                    enter(Auth.uid(e), e.trim().lowercase(), e.trim().substringBefore("@"), "email")
                } catch (x: Exception) { err.text = x.message ?: "Something went wrong" }
            }
        }
        col.addView(em); col.addView(pw); col.addView(err)
        col.addView(btn("Sign in", C.accent, Color.WHITE) { go(false) })
        col.addView(btn("Create account", C.card2, C.text) { go(true) })
        col.addView(tv("Continue as guest", 15f, C.sub).apply { gravity = Gravity.CENTER; setPadding(0, dp(24), 0, dp(10)); onTap { enter("guest", "", "Guest", "guest") } })
        col.addView(tv("Login setup (optional)", 13f, C.accent).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(6))
            onTap { formPage("Login setup", listOf(Triple("fbkey", "Firebase Web API key (optional)", "AIza...")), "Email login works with no setup as an account on this phone. Paste a Firebase Web API key to use real cloud accounts.") { showLogin() } }
        })
        setContentView(ScrollView(this).apply { isFillViewport = true; setBackgroundColor(C.bg); addView(col) })
    }

    // ───────────── preview / save / share ─────────────
    private fun preview(rel: String) {
        val x = try { sb.f(rel) } catch (e: Exception) { return }
        if (!x.exists()) return
        val ext = x.extension.lowercase()
        val body: View = when {
            ext in imgExt || ext == "svg" || ext == "html" || ext == "htm" -> WebView(this).apply {
                setBackgroundColor(0xFF111111.toInt())
                settings.javaScriptEnabled = true; settings.allowFileAccess = true
                settings.builtInZoomControls = true; settings.displayZoomControls = false
                settings.useWideViewPort = true; settings.loadWithOverviewMode = true
                if (ext in imgExt) loadDataWithBaseURL("file://${x.parent}/", "<body style='margin:0;background:#111'><img src='${Uri.encode(x.name)}' style='max-width:100%'></body>", "text/html", "utf-8", null)
                else loadUrl("file://${x.path}")
            }
            ext in textExt -> scrolled(TextView(this).apply { text = x.readText().take(30000); typeface = Typeface.MONOSPACE; textSize = 12.5f; setTextColor(C.text); setTextIsSelectable(true); setPadding(dp(16), dp(16), dp(16), dp(16)) })
            ext == "zip" || ext == "jar" -> scrolled(TextView(this).apply {
                val names = try { ZipFile(x).use { z -> z.entries().toList().map { it.name } } } catch (e: Exception) { emptyList() }
                text = "${names.size} entries\n\n" + names.take(150).joinToString("\n") + if (names.size > 150) "\n..." else ""
                typeface = Typeface.MONOSPACE; textSize = 12.5f; setTextColor(C.text); setPadding(dp(16), dp(16), dp(16), dp(16))
            })
            else -> tv("No preview for .$ext files. Use download or share.", 15f, C.sub).apply { gravity = Gravity.CENTER; setPadding(dp(24), dp(48), dp(24), 0) }
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.bg) }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        lateinit var dlg: Dialog
        top.addView(iconBtn("ic_close") { dlg.dismiss() })
        top.addView(tv(pretty(x), 18f, C.text, serif = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(8), 0, 0, 0) }, lp(0, WRAP, 1f))
        top.addView(iconBtn("ic_download") { save(rel) })
        top.addView(iconBtn("ic_share") { share(rel) })
        col.addView(top)
        col.addView(body, lp(MATCH, 0, 1f))
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
            toast("Saved to Downloads/14AI")
        } catch (e: Exception) { toast("Save failed: ${e.message}") }
    }

    // ───────────── attachments (files, photos, camera, paste) ─────────────
    private fun pickFiles(mime: String) {
        try {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply {
                type = mime; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true); addCategory(Intent.CATEGORY_OPENABLE)
            }, 1)
        } catch (e: Exception) { toast("No file picker found") }
    }

    private fun camera() {
        try {
            val f = File(sb.root, "uploads/camera_${System.currentTimeMillis()}.jpg").apply { parentFile?.mkdirs() }
            camFile = f
            val uri = Uri.Builder().scheme("content").authority("com.amir.ai14.share").path("uploads/" + f.name).build()
            val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri); clipData = ClipData.newRawUri("", uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivityForResult(i, 2)
        } catch (e: Exception) { toast("No camera app available") }
    }

    private fun pasteImages() {
        val clip = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        val us = if (clip == null) emptyList() else (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        if (us.isEmpty()) toast("No image on the clipboard") else addUris(us)
    }

    private fun copyIn(u: Uri): File {
        var name = ""
        contentResolver.query(u, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) { val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) name = c.getString(i) ?: "" }
        }
        if (name.isBlank()) {
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(contentResolver.getType(u) ?: "") ?: "png"
            name = "pasted_${System.currentTimeMillis()}.$ext"
        }
        var dest = File(sb.root, "uploads/" + name.replace("/", "_")).apply { parentFile?.mkdirs() }
        if (dest.exists()) dest = File(dest.parentFile, "${System.currentTimeMillis()}_${dest.name}")
        contentResolver.openInputStream(u)!!.use { ins -> dest.outputStream().use { ins.copyTo(it) } }
        return dest
    }

    private fun addUris(us: List<Uri>) {
        scope.launch {
            val added = withContext(Dispatchers.IO) { us.mapNotNull { u -> try { copyIn(u) } catch (e: Exception) { null } } }
            added.forEach { if (pending.size < 12) pending.add(it) }
            if (added.size < us.size) toast("Some files couldn't be added")
            refreshAtt(); updSend()
        }
    }

    private fun refreshAtt() {
        attStrip.removeAllViews()
        attScroll.visibility = if (pending.isEmpty()) View.GONE else View.VISIBLE
        pending.toList().forEach { f ->
            val bm = if (isImg(f)) thumb(f, dp(56)) else null
            val v: View = if (bm != null) ImageView(this).apply { setImageBitmap(bm); scaleType = ImageView.ScaleType.CENTER_CROP }
            else LinearLayout(this).apply {
                gravity = Gravity.CENTER; orientation = LinearLayout.VERTICAL; setBackgroundColor(C.card2); setPadding(dp(4), dp(4), dp(4), dp(4))
                addView(ic("ic_file", C.sub, 20), lp(dp(20), dp(20)))
                addView(tv(f.name.take(9), 10f, C.sub).apply { gravity = Gravity.CENTER; maxLines = 1 })
            }
            val fr = FrameLayout(this)
            fr.addView(v, fl(dp(56), dp(56), Gravity.CENTER))
            fr.addView(FrameLayout(this).apply {
                setBackgroundColor(0xCC000000.toInt()); addView(ic("ic_close", Color.WHITE, 12), fl(dp(12), dp(12), Gravity.CENTER))
                onTap { pending.remove(f); refreshAtt(); updSend() }
            }, fl(dp(20), dp(20), Gravity.TOP or Gravity.END))
            attStrip.addView(fr, lp(dp(62), dp(62)))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(rq: Int, rs: Int, d: Intent?) {
        super.onActivityResult(rq, rs, d)
        if (rq == 2) {
            val f = camFile
            if (rs == RESULT_OK && f != null && f.exists() && f.length() > 0) { pending.add(f); refreshAtt(); updSend() } else f?.delete()
            return
        }
        if (rq != 1 || rs != RESULT_OK || d == null) return
        val uris = mutableListOf<Uri>()
        val cd = d.clipData
        if (cd != null) { for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri) } else { d.data?.let { uris.add(it) } }
        addUris(uris)
    }

    // ───────────── voice + live mode ─────────────
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            if (live) liveListen() else pendingMic?.let { listen(it) }
        } else { toast("Mic permission is needed for voice"); stopLive() }
    }

    private fun listen(onText: (String) -> Unit) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingMic = onText
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7); return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { toast("No speech recognizer on this phone. Install Google app or Speech Services."); stopLive(); setMicOn(false); return }
        rec?.destroy()
        rec = SpeechRecognizer.createSpeechRecognizer(this).also { r ->
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) { input.hint = "Listening" }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {
                    if (live && liveState.startsWith("Listening")) wave?.amp = 0.12f + v.coerceIn(0f, 10f) / 10f * 0.75f
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
                    input.hint = "Message 14AI"
                    val t = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!t.isNullOrBlank()) onText(t) else { setMicOn(false); if (live && !busy) relisten() }
                }
                override fun onError(e: Int) {
                    input.hint = "Message 14AI"
                    if (!live) setMicOn(false)
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

    private fun liveListen() { if (live && !busy) { setLive("Listening"); listen { t -> input.setText(""); send(t, true) } } }
    private fun relisten() { Handler(Looper.getMainLooper()).postDelayed({ liveListen() }, 500) }

    private fun setLive(s: String, sub: String? = null) {
        liveState = s
        liveStatus?.text = s
        if (sub != null) liveSub?.text = sub
        wave?.amp = when {
            s.startsWith("Thinking") -> 0.22f
            s.startsWith("Speaking") -> 0.6f
            else -> 0.15f
        }
    }

    private fun showLive() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setBackgroundColor(C.bg); setPadding(dp(24), dp(20), dp(24), dp(28)) }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(View(this), lp(0, 1, 1f))
        top.addView(iconBtn("ic_close") { stopLive() })
        col.addView(top, lp())
        liveStatus = tv("Listening", 26f, C.text, serif = true).apply { gravity = Gravity.CENTER }
        col.addView(liveStatus, lp().apply { topMargin = dp(20) })
        wave = WaveView(this).apply { color = C.accent; onTap { if (liveState.startsWith("Speaking")) { tts?.stop(); liveListen() } }; run(true) }
        col.addView(FrameLayout(this).apply { addView(wave, fl(MATCH, dp(150), Gravity.CENTER)) }, lp(MATCH, 0, 1f))
        liveSub = tv("Talk to me. Tap the bars to interrupt.", 15f, C.sub).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.15f) }
        col.addView(scrolled(liveSub!!), lp(MATCH, dp(150)))
        col.addView(btn("End live mode", C.card2, C.text) { stopLive() })
        liveDlg = Dialog(this, android.R.style.Theme_Material_NoActionBar_Fullscreen).apply {
            setContentView(col); setOnCancelListener { stopLive() }; show()
        }
    }

    private fun toggleLive() {
        if (live) stopLive() else {
            live = true; window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            showLive(); liveListen()
        }
    }

    private fun stopLive() {
        live = false
        wave?.run(false)
        tts?.stop(); rec?.cancel()
        liveDlg?.dismiss(); liveDlg = null; liveStatus = null; liveSub = null; wave = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (::micIcon.isInitialized) setMicOn(false)
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
        if (cmode == "") { cmode = "agent"; updChip(); list.removeAllViews() }
        busy = true; input.setText("")
        val atts = pending.toList(); pending.clear(); refreshAtt(); updSend()
        val hist = ui.filter { it.first == "user" || it.first == "ai" }.takeLast(14)
        if (atts.isNotEmpty()) addBubble("att", atts.joinToString("\n") { sb.rel(it) })
        if (text.isNotEmpty()) addBubble("user", text)
        titleV.text = chatTitle()
        stepsBody = null; stepsN = 0
        val think = tv("Thinking", 14f, C.sub).apply { setPadding(dp(16), dp(8), dp(16), dp(8)) }
        list.addView(think); scrollDown()
        if (live) setLive("Thinking", "You: $text")
        scope.launch {
            val reply = try {
                withContext(Dispatchers.IO) { agent(text.ifBlank { "Please look at the attached files." }, atts, spoken, hist) }
            } catch (e: Exception) { "Error: ${e.message}" }
            list.removeView(think)
            addBubble("ai", reply)
            sb.produced.take(12).forEach { r ->
                fileCard(r)?.let { c -> ui.add("file" to r); list.addView(c, lp().apply { setMargins(dp(14), dp(5), dp(14), dp(5)) }) }
            }
            scrollDown()
            saveChat(); busy = false
            if (live) { if (spoken) { setLive("Speaking", reply); speak(reply) } else liveListen() }
        }
    }

    private fun provKey(p: String) = when (p) { "groq" -> "grkey"; "gemini" -> "gkey"; else -> "qkey" }

    private fun pickProv(): String {
        val order = when (st.get("prov", "Auto")) {
            "Groq" -> listOf("groq", "gemini", "qwen")
            "Gemini" -> listOf("gemini", "groq", "qwen")
            "Qwen" -> listOf("qwen", "groq", "gemini")
            else -> listOf("groq", "gemini", "qwen")
        }
        return order.firstOrNull { st.get(provKey(it)).isNotBlank() } ?: order[0]
    }

    private fun modelFor(p: String, img: Boolean): String = when (p) {
        "groq" -> if (img) st.get("grvmodel", "meta-llama/llama-4-scout-17b-16e-instruct")
        else if (cmode == "coding") st.get("grcmodel", "moonshotai/kimi-k2-instruct-0905") else st.get("grmodel", "openai/gpt-oss-120b")
        "gemini" -> st.get("gmodel", "gemini-2.5-flash")
        else -> if (cmode == "coding") st.get("cmodel", "qwen3-coder-plus") else st.get("qmodel", "qwen-plus")
    }

    private fun baseFor(p: String) = when (p) {
        "groq" -> Api.GROQ_BASE
        "gemini" -> Api.GEMINI_BASE
        else -> st.get("qbase", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1")
    }

    private fun call(p: String, msgs: JSONArray, tools: JSONArray?, img: Boolean): JSONObject {
        val key = st.get(provKey(p))
        if (key.isBlank()) throw Exception("Add an API key first: Settings, API keys.")
        return Api.chat(baseFor(p), key, modelFor(p, img), msgs, tools) { w -> runOnUiThread { stepLine(w) } }
    }

    private fun sysPrompt(spoken: Boolean): String {
        val day = SimpleDateFormat("EEEE d MMMM yyyy", Locale.US).format(Date())
        val pName = st.get("persona", "Friendly")
        val persona = personas[pName] ?: personas.getValue("Friendly")
        val who = if (st.get("method") == "guest") "" else "The user is ${st.get("name")} (${st.get("email")})."
        val modeBlock = if (cmode == "coding") """CODING MODE. You are a senior software engineer. Work like this:
1. For anything non-trivial, web_search the current docs and library versions first and fetch_url the best page.
2. Say your file list in one short sentence, then write EVERY file completely. No TODO, no placeholders, no cut-off code.
3. Android defaults that are known to build: Gradle 8.7 on JDK 17 (CI provides them), AGP 8.5.2, Kotlin 1.9.24, compileSdk 34, minSdk 26. Include settings.gradle.kts, build.gradle.kts, app/build.gradle.kts, AndroidManifest.xml, gradle.properties and README.txt.
4. Before packing, read_file your main files and check imports, names, types and that every referenced resource exists. Fix problems, then pack.
5. Keep code simple and standard. Prefer plain APIs you are sure about over clever ones."""
        else "AGENT MODE. You are a general assistant. Chat normally about anything and use tools only when they help."
        val mem = if (cap("mem")) "MEMORY: memory.md holds lasting facts about the user. When you learn something lasting (name, preferences, projects, goals) call memory_save. Use memory_forget when asked to forget. Current memory:\n${st.memory().ifBlank { "(empty)" }.takeLast(4000)}" else ""
        return """You are 14AI, a capable personal AI agent inside an Android app. Today is $day. $who
PERSONALITY ($pName): $persona Never use emojis. Stay in character but never be less accurate or useful.
$modeBlock
TOOLS: you own a private sandbox. When the user wants a file (code, image, document, archive), CREATE it with tools, then say briefly what you made. The app shows file cards automatically, so never paste whole files into chat.
FILE NAMES: you always choose short, clear, lowercase_with_underscores names (home_dashboard.png, budget_report.pdf, Calculator_v1.0.zip). Never ask the user what to call a file.
WEB: use web_search and fetch_url when facts may be recent or you are unsure (versions, docs, news, prices). Search first, read the best result, then answer and name your sources briefly.
FORMATS: text files with write_file. Images: draw with run_python (Pillow, numpy; fonts ImageFont.truetype('/system/fonts/Roboto-Regular.ttf', size) with load_default as fallback), or write HTML/SVG then render_html_to_image (UI mockups at 1280x800), or generate_image for photo-style art. PDF: make_pdf. Office files, epub, jar, zip: write the inner files then zip_folder with the right extension. Android projects: build the normal folder in ONE sandbox folder then call flat_zip(folder, output). Never zip_folder an Android project and never write build.yml yourself. Name it ProjectName_v1.0.zip.
run_python is Python 3.12 with only Pillow and numpy, no internet. You cannot compile Kotlin or Java.
STYLE: light markdown is fine: **bold**, `code`, - bullets and fenced code blocks. No tables. Short paragraphs.
$mem
Reply in the user's language.
${if (spoken) "LIVE VOICE MODE: your reply is spoken aloud. Use short natural sentences, no markdown, no code, no emojis." else ""}
${st.get("custom")}"""
    }

    private fun userMsg(text: String, atts: List<File>, maxImgs: Int): JSONObject {
        var t = text
        val imgs = mutableListOf<JSONObject>()
        atts.forEach { f ->
            val r = sb.rel(f)
            if (isImg(f)) {
                if (imgs.size < maxImgs) { imgs.add(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", dataUri(f)))); t += "\n[attached image: $r]" }
                else t += "\n[attached image saved at $r, not shown to you because of the image limit]"
            }
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
        while (maxOf(bo.outWidth, bo.outHeight) / s > 1024) s *= 2
        val bm = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
        val out = ByteArrayOutputStream()
        bm.compress(Bitmap.CompressFormat.JPEG, 78, out)
        return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private suspend fun agent(text: String, atts: List<File>, spoken: Boolean, hist: List<Pair<String, String>>): String {
        sb.produced.clear()
        val hasImg = atts.any { isImg(it) }
        val prov = pickProv()
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", sysPrompt(spoken)))
        hist.forEach { msgs.put(JSONObject().put("role", if (it.first == "user") "user" else "assistant").put("content", it.second)) }
        msgs.put(userMsg(text, atts, if (prov == "groq") 5 else 10))
        val tools = Sandbox.specs(cap("web"), cap("py"), cap("img"), cap("mem"))
        var answer = ""
        var finished = false
        var bad = 0
        val maxSteps = if (cmode == "coding") 30 else 18
        for (step in 1..maxSteps) {
            val m = try {
                call(prov, msgs, tools, hasImg)
            } catch (e: HttpErr) {
                if (e.code == 400 && (e.message ?: "").contains("tool_use_failed") && bad < 2) {
                    bad++
                    msgs.put(JSONObject().put("role", "user").put("content", "Your last tool call was malformed. Call the tool again with valid JSON arguments."))
                    continue
                }
                throw e
            }
            val calls = m.optJSONArray("tool_calls")
            val content = if (m.isNull("content")) "" else m.optString("content")
            if (calls == null || calls.length() == 0) { answer = content; finished = true; break }
            msgs.put(if (prov == "gemini") m else JSONObject().put("role", "assistant").put("content", content).put("tool_calls", calls))
            for (i in 0 until calls.length()) {
                val c = calls.getJSONObject(i); val fn = c.getJSONObject("function")
                val args = try { JSONObject(fn.optString("arguments").ifBlank { "{}" }) } catch (e: Exception) { JSONObject() }
                val nm = fn.getString("name")
                val label = when (nm) {
                    "web_search" -> "Searching: ${args.optString("query")}"
                    "fetch_url" -> "Reading ${args.optString("url").take(60)}"
                    "run_python" -> "Running Python"
                    "flat_zip" -> "Packing ${args.optString("output")}"
                    else -> "$nm ${args.optString("path", args.optString("output", ""))}".trim()
                }
                withContext(Dispatchers.Main) { stepLine(label); if (live) liveSub?.text = label }
                val res = sb.run(nm, args)
                msgs.put(JSONObject().put("role", "tool").put("tool_call_id", c.optString("id")).put("content", res.take(8000)))
            }
        }
        if (!finished) answer = "I hit my step limit. Open Files to see what I made so far, then tell me to continue."
        if (answer.isBlank()) answer = "Done."
        return answer
    }
}

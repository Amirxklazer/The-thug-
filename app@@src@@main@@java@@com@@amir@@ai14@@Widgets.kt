package com.amir.ai14

import android.animation.ValueAnimator
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat

/** Live colour palette. Black + orange by default, light mode available. */
internal object C {
    var dark = true
    var bg = 0; var card = 0; var card2 = 0; var sheet = 0
    var text = 0; var sub = 0; var hint = 0; var line = 0; var accent = 0
}

/** Edit box that also accepts pasted images and images from the keyboard (stickers, GIFs, screenshots). */
class PasteEdit(ctx: Context) : EditText(ctx) {
    var onImages: ((List<Uri>) -> Unit)? = null

    override fun onCreateInputConnection(ei: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(ei) ?: return null
        EditorInfoCompat.setContentMimeTypes(ei, arrayOf("image/*"))
        val cb = InputConnectionCompat.OnCommitContentListener { info, flags, _ ->
            var ok = true
            if (Build.VERSION.SDK_INT >= 25 && (flags and InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION) != 0) {
                try { info.requestPermission() } catch (e: Exception) { ok = false }
            }
            if (ok) onImages?.invoke(listOf(info.contentUri))
            ok
        }
        return InputConnectionCompat.createWrapper(ic, ei, cb)
    }

    override fun onTextContextMenuItem(id: Int): Boolean {
        if (id == android.R.id.paste) {
            val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
            if (clip != null && clip.itemCount > 0 && clip.description.hasMimeType("image/*")) {
                val us = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
                if (us.isNotEmpty()) { onImages?.invoke(us); return true }
            }
        }
        return super.onTextContextMenuItem(id)
    }
}

/** Square audio bars used by live mode. */
class WaveView(ctx: Context) : View(ctx) {
    private val p = Paint()
    var color = Color.WHITE
    var amp = 0.2f
    private var phase = 0f
    private val anim = ValueAnimator.ofFloat(0f, 6.2831855f).apply {
        duration = 1500
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    fun run(on: Boolean) { if (on) { if (!anim.isRunning) anim.start() } else anim.cancel() }

    override fun onDraw(c: Canvas) {
        val n = 11
        val bw = width / (2f * n - 1)
        p.color = color
        for (i in 0 until n) {
            val s = 0.5f + 0.5f * kotlin.math.sin(phase + i * 0.65f)
            val h = (height * (0.10f + amp.coerceIn(0f, 0.9f) * s)).coerceAtMost(height.toFloat())
            val x = i * 2 * bw
            c.drawRect(x, (height - h) / 2f, x + bw, (height + h) / 2f, p)
        }
    }
}

/** Little Light / Dark / System mock screens for the colour mode picker. */
class PreviewView(ctx: Context, private val kind: String, private val sel: Boolean, private val accent: Int) : View(ctx) {
    private val p = Paint()
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val light = 0xFFFAF9F5.toInt(); val dark = 0xFF161616.toInt()
        p.style = Paint.Style.FILL
        when (kind) {
            "Light" -> { p.color = light; c.drawRect(0f, 0f, w, h, p) }
            "Dark" -> { p.color = dark; c.drawRect(0f, 0f, w, h, p) }
            else -> {
                p.color = dark; c.drawRect(0f, 0f, w, h, p)
                val path = Path(); path.moveTo(0f, 0f); path.lineTo(w, 0f); path.lineTo(0f, h); path.close()
                p.color = light; c.drawPath(path, p)
            }
        }
        p.color = if (kind == "Light") 0xFF8A8883.toInt() else if (kind == "Dark") 0xFF8A8A8A.toInt() else 0xFF8A8A8A.toInt()
        c.drawRect(w * 0.14f, h * 0.20f, w * 0.60f, h * 0.27f, p)
        c.drawRect(w * 0.14f, h * 0.36f, w * 0.40f, h * 0.43f, p)
        p.color = accent
        c.drawRect(w * 0.72f, h * 0.60f, w * 0.72f + h * 0.22f, h * 0.82f, p)
        if (sel) {
            p.style = Paint.Style.STROKE; p.strokeWidth = 4f; p.color = accent
            c.drawRect(2f, 2f, w - 2f, h - 2f, p)
        }
    }
}

/** Tiny markdown: **bold**, `code`, # headings, - bullets. */
internal fun mdSpans(src: String): CharSequence {
    val sb = SpannableStringBuilder()
    val ls = src.lines()
    val fl = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
    ls.forEachIndexed { idx, raw ->
        var line = raw
        var hdr = 0
        val h = Regex("^(#{1,3})\\s+(.*)").find(line)
        if (h != null) { hdr = h.groupValues[1].length; line = h.groupValues[2] }
        else {
            val b = Regex("^(\\s*)[-*]\\s+(.*)").find(line)
            if (b != null) line = b.groupValues[1] + "\u2022 " + b.groupValues[2]
        }
        val start = sb.length
        var i = 0
        while (i < line.length) {
            if (line.startsWith("**", i)) {
                val e = line.indexOf("**", i + 2)
                if (e > 0) {
                    val s0 = sb.length
                    sb.append(line.substring(i + 2, e))
                    sb.setSpan(StyleSpan(Typeface.BOLD), s0, sb.length, fl)
                    i = e + 2
                    continue
                }
            }
            if (line[i] == '`') {
                val e = line.indexOf('`', i + 1)
                if (e > 0) {
                    val s0 = sb.length
                    sb.append(line.substring(i + 1, e))
                    sb.setSpan(TypefaceSpan("monospace"), s0, sb.length, fl)
                    sb.setSpan(BackgroundColorSpan(C.card2), s0, sb.length, fl)
                    i = e + 1
                    continue
                }
            }
            sb.append(line[i]); i++
        }
        if (hdr > 0) {
            sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, fl)
            sb.setSpan(RelativeSizeSpan(if (hdr == 1) 1.35f else if (hdr == 2) 1.2f else 1.08f), start, sb.length, fl)
        }
        if (idx < ls.size - 1) sb.append('\n')
    }
    return sb
}

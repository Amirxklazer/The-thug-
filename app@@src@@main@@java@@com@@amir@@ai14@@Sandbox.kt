package com.amir.ai14

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume

/** The AI's private workspace + the tools it can call. */
class Sandbox(private val act: Activity, private val st: Store) {
    val root: File = st.root.canonicalFile
    val produced = LinkedHashSet<String>()

    fun f(p: String): File {
        val x = File(root, p.trimStart('/')).canonicalFile
        require(x.path == root.path || x.path.startsWith(root.path + "/")) { "path escapes sandbox" }
        return x
    }

    fun rel(x: File): String = x.path.removePrefix(root.path).trimStart('/')

    private fun mark(x: File) {
        val r = rel(x)
        if (!r.startsWith("uploads/") && r != "memory.md") produced.add(r)
    }

    suspend fun run(name: String, a: JSONObject): String = try {
        when (name) {
            "write_file" -> {
                val x = f(a.getString("path")); x.parentFile?.mkdirs()
                x.writeText(a.getString("content")); mark(x)
                "ok ${rel(x)} (${x.length()} bytes)"
            }
            "write_base64" -> {
                val x = f(a.getString("path")); x.parentFile?.mkdirs()
                x.writeBytes(Base64.decode(a.getString("base64").substringAfter("base64,"), Base64.DEFAULT)); mark(x)
                "ok ${rel(x)} (${x.length()} bytes)"
            }
            "read_file" -> f(a.getString("path")).readText().take(20000)
            "list_files" -> {
                val d = f(a.optString("path", ""))
                d.walkTopDown().filter { it.isFile }.take(200).joinToString("\n") { "${rel(it)} (${it.length()}b)" }.ifBlank { "(empty)" }
            }
            "delete_file" -> { f(a.getString("path")).deleteRecursively(); "deleted" }
            "zip_folder" -> {
                val d = f(a.getString("folder")); val o = f(a.getString("output")); o.parentFile?.mkdirs()
                zipDir(d, o)
                val pre = rel(d).let { if (it.isEmpty()) "" else "$it/" }
                produced.removeAll { it.startsWith(pre) }
                mark(o)
                "ok ${rel(o)} (${o.length()} bytes)"
            }
            "flat_zip" -> {
                val d = f(a.getString("folder")); val o = f(a.getString("output")); o.parentFile?.mkdirs()
                var n = 0
                ZipOutputStream(FileOutputStream(o)).use { z ->
                    d.walkTopDown().filter { it.isFile && it != o }.forEach { fl ->
                        val p = fl.relativeTo(d).path.replace('\\', '/')
                        if (p == "build.yml" || p.startsWith(".github/") || p.startsWith(".gradle/") || p.startsWith("build/") || p.contains("/build/")) return@forEach
                        z.putNextEntry(ZipEntry(p.replace("/", "@@")))
                        fl.inputStream().use { it.copyTo(z) }
                        z.closeEntry(); n++
                    }
                    z.putNextEntry(ZipEntry("build.yml")); z.write(FLAT_BUILD_YML.toByteArray()); z.closeEntry()
                }
                val pre = rel(d).let { if (it.isEmpty()) "" else "$it/" }
                produced.removeAll { it.startsWith(pre) }
                mark(o)
                "ok ${rel(o)}: $n files flat at the zip root + build.yml"
            }
            "unzip" -> {
                val d = f(a.getString("dest")); d.mkdirs(); var n = 0
                ZipInputStream(FileInputStream(f(a.getString("path")))).use { z ->
                    var e = z.nextEntry
                    while (e != null) {
                        val o = File(d, e.name).canonicalFile
                        require(o.path.startsWith(d.canonicalPath)) { "bad zip entry" }
                        if (e.isDirectory) o.mkdirs() else { o.parentFile?.mkdirs(); o.outputStream().use { z.copyTo(it) }; n++ }
                        e = z.nextEntry
                    }
                }
                "ok extracted $n files to ${rel(d)}"
            }
            "render_html_to_image" -> {
                val src = f(a.getString("html_path")); val out = f(a.getString("output"))
                var html = src.readText()
                if (src.extension.lowercase() == "svg") html = "<html><body style='margin:0'>$html</body></html>"
                val w = a.optInt("width", 1080).coerceIn(100, 4096)
                val h = a.optInt("height", 1080).coerceIn(100, 4096)
                saveBmp(render(html, "file://${src.parent}/", w, h), out); mark(out)
                "ok ${rel(out)}"
            }
            "generate_image" -> {
                val o = f(a.getString("output")); val key = st.get("gkey")
                require(key.isNotBlank()) { "Gemini API key missing" }
                val bytes = Api.image(key, st.get("imodel", "gemini-2.5-flash-image"), a.getString("prompt"))
                saveBmp(BitmapFactory.decodeByteArray(bytes, 0, bytes.size), o); mark(o)
                "ok ${rel(o)}"
            }
            "make_pdf" -> {
                val o = f(a.getString("output")); makePdf(a.getString("text"), o); mark(o)
                "ok ${rel(o)}"
            }
            "run_python" -> {
                fun snap() = root.walkTopDown().filter { it.isFile }.associate { it.path to "${it.lastModified()}:${it.length()}" }
                val before = snap()
                if (!Python.isStarted()) Python.start(AndroidPlatform(act))
                val out = Python.getInstance().getModule("runner").callAttr("run", a.getString("code"), root.path).toString()
                root.walkTopDown().filter { it.isFile }.forEach { if (before[it.path] != "${it.lastModified()}:${it.length()}") mark(it) }
                out.ifBlank { "(ran fine, no output)" }
            }
            "web_search" -> Web.search(a.getString("query"))
            "fetch_url" -> Web.fetch(a.getString("url"))
            "memory_save" -> {
                val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                st.memFile.appendText("- $day: ${a.getString("note").trim()}\n"); "saved"
            }
            "memory_forget" -> {
                val m = a.getString("match").lowercase()
                val kept = st.memory().lines().filter { it.isNotBlank() && !it.lowercase().contains(m) }
                st.memFile.writeText(kept.joinToString("\n") + if (kept.isEmpty()) "" else "\n"); "forgotten"
            }
            else -> "ERROR: unknown tool $name"
        }
    } catch (e: Exception) {
        "ERROR: ${e.message}"
    }

    private fun zipDir(dir: File, out: File) {
        ZipOutputStream(FileOutputStream(out)).use { z ->
            dir.walkTopDown().filter { it.isFile && it != out }.forEach { fl ->
                z.putNextEntry(ZipEntry(fl.relativeTo(dir).path.replace('\\', '/')))
                fl.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun saveBmp(b: Bitmap, o: File) {
        o.parentFile?.mkdirs()
        val fmt = when (o.extension.lowercase()) {
            "jpg", "jpeg" -> Bitmap.CompressFormat.JPEG
            "webp" -> if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
            else -> Bitmap.CompressFormat.PNG
        }
        FileOutputStream(o).use { b.compress(fmt, 92, it) }
    }

    private fun makePdf(text: String, out: File) {
        val pw = 595; val ph = 842; val m = 40
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f; color = Color.BLACK }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, pw - 2 * m).build()
        val pdf = PdfDocument()
        var y = 0; var n = 1
        while (y < layout.height || n == 1) {
            val pg = pdf.startPage(PdfDocument.PageInfo.Builder(pw, ph, n).create())
            val c = pg.canvas
            c.translate(m.toFloat(), m.toFloat())
            c.clipRect(0f, 0f, (pw - 2 * m).toFloat(), (ph - 2 * m).toFloat())
            c.translate(0f, -y.toFloat())
            layout.draw(c)
            pdf.finishPage(pg)
            y += ph - 2 * m; n++
        }
        out.parentFile?.mkdirs()
        FileOutputStream(out).use { pdf.writeTo(it) }
        pdf.close()
    }

    private suspend fun render(html: String, base: String, w: Int, h: Int): Bitmap =
        withTimeout(25000) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<Bitmap> { c ->
                    val wv = WebView(act)
                    var done = false
                    wv.settings.javaScriptEnabled = true
                    wv.settings.allowFileAccess = true
                    wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    wv.translationX = -(w + 200).toFloat()
                    val deco = act.window.decorView as ViewGroup
                    deco.addView(wv, ViewGroup.LayoutParams(w, h))
                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(v: WebView, u: String?) {
                            if (done) return
                            done = true
                            v.postDelayed({
                                try {
                                    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                    val cv = Canvas(b); cv.drawColor(Color.WHITE); v.draw(cv)
                                    deco.removeView(v)
                                    c.resume(b)
                                } catch (e: Exception) {
                                    deco.removeView(v)
                                    c.resumeWith(Result.failure(e))
                                }
                            }, 700)
                        }
                    }
                    wv.loadDataWithBaseURL(base, html, "text/html", "utf-8", null)
                }
            }
        }

    companion object {
        private val FLAT_BUILD_YML = """name: Build APK
on:
  push:
    branches: [main, master]
  workflow_dispatch:
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Sort flat files into Android folders
        run: |
          python3 - <<'PY'
          import os, shutil
          for n in sorted(os.listdir('.')):
              if os.path.isfile(n) and '@@' in n:
                  d = n.replace('@@', '/')
                  os.makedirs(os.path.dirname(d), exist_ok=True)
                  shutil.move(n, d)
          PY
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17
      - uses: actions/setup-python@v5
        with:
          python-version: "3.12"
      - uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: 8.7
      - run: gradle assembleDebug --no-daemon
      - uses: actions/upload-artifact@v4
        with:
          name: app-debug-apk
          path: app/build/outputs/apk/debug/*.apk
"""

        private fun t(n: String, d: String, vararg p: Pair<String, String>, req: List<String> = p.map { it.first }): JSONObject {
            val props = JSONObject()
            p.forEach { props.put(it.first, JSONObject().put("type", it.second)) }
            val params = JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req))
            return JSONObject().put("type", "function")
                .put("function", JSONObject().put("name", n).put("description", d).put("parameters", params))
        }

        private val S = "string"
        private val I = "integer"

        fun specs(web: Boolean = true, py: Boolean = true, img: Boolean = true, mem: Boolean = true): JSONArray {
            val all = JSONArray()
            all.put(t("write_file", "Write a TEXT file (code, xml, json, html, svg, md, csv, yaml...). Creates folders automatically. YOU choose a clear descriptive file name.", "path" to S, "content" to S))
            all.put(t("write_base64", "Write a small BINARY file from base64 data.", "path" to S, "base64" to S))
            all.put(t("read_file", "Read a text file from the sandbox.", "path" to S))
            all.put(t("list_files", "List files in a sandbox folder (empty path = everything).", "path" to S, req = listOf()))
            all.put(t("delete_file", "Delete a file or folder.", "path" to S))
            all.put(t("zip_folder", "Zip a folder into any zip-based file: .zip .docx .xlsx .pptx .epub .jar", "folder" to S, "output" to S))
            all.put(t("flat_zip", "Pack an Android project folder into a FLAT zip (no folders at all: every file at the zip root, paths encoded with @@) plus a build.yml that sorts them back. ALWAYS use this for Android projects instead of zip_folder.", "folder" to S, "output" to S))
            all.put(t("unzip", "Extract a zip into a folder.", "path" to S, "dest" to S))
            all.put(t("render_html_to_image", "Render an .html or .svg file to a .png/.jpg/.webp image.", "html_path" to S, "output" to S, "width" to I, "height" to I, req = listOf("html_path", "output")))
            all.put(t("generate_image", "Generate a photo/art image with an image model and save it as .png/.jpg/.webp.", "prompt" to S, "output" to S))
            all.put(t("make_pdf", "Make a simple multi-page PDF from plain text.", "text" to S, "output" to S))
            all.put(t("run_python", "Run Python 3 code inside the sandbox folder (Pillow/PIL and numpy installed, no internet, no pip). Save images/files with relative paths. Returns printed output and errors.", "code" to S))
            all.put(t("web_search", "Search the web (current info, docs, library versions, news, anything). Returns top results with titles, URLs and snippets.", "query" to S))
            all.put(t("fetch_url", "Open a web page URL and read its text (docs, articles, GitHub readmes).", "url" to S))
            all.put(t("memory_save", "Save one durable fact about the user to memory.md.", "note" to S))
            all.put(t("memory_forget", "Remove memory lines containing this text.", "match" to S))

            val off = mutableSetOf<String>()
            if (!web) off += listOf("web_search", "fetch_url")
            if (!py) off += "run_python"
            if (!img) off += "generate_image"
            if (!mem) off += listOf("memory_save", "memory_forget")
            val r = JSONArray()
            for (i in 0 until all.length()) {
                val o = all.getJSONObject(i)
                if (o.getJSONObject("function").getString("name") !in off) r.put(o)
            }
            return r
        }
    }
}

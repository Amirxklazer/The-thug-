package com.amir.ai14

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/** Lets other apps receive files from the sandbox (share sheet) and lets the camera write into uploads/. */
class ShareProvider : ContentProvider() {
    private fun file(uri: Uri, write: Boolean = false): File {
        val ctx = context ?: throw FileNotFoundException()
        val uid = ctx.getSharedPreferences("ai14", 0).getString("uid", null) ?: throw FileNotFoundException()
        val root = File(ctx.filesDir, "users/$uid/sandbox").canonicalFile
        val f = File(root, (uri.path ?: "").trimStart('/')).canonicalFile
        if (!f.path.startsWith(root.path + "/")) throw FileNotFoundException()
        if (write) {
            if (!f.path.startsWith(root.path + "/uploads/")) throw FileNotFoundException()
            f.parentFile?.mkdirs()
            return f
        }
        if (!f.isFile) throw FileNotFoundException()
        return f
    }

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = file(uri)
        val c = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
        c.addRow(arrayOf<Any>(f.name, f.length()))
        return c
    }

    override fun getType(uri: Uri): String {
        val ext = (uri.path ?: "").substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val write = mode.contains("w")
        return ParcelFileDescriptor.open(file(uri, write), ParcelFileDescriptor.parseMode(mode))
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

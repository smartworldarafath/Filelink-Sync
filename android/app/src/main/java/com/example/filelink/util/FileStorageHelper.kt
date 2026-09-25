package com.example.filelink.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

object FileStorageHelper {

    data class OutputDestination(
        val outputStream: OutputStream,
        val publicUri: Uri?,
        val absolutePath: String?
    )

    fun createDownloadOutputStream(context: Context, filename: String, mimeType: String = "application/octet-stream"): OutputDestination? {
        val safeName = sanitizeFilename(filename)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Filelink")
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    ?: return null
                val stream = context.contentResolver.openOutputStream(uri) ?: return null
                OutputDestination(stream, uri, null)
            } else {
                @Suppress("DEPRECATION")
                val downloadDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Filelink")
                if (!downloadDir.exists()) {
                    downloadDir.mkdirs()
                }
                var targetFile = File(downloadDir, safeName)
                var counter = 1
                val base = safeName.substringBeforeLast(".")
                val ext = if (safeName.contains(".")) "." + safeName.substringAfterLast(".") else ""
                while (targetFile.exists()) {
                    targetFile = File(downloadDir, "$base ($counter)$ext")
                    counter++
                }
                val stream = FileOutputStream(targetFile)
                OutputDestination(stream, Uri.fromFile(targetFile), targetFile.absolutePath)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback to internal app files
            try {
                val fallbackDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Filelink")
                fallbackDir.mkdirs()
                val targetFile = File(fallbackDir, safeName)
                val stream = FileOutputStream(targetFile)
                OutputDestination(stream, Uri.fromFile(targetFile), targetFile.absolutePath)
            } catch (ex: Exception) {
                ex.printStackTrace()
                null
            }
        }
    }

    fun sanitizeFilename(name: String): String {
        val base = name.split('/', '\\').lastOrNull() ?: "file"
        val clean = base.replace(Regex("[\\p{Cntrl}]"), "")
        return if (clean.isBlank() || clean == "." || clean == "..") "file_${System.currentTimeMillis()}" else clean
    }

    fun openFile(context: Context, uri: Uri?, filePath: String?) {
        try {
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            if (uri != null) {
                val mime = context.contentResolver.getType(uri) ?: getMimeType(uri.toString())
                viewIntent.setDataAndType(uri, mime)
                context.startActivity(Intent.createChooser(viewIntent, "Open with"))
            } else if (filePath != null) {
                val file = File(filePath)
                val fileUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val mime = getMimeType(file.name)
                viewIntent.setDataAndType(fileUri, mime)
                context.startActivity(Intent.createChooser(viewIntent, "Open with"))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getMimeType(url: String): String {
        val extension = MimeTypeMap.getFileExtensionFromUrl(url)
        return if (extension != null) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
        } else {
            "*/*"
        }
    }
}

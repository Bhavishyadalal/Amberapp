package com.amber.player

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
import java.io.FileInputStream

object FileHelper {

    fun mimeFor(file: File): String {
        val ext = file.extension.lowercase()
        val fromMap = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        if (!fromMap.isNullOrBlank()) return fromMap
        return when (ext) {
            "mp4", "mkv", "webm", "avi" -> "video/*"
            "m4a", "mp3", "opus", "ogg", "wav", "flac", "aac" -> "audio/*"
            else -> "application/octet-stream"
        }
    }

    fun contentUri(context: Context, file: File): Uri {
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    fun openFile(context: Context, path: String): Boolean {
        val file = File(path)
        if (!file.isFile) return false
        return try {
            val uri = contentUri(context, file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeFor(file))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun shareFile(context: Context, path: String): Boolean {
        val file = File(path)
        if (!file.isFile) return false
        return try {
            val uri = contentUri(context, file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mimeFor(file)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, context.getString(R.string.share_title)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun exportToPublic(context: Context, path: String): Boolean {
        val file = File(path)
        if (!file.isFile) return false
        val mime = mimeFor(file)
        val isVideo = mime.startsWith("video")
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collection = if (isVideo) {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        if (isVideo) Environment.DIRECTORY_MOVIES + "/Amber"
                        else Environment.DIRECTORY_MUSIC + "/Amber"
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(collection, values) ?: return false
                resolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(file).use { input -> input.copyTo(out) }
                } ?: return false
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(
                        if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC
                    ),
                    "Amber"
                )
                if (!dir.exists()) dir.mkdirs()
                val dest = File(dir, file.name)
                file.copyTo(dest, overwrite = true)
                val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(dest))
                context.sendBroadcast(intent)
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}

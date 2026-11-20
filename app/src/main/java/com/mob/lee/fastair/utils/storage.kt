package com.mob.lee.fastair.utils

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.preference.PreferenceManager
import com.mob.lee.fastair.R
import java.io.File
import java.io.FileOutputStream

fun Context.getPaths(file: File?): List<File> {
    if (Environment.MEDIA_MOUNTED == Environment.getExternalStorageState()) {
        val path=file?: Environment.getExternalStorageDirectory()
        return path.listFiles { dir, name ->
            dir.isDirectory && !name.startsWith(".")
        }?.sortedBy {it.name.lowercase() } ?: emptyList()
    }
    return emptyList()
}

fun Context.urlToPath(uri: Uri):String?{
    if (DocumentsContract.isDocumentUri(this, uri)) {
        if (isExternalStorageDocument(uri)) {
            val docId = DocumentsContract.getDocumentId(uri)
            val split = docId.split(":")
            val type = split[0]
            if ("primary" == (type) && split.size > 1) {
                return "${Environment.getExternalStorageDirectory()}/${split[1]}"
            }
        } else if (isDownloadsDocument(uri)) {
            val id = DocumentsContract.getDocumentId(uri)
            // Download ids may be non-numeric (e.g. "msf:1000000123"), which
            // toLong() would reject; fall through so the caller can copy instead.
            val numeric = id.toLongOrNull() ?: return null
            val contentUri = ContentUris.withAppendedId(
                Uri.parse("content://downloads/public_downloads"), numeric
            )
            return getDataColumn(this,contentUri, null, null)
        } else if (isMediaDocument(uri)) {
            val docId = DocumentsContract.getDocumentId(uri)
            val split = docId.split(":")
            if (split.size < 2) return null

            val contentUri=when(split[0]){
                "image"-> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                "video"-> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                "audio"-> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                else ->MediaStore.Files.getContentUri("external")
            }
            val selection = "_id=?"
            val selectionArgs = arrayOf(split[1])
            return getDataColumn(this,contentUri, selection, selectionArgs)
        }

    } else if ("content" == uri.scheme) {
        return getDataColumn(this,uri, null, null)
    } else if ("file" == uri.scheme) {
        return uri.path
    }
    return null
}

fun Context.updateStorage(path: String?) {
    path ?: return
    MediaScannerConnection.scanFile(this, arrayOf(path), null, null)
}

private const val SHARE_DIR = "shared"

/**
 * Resolve a Uri received from an external share to a readable file.
 *
 * The transfer pipeline reads files by absolute path, so the real path is
 * preferred. Uris that cannot be mapped to one (removable storage, third-party
 * providers) are copied into the app cache instead, which any provider supports.
 *
 * The returned file may live in the cache, so it is a temporary copy and can be
 * cleared by the system under storage pressure.
 */
fun Context.resolveShareUri(uri: Uri): File? {
    urlToPath(uri)?.let { path ->
        val file = File(path)
        if (file.canRead()) return file
    }
    return copyToCache(uri)
}

private fun Context.copyToCache(uri: Uri): File? {
    val name = contentResolver.displayName(uri) ?: "shared_${System.currentTimeMillis()}"
    val dir = File(cacheDir, SHARE_DIR)
    if (!dir.exists() && !dir.mkdirs()) return null
    val target = File(dir, name).uniqueFile()

    val stream = try {
        contentResolver.openInputStream(uri)
    } catch (e: Exception) {
        e.printStackTrace()
        return null
    } ?: return null

    return try {
        stream.use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
        if (target.length() == 0L) {
            target.delete()
            null
        } else {
            target
        }
    } catch (e: Exception) {
        e.printStackTrace()
        target.delete()
        null
    }
}

private fun File.uniqueFile(): File {
    if (!exists()) return this
    val base = nameWithoutExtension
    val ext = extension
    var i = 0
    var candidate: File
    do {
        candidate = File(parentFile, if (ext.isEmpty()) "$base($i)" else "$base($i).$ext")
        i++
    } while (candidate.exists())
    return candidate
}

private fun ContentResolver.displayName(uri: Uri): String? {
    val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
    try {
        query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                if (index >= 0) {
                    cursor.getString(index)?.let { return it }
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return uri.lastPathSegment
}

fun Context.openFile(file: String) {
    val intent = Intent(Intent.ACTION_VIEW)
    intent.data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val uri = FileProvider.getUriForFile(
                this,
                "${this.getPackageName()}.FileProvider",
                File(file))
        this.grantUriPermission(this.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        uri
    } else {
        Uri.fromFile(File(file))
    }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        this.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        errorToast(R.string.no_application_find)
    }
}

fun Context.createFile(name: String): File {
    val path = readDownloadPath()
    var file = File(path, name)
    val key = resources.getString(R.string.key_default_rewrite)
    val overrite = PreferenceManager.getDefaultSharedPreferences(this).getBoolean(key, true)
    if (file.exists() && !overrite) {
        var i = 0
        val tempName = file.nameWithoutExtension
        val suf = file.extension
        while (file.exists()) {
            file = File(path, "$tempName($i).$suf")
            i++
        }
    }
    return file
}

fun Context.readDownloadPath(): String {
    val key = resources.getString(R.string.key_default_download)
    return PreferenceManager.getDefaultSharedPreferences(this).getString(key, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath)
            ?: ""
}

fun Context.writeDownloadPath(path: String) {
    val infos = getSharedPreferences("infos", Context.MODE_PRIVATE)
    infos.edit().putString("downloadPath", path).apply()
}

fun getDataColumn(
    context: Context?,
    uri: Uri?,
    selection: String?,
    selectionArgs: Array<String>?
): String? {
    uri?:return null
    var cursor: Cursor? = null
    val column = MediaStore.MediaColumns.DATA
    val projection = arrayOf(column)
    try {
        cursor = context?.contentResolver?.query(
            uri, projection, selection, selectionArgs,
            null
        )
        if (cursor != null && cursor.moveToFirst()) {
            val column_index = cursor.getColumnIndexOrThrow(column)
            return cursor.getString(column_index)
        }
    } catch (e: Exception) {
        e.printStackTrace()
    } finally {
        if (cursor != null)
            cursor.close()
    }
    return null
}

private fun isExternalStorageDocument(uri: Uri)= "com.android.externalstorage.documents" == uri.authority

private fun isDownloadsDocument(uri: Uri)="com.android.providers.downloads.documents" == uri.authority

private fun isMediaDocument(uri: Uri)="com.android.providers.media.documents" == uri.authority
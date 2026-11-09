package com.rezaul.printerkit

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.rezaul.printerkit.model.SaveFileParams
import com.rezaul.printerkit.model.SaveFileResult
import com.rezaul.printerkit.model.FileLocation
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Copies a finished file to the destination described by [SaveFileParams]. Internal: use [PosPrinter.saveFile]. */
internal object FileSaver {

    fun save(context: Context, params: SaveFileParams): SaveFileResult {
        return try {
            val source = File(params.sourcePath)
            if (!source.isFile) return fail("Source file not found: ${params.sourcePath}")
            val name = sanitizeName(params.fileName ?: source.name)
                ?: return fail("Invalid file name")
            val mime = params.mimeType ?: guessMime(name)

            val result = when (params.location) {
                FileLocation.CACHE -> saveToDir(source, context.cacheDir, params.subFolder, name, params)
                FileLocation.APP_FILES -> saveToDir(source, context.filesDir, params.subFolder, name, params)
                FileLocation.DOWNLOADS -> savePublic(context, source, Environment.DIRECTORY_DOWNLOADS, name, mime, params)
                FileLocation.DOCUMENTS -> savePublic(context, source, Environment.DIRECTORY_DOCUMENTS, name, mime, params)
                FileLocation.CUSTOM_FOLDER -> saveToCustomFolder(context, source, name, mime, params)
            }
            if (result.success && params.deleteSource) source.delete()
            result
        } catch (e: Exception) {
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun saveToDir(source: File, base: File, subFolder: String?, name: String, params: SaveFileParams): SaveFileResult {
        val dir = resolveDir(base, subFolder)
        return copyToFile(source, File(dir, name), params.overwrite)
    }

    private fun resolveDir(base: File, subFolder: String?): File {
        val sub = subFolder?.let(::sanitizePath)
        val dir = if (sub.isNullOrEmpty()) base else File(base, sub)
        if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("Cannot create folder: ${dir.absolutePath}")
        return dir
    }

    private fun copyToFile(source: File, target: File, overwrite: Boolean): SaveFileResult {
        if (target.exists() && !overwrite) return fail("File already exists: ${target.name}")
        if (source.canonicalPath != target.canonicalPath) {
            source.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        return SaveFileResult(true, path = target.absolutePath, fileName = target.name)
    }

    private fun savePublic(
        context: Context, source: File, publicDir: String, name: String, mime: String, params: SaveFileParams
    ): SaveFileResult {
        val sub = params.subFolder?.let(::sanitizePath)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relative = if (sub.isNullOrEmpty()) publicDir else "$publicDir/$sub"
            val resolver = context.contentResolver
            val collection = if (publicDir == Environment.DIRECTORY_DOWNLOADS)
                MediaStore.Downloads.EXTERNAL_CONTENT_URI
            else MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

            if (params.overwrite) {
                // Only succeeds for entries this app created; otherwise the system renames the new file.
                resolver.query(
                    collection, arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                    arrayOf(name, "$relative/"), null
                )?.use { c ->
                    while (c.moveToNext()) {
                        runCatching { resolver.delete(Uri.withAppendedPath(collection, c.getLong(0).toString()), null, null) }
                    }
                }
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: return fail("Could not create file in $publicDir")
            try {
                resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                    ?: throw IllegalStateException("Could not open output stream")
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            return SaveFileResult(true, uri = uri.toString(), fileName = name)
        }
        // Android 9 and below: plain file access, needs WRITE_EXTERNAL_STORAGE granted by the app.
        @Suppress("DEPRECATION")
        val base = Environment.getExternalStoragePublicDirectory(publicDir)
        return copyToFile(source, File(resolveDir(base, sub), name), params.overwrite)
    }

    private fun saveToCustomFolder(
        context: Context, source: File, name: String, mime: String, params: SaveFileParams
    ): SaveFileResult {
        val folder = params.customFolder?.takeIf { it.isNotBlank() }
            ?: return fail("customFolder is required for CUSTOM_FOLDER")
        if (!folder.startsWith("content://")) {
            return copyToFile(source, File(resolveDir(File(folder), null), name), params.overwrite)
        }
        val resolver = context.contentResolver
        val treeUri = Uri.parse(folder)
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

        var existing: Uri? = null
        resolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == name) {
                    existing = DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0)); break
                }
            }
        }
        existing?.let {
            if (!params.overwrite) return fail("File already exists: $name")
            DocumentsContract.deleteDocument(resolver, it)
        }
        val created = DocumentsContract.createDocument(resolver, parent, mime, name)
            ?: return fail("Could not create file in the selected folder")
        resolver.openOutputStream(created)?.use { out: OutputStream ->
            source.inputStream().use { input: InputStream -> input.copyTo(out) }
        } ?: return fail("Could not open output stream")
        return SaveFileResult(true, uri = created.toString(), fileName = name)
    }

    private fun guessMime(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    /** File names must not contain path separators. */
    private fun sanitizeName(name: String): String? =
        name.trim().replace('/', '_').replace('\\', '_').takeIf { it.isNotEmpty() && it != "." && it != ".." }

    /** Sub-folder paths may nest ("a/b") but cannot climb out with "..". */
    private fun sanitizePath(path: String): String =
        path.split('/', '\\').filter { it.isNotBlank() && it != "." && it != ".." }.joinToString("/")

    private fun fail(message: String) = SaveFileResult(false, error = message)
}

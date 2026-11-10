package com.rezaul.printerkit

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Base64
import android.webkit.MimeTypeMap
import com.rezaul.printerkit.model.DirEntry
import com.rezaul.printerkit.model.ReadDirParams
import com.rezaul.printerkit.model.ReadDirResult
import com.rezaul.printerkit.model.ReadFileFormat
import com.rezaul.printerkit.model.ReadFileParams
import com.rezaul.printerkit.model.ReadFileResult
import com.rezaul.printerkit.model.RemoveFileParams
import com.rezaul.printerkit.model.RemoveFileResult
import com.rezaul.printerkit.model.SaveFileParams
import com.rezaul.printerkit.model.SaveFileResult
import com.rezaul.printerkit.model.FileLocation
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Copies a finished file to the destination described by [SaveFileParams]. Internal: use [PosPrinter.saveFile]. */
internal object FileSaver {

    fun save(context: Context, params: SaveFileParams): SaveFileResult {
        // Content given directly (text / base64) is written to a temp file first, so every destination below
        // keeps working from a plain source file. The temp file is always removed again.
        var temp: File? = null
        return try {
            val given = listOfNotNull(params.sourcePath, params.text, params.base64).size
            if (given != 1) return fail("Provide exactly one of sourcePath, text or base64")

            val source: File
            if (params.sourcePath != null) {
                source = File(params.sourcePath)
                if (!source.isFile) return fail("Source file not found: ${params.sourcePath}")
            } else {
                if (params.fileName.isNullOrBlank()) return fail("fileName is required when saving text or base64 content")
                val file = File(context.cacheDir, "save_${System.nanoTime()}.tmp")
                temp = file
                if (params.text != null) {
                    file.writeText(params.text, Charsets.UTF_8)
                } else {
                    // Accept a "data:...;base64," prefix as well as plain base64.
                    val raw = params.base64!!.let { if (it.startsWith("data:")) it.substringAfter("base64,") else it }
                    file.writeBytes(Base64.decode(raw, Base64.DEFAULT))
                }
                source = file
            }
            val name = sanitizeName(params.fileName ?: source.name)
                ?: return fail("Invalid file name")
            val mime = params.mimeType ?: guessMime(name)

            val result = when (params.location) {
                FileLocation.CACHE -> saveToDir(source, context.cacheDir, params.subFolder, name, params)
                FileLocation.APP_FILES -> saveToDir(source, context.filesDir, params.subFolder, name, params)
                FileLocation.DOWNLOADS -> savePublic(context, source, Environment.DIRECTORY_DOWNLOADS, name, mime, params)
                FileLocation.DOCUMENTS -> savePublic(context, source, Environment.DIRECTORY_DOCUMENTS, name, mime, params)
                FileLocation.PICTURES -> savePublic(context, source, Environment.DIRECTORY_PICTURES, name, mime, params)
                FileLocation.CUSTOM_FOLDER -> saveToCustomFolder(context, source, name, mime, params)
            }
            if (result.success && params.deleteSource && params.sourcePath != null) source.delete()
            result
        } catch (e: Exception) {
            fail(e.message ?: e.javaClass.simpleName)
        } finally {
            temp?.delete()
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
        // The Pictures collection of MediaStore only takes images; refuse early with a clear message.
        if (publicDir == Environment.DIRECTORY_PICTURES && !mime.startsWith("image/")) {
            return fail("PICTURES only accepts images (got \"$mime\"); use a .png/.jpg file name or DOWNLOADS/DOCUMENTS")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relative = if (sub.isNullOrEmpty()) publicDir else "$publicDir/$sub"
            val resolver = context.contentResolver
            val collection = when (publicDir) {
                Environment.DIRECTORY_DOWNLOADS -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
                Environment.DIRECTORY_PICTURES -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else -> MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }

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
        val result = copyToFile(source, File(resolveDir(base, sub), name), params.overwrite)
        if (result.success && publicDir == Environment.DIRECTORY_PICTURES && result.path != null) {
            // Without this the Gallery would not notice the new file until the next reboot / scan.
            android.media.MediaScannerConnection.scanFile(context, arrayOf(result.path), arrayOf(mime), null)
        }
        return result
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

    /**
     * Deletes exactly one file, given by absolute path or by `content://` Uri. Never deletes a folder and
     * never expands patterns. On Android 10+ a Uri from MediaStore can only be deleted if this app created it.
     */
    fun remove(context: Context, params: RemoveFileParams): RemoveFileResult {
        val target = params.path.trim()
        if (target.isEmpty()) return RemoveFileResult(false, error = "path is required")
        return try {
            if (target.startsWith("content://")) removeByUri(context, Uri.parse(target)) else removeByPath(target)
        } catch (e: SecurityException) {
            RemoveFileResult(false, error = "Not allowed to delete this file (this app did not create it)")
        } catch (e: Exception) {
            RemoveFileResult(false, error = e.message ?: e.javaClass.simpleName)
        }
    }

    private fun removeByPath(path: String): RemoveFileResult {
        val file = File(path)
        if (!file.isAbsolute) return RemoveFileResult(false, error = "path must be absolute: $path")
        if (!file.exists()) return RemoveFileResult(false, error = "File not found: $path")
        if (!file.isFile) return RemoveFileResult(false, error = "Not a file (folders are never deleted): $path")
        return if (file.delete()) RemoveFileResult(true, path = file.absolutePath, fileName = file.name)
        else RemoveFileResult(false, error = "Could not delete: $path")
    }

    private fun removeByUri(context: Context, uri: Uri): RemoveFileResult {
        val resolver = context.contentResolver
        if (DocumentsContract.isDocumentUri(context, uri)) {
            // A file inside a folder the user picked with the system folder picker.
            return if (DocumentsContract.deleteDocument(resolver, uri)) RemoveFileResult(true, uri = uri.toString())
            else RemoveFileResult(false, error = "Could not delete the document")
        }
        val rows = resolver.delete(uri, null, null)
        return if (rows > 0) RemoveFileResult(true, uri = uri.toString())
        else RemoveFileResult(false, error = "Nothing was deleted (the file does not exist, or this app did not create it)")
    }

    /**
     * Reads ONE folder (no recursion), by absolute path or by a folder `content://` Uri from the system folder
     * picker. Read-only. Folders first, then A-Z (case-insensitive).
     */
    fun readDir(context: Context, params: ReadDirParams): ReadDirResult {
        val target = params.path.trim()
        if (target.isEmpty()) return ReadDirResult(false, error = "path is required")
        return try {
            if (target.startsWith("content://")) readDirByUri(context, target) else readDirByPath(target)
        } catch (e: SecurityException) {
            ReadDirResult(false, path = target, error = "Not allowed to read this folder")
        } catch (e: Exception) {
            ReadDirResult(false, path = target, error = e.message ?: e.javaClass.simpleName)
        }
    }

    private fun readDirByPath(path: String): ReadDirResult {
        val dir = File(path)
        if (!dir.isAbsolute) return ReadDirResult(false, path = path, error = "path must be absolute: $path")
        if (!dir.exists()) return ReadDirResult(false, path = path, error = "Folder not found: $path")
        if (!dir.isDirectory) return ReadDirResult(false, path = path, error = "Not a folder: $path")
        // null means the folder exists but cannot be read (e.g. no storage permission for a public folder).
        val children = dir.listFiles()
            ?: return ReadDirResult(false, path = path, error = "Cannot read this folder (no permission): $path")
        val entries = children.map { f ->
            DirEntry(
                name = f.name,
                path = f.absolutePath,
                isDirectory = f.isDirectory,
                sizeBytes = if (f.isFile) f.length() else null,
                lastModified = f.lastModified().takeIf { it > 0 }
            )
        }
        return ReadDirResult(true, path = dir.absolutePath, entries = sortEntries(entries))
    }

    private fun readDirByUri(context: Context, target: String): ReadDirResult {
        val uri = Uri.parse(target)
        val treeId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (e: IllegalArgumentException) {
            return ReadDirResult(
                false, path = target,
                error = "Only a folder chosen with the system folder picker can be listed by Uri"
            )
        }
        // A sub-folder path returned by an earlier call is a document Uri inside the tree.
        val parentId = if (DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.getDocumentId(uri) else treeId
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, parentId)

        val entries = mutableListOf<DirEntry>()
        val cursor = context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            ),
            null, null, null
        ) ?: return ReadDirResult(false, path = target, error = "Could not read the folder")
        cursor.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                val isDir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                entries += DirEntry(
                    name = name,
                    path = DocumentsContract.buildDocumentUriUsingTree(uri, c.getString(0)).toString(),
                    isDirectory = isDir,
                    sizeBytes = if (isDir || c.isNull(3)) null else c.getLong(3),
                    lastModified = if (c.isNull(4)) null else c.getLong(4)
                )
            }
        }
        return ReadDirResult(true, path = target, entries = sortEntries(entries))
    }

    private fun sortEntries(entries: List<DirEntry>): List<DirEntry> =
        entries.sortedWith(compareByDescending<DirEntry> { it.isDirectory }.thenBy { it.name.lowercase() })

    /**
     * Reads ONE file (absolute path or `content://` Uri) and returns its content in the requested format. Read-only.
     * Refuses a file bigger than [ReadFileParams.maxBytes] (and, for [ReadFileFormat.BYTES], bigger than 1 MB) instead
     * of loading it into memory.
     */
    fun read(context: Context, params: ReadFileParams): ReadFileResult {
        val target = params.path.trim()
        if (target.isEmpty()) return ReadFileResult(false, error = "path is required")
        if (params.maxBytes <= 0) return ReadFileResult(false, error = "maxBytes must be positive")
        val limit = if (params.format == ReadFileFormat.BYTES) {
            minOf(params.maxBytes, ReadFileParams.MAX_BYTES_FOR_BYTES_FORMAT)
        } else params.maxBytes

        return try {
            val file = if (target.startsWith("content://")) readUriBytes(context, Uri.parse(target), limit)
            else readPathBytes(target, limit)
            if (file.error != null) return ReadFileResult(false, error = file.error)
            val data = file.data!!
            val mime = file.mime ?: "application/octet-stream"
            val base = ReadFileResult(true, params.format, sizeBytes = data.size.toLong(), mimeType = mime)
            when (params.format) {
                ReadFileFormat.BASE64 -> base.copy(content = Base64.encodeToString(data, Base64.NO_WRAP))
                ReadFileFormat.DATA_URI ->
                    base.copy(content = "data:$mime;base64," + Base64.encodeToString(data, Base64.NO_WRAP))
                ReadFileFormat.TEXT -> {
                    val charset = try {
                        java.nio.charset.Charset.forName(params.encoding ?: "UTF-8")
                    } catch (e: IllegalArgumentException) {
                        return ReadFileResult(false, error = "Unknown encoding: ${params.encoding}")
                    }
                    base.copy(content = String(data, charset))
                }
                ReadFileFormat.HEX -> base.copy(content = data.joinToString("") { "%02x".format(it.toInt() and 0xFF) })
                ReadFileFormat.BYTES -> base.copy(bytes = data.map { it.toInt() and 0xFF })
            }
        } catch (e: SecurityException) {
            ReadFileResult(false, error = "Not allowed to read this file")
        } catch (e: OutOfMemoryError) {
            ReadFileResult(false, error = "Not enough memory to read this file; lower maxBytes or read a smaller file")
        } catch (e: Exception) {
            ReadFileResult(false, error = e.message ?: e.javaClass.simpleName)
        }
    }

    private class RawFile(val data: ByteArray? = null, val mime: String? = null, val error: String? = null)

    private fun tooLarge(size: Long?, limit: Long) = RawFile(
        error = "File is " + (size?.let { "$it bytes" } ?: "larger than the limit") +
            ", over the limit of $limit bytes (raise maxBytes; the bytes format is capped at 1 MB)"
    )

    private fun readPathBytes(path: String, limit: Long): RawFile {
        val file = File(path)
        if (!file.isAbsolute) return RawFile(error = "path must be absolute: $path")
        if (!file.exists()) return RawFile(error = "File not found: $path")
        if (!file.isFile) return RawFile(error = "Not a file: $path")
        if (file.length() > limit) return tooLarge(file.length(), limit)
        return RawFile(data = file.readBytes(), mime = guessMime(file.name))
    }

    private fun readUriBytes(context: Context, uri: Uri, limit: Long): RawFile {
        val resolver = context.contentResolver
        val input = resolver.openInputStream(uri) ?: return RawFile(error = "Could not open: $uri")
        input.use { stream ->
            // The size is not known up front for a Uri, so read at most limit + 1 bytes to notice an overflow.
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                total += n
                if (total > limit) return tooLarge(null, limit)
                out.write(buffer, 0, n)
            }
            val mime = resolver.getType(uri)
            return RawFile(data = out.toByteArray(), mime = mime)
        }
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

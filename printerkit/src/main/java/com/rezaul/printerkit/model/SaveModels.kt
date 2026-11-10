package com.rezaul.printerkit.model

/**
 * Where [com.rezaul.printerkit.PosPrinter.saveFile] puts a file.
 *
 * Kept as a plain enum (no Android types) so it maps 1:1 to a string in Flutter / React Native bridges.
 */
enum class FileLocation {
    /** App cache dir. Temporary - the system may delete it. */
    CACHE,

    /** App-private persistent storage (`filesDir`). Not visible to the user or other apps. */
    APP_FILES,

    /** Public Downloads folder. Visible in the Files app. */
    DOWNLOADS,

    /** Public Documents folder. Visible in the Files app. */
    DOCUMENTS,

    /**
     * Public Pictures folder, so the image also shows up in the Gallery app. **Images only** (the file's
     * type must be `image/...`, e.g. a `.png` or `.jpg` name); anything else is refused.
     */
    PICTURES,

    /**
     * A folder chosen by the caller via [SaveFileParams.customFolder]: either a
     * `content://` tree Uri string (from the system folder picker / SAF) or an absolute directory path.
     */
    CUSTOM_FOLDER
}

/**
 * Parameters for [com.rezaul.printerkit.PosPrinter.saveFile]. Every field is a
 * primitive/string so it can cross a Flutter or React Native bridge unchanged.
 *
 * @param sourcePath absolute path of the existing file to save (e.g. the result of htmlToPdf / pdfToImage).
 * @param fileName name to save as; defaults to the source file's name.
 * @param location destination type, see [FileLocation].
 * @param customFolder required when [location] is [FileLocation.CUSTOM_FOLDER].
 * @param subFolder optional sub-folder created under the destination (not used for CUSTOM_FOLDER).
 * @param mimeType override; guessed from the file extension when null.
 * @param overwrite replace an existing file with the same name. When false, an existing file is an error
 *   for file-path destinations and auto-renamed by the system for Downloads/Documents on Android 10+.
 * @param deleteSource delete [sourcePath] after a successful save (move instead of copy).
 */
data class SaveFileParams(
    val sourcePath: String? = null,
    val fileName: String? = null,
    val location: FileLocation = FileLocation.DOWNLOADS,
    val customFolder: String? = null,
    val subFolder: String? = null,
    val mimeType: String? = null,
    val overwrite: Boolean = false,
    val deleteSource: Boolean = false,
    val text: String? = null,
    val base64: String? = null
)

/**
 * Outcome of a save. Never throws across the API: failures come back with [success] = false and [error] set.
 * [path] is set for file-path destinations, [uri] (a string) for MediaStore / SAF destinations -
 * either can be passed to `openFile`. Use [toMap] to send it over a bridge.
 */
data class SaveFileResult(
    val success: Boolean,
    val path: String? = null,
    val uri: String? = null,
    val fileName: String? = null,
    val error: String? = null
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "success" to success,
        "path" to path,
        "uri" to uri,
        "fileName" to fileName,
        "error" to error
    )
}

/**
 * Parameters for [com.rezaul.printerkit.PosPrinter.removeFile]. [path] is an absolute file path, or a
 * `content://` Uri such as the `uri` that `saveFile` returned for a file in Downloads/Documents.
 */
data class RemoveFileParams(
    val path: String
)

/** Result of `removeFile`. Never thrown: check [success]. [toMap] is bridge-friendly. */
data class RemoveFileResult(
    val success: Boolean,
    val path: String? = null,
    val uri: String? = null,
    val fileName: String? = null,
    val error: String? = null
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "success" to success,
        "path" to path,
        "uri" to uri,
        "fileName" to fileName,
        "error" to error
    )
}

/**
 * Parameters for [com.rezaul.printerkit.PosPrinter.readDir]. [path] is an absolute folder path, or a `content://`
 * Uri of a folder the user picked with the system folder picker (or a sub-folder `path` returned by an earlier call).
 */
data class ReadDirParams(
    val path: String
)

/** One file or folder inside a listed folder. [path] can be passed to `removeFile` (files) or `readDir` (folders). */
data class DirEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long? = null,
    val lastModified: Long? = null
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "name" to name, "path" to path, "isDirectory" to isDirectory,
        "sizeBytes" to sizeBytes, "lastModified" to lastModified
    )
}

/**
 * Result of `readDir`: the entries of ONE folder (not recursive), folders first and then A-Z. Never thrown: check
 * [success]. [names] is just the names, in the same order. [toMap] is bridge-friendly.
 */
data class ReadDirResult(
    val success: Boolean,
    val path: String? = null,
    val entries: List<DirEntry> = emptyList(),
    val error: String? = null
) {
    val names: List<String> get() = entries.map { it.name }

    fun toMap(): Map<String, Any?> = mapOf(
        "success" to success, "path" to path, "names" to names,
        "entries" to entries.map { it.toMap() }, "error" to error
    )
}

/**
 * Real, absolute paths for common storage locations on this device. Returned by
 * [com.rezaul.printerkit.PosPrinter.getDirectoryPaths]. Mirrors the `DocumentDirectoryPath`/
 * `CachesDirectoryPath`-style constants of other file-path libraries.
 *
 * [cache], [appFiles], [externalFiles] and [externalCache] are app-private: a plain file path into any
 * of them is writable directly, no permission needed, on every Android version - safe to use directly
 * as [com.rezaul.printerkit.model.HtmlToPdfParams.outputPath].
 *
 * [downloads], [documents] and [pictures] are the public folders. A raw write there still fails on
 * Android 10+ (scoped storage) - but [com.rezaul.printerkit.PosPrinter.htmlToPdf] detects an
 * `outputPath` under one of these and routes it through MediaStore automatically, and
 * [com.rezaul.printerkit.PosPrinter.saveFile] does the same for any file.
 */
data class DirectoryPaths(
    val cache: String,
    val appFiles: String,
    val externalFiles: String?,
    val externalCache: String?,
    val downloads: String,
    val documents: String,
    val pictures: String
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "CACHE" to cache,
        "APP_FILES" to appFiles,
        "EXTERNAL_FILES" to externalFiles,
        "EXTERNAL_CACHE" to externalCache,
        "DOWNLOADS" to downloads,
        "DOCUMENTS" to documents,
        "PICTURES" to pictures
    )
}

/** The shape [com.rezaul.printerkit.PosPrinter.readFile] hands the file's content back in. */
enum class ReadFileFormat(val wireName: String) {
    /** Plain base64 text (no line breaks): for images, PDFs and any binary file. */
    BASE64("base64"),

    /** `data:<type>;base64,<base64>`, with the type taken from the file extension: ready for an `<img>` or an upload. */
    DATA_URI("dataUri"),

    /** The file decoded as text (UTF-8 unless an `encoding` is given). Meaningless for binary files. */
    TEXT("text"),

    /** Lower-case hex digits, two per byte. */
    HEX("hex"),

    /** The raw bytes as numbers 0..255. Heavy for big files, so it is limited to 1 MB. */
    BYTES("bytes");

    companion object {
        fun fromWireName(name: String?): ReadFileFormat? = entries.firstOrNull { it.wireName == name }
    }
}

/**
 * Parameters for [com.rezaul.printerkit.PosPrinter.readFile]. [path] is an absolute file path, or a `content://` Uri
 * such as the `uri` `saveFile` returned. [encoding] only matters for [ReadFileFormat.TEXT] (e.g. "UTF-8",
 * "UTF-16", "ISO-8859-1"). Files bigger than [maxBytes] are refused instead of being loaded into memory.
 */
data class ReadFileParams(
    val path: String,
    val format: ReadFileFormat = ReadFileFormat.BASE64,
    val encoding: String? = null,
    val maxBytes: Long = DEFAULT_MAX_BYTES
) {
    companion object {
        const val DEFAULT_MAX_BYTES = 10L * 1024 * 1024
        const val MAX_BYTES_FOR_BYTES_FORMAT = 1L * 1024 * 1024
    }
}

/**
 * Result of `readFile`. Exactly one of [content] (base64, dataUri, text, hex) or [bytes] is set on success.
 * Never thrown: check [success]. [toMap] is bridge-friendly.
 */
data class ReadFileResult(
    val success: Boolean,
    val format: ReadFileFormat? = null,
    val content: String? = null,
    val bytes: List<Int>? = null,
    val sizeBytes: Long? = null,
    val mimeType: String? = null,
    val error: String? = null
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "success" to success, "format" to format?.wireName, "content" to content, "bytes" to bytes,
        "sizeBytes" to sizeBytes, "mimeType" to mimeType, "error" to error
    )
}

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
    val sourcePath: String,
    val fileName: String? = null,
    val location: FileLocation = FileLocation.DOWNLOADS,
    val customFolder: String? = null,
    val subFolder: String? = null,
    val mimeType: String? = null,
    val overwrite: Boolean = false,
    val deleteSource: Boolean = false
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

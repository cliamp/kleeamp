package stream.kleeamp.mobile.library

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.model.StationSource

/**
 * `/storage/...` path behind a SAF folder picker result, or null when the
 * provider does not point at a shared storage volume (cloud providers and the
 * Documents root have no path). Folder imports match against the library by
 * path, so they stay on the same MediaStore rows, playback uris and metadata
 * that everything else uses.
 */
fun folderPathFor(context: Context, treeUri: Uri): String? {
    val documentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
        ?: return null
    return folderPathForDocumentId(documentId) { uuid -> volumeRoot(context, uuid) }
}

/**
 * Pure half of [folderPathFor]: turns a SAF document id (`primary:Music/Album`,
 * `<uuid>:Music`, `raw:/storage/...`) into an absolute path. [rootFor] maps a
 * volume uuid to its mount point, with null naming the primary volume, so the
 * mapping stays testable without the framework.
 */
internal fun folderPathForDocumentId(documentId: String, rootFor: (String?) -> String?): String? {
    // Ids arrive percent-decoded: DocumentsContract reads them from decoded
    // path segments, so `primary:Music/My Album` is already literal.
    // The Downloads provider hands out `raw:/storage/...` ids for real files.
    if (documentId.startsWith("raw:")) return documentId.removePrefix("raw:")
    val volume = documentId.substringBefore(':', missingDelimiterValue = "")
    if (volume.isEmpty()) return null
    val root = rootFor(if (volume == "primary") null else volume) ?: return null
    val relative = documentId.substringAfter(':', "").trim('/')
    return if (relative.isEmpty()) root.trimEnd('/') else root.trimEnd('/') + "/" + relative
}

/** Mount point for a SAF volume uuid, null for the primary volume. */
private fun volumeRoot(context: Context, uuid: String?): String? {
    val volumes = runCatching {
        context.getSystemService(StorageManager::class.java)?.storageVolumes.orEmpty()
    }.getOrDefault(emptyList())
    if (uuid == null) {
        val primary = volumes.firstOrNull { it.isPrimary }
        if (primary != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            primary.directory?.absolutePath?.let { return it }
        }
        return Environment.getExternalStorageDirectory()?.absolutePath
    }
    val volume = volumes.firstOrNull { it.uuid?.equals(uuid, ignoreCase = true) == true }
    if (volume != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        volume.directory?.absolutePath?.let { return it }
    }
    // Old platforms expose no directory on a volume: the app's own folder on
    // that card still names its mount point. Unknown ids (`home:`) stay null.
    return context.getExternalFilesDirs(null).firstOrNull {
        it?.path?.startsWith("/storage/$uuid/") == true
    }?.path?.substringBefore("/Android/")
}

/**
 * Library rows that live in [folder], nested folders included. Matching by the
 * `local:` id prefix keeps it on the exact same rows the library resolved, so
 * an import adds playable members instead of lookalike duplicates.
 */
internal fun songsUnder(songs: List<Station>, folder: String): List<Station> {
    val prefix = folder.trimEnd('/') + "/"
    return songs.filter { s ->
        s.source == StationSource.Local &&
            s.id.startsWith("local:") &&
            s.id.removePrefix("local:").startsWith(prefix)
    }
}

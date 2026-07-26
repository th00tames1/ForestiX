// Android side of the boundary import: turn a Storage Access Framework
// Uri into (display name, bytes, sibling .prj text) for BoundaryImporter.
//
// A bare .shp carries no CRS of its own — the .prj sits beside it — but a
// single-document pick hands us exactly one file. We make a best effort to
// resolve the sibling (file:// paths, and content:// document ids whose
// last segment is the file name, which covers the system Files provider).
// When that fails the importer refuses with "no .prj file", which is the
// correct answer: the sheet's hint tells the cruiser to bring a .zip.

package com.hcjeong.forestix.geo

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import java.io.File

object BoundaryFilePicker {

    private const val TAG = "BoundaryPicker"

    /// Anything bigger than this is not a field boundary — refuse before
    /// pulling it into memory.
    private const val MAX_BYTES = 64L * 1024 * 1024

    /// MIME filter for ACTION_OPEN_DOCUMENT. Providers are inconsistent
    /// about SHP/KML types, so "*/*" is the only filter that reliably
    /// shows every supported file.
    val OPEN_DOCUMENT_TYPES = arrayOf("*/*")

    data class Picked(
        val fileName: String,
        val bytes: ByteArray,
        val sidecarPrj: String?,
    )

    /// Read a picked document. Throws BoundaryImportError with a message
    /// the sheet can show as-is.
    fun read(context: Context, uri: Uri): Picked {
        val name = displayName(context, uri)
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buffered = input.buffered()
                val out = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = buffered.read(chunk)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_BYTES) {
                        throw BoundaryImportError("That file is too large to import (over 64 MB).")
                    }
                    out.write(chunk, 0, read)
                }
                out.toByteArray()
            } ?: throw BoundaryImportError("That file could not be opened.")
        } catch (e: BoundaryImportError) {
            throw e
        } catch (e: Exception) {
            throw BoundaryImportError("That file could not be read (${e.message ?: "read failed"}).")
        }
        val prj = if (name.endsWith(".shp", ignoreCase = true)) siblingPrj(context, uri) else null
        return Picked(fileName = name, bytes = bytes, sidecarPrj = prj)
    }

    // MARK: - Internal

    private fun displayName(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) {
                    c.getString(idx)?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Display name lookup failed", e)
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "boundary"
    }

    /// Best-effort sibling `<stem>.prj` next to a picked `<stem>.shp`.
    private fun siblingPrj(context: Context, uri: Uri): String? {
        // Plain file paths — trivially resolvable.
        if (uri.scheme == "file") {
            val path = uri.path ?: return null
            val sibling = File(path.dropLast(4) + ".prj")
            return if (sibling.isFile) runCatching { sibling.readText() }.getOrNull() else null
        }
        return try {
            val docId = DocumentsContract.getDocumentId(uri)
            if (!docId.endsWith(".shp", ignoreCase = true)) return null
            val siblingId = docId.dropLast(4) + ".prj"
            val siblingUri = DocumentsContract.buildDocumentUri(uri.authority, siblingId)
            context.contentResolver.openInputStream(siblingUri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            }
        } catch (e: Exception) {
            // No sibling access — the importer will refuse for lack of a
            // .prj, which is exactly what we want it to do.
            Log.w(TAG, "Sibling .prj lookup failed", e)
            null
        }
    }
}

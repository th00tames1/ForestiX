// Phase 7 (Android) — backup + restore coordinator.
//
// Kotlin sibling of iOS ViewModels/BackupViewModel.swift. Wraps
// [BackupArchive] so SettingsScreen can offer a one-button "Export backup"
// (handed to the Android share sheet) and a file-picker driven restore,
// emitting the local-only ForestixLogger events at each side effect.
//
// It is a plain class (not an androidx ViewModel): the single AppEnvironment
// is already a process singleton, so the composable owns the transient UI
// state and just calls these two suspend functions inside its coroutine
// scope, mirroring how the iOS @StateObject drives the SwiftUI form.

package com.hcjeong.forestix.backup

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.hcjeong.forestix.AppEnvironment
import com.hcjeong.forestix.common.ForestixLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BackupViewModel(private val env: AppEnvironment) {

    /// Build a `.tcproj` of every project and return a share-ready content Uri
    /// plus the human-readable file size. Emits `backupCreated` once per
    /// project included (matching the iOS per-project emission). Throws
    /// [BackupError] (or any I/O failure) for the caller to surface. Runs on
    /// the IO dispatcher so zipping never janks the field UI.
    suspend fun exportAllProjects(context: Context): ExportOutcome = withContext(Dispatchers.IO) {
        val result = BackupArchive.export(
            context = context,
            env = env,
            appVersion = appVersionName(context))
        result.projectIds.forEach { pid ->
            ForestixLogger.backupCreated(pid, result.byteSize)
        }
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", result.file)
        ExportOutcome(
            shareUri = uri,
            fileName = result.file.name,
            byteSize = result.byteSize)
    }

    /// Restore a user-picked `.tcproj`. Emits `backupRestored` once per
    /// imported project, wiring the event that was previously scaffolding
    /// only. Throws [BackupError] for the caller to surface.
    suspend fun restore(context: Context, uri: Uri): RestoreResult = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw BackupError.IoFailed("Could not open the selected file.")
        val name = displayName(context, uri) ?: uri.lastPathSegment ?: "backup.tcproj"
        val result = BackupArchive.restore(
            context = context,
            env = env,
            archiveBytes = bytes,
            sourceName = name)
        result.importedProjectIds.forEach { pid ->
            ForestixLogger.backupRestored(pid, name)
        }
        result
    }

    private fun appVersionName(context: Context): String =
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }

    private fun displayName(context: Context, uri: Uri): String? =
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (_: Exception) {
            null
        }

    data class ExportOutcome(
        val shareUri: Uri,
        val fileName: String,
        val byteSize: Long,
    )
}

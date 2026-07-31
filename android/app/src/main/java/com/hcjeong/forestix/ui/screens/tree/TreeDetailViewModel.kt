// Port of iOS ViewModels/TreeDetailViewModel.swift.
// Phase 5 §5.3 TreeDetailScreen view model. REQ-TAL-006.
//
// Lets the user inspect, edit, soft-delete, or undelete a single tree. Raw
// metadata (DBH sigma, coverage, inliers, height alphas, etc.) is exposed
// read-only so auditors can confirm how the measurement was captured.

package com.hcjeong.forestix.ui.screens.tree

import com.hcjeong.forestix.data.cruise.Tree
import com.hcjeong.forestix.data.cruise.TreeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TreeDetailViewModel(tree: Tree, private val treeRepo: TreeRepository) {

    // Editable state mirrors a subset of Tree fields.
    private val _tree = MutableStateFlow(tree)
    val tree: StateFlow<Tree> = _tree.asStateFlow()

    val speciesCode = MutableStateFlow(tree.speciesCode)
    val status = MutableStateFlow(tree.status)
    /// WHEN THIS STEM WAS TALLIED. `createdAt` is the only measurement time a
    /// cruise tree has — the analysis pairs stems across two phones by capture
    /// time, so a stem that went into a notebook at the tree and into the app
    /// back at the office carries the office time until somebody corrects it.
    /// The form's Time row is that correction, so the instant is an edit mirror
    /// like the species and the notes.
    val measuredAt = MutableStateFlow(tree.createdAt)
    val dbhCm = MutableStateFlow(tree.dbhCm)
    val dbhIsIrregular = MutableStateFlow(tree.dbhIsIrregular)
    val heightM = MutableStateFlow<Float?>(tree.heightM)
    val crownClass = MutableStateFlow<String?>(tree.crownClass)
    val damageCodes = MutableStateFlow(tree.damageCodes)
    val notes = MutableStateFlow(tree.notes)
    val bearingFromCenterDeg = MutableStateFlow<Float?>(tree.bearingFromCenterDeg)
    val distanceFromCenterM = MutableStateFlow<Float?>(tree.distanceFromCenterM)

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _dirty = MutableStateFlow(false)
    val dirty: StateFlow<Boolean> = _dirty.asStateFlow()

    val isDeleted: Boolean get() = _tree.value.deletedAt != null

    fun markDirty() {
        _dirty.value = true
    }

    suspend fun save() {
        if (_isSaving.value) return
        _isSaving.value = true
        try {
            val t = _tree.value.copy()
            t.speciesCode = speciesCode.value
            t.status = status.value
            if (dbhCm.value != t.dbhCm) {
                t.dbhCm = dbhCm.value
                // A diameter typed over on this screen was not produced by any
                // estimator, so the row stops claiming one produced it — null
                // is the epoch field's word for "unknown" — AND says it was
                // typed, because clearing only the epoch re-arms the row for
                // DbhEpochRecompute. A hand correction is normally a small
                // nudge, well inside the 2 % window the bundle match uses, so
                // the row would match its own old capture and the replay would
                // write over the tape reading the cruiser came here to enter.
                // A cruiser who overrides the app has said the last word on
                // that stem.
                //
                // Mirrors iOS TreeDetailViewModel.
                t.dbhEstimatorEpoch = null
                t.dbhCaptureMode = "typed"
            }
            t.dbhIsIrregular = dbhIsIrregular.value
            if (heightM.value != t.heightM) {
                t.heightM = heightM.value
                // If a height was entered, mark as measured; otherwise cleared.
                t.heightSource = if (heightM.value != null) "measured" else null
            }
            t.crownClass = crownClass.value
            t.damageCodes = damageCodes.value
            t.notes = notes.value
            t.bearingFromCenterDeg = bearingFromCenterDeg.value
            t.distanceFromCenterM = distanceFromCenterM.value
            t.createdAt = measuredAt.value
            t.updatedAt = System.currentTimeMillis()

            _tree.value = treeRepo.update(t)
            _dirty.value = false
            _errorMessage.value = null
        } catch (e: Exception) {
            _errorMessage.value = "Save failed: ${e.message ?: e}"
        } finally {
            _isSaving.value = false
        }
    }

    /// Re-read the stored row after a write this screen did not make itself —
    /// the Plot row re-parents the tree through `TreeMover`, which reads and
    /// writes the row in the store, and a snapshot taken before that move
    /// still names the plot the tree has left (and, when the destination was
    /// already using its number, the number it no longer wears).
    ///
    /// The edit mirrors are deliberately left as the cruiser typed them: a
    /// half-finished species or note is theirs, and their Save still lands —
    /// on the row where it now lives, because `save()` copies from this
    /// re-read tree.
    suspend fun reload() {
        try {
            treeRepo.read(_tree.value.id, includeDeleted = true)?.let { fresh ->
                _tree.value = fresh
            }
        } catch (e: Exception) {
            _errorMessage.value = "Reload failed: ${e.message ?: e}"
        }
    }

    suspend fun softDelete() {
        try {
            treeRepo.delete(_tree.value.id)
            treeRepo.read(_tree.value.id, includeDeleted = true)?.let { fresh ->
                _tree.value = fresh
            }
            _errorMessage.value = null
        } catch (e: Exception) {
            _errorMessage.value = "Delete failed: ${e.message ?: e}"
        }
    }

    suspend fun undelete() {
        try {
            val t = _tree.value.copy()
            t.deletedAt = null
            t.updatedAt = System.currentTimeMillis()
            _tree.value = treeRepo.update(t)
            _errorMessage.value = null
        } catch (e: Exception) {
            _errorMessage.value = "Undelete failed: ${e.message ?: e}"
        }
    }

    /// External reset for the error alert.
    fun clearError() {
        _errorMessage.value = null
    }
}

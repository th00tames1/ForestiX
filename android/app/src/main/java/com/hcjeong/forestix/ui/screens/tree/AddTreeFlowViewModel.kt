// Port of iOS ViewModels/AddTreeFlowViewModel.swift.
// Phase 5 §5.2 AddTreeFlow view model. REQ-TAL-001..004, REQ-HGT-007.
//
// Stepper state machine: Species → DBH → Height (optional) → Extras → Review.
// Uses `HeightSubsample.shouldMeasureHeight` to decide whether the Height step
// is required; the user may still skip and accept H–D imputation later at
// plot-close time.
//
// Multistem: after a main stem is saved, the user can stamp additional child
// stems that share the same (x,y) placement but carry their own tree numbers
// and DBH values. Child stems store `parentTreeId = <main stem id>` and
// `isMultistem = true`.
//
// Red-tier DBH/Height triggers a single-shot `redTierWarning` flag that the
// Review step surfaces but does not block saving — per REQ-DBH-006/009 the
// warning is informational.

package com.hcjeong.forestix.ui.screens.tree

import com.hcjeong.forestix.data.cruise.CruiseDesign
import com.hcjeong.forestix.data.cruise.Plot
import com.hcjeong.forestix.data.cruise.Project
import com.hcjeong.forestix.data.cruise.SpeciesConfig
import com.hcjeong.forestix.data.cruise.Tree
import com.hcjeong.forestix.data.cruise.TreeRepository
import com.hcjeong.forestix.data.cruise.TreeStatus
import com.hcjeong.forestix.inventory.HeightSubsample
import com.hcjeong.forestix.sensors.Check
import com.hcjeong.forestix.sensors.ConfidenceTier
import com.hcjeong.forestix.sensors.DBHMethod
import com.hcjeong.forestix.sensors.HeightMethod
import com.hcjeong.forestix.sensors.Severity
import com.hcjeong.forestix.sensors.check
import com.hcjeong.forestix.sensors.combineChecks
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AddTreeFlowViewModel(
    // Inputs
    val project: Project,
    val design: CruiseDesign,
    val plot: Plot,
    val existingTrees: List<Tree>,
    val speciesByCode: Map<String, SpeciesConfig>,
    // Repo
    private val treeRepo: TreeRepository,
    recentSpeciesCodes: List<String> = emptyList(),
) {

    enum class Step { SPECIES, DBH, HEIGHT, EXTRAS, REVIEW }

    // Stepper
    private val _currentStep = MutableStateFlow(Step.SPECIES)
    val currentStep: StateFlow<Step> = _currentStep.asStateFlow()

    private val _history = MutableStateFlow<List<Step>>(emptyList())
    val history: StateFlow<List<Step>> = _history.asStateFlow()

    // Species
    val speciesCode = MutableStateFlow("")

    private val _recentSpeciesCodes = MutableStateFlow(recentSpeciesCodes)
    val recentSpeciesCodes: StateFlow<List<String>> = _recentSpeciesCodes.asStateFlow()

    // DBH
    val dbhCm = MutableStateFlow(0f)
    val dbhMethod = MutableStateFlow(DBHMethod.MANUAL_CALIPER)
    val dbhIsIrregular = MutableStateFlow(false)

    // Height
    val heightM = MutableStateFlow<Float?>(null)
    val heightMethod = MutableStateFlow<HeightMethod?>(null)

    private val _heightRequired = MutableStateFlow(false)
    val heightRequired: StateFlow<Boolean> = _heightRequired.asStateFlow()

    // Extras
    val status = MutableStateFlow(TreeStatus.LIVE)
    val crownClass = MutableStateFlow<String?>(null)
    val damageCodes = MutableStateFlow<List<String>>(emptyList())
    val notes = MutableStateFlow("")
    val bearingFromCenterDeg = MutableStateFlow<Float?>(null)
    val distanceFromCenterM = MutableStateFlow<Float?>(null)

    // Multistem (after main save; stamp more stems)
    val isMultistem = MutableStateFlow(false)
    val parentTreeId = MutableStateFlow<UUID?>(null)

    // Confidence + warnings
    private val _dbhConfidence = MutableStateFlow(ConfidenceTier.GREEN)
    val dbhConfidence: StateFlow<ConfidenceTier> = _dbhConfidence.asStateFlow()

    private val _heightConfidence = MutableStateFlow<ConfidenceTier?>(null)
    val heightConfidence: StateFlow<ConfidenceTier?> = _heightConfidence.asStateFlow()

    private val _redTierWarning = MutableStateFlow<String?>(null)
    val redTierWarning: StateFlow<String?> = _redTierWarning.asStateFlow()

    // Save state
    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _savedTree = MutableStateFlow<Tree?>(null)
    val savedTree: StateFlow<Tree?> = _savedTree.asStateFlow()

    /// Next tree number = max(live number) + 1.
    val nextTreeNumber: Int
        get() {
            val live = existingTrees.filter { it.deletedAt == null }
            return (live.maxOfOrNull { it.treeNumber } ?: 0) + 1
        }

    // MARK: - Apply scan results (Phase 7.1)
    //
    // The DBH and Height scan screens hand back full result records.
    // These setters let AddTreeFlowScreen forward those into the VM
    // without leaking Sensors-module types into ViewModels — the screen
    // observes the quick-measure history and unpacks each field at the
    // call site.

    fun applyDBHScan(diameterCm: Float, confidence: ConfidenceTier) {
        dbhCm.value = diameterCm
        dbhMethod.value = DBHMethod.LIDAR_PARTIAL_ARC_SINGLE_VIEW
        _dbhConfidence.value = confidence
    }

    fun applyHeightScan(heightM: Float, confidence: ConfidenceTier) {
        this.heightM.value = heightM
        heightMethod.value = HeightMethod.VIO_WALKOFF_TANGENT
        _heightConfidence.value = confidence
    }

    // MARK: - Navigation

    fun advance() {
        _history.value = _history.value + _currentStep.value
        when (_currentStep.value) {
            Step.SPECIES -> {
                evaluateHeightRequirement()
                _currentStep.value = Step.DBH
            }
            Step.DBH -> {
                recomputeDbhConfidence()
                _currentStep.value = if (_heightRequired.value) Step.HEIGHT else Step.EXTRAS
            }
            Step.HEIGHT -> {
                recomputeHeightConfidence()
                _currentStep.value = Step.EXTRAS
            }
            Step.EXTRAS -> {
                computeRedTierWarning()
                _currentStep.value = Step.REVIEW
            }
            Step.REVIEW -> Unit
        }
    }

    fun back() {
        val stack = _history.value
        val prev = stack.lastOrNull() ?: return
        _history.value = stack.dropLast(1)
        _currentStep.value = prev
    }

    fun canAdvance(): Boolean = when (_currentStep.value) {
        Step.SPECIES -> speciesByCode[speciesCode.value] != null
        Step.DBH -> dbhCm.value > 0f
        // Height step can be skipped via skipHeight().
        Step.HEIGHT -> heightM.value != null
        Step.EXTRAS -> true
        Step.REVIEW -> true
    }

    /// User chose to skip measurement; height will be imputed at plot close.
    fun skipHeight() {
        heightM.value = null
        heightMethod.value = null
        _heightConfidence.value = null
        _history.value = _history.value + _currentStep.value
        _currentStep.value = Step.EXTRAS
    }

    // MARK: - Evaluators

    private fun evaluateHeightRequirement() {
        _heightRequired.value = HeightSubsample.shouldMeasureHeight(
            rule = design.heightSubsampleRule,
            newTreeNumber = nextTreeNumber,
            newSpeciesCode = speciesCode.value,
            existingTreesOnPlot = existingTrees)
    }

    private fun recomputeDbhConfidence() {
        val sp = speciesByCode[speciesCode.value]
        if (sp == null) {
            _dbhConfidence.value = ConfidenceTier.YELLOW
            return
        }
        val d = dbhCm.value
        val checks: List<Check> = listOf(
            check(d > 0f, Severity.REJECT, "DBH must be positive"),
            check(d >= sp.expectedDbhMinCm, Severity.WARN, "DBH below species minimum"),
            check(d <= sp.expectedDbhMaxCm, Severity.WARN, "DBH above species maximum"),
        )
        _dbhConfidence.value = combineChecks(checks)
    }

    private fun recomputeHeightConfidence() {
        val h = heightM.value
        if (h == null) {
            _heightConfidence.value = null
            return
        }
        val sp = speciesByCode[speciesCode.value]
        if (sp == null) {
            _heightConfidence.value = ConfidenceTier.YELLOW
            return
        }
        val checks: List<Check> = listOf(
            check(h > 0f, Severity.REJECT, "Height must be positive"),
            check(h >= sp.expectedHeightMinM, Severity.WARN, "Height below species minimum"),
            check(h <= sp.expectedHeightMaxM, Severity.WARN, "Height above species maximum"),
        )
        _heightConfidence.value = combineChecks(checks)
    }

    private fun computeRedTierWarning() {
        val parts = mutableListOf<String>()
        if (_dbhConfidence.value == ConfidenceTier.RED) {
            parts.add("DBH is red-tier (out of expected range).")
        }
        if (_heightConfidence.value == ConfidenceTier.RED) {
            parts.add("Height is red-tier (out of expected range).")
        }
        _redTierWarning.value = if (parts.isEmpty()) null else parts.joinToString(" ")
    }

    // MARK: - Save

    suspend fun save() {
        if (_isSaving.value) return
        _isSaving.value = true
        try {
            val now = System.currentTimeMillis()
            val h = heightM.value
            val tree = Tree(
                id = UUID.randomUUID(),
                plotId = plot.id,
                treeNumber = nextTreeNumber,
                speciesCode = speciesCode.value,
                status = status.value,
                dbhCm = dbhCm.value,
                dbhMethod = dbhMethod.value,
                dbhSigmaMm = null,
                dbhRmseMm = null,
                dbhCoverageDeg = null,
                dbhNInliers = null,
                dbhConfidence = _dbhConfidence.value,
                dbhIsIrregular = dbhIsIrregular.value,
                heightM = h,
                heightMethod = heightMethod.value,
                heightSource = if (h != null) "measured" else null,
                heightSigmaM = null,
                heightDHM = null,
                heightAlphaTopDeg = null,
                heightAlphaBaseDeg = null,
                heightConfidence = _heightConfidence.value,
                bearingFromCenterDeg = bearingFromCenterDeg.value,
                distanceFromCenterM = distanceFromCenterM.value,
                boundaryCall = null,
                crownClass = crownClass.value,
                damageCodes = damageCodes.value,
                isMultistem = isMultistem.value,
                parentTreeId = parentTreeId.value,
                notes = notes.value,
                photoPath = null,
                rawScanPath = null,
                createdAt = now,
                updatedAt = now,
                deletedAt = null)

            val saved = treeRepo.create(tree)
            _savedTree.value = saved
            _errorMessage.value = null
        } catch (e: Exception) {
            _errorMessage.value = "Save failed: ${e.message ?: e}"
        } finally {
            _isSaving.value = false
        }
    }

    /// External reset for the error alert.
    fun clearError() {
        _errorMessage.value = null
    }

    /// Prepare for a follow-up multistem child. Retains species + placement
    /// from the parent, clears DBH/height/number, flags child as multistem,
    /// and returns to the DBH step.
    fun prepareMultistemChild() {
        val parent = _savedTree.value ?: return
        parentTreeId.value = parent.id
        isMultistem.value = true
        // Keep: speciesCode, bearingFromCenterDeg, distanceFromCenterM, status
        dbhCm.value = 0f
        dbhIsIrregular.value = false
        heightM.value = null
        heightMethod.value = null
        _heightConfidence.value = null
        _redTierWarning.value = null
        _savedTree.value = null
        _history.value = emptyList()
        _currentStep.value = Step.DBH
    }

    /// Reset the form to a clean state so the cruiser can keep
    /// adding trees back-to-back without leaving the flow.
    /// Recent species + status defaults survive (most cruisers tag
    /// the same species several stems in a row); everything else
    /// returns to its initial value. Resets the step to SPECIES.
    fun resetForNextTree() {
        _savedTree.value = null
        parentTreeId.value = null
        isMultistem.value = false
        dbhCm.value = 0f
        dbhIsIrregular.value = false
        heightM.value = null
        heightMethod.value = null
        _heightConfidence.value = null
        damageCodes.value = emptyList()
        notes.value = ""
        bearingFromCenterDeg.value = null
        distanceFromCenterM.value = null
        _redTierWarning.value = null
        _history.value = emptyList()
        _currentStep.value = Step.SPECIES
    }
}

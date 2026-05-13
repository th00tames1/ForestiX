package edu.oregonstate.forestrix

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import edu.oregonstate.forestrix.inventory.HeightSubsample
import edu.oregonstate.forestrix.measurement.Check
import edu.oregonstate.forestrix.measurement.ConfidenceTier
import edu.oregonstate.forestrix.measurement.Severity
import edu.oregonstate.forestrix.measurement.check
import edu.oregonstate.forestrix.measurement.combineChecks
import edu.oregonstate.forestrix.models.DbhMethod
import edu.oregonstate.forestrix.models.HeightMethod
import edu.oregonstate.forestrix.models.SpeciesCatalog
import edu.oregonstate.forestrix.models.SpeciesConfig
import edu.oregonstate.forestrix.models.Tree
import edu.oregonstate.forestrix.models.TreeStatus
import edu.oregonstate.forestrix.storage.FieldSession
import edu.oregonstate.forestrix.storage.ForestrixStore
import java.util.UUID

class AddTreeActivity : Activity() {
    private enum class Step { SPECIES, DBH, HEIGHT, EXTRAS, REVIEW }
    private enum class SaveMode { CLOSE, NEXT, STEM }

    private val primary = Color.rgb(45, 95, 74)
    private val primaryMuted = Color.argb(38, 45, 95, 74)
    private val accent = Color.rgb(201, 167, 107)
    private val canvas = Color.WHITE
    private val surface = Color.rgb(242, 242, 247)
    private val surfaceRaised = Color.rgb(247, 247, 250)
    private val divider = Color.rgb(218, 218, 224)
    private val textPrimary = Color.rgb(29, 29, 31)
    private val textSecondary = Color.rgb(106, 106, 112)
    private val confidenceOk = Color.rgb(74, 138, 92)
    private val confidenceWarn = Color.rgb(184, 137, 74)
    private val confidenceBad = Color.rgb(176, 86, 86)

    private lateinit var store: ForestrixStore
    private lateinit var session: FieldSession
    private lateinit var plotId: UUID
    private lateinit var root: LinearLayout

    private var currentStep = Step.SPECIES
    private val history = mutableListOf<Step>()

    private var speciesCode = "DF"
    private var dbhCm = 0f
    private var dbhMethod = DbhMethod.MANUAL_CALIPER
    private var dbhIsIrregular = false
    private var dbhConfidence = ConfidenceTier.GREEN
    private var heightM: Float? = null
    private var heightMethod: HeightMethod? = null
    private var heightConfidence: ConfidenceTier? = null
    private var status = TreeStatus.LIVE
    private var crownClass: String? = null
    private var damageCodes: List<String> = emptyList()
    private var notes = ""
    private var bearingFromCenterDeg: Float? = null
    private var distanceFromCenterM: Float? = null
    private var isMultistem = false
    private var parentTreeId: UUID? = null
    private var redTierWarning: String? = null

    private var dbhInput: EditText? = null
    private var dbhIrregularCheck: CheckBox? = null
    private var heightInput: EditText? = null
    private var crownInput: EditText? = null
    private var damageInput: EditText? = null
    private var bearingInput: EditText? = null
    private var distanceInput: EditText? = null
    private var notesInput: EditText? = null

    private val dbhMethods = listOf(
        "Caliper" to DbhMethod.MANUAL_CALIPER,
        "Visual" to DbhMethod.MANUAL_VISUAL,
        "Depth" to DbhMethod.RAW_DEPTH_CHORD_SILHOUETTE,
        "LiDAR single" to DbhMethod.LIDAR_PARTIAL_ARC_SINGLE_VIEW,
        "LiDAR dual" to DbhMethod.LIDAR_PARTIAL_ARC_DUAL_VIEW,
        "Irregular" to DbhMethod.LIDAR_IRREGULAR
    )
    private val heightMethods = listOf(
        "Manual entry" to HeightMethod.MANUAL_ENTRY,
        "Tape + tangent" to HeightMethod.TAPE_TANGENT,
        "VIO walk-off" to HeightMethod.ARCORE_VIO_WALKOFF_TANGENT
    )
    private val statusOptions = listOf(
        "Live" to TreeStatus.LIVE,
        "Dead standing" to TreeStatus.DEAD_STANDING,
        "Dead down" to TreeStatus.DEAD_DOWN,
        "Cull" to TreeStatus.CULL
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.WHITE
        store = ForestrixStore(this)
        session = store.loadSession()
        plotId = intent.getStringExtra(EXTRA_PLOT_ID)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: session.plot.id
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(canvas)
        }
        setContentView(root)
        render()
    }

    @Deprecated("Android framework callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQUEST_DBH -> {
                dbhCm = data.getFloatExtra(DbhScanActivity.EXTRA_DIAMETER_CM, dbhCm)
                dbhConfidence = data.getStringExtra(DbhScanActivity.EXTRA_CONFIDENCE)
                    ?.let { runCatching { ConfidenceTier.valueOf(it) }.getOrNull() }
                    ?: ConfidenceTier.YELLOW
                dbhMethod = data.getStringExtra(DbhScanActivity.EXTRA_METHOD)
                    ?.let { runCatching { DbhMethod.valueOf(it) }.getOrNull() }
                    ?: DbhMethod.RAW_DEPTH_CHORD_SILHOUETTE
                render()
            }
            REQUEST_HEIGHT -> {
                heightM = data.getFloatExtra(HeightScanActivity.EXTRA_HEIGHT_M, heightM ?: 0f)
                    .takeIf { it > 0f }
                heightConfidence = data.getStringExtra(HeightScanActivity.EXTRA_CONFIDENCE)
                    ?.let { runCatching { ConfidenceTier.valueOf(it) }.getOrNull() }
                    ?: ConfidenceTier.YELLOW
                heightMethod = data.getStringExtra(HeightScanActivity.EXTRA_METHOD)
                    ?.let { runCatching { HeightMethod.valueOf(it) }.getOrNull() }
                    ?: HeightMethod.ARCORE_VIO_WALKOFF_TANGENT
                render()
            }
        }
    }

    private fun render() {
        clearFieldRefs()
        if (currentStep == Step.REVIEW) {
            recomputeDbhConfidence()
            recomputeHeightConfidence()
            computeRedTierWarning()
        }
        root.removeAllViews()
        root.addView(navBar())
        root.addView(progressStrip())

        val scroll = ScrollView(this).apply {
            setBackgroundColor(canvas)
            isFillViewport = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(20))
        }
        scroll.addView(content)
        when (currentStep) {
            Step.SPECIES -> content.addView(speciesStep())
            Step.DBH -> content.addView(dbhStep())
            Step.HEIGHT -> content.addView(heightStep())
            Step.EXTRAS -> content.addView(extrasStep())
            Step.REVIEW -> content.addView(reviewStep())
        }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        root.addView(dividerLine())
        root.addView(actionBar())
    }

    private fun navBar(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(8))
            setBackgroundColor(Color.WHITE)

            addView(toolbarButton("Cancel") { finish() }, LinearLayout.LayoutParams(dp(92), dp(44)))

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(TextView(context).apply {
                    text = "Tree #${nextTreeNumber()}"
                    setTextColor(textPrimary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                })
                addView(TextView(context).apply {
                    text = stepTitle(currentStep)
                    setTextColor(textSecondary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    gravity = Gravity.CENTER
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            addView(View(context), LinearLayout.LayoutParams(dp(92), dp(44)))
        }

    private fun progressStrip(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundColor(Color.WHITE)
            Step.values().forEach { step ->
                addView(View(context).apply {
                    background = rounded(progressColor(step), dp(3), Color.TRANSPARENT)
                }, LinearLayout.LayoutParams(0, dp(6), 1f).apply {
                    setMargins(dp(3), 0, dp(3), 0)
                })
            }
        }

    private fun speciesStep(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val recent = SpeciesCatalog.recentCodes(existingTrees(), limit = 5)
            if (recent.isNotEmpty()) {
                addView(sectionLabel("Recent"))
                addView(speciesGrid(recent.mapNotNull { SpeciesCatalog.byCode[it] }))
                addView(sectionSpacer())
            }
            addView(sectionLabel("All species"))
            addView(speciesGrid(SpeciesCatalog.all))
        }

    private fun speciesGrid(species: List<SpeciesConfig>): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            species.chunked(2).forEach { rowSpecies ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    rowSpecies.forEach { speciesConfig ->
                        addView(speciesButton(speciesConfig), LinearLayout.LayoutParams(0, dp(60), 1f).apply {
                            setMargins(0, dp(4), dp(8), dp(6))
                        })
                    }
                    if (rowSpecies.size == 1) addView(View(context), LinearLayout.LayoutParams(0, dp(60), 1f))
                })
            }
        }

    private fun speciesButton(speciesConfig: SpeciesConfig): Button {
        val selected = speciesCode == speciesConfig.code
        return Button(this).apply {
            text = "${speciesConfig.code}\n${speciesConfig.commonName}"
            setAllCaps(false)
            textSize = 13f
            setTextColor(if (selected) Color.WHITE else textPrimary)
            background = rounded(if (selected) primary else surfaceRaised, dp(10), if (selected) primary else divider)
            setOnClickListener {
                speciesCode = speciesConfig.code
                render()
            }
        }
    }

    private fun dbhStep(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(formSection("DBH") {
                addView(primaryButton("Scan with Depth") {
                    startActivityForResult(Intent(this@AddTreeActivity, DbhScanActivity::class.java), REQUEST_DBH)
                }, fullButtonParams())
                addView(rowLabel("DBH (cm)"))
                dbhInput = numberInput(dbhCm.takeIf { it > 0f }?.oneDecimal() ?: "").also { addView(it) }
                addView(rowLabel("Method"))
                addView(optionGrid(dbhMethods, dbhMethod) {
                    dbhMethod = it
                    render()
                })
                dbhIrregularCheck = CheckBox(context).apply {
                    text = "Irregular cross-section"
                    setTextColor(textPrimary)
                    textSize = 15f
                    isChecked = dbhIsIrregular
                    setPadding(0, dp(8), 0, 0)
                }.also { addView(it) }
            })
            SpeciesCatalog.byCode[speciesCode]?.let {
                addView(footnote("Expected ${it.expectedDbhMinCm.oneDecimal()}-${it.expectedDbhMaxCm.oneDecimal()} cm for ${it.commonName}."))
            }
        }

    private fun heightStep(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(formSection("Height") {
                addView(primaryButton("Scan with VIO walk-off") {
                    startActivityForResult(Intent(this@AddTreeActivity, HeightScanActivity::class.java), REQUEST_HEIGHT)
                }, fullButtonParams())
                addView(rowLabel("Height (m)"))
                heightInput = numberInput(heightM?.oneDecimal() ?: "").also { addView(it) }
                addView(rowLabel("Method"))
                addView(optionGrid(heightMethods, heightMethod ?: HeightMethod.MANUAL_ENTRY) {
                    heightMethod = it
                    render()
                })
            })
            SpeciesCatalog.byCode[speciesCode]?.let {
                addView(footnote("Expected ${it.expectedHeightMinM.oneDecimal()}-${it.expectedHeightMaxM.oneDecimal()} m for ${it.commonName}."))
            }
        }

    private fun extrasStep(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(formSection("Status") {
                addView(optionGrid(statusOptions, status) {
                    status = it
                    render()
                })
            })
            addView(sectionSpacer())
            addView(formSection("Placement") {
                addView(rowLabel("Bearing from center (deg)"))
                bearingInput = numberInput(bearingFromCenterDeg?.oneDecimal() ?: "").also { addView(it) }
                addView(rowLabel("Distance from center (m)"))
                distanceInput = numberInput(distanceFromCenterM?.oneDecimal() ?: "").also { addView(it) }
            })
            addView(sectionSpacer())
            addView(formSection("Attributes") {
                addView(rowLabel("Crown class"))
                crownInput = textInput(crownClass ?: "", "dominant, codominant, suppressed").also { addView(it) }
                addView(rowLabel("Damage codes"))
                damageInput = textInput(damageCodes.joinToString(", "), "comma-separated").also { addView(it) }
                addView(rowLabel("Notes"))
                notesInput = textInput(notes, "optional notes").apply {
                    minLines = 2
                    gravity = Gravity.TOP
                }.also { addView(it) }
            })
        }

    private fun reviewStep(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(formSection("Species") {
                addView(reviewRow("Code", SpeciesCatalog.displayName(speciesCode)))
            })
            addView(sectionSpacer())
            addView(formSection("DBH") {
                addView(reviewRow("Value", "${dbhCm.oneDecimal()} cm"))
                addView(reviewRow("Confidence", dbhConfidence.displayName, dbhConfidence))
                addView(reviewRow("Method", methodLabel(dbhMethods, dbhMethod)))
            })
            addView(sectionSpacer())
            addView(formSection("Height") {
                if (heightM != null) {
                    addView(reviewRow("Value", "${heightM!!.oneDecimal()} m"))
                    addView(reviewRow("Confidence", heightConfidence?.displayName ?: "Fair", heightConfidence))
                    addView(reviewRow("Method", methodLabel(heightMethods, heightMethod ?: HeightMethod.MANUAL_ENTRY)))
                } else {
                    addView(reviewRow("Value", "Not measured"))
                }
            })
            if (redTierWarning != null) {
                addView(sectionSpacer())
                addView(warningPanel(redTierWarning ?: "Check measurement."))
            }
        }

    private fun actionBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = if (currentStep == Step.REVIEW) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(14), dp(16), dp(28))
            setBackgroundColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
        }

        if (currentStep == Step.REVIEW) {
            if (history.isNotEmpty()) {
                bar.addView(secondaryButton("Back") { back() }, fullButtonParams())
            }
            bar.addView(secondaryButton("Save & add stem") { saveTree(SaveMode.STEM) }, fullButtonParams())
            bar.addView(secondaryButton("Save & next tree") { saveTree(SaveMode.NEXT) }, fullButtonParams())
            bar.addView(primaryButton("Save & close") { saveTree(SaveMode.CLOSE) }, fullButtonParams())
            return bar
        }

        if (history.isNotEmpty()) {
            bar.addView(secondaryButton("Back") { back() }, weightButtonParams())
        }
        if (currentStep == Step.HEIGHT) {
            bar.addView(secondaryButton("Skip") { skipHeight() }, weightButtonParams())
        }
        bar.addView(primaryButton("Next") { advance() }, weightButtonParams())
        return bar
    }

    private fun advance() {
        persistCurrentInputs()
        when (currentStep) {
            Step.SPECIES -> {
                if (SpeciesCatalog.byCode[speciesCode] == null) {
                    toast("Choose a species.")
                    return
                }
                moveTo(Step.DBH)
            }
            Step.DBH -> {
                recomputeDbhConfidence()
                if (dbhCm <= 0f) {
                    toast("Enter or scan DBH first.")
                    return
                }
                moveTo(if (heightRequired()) Step.HEIGHT else Step.EXTRAS)
            }
            Step.HEIGHT -> {
                recomputeHeightConfidence()
                if (heightM == null) {
                    toast("Measure height or tap Skip.")
                    return
                }
                moveTo(Step.EXTRAS)
            }
            Step.EXTRAS -> moveTo(Step.REVIEW)
            Step.REVIEW -> Unit
        }
    }

    private fun skipHeight() {
        persistCurrentInputs()
        heightM = null
        heightMethod = null
        heightConfidence = null
        moveTo(Step.EXTRAS)
    }

    private fun moveTo(step: Step) {
        history += currentStep
        currentStep = step
        render()
    }

    private fun back() {
        val previous = history.removeLastOrNull() ?: return
        currentStep = previous
        render()
    }

    private fun saveTree(mode: SaveMode) {
        persistCurrentInputs()
        recomputeDbhConfidence()
        recomputeHeightConfidence()
        computeRedTierWarning()
        if (dbhCm <= 0f) {
            toast("DBH is required.")
            return
        }
        val now = System.currentTimeMillis()
        val tree = Tree(
            id = UUID.randomUUID(),
            plotId = plotId,
            treeNumber = nextTreeNumber(),
            speciesCode = speciesCode,
            status = status,
            dbhCm = dbhCm,
            dbhMethod = dbhMethod,
            dbhConfidence = dbhConfidence,
            dbhIsIrregular = dbhIsIrregular,
            heightM = heightM,
            heightMethod = heightM?.let { heightMethod ?: HeightMethod.MANUAL_ENTRY },
            heightSource = heightM?.let { "measured" },
            heightConfidence = heightM?.let { heightConfidence ?: ConfidenceTier.YELLOW },
            bearingFromCenterDeg = bearingFromCenterDeg,
            distanceFromCenterM = distanceFromCenterM,
            crownClass = crownClass,
            damageCodes = damageCodes,
            isMultistem = isMultistem,
            parentTreeId = parentTreeId,
            notes = notes,
            createdAtMillis = now,
            updatedAtMillis = now
        )
        store.upsertTree(tree)
        setResult(RESULT_OK)
        toast("Tree #${tree.treeNumber} saved.")
        when (mode) {
            SaveMode.CLOSE -> finish()
            SaveMode.NEXT -> resetForNextTree()
            SaveMode.STEM -> prepareMultistemChild(tree)
        }
    }

    private fun resetForNextTree() {
        parentTreeId = null
        isMultistem = false
        dbhCm = 0f
        dbhMethod = DbhMethod.MANUAL_CALIPER
        dbhIsIrregular = false
        dbhConfidence = ConfidenceTier.GREEN
        heightM = null
        heightMethod = null
        heightConfidence = null
        crownClass = null
        damageCodes = emptyList()
        notes = ""
        bearingFromCenterDeg = null
        distanceFromCenterM = null
        redTierWarning = null
        history.clear()
        currentStep = Step.SPECIES
        render()
    }

    private fun prepareMultistemChild(parent: Tree) {
        parentTreeId = parent.id
        isMultistem = true
        dbhCm = 0f
        dbhMethod = DbhMethod.MANUAL_CALIPER
        dbhIsIrregular = false
        dbhConfidence = ConfidenceTier.GREEN
        heightM = null
        heightMethod = null
        heightConfidence = null
        redTierWarning = null
        history.clear()
        currentStep = Step.DBH
        render()
    }

    private fun persistCurrentInputs() {
        dbhInput?.text?.toString()?.toFloatOrNull()?.let { if (it > 0f) dbhCm = it }
        dbhIrregularCheck?.let { dbhIsIrregular = it.isChecked }
        heightInput?.text?.toString()?.toFloatOrNull()?.let { heightM = it.takeIf { value -> value > 0f } }
        crownClass = crownInput?.text?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: crownClass
        damageCodes = damageInput?.text?.toString()
            ?.split(",", ";")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: damageCodes
        notes = notesInput?.text?.toString() ?: notes
        bearingFromCenterDeg = bearingInput?.text?.toString()?.toFloatOrNull() ?: bearingFromCenterDeg
        distanceFromCenterM = distanceInput?.text?.toString()?.toFloatOrNull() ?: distanceFromCenterM
    }

    private fun recomputeDbhConfidence() {
        val species = SpeciesCatalog.byCode[speciesCode]
        if (species == null) {
            dbhConfidence = ConfidenceTier.YELLOW
            return
        }
        val checks: List<Check> = listOf(
            check(dbhCm > 0f, Severity.REJECT, "DBH must be positive."),
            check(dbhCm >= species.expectedDbhMinCm, Severity.WARN, "DBH below species range."),
            check(dbhCm <= species.expectedDbhMaxCm, Severity.WARN, "DBH above species range.")
        )
        dbhConfidence = combineChecks(checks)
    }

    private fun recomputeHeightConfidence() {
        val h = heightM
        if (h == null) {
            heightConfidence = null
            return
        }
        val species = SpeciesCatalog.byCode[speciesCode]
        if (species == null) {
            heightConfidence = ConfidenceTier.YELLOW
            return
        }
        val checks: List<Check> = listOf(
            check(h > 0f, Severity.REJECT, "Height must be positive."),
            check(h >= species.expectedHeightMinM, Severity.WARN, "Height below species range."),
            check(h <= species.expectedHeightMaxM, Severity.WARN, "Height above species range.")
        )
        heightConfidence = combineChecks(checks)
    }

    private fun computeRedTierWarning() {
        val parts = mutableListOf<String>()
        if (dbhConfidence == ConfidenceTier.RED) parts += "DBH is red-tier."
        if (heightConfidence == ConfidenceTier.RED) parts += "Height is red-tier."
        redTierWarning = parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    private fun heightRequired(): Boolean =
        HeightSubsample.shouldMeasureHeight(
            rule = session.design.heightSubsampleRule,
            newTreeNumber = nextTreeNumber(),
            newSpeciesCode = speciesCode,
            existingTreesOnPlot = existingTrees()
        )

    private fun existingTrees(): List<Tree> =
        store.loadTrees(plotId, includeDeleted = true)

    private fun nextTreeNumber(): Int =
        store.nextTreeNumber(plotId)

    private fun clearFieldRefs() {
        dbhInput = null
        dbhIrregularCheck = null
        heightInput = null
        crownInput = null
        damageInput = null
        bearingInput = null
        distanceInput = null
        notesInput = null
    }

    private fun formSection(title: String, content: LinearLayout.() -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(surface, dp(12), Color.TRANSPARENT)
            setPadding(dp(14), dp(12), dp(14), dp(14))
            addView(TextView(context).apply {
                text = title.uppercase()
                setTextColor(textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dp(10))
            })
            content()
        }

    private fun sectionLabel(value: String): TextView =
        TextView(this).apply {
            text = value.uppercase()
            setTextColor(textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        }

    private fun rowLabel(value: String): TextView =
        TextView(this).apply {
            text = value
            setTextColor(textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(12), 0, dp(5))
        }

    private fun reviewRow(label: String, value: String, tier: ConfidenceTier? = null): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            addView(TextView(context).apply {
                text = label
                setTextColor(textPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (tier != null) addView(confidenceDot(tier))
            addView(TextView(context).apply {
                text = value
                setTextColor(textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                gravity = Gravity.END
            })
        }

    private fun <T> optionGrid(
        options: List<Pair<String, T>>,
        selected: T,
        onSelect: (T) -> Unit
    ): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            options.chunked(2).forEach { rowOptions ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    rowOptions.forEach { option ->
                        addView(optionButton(option.first, option.second == selected) {
                            onSelect(option.second)
                        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                            setMargins(0, dp(3), dp(7), dp(4))
                        })
                    }
                    if (rowOptions.size == 1) addView(View(context), LinearLayout.LayoutParams(0, dp(44), 1f))
                })
            }
        }

    private fun optionButton(label: String, selected: Boolean, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setAllCaps(false)
            textSize = 13f
            setTextColor(if (selected) Color.WHITE else textPrimary)
            background = rounded(if (selected) primary else Color.WHITE, dp(8), if (selected) primary else divider)
            setOnClickListener { onClick() }
        }

    private fun textInput(value: String, hintValue: String): EditText =
        EditText(this).apply {
            setText(value)
            hint = hintValue
            setTextColor(textPrimary)
            setHintTextColor(textSecondary)
            textSize = 15f
            setSingleLine(false)
            background = rounded(Color.WHITE, dp(8), divider)
            setPadding(dp(10), dp(7), dp(10), dp(7))
        }

    private fun numberInput(value: String): EditText =
        textInput(value, "0.0").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
                InputType.TYPE_NUMBER_FLAG_SIGNED
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            setSingleLine(true)
        }

    private fun primaryButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setAllCaps(false)
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(primary, dp(10), primary)
            setOnClickListener { onClick() }
        }

    private fun secondaryButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setAllCaps(false)
            setTextColor(primary)
            textSize = 15f
            background = rounded(Color.WHITE, dp(10), divider)
            setOnClickListener { onClick() }
        }

    private fun toolbarButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setAllCaps(false)
            textSize = 13f
            setTextColor(primary)
            background = rounded(Color.TRANSPARENT, dp(8), Color.TRANSPARENT)
            setOnClickListener { onClick() }
        }

    private fun warningPanel(text: String): View =
        TextView(this).apply {
            this.text = text
            setTextColor(confidenceBad)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(Color.argb(24, 176, 86, 86), dp(12), Color.TRANSPARENT)
        }

    private fun confidenceDot(tier: ConfidenceTier): View =
        View(this).apply {
            background = oval(when (tier) {
                ConfidenceTier.GREEN -> confidenceOk
                ConfidenceTier.YELLOW -> confidenceWarn
                ConfidenceTier.RED -> confidenceBad
            })
            val size = dp(10)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                setMargins(dp(8), 0, dp(8), 0)
            }
        }

    private fun footnote(value: String): TextView =
        TextView(this).apply {
            text = value
            setTextColor(textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(4), dp(8), dp(4), 0)
        }

    private fun sectionSpacer(): View =
        View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(16))
        }

    private fun dividerLine(): View =
        View(this).apply {
            setBackgroundColor(divider)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        }

    private fun fullButtonParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
            setMargins(0, dp(4), 0, dp(6))
        }

    private fun weightButtonParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            setMargins(dp(3), 0, dp(3), 0)
        }

    private fun rounded(color: Int, radius: Int, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = radius.toFloat()
            setColor(color)
            setStroke(if (stroke == Color.TRANSPARENT) 0 else 1, stroke)
        }

    private fun oval(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    private fun progressColor(step: Step): Int =
        when {
            step.ordinal < currentStep.ordinal -> primary
            step.ordinal == currentStep.ordinal -> accent
            else -> divider
        }

    private fun stepTitle(step: Step): String =
        when (step) {
            Step.SPECIES -> "Species"
            Step.DBH -> "DBH"
            Step.HEIGHT -> "Height"
            Step.EXTRAS -> "Extras"
            Step.REVIEW -> "Review"
        }

    private fun <T> methodLabel(options: List<Pair<String, T>>, value: T): String =
        options.firstOrNull { it.second == value }?.first ?: value.toString()

    private fun toast(value: String) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show()
    }

    private fun Float.oneDecimal(): String = String.format("%.1f", this)

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    companion object {
        const val EXTRA_PLOT_ID = "plot_id"
        private const val REQUEST_DBH = 201
        private const val REQUEST_HEIGHT = 202
    }
}

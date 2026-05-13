package edu.oregonstate.forestrix

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Environment
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import edu.oregonstate.forestrix.export.CsvExporter
import edu.oregonstate.forestrix.inventory.PlotStats
import edu.oregonstate.forestrix.inventory.PlotStatsCalculator
import edu.oregonstate.forestrix.measurement.ConfidenceTier
import edu.oregonstate.forestrix.models.Tree
import edu.oregonstate.forestrix.storage.FieldSession
import edu.oregonstate.forestrix.storage.ForestrixStore
import java.io.File
import java.nio.charset.StandardCharsets

class MainActivity : Activity() {
    private val primary = Color.rgb(45, 95, 74)
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
    private lateinit var root: LinearLayout
    private lateinit var statsStrip: LinearLayout
    private lateinit var treeList: LinearLayout
    private lateinit var emptyState: TextView

    private var trees: List<Tree> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.WHITE
        store = ForestrixStore(this)
        session = store.loadSession()
        requestRuntimePermissions()
        reloadTrees()
        buildUi()
        refreshTally()
    }

    override fun onResume() {
        super.onResume()
        if (::store.isInitialized && ::treeList.isInitialized) {
            reloadTrees()
            refreshTally()
        }
    }

    @Deprecated("Android framework callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ADD_TREE && resultCode == RESULT_OK) {
            reloadTrees()
            refreshTally()
        }
    }

    private fun requestRuntimePermissions() {
        val needed = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), 42)
    }

    private fun reloadTrees() {
        trees = store.loadTrees(session.plot.id, includeDeleted = true)
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        setContentView(root)

        root.addView(navBar())
        statsStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(surfaceRaised)
        }
        root.addView(statsStrip)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            isFillViewport = true
        }
        treeList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(16))
        }
        scroll.addView(treeList)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        root.addView(dividerLine())
        root.addView(actionRow())
    }

    private fun navBar(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(8))
            setBackgroundColor(Color.WHITE)

            addView(toolbarButton("Export", primaryText = false) { exportCsv() },
                LinearLayout.LayoutParams(dp(96), dp(44)))

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(TextView(context).apply {
                    text = "Plot ${session.plot.plotNumber}"
                    setTextColor(textPrimary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                })
                addView(TextView(context).apply {
                    text = session.project.name
                    setTextColor(textSecondary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    gravity = Gravity.CENTER
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            addView(toolbarButton("Close Plot", destructive = true) { confirmClosePlot() },
                LinearLayout.LayoutParams(dp(96), dp(44)))
        }

    private fun refreshTally() {
        val stats = PlotStatsCalculator.compute(session.plot, session.design, trees)
        renderStats(stats)
        renderTreeList()
    }

    private fun renderStats(stats: PlotStats) {
        statsStrip.removeAllViews()
        statsStrip.addView(statCell("Live", stats.liveTreeCount.toString()), statParams())
        statsStrip.addView(statCell("Trees/ac", stats.tpa.oneDecimal()), statParams())
        statsStrip.addView(statCell("Basal m²/ac", stats.baPerAcreM2.twoDecimals()), statParams())
        statsStrip.addView(statCell("Mean DBH cm", stats.qmdCm.oneDecimal()), statParams())
        statsStrip.addView(statCell("Volume m³/ac", stats.grossVolumePerAcreM3.oneDecimal()), statParams())
    }

    private fun renderTreeList() {
        treeList.removeAllViews()
        val live = trees.filter { it.deletedAtMillis == null }
        val deleted = trees.filter { it.deletedAtMillis != null }

        if (live.isEmpty() && deleted.isEmpty()) {
            emptyState = TextView(this).apply {
                text = "No trees tallied yet."
                setTextColor(textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(88), dp(16), dp(16))
            }
            treeList.addView(emptyState, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            return
        }

        if (live.isNotEmpty()) {
            treeList.addView(sectionHeader("Trees (${live.size})"))
            live.sortedBy { it.treeNumber }.forEach { tree ->
                treeList.addView(treeRow(tree, deleted = false))
                treeList.addView(indentedDivider())
            }
        }

        if (deleted.isNotEmpty()) {
            treeList.addView(sectionHeader("Deleted (${deleted.size})"))
            deleted.sortedBy { it.treeNumber }.forEach { tree ->
                treeList.addView(treeRow(tree, deleted = true))
                treeList.addView(indentedDivider())
            }
        }
    }

    private fun statCell(label: String, value: String): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(TextView(context).apply {
                text = value
                setTextColor(textPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                gravity = Gravity.CENTER
                includeFontPadding = false
            })
            addView(TextView(context).apply {
                text = label
                setTextColor(textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                gravity = Gravity.CENTER
                includeFontPadding = false
            })
        }

    private fun treeRow(tree: Tree, deleted: Boolean): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            alpha = if (deleted) 0.48f else 1f

            addView(TextView(context).apply {
                text = "#${tree.treeNumber}"
                setTextColor(textPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            }, LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT))

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = tree.speciesCode
                    setTextColor(textPrimary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                })
                addView(TextView(context).apply {
                    text = tree.status.name.lowercase()
                    setTextColor(textSecondary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                addView(TextView(context).apply {
                    text = "${tree.dbhCm.oneDecimal()} cm"
                    setTextColor(textPrimary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                    gravity = Gravity.END
                })
                addView(TextView(context).apply {
                    text = tree.heightM?.let { "${it.oneDecimal()} m" } ?: ""
                    setTextColor(textSecondary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                    gravity = Gravity.END
                })
            }, LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT))

            addView(confidenceDot(tree.dbhConfidence))
            addView(toolbarButton(if (deleted) "Undo" else "Delete", primaryText = false) {
                if (deleted) store.undeleteTree(tree.id) else store.softDeleteTree(tree.id)
                reloadTrees()
                refreshTally()
            }, LinearLayout.LayoutParams(dp(72), dp(40)))
        }

    private fun sectionHeader(text: String): TextView =
        TextView(this).apply {
            this.text = text.uppercase()
            setTextColor(textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(16), dp(18), dp(16), dp(6))
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
                setMargins(dp(10), 0, dp(8), 0)
            }
        }

    private fun actionRow(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(14), dp(16), dp(28))
            setBackgroundColor(Color.WHITE)
            addView(primaryButton("Add Tree") {
                val intent = Intent(this@MainActivity, AddTreeActivity::class.java)
                    .putExtra(AddTreeActivity.EXTRA_PLOT_ID, session.plot.id.toString())
                startActivityForResult(intent, REQUEST_ADD_TREE)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52)
            ))
        }

    private fun confirmClosePlot() {
        AlertDialog.Builder(this)
            .setTitle("Close this plot?")
            .setMessage("${trees.count { it.deletedAtMillis == null }} live trees tallied.")
            .setNegativeButton("Keep tallying", null)
            .setPositiveButton("Review + close") { _, _ ->
                Toast.makeText(this, "Plot close review is next in the parity pass.", Toast.LENGTH_LONG).show()
            }
            .show()
    }

    private fun exportCsv() {
        reloadTrees()
        val stats = PlotStatsCalculator.compute(session.plot, session.design, trees)
        val directory = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: filesDir
        if (!directory.exists()) directory.mkdirs()
        val treeFile = File(directory, "forestrix_trees.csv")
        val plotFile = File(directory, "forestrix_plots.csv")
        treeFile.writeText(CsvExporter.treesCsv(trees, includeBom = true), StandardCharsets.UTF_8)
        plotFile.writeText(
            CsvExporter.plotsCsv(listOf(session.plot), mapOf(session.plot.id to stats), includeBom = true),
            StandardCharsets.UTF_8
        )
        Toast.makeText(this, "CSV exported to app documents.", Toast.LENGTH_LONG).show()
    }

    private fun primaryButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setAllCaps(false)
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(primary, dp(10), primary)
            setOnClickListener { onClick() }
        }

    private fun toolbarButton(
        label: String,
        destructive: Boolean = false,
        primaryText: Boolean = true,
        onClick: () -> Unit
    ): Button =
        Button(this).apply {
            text = label
            setAllCaps(false)
            textSize = 12f
            setTextColor(if (destructive) confidenceBad else if (primaryText) primary else textSecondary)
            background = rounded(Color.TRANSPARENT, dp(8), Color.TRANSPARENT)
            setOnClickListener { onClick() }
        }

    private fun dividerLine(): View =
        View(this).apply {
            setBackgroundColor(divider)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        }

    private fun indentedDivider(): View =
        View(this).apply {
            setBackgroundColor(divider)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                setMargins(dp(64), 0, 0, 0)
            }
        }

    private fun statParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

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

    private fun Float.oneDecimal(): String = String.format("%.1f", this)
    private fun Float.twoDecimals(): String = String.format("%.2f", this)

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    companion object {
        private const val REQUEST_ADD_TREE = 301
    }
}

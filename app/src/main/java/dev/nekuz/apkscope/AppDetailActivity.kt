package dev.nekuz.apkscope

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.time.ZoneId
import java.util.concurrent.Executors

class AppDetailActivity : Activity() {

    private enum class Tab { DETAILS, PERMISSIONS, COMPONENTS }

    private lateinit var adapter: RowAdapter
    private lateinit var progress: ProgressBar
    private lateinit var empty: TextView
    private lateinit var title: TextView
    private lateinit var filterRow: View
    private lateinit var tabDetails: Button
    private lateinit var tabPermissions: Button
    private lateinit var tabComponents: Button

    private var packageId = ""
    private var details: AppDetails? = null
    private var tab = Tab.DETAILS
    private var exportedOnly = false
    private var pendingApk: String? = null
    private var generation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requested = try {
            intent.getStringExtra(EXTRA_PACKAGE)
        } catch (e: RuntimeException) {
            null
        }
        if (requested == null || !PackageNames.isValid(requested)) {
            finish()
            return
        }
        packageId = requested
        tab = savedInstanceState?.getString(STATE_TAB)?.let { name ->
            Tab.entries.firstOrNull { it.name == name }
        } ?: Tab.DETAILS
        exportedOnly = savedInstanceState?.getBoolean(STATE_EXPORTED) ?: false
        pendingApk = savedInstanceState?.getString(STATE_PENDING_APK)

        setContentView(R.layout.activity_detail)
        progress = findViewById(R.id.progress)
        empty = findViewById(R.id.empty)
        title = findViewById(R.id.title)
        filterRow = findViewById(R.id.filterRow)
        tabDetails = findViewById(R.id.tabDetails)
        tabPermissions = findViewById(R.id.tabPermissions)
        tabComponents = findViewById(R.id.tabComponents)

        title.text = packageId
        title.setOnLongClickListener {
            Actions.copy(this, title.text.toString())
            true
        }
        val subtitle = findViewById<TextView>(R.id.subtitle)
        subtitle.text = packageId
        subtitle.setOnLongClickListener {
            Actions.copy(this, packageId)
            true
        }
        Icons.bind(this, findViewById<ImageView>(R.id.icon), packageId)

        adapter = RowAdapter(this)
        val list = findViewById<ListView>(R.id.list)
        list.adapter = adapter
        list.setOnItemLongClickListener { _, _, position, _ ->
            val row = adapter.rows[position]
            if (row is Row.Value) Actions.copy(this, row.value)
            row is Row.Value
        }

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<View>(R.id.actionOpen).setOnClickListener { Actions.openApp(this, packageId) }
        findViewById<View>(R.id.actionSettings).setOnClickListener { Actions.openSettings(this, packageId) }
        findViewById<View>(R.id.actionReport).setOnClickListener { showReportChoices() }
        findViewById<View>(R.id.actionSave).setOnClickListener { showSaveChoices() }
        tabDetails.setOnClickListener { select(Tab.DETAILS) }
        tabPermissions.setOnClickListener { select(Tab.PERMISSIONS) }
        tabComponents.setOnClickListener { select(Tab.COMPONENTS) }

        val exportedSwitch = findViewById<Switch>(R.id.exportedSwitch)
        exportedSwitch.isChecked = exportedOnly
        exportedSwitch.setOnCheckedChangeListener { _, checked ->
            exportedOnly = checked
            render()
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        if (packageId.isNotEmpty()) load()
    }

    override fun onDestroy() {
        generation++
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TAB, tab.name)
        outState.putBoolean(STATE_EXPORTED, exportedOnly)
        outState.putString(STATE_PENDING_APK, pendingApk)
    }

    private fun load() {
        val current = ++generation
        val appContext = applicationContext
        val target = packageId
        loader.execute {
            val result = AppDetailsReader.read(appContext, target)
            main.post { if (current == generation) show(result) }
        }
    }

    private fun show(result: DetailsResult) {
        when (result) {
            is DetailsResult.Ok -> details = result.details
            DetailsResult.Missing -> {
                Toast.makeText(this, R.string.app_missing, Toast.LENGTH_LONG).show()
                finish()
                return
            }
            DetailsResult.Failed -> if (details == null) {
                progress.visibility = View.GONE
                empty.visibility = View.VISIBLE
                empty.setText(R.string.details_failed)
                return
            }
        }
        render()
    }

    private fun select(selected: Tab) {
        tab = selected
        render()
    }

    private fun render() {
        val current = details
        styleTab(tabDetails, tab == Tab.DETAILS)
        styleTab(tabPermissions, tab == Tab.PERMISSIONS)
        styleTab(tabComponents, tab == Tab.COMPONENTS)
        filterRow.visibility = if (tab == Tab.COMPONENTS) View.VISIBLE else View.GONE
        if (current == null) {
            progress.visibility = View.VISIBLE
            tabPermissions.setText(R.string.tab_permissions_empty)
            tabComponents.setText(R.string.tab_components_empty)
            return
        }
        progress.visibility = View.GONE
        empty.visibility = View.GONE
        title.text = current.label
        tabPermissions.text = getString(R.string.tab_permissions, current.permissions.size)
        tabComponents.text = getString(R.string.tab_components, current.components.size)
        adapter.rows = when (tab) {
            Tab.DETAILS -> detailRows(current)
            Tab.PERMISSIONS -> permissionRows(current)
            Tab.COMPONENTS -> componentRows(current)
        }
    }

    private fun styleTab(button: Button, selected: Boolean) {
        button.setBackgroundResource(if (selected) R.drawable.bg_button_primary else R.drawable.bg_button_outline)
        button.setTextColor(getColor(if (selected) R.color.on_brand else R.color.text_primary))
    }

    private fun detailRows(current: AppDetails): List<Row> = buildList {
        if (!current.manifestComplete) add(Row.Note(getString(R.string.manifest_incomplete)))
        DetailSections.build(current, ZoneId.systemDefault()).forEach { section ->
            add(Row.Header(section.title))
            section.fields.forEach { add(Row.Value(it.label, it.value)) }
        }
    }

    private fun permissionRows(current: AppDetails): List<Row> = buildList {
        if (!current.manifestComplete) add(Row.Note(getString(R.string.manifest_incomplete)))
        if (current.permissions.isEmpty()) add(Row.Note(getString(R.string.no_permissions)))
        current.permissions.forEach { permission ->
            val (tagText, color) = when (permission.granted) {
                true -> R.string.tag_granted to R.color.brand_soft
                false -> R.string.tag_not_granted to R.color.text_secondary
                null -> R.string.tag_grant_unknown to R.color.text_secondary
            }
            add(Row.Value("", permission.name, getString(tagText), color))
        }
    }

    private fun componentRows(current: AppDetails): List<Row> = buildList {
        val stats = Components.stats(current.components)
        if (!current.manifestComplete) add(Row.Note(getString(R.string.manifest_incomplete)))
        add(Row.Note(getString(R.string.component_stats, stats.total, stats.exported, stats.exportedWithoutPermission)))
        if (stats.exportedWithoutPermission > 0) add(Row.Note(getString(R.string.component_note)))
        val visible = Components.filter(current.components, exportedOnly)
        if (visible.isEmpty()) {
            add(Row.Note(getString(if (exportedOnly) R.string.no_exported else R.string.no_components)))
        }
        ComponentType.entries.forEach { type ->
            val entries = visible.filter { it.type == type }
            if (entries.isEmpty()) return@forEach
            add(Row.Header("${type.title} (${entries.size})"))
            entries.forEach { add(componentRow(it)) }
        }
    }

    private fun componentRow(entry: ComponentEntry): Row {
        val (tagText, color) = when {
            entry.exportedWithoutPermission -> R.string.tag_exported_open to R.color.brand_soft
            entry.exported -> R.string.tag_exported to R.color.text_primary
            else -> R.string.tag_not_exported to R.color.text_secondary
        }
        return Row.Value(
            entry.protectionText,
            entry.name,
            getString(tagText),
            color,
            entry.exportedWithoutPermission,
            labelCopyable = true,
        )
    }

    private fun showReportChoices() {
        val current = details ?: return
        val choices = arrayOf(getString(R.string.report_share), getString(R.string.report_save))
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.report_title)
            .setItems(choices) { _, which ->
                if (which == 0) shareReport(current) else requestReportDocument()
            }
            .setNegativeButton(R.string.close, null)
            .create()
        showFiltered(dialog)
    }

    private fun shareReport(current: AppDetails) {
        val fitted = ReportBuilder.fit(
            ReportBuilder.build(current, System.currentTimeMillis(), ZoneId.systemDefault()),
        )
        if (fitted.truncated) Toast.makeText(this, R.string.report_truncated, Toast.LENGTH_LONG).show()
        Actions.shareText(this, "ApkScope report: ${current.packageName}", fitted.text)
    }

    private fun showSaveChoices() {
        val current = details ?: return
        val apks = current.apks
        if (apks.isEmpty()) {
            Actions.toast(this, R.string.save_failed)
            return
        }
        if (apks.size == 1) {
            requestApkDocument(current, apks[0].name)
            return
        }
        val choices = apks.map { "${it.name} · ${Format.size(it.sizeBytes)}" }.toTypedArray()
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.save_title)
            .setItems(choices) { _, which -> requestApkDocument(current, apks[which].name) }
            .setNegativeButton(R.string.close, null)
            .create()
        showFiltered(dialog)
    }

    private fun showFiltered(dialog: AlertDialog) {
        dialog.show()
        dialog.window?.decorView?.filterTouchesWhenObscured = true
    }

    private fun requestApkDocument(current: AppDetails, apkName: String) {
        pendingApk = apkName
        requestDocument(
            DocumentSaver.APK_MIME,
            FileNames.apk(current.packageName, current.versionName, apkName),
            REQUEST_SAVE_APK,
        )
    }

    private fun requestReportDocument() {
        requestDocument(DocumentSaver.TEXT_MIME, FileNames.report(packageId), REQUEST_SAVE_REPORT)
    }

    private fun requestDocument(mime: String, fileName: String, request: Int) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mime)
            .putExtra(Intent.EXTRA_TITLE, fileName)
        try {
            startActivityForResult(intent, request)
        } catch (e: ActivityNotFoundException) {
            Actions.toast(this, R.string.save_unavailable)
        } catch (e: SecurityException) {
            Actions.toast(this, R.string.save_unavailable)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val apkName = pendingApk
        if (requestCode == REQUEST_SAVE_APK) pendingApk = null
        if (resultCode != RESULT_OK || packageId.isEmpty()) return
        val target = contentUri(data) ?: run {
            Actions.toast(this, R.string.save_failed)
            return
        }
        when (requestCode) {
            REQUEST_SAVE_APK -> if (apkName != null) DocumentSaver.saveApk(this, packageId, apkName, target)
            REQUEST_SAVE_REPORT -> DocumentSaver.saveReport(this, packageId, target)
        }
    }

    private fun contentUri(data: Intent?): Uri? = data?.data?.takeIf { it.scheme == "content" }

    companion object {
        const val EXTRA_PACKAGE = "package"

        private const val STATE_TAB = "tab"
        private const val STATE_EXPORTED = "exported"
        private const val STATE_PENDING_APK = "pending_apk"
        private const val REQUEST_SAVE_APK = 1
        private const val REQUEST_SAVE_REPORT = 2

        private val main = Handler(Looper.getMainLooper())
        private val loader = Executors.newSingleThreadExecutor()
    }
}

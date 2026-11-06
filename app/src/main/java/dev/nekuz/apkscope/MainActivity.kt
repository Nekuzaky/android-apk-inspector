package dev.nekuz.apkscope

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import java.time.ZoneId

class MainActivity : Activity() {

    private lateinit var count: TextView
    private lateinit var search: EditText
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var progress: ProgressBar
    private lateinit var adapter: RowAdapter
    private lateinit var sortButtons: Map<SortOrder, Button>

    private var sortOrder = SortOrder.NAME
    private var includeSystem = false
    private val listener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        count = findViewById(R.id.count)
        search = findViewById(R.id.search)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        progress = findViewById(R.id.progress)

        sortOrder = savedInstanceState?.getString(STATE_SORT)?.let { name ->
            SortOrder.entries.firstOrNull { it.name == name }
        } ?: SortOrder.NAME
        includeSystem = savedInstanceState?.getBoolean(STATE_SYSTEM) ?: false

        adapter = RowAdapter(this)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> onRowClick(adapter.rows[position]) }
        list.setOnItemLongClickListener { _, _, position, _ ->
            val row = adapter.rows[position]
            if (row is Row.App) Actions.copy(this, row.packageName)
            row is Row.App
        }

        sortButtons = mapOf(
            SortOrder.NAME to findViewById(R.id.sortName),
            SortOrder.LAST_UPDATE to findViewById(R.id.sortUpdate),
            SortOrder.TARGET_SDK to findViewById(R.id.sortTarget),
        )
        sortButtons.forEach { (order, button) ->
            button.setOnClickListener {
                sortOrder = order
                updateSortButtons()
                render()
                list.setSelection(0)
            }
        }
        updateSortButtons()

        val systemSwitch = findViewById<Switch>(R.id.systemSwitch)
        systemSwitch.isChecked = includeSystem
        systemSwitch.setOnCheckedChangeListener { _, checked ->
            includeSystem = checked
            render()
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = render()
        })
        findViewById<View>(R.id.infoButton).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.info)
                .setMessage(R.string.info_message)
                .setPositiveButton(R.string.close, null)
                .show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SORT, sortOrder.name)
        outState.putBoolean(STATE_SYSTEM, includeSystem)
    }

    override fun onStart() {
        super.onStart()
        AppListStore.addListener(listener)
        render()
    }

    override fun onResume() {
        super.onResume()
        AppListStore.refresh(this)
    }

    override fun onStop() {
        super.onStop()
        AppListStore.removeListener(listener)
    }

    private fun updateSortButtons() {
        sortButtons.forEach { (order, button) ->
            val selected = order == sortOrder
            button.setBackgroundResource(if (selected) R.drawable.bg_button_primary else R.drawable.bg_button_outline)
            button.setTextColor(getColor(if (selected) R.color.on_brand else R.color.text_primary))
        }
    }

    private fun render() {
        val snapshot = AppListStore.snapshot
        val query = search.text.toString()
        val zone = ZoneId.systemDefault()
        val apps = AppLists.sort(AppLists.filter(snapshot.apps, query, includeSystem), sortOrder)
        adapter.rows = apps.map { app -> appRow(app, zone) }
        progress.visibility = if (snapshot.loaded) View.GONE else View.VISIBLE
        count.text = when {
            !snapshot.loaded -> getString(R.string.loading)
            else -> resources.getQuantityString(R.plurals.list_count, apps.size, apps.size)
        }
        empty.visibility = if (snapshot.loaded && apps.isEmpty()) View.VISIBLE else View.GONE
        empty.setText(
            when {
                snapshot.failed -> R.string.load_failed
                query.isNotBlank() -> R.string.empty_search
                else -> R.string.empty_apps
            },
        )
    }

    private fun appRow(app: AppSummary, zone: ZoneId) = Row.App(
        packageName = app.packageName,
        title = app.label,
        meta = getString(
            R.string.app_meta,
            app.versionName.ifEmpty { Format.UNKNOWN },
            app.versionCode,
            app.targetSdk,
            Format.date(app.lastUpdate, zone),
        ),
        tag = if (app.isSystem) getString(R.string.tag_system) else null,
    )

    private fun onRowClick(row: Row) {
        if (row !is Row.App) return
        startActivity(
            Intent(this, AppDetailActivity::class.java)
                .putExtra(AppDetailActivity.EXTRA_PACKAGE, row.packageName),
        )
    }

    private companion object {
        const val STATE_SORT = "sort"
        const val STATE_SYSTEM = "system"
    }
}

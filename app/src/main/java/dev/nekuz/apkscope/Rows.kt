package dev.nekuz.apkscope

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView

sealed class Row {
    data class Header(val text: String) : Row()

    data class Note(val text: String) : Row()

    data class App(
        val packageName: String,
        val title: String,
        val meta: String,
        val tag: String?,
    ) : Row()

    data class Value(
        val label: String,
        val value: String,
        val tag: String? = null,
        val tagColor: Int = R.color.text_secondary,
        val highlighted: Boolean = false,
        val labelCopyable: Boolean = false,
    ) : Row()
}

class RowAdapter(private val context: Context) : BaseAdapter() {
    var rows: List<Row> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    private val inflater = LayoutInflater.from(context)

    override fun getCount() = rows.size
    override fun getItem(position: Int) = rows[position]
    override fun getItemId(position: Int) = position.toLong()
    override fun getViewTypeCount() = 4
    override fun isEnabled(position: Int) = rows[position] is Row.App || rows[position] is Row.Value

    override fun getItemViewType(position: Int) = when (rows[position]) {
        is Row.Header -> 0
        is Row.Note -> 1
        is Row.App -> 2
        is Row.Value -> 3
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
        when (val row = rows[position]) {
            is Row.Header -> text(convertView, parent, R.layout.row_header, row.text)
            is Row.Note -> text(convertView, parent, R.layout.row_note, row.text)
            is Row.App -> app(convertView, parent, row)
            is Row.Value -> value(convertView, parent, row)
        }

    private fun text(convertView: View?, parent: ViewGroup, layout: Int, text: String): View {
        val view = convertView ?: inflater.inflate(layout, parent, false)
        (view as TextView).text = text
        return view
    }

    private fun app(convertView: View?, parent: ViewGroup, row: Row.App): View {
        val view = convertView ?: inflater.inflate(R.layout.row_app, parent, false)
        Icons.bind(context, view.findViewById<ImageView>(R.id.icon), row.packageName)
        view.findViewById<TextView>(R.id.title).text = row.title
        view.findViewById<TextView>(R.id.packageName).text = row.packageName
        view.findViewById<TextView>(R.id.meta).text = row.meta
        val tag = view.findViewById<TextView>(R.id.tag)
        tag.visibility = if (row.tag == null) View.GONE else View.VISIBLE
        tag.text = row.tag
        return view
    }

    private fun value(convertView: View?, parent: ViewGroup, row: Row.Value): View {
        val view = convertView ?: inflater.inflate(R.layout.row_value, parent, false)
        view.setBackgroundResource(if (row.highlighted) R.drawable.bg_row_highlight else R.drawable.bg_row)
        val label = view.findViewById<TextView>(R.id.label)
        label.text = row.label
        if (row.labelCopyable) {
            label.setOnLongClickListener {
                Actions.copy(context, row.label)
                true
            }
        } else {
            label.setOnLongClickListener(null)
            label.isLongClickable = false
        }
        val tag = view.findViewById<TextView>(R.id.tag)
        tag.visibility = if (row.tag == null) View.GONE else View.VISIBLE
        tag.text = row.tag
        tag.setTextColor(context.getColor(row.tagColor))
        view.findViewById<View>(R.id.labelRow).visibility =
            if (row.label.isEmpty() && row.tag == null) View.GONE else View.VISIBLE
        view.findViewById<TextView>(R.id.value).text = row.value
        return view
    }
}

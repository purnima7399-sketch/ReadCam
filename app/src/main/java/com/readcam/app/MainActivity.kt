package com.readcam.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var emptyText: TextView
    private var notes: List<Note> = emptyList()

    private val adapter = object : BaseAdapter() {
        override fun getCount(): Int = notes.size
        override fun getItem(position: Int): Any = notes[position]
        override fun getItemId(position: Int): Long = notes[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: LayoutInflater.from(parent.context).inflate(R.layout.item_note, parent, false)
            val note = notes[position]
            view.findViewById<TextView>(R.id.noteTitle).text = note.displayTitle()
            view.findViewById<TextView>(R.id.notePreview).text =
                note.body.trim().replace(Regex("\\s+"), " ")
            return view
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.root).applySystemBarPadding(includeIme = false)

        listView = findViewById(R.id.notesList)
        emptyText = findViewById(R.id.emptyText)
        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, position, _ ->
            openEditor(notes[position].id)
        }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            confirmDelete(notes[position])
            true
        }
        findViewById<View>(R.id.newNoteButton).setOnClickListener {
            val note = NotesStore.newNote()
            NotesStore.save(this, note)
            openEditor(note.id)
        }

        handleSharedText(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSharedText(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        notes = NotesStore.all(this)
        adapter.notifyDataSetChanged()
        emptyText.visibility = if (notes.isEmpty()) View.VISIBLE else View.GONE
    }

    /** Text shared from Google Keep (or any app) becomes a new script. */
    private fun handleSharedText(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        if (intent.type?.startsWith("text/") != true) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
        setIntent(Intent(this, MainActivity::class.java)) // don't re-import on rotation
        if (text.isBlank()) return
        val note = NotesStore.newNote(title = subject, body = text)
        NotesStore.save(this, note)
        openEditor(note.id)
    }

    private fun openEditor(id: Long) {
        startActivity(
            Intent(this, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_NOTE_ID, id)
        )
    }

    private fun confirmDelete(note: Note) {
        AlertDialog.Builder(this)
            .setTitle("Delete script?")
            .setMessage("“${note.displayTitle()}” will be removed.")
            .setPositiveButton("Delete") { _, _ ->
                NotesStore.delete(this, note.id)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

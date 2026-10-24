package com.readcam.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class EditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NOTE_ID = "note_id"
    }

    private lateinit var titleEdit: EditText
    private lateinit var bodyEdit: EditText
    private lateinit var note: Note

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)
        findViewById<View>(R.id.root).applySystemBarPadding(includeIme = true)

        titleEdit = findViewById(R.id.titleEdit)
        bodyEdit = findViewById(R.id.bodyEdit)

        val id = intent.getLongExtra(EXTRA_NOTE_ID, -1L)
        note = NotesStore.get(this, id) ?: NotesStore.newNote()

        if (savedInstanceState == null) {
            titleEdit.setText(note.title)
            bodyEdit.setText(note.body)
        }

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }

        findViewById<View>(R.id.recordButton).setOnClickListener {
            saveNote()
            if (note.body.isBlank()) {
                Toast.makeText(this, "Add your script first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startActivity(
                Intent(this, RecordActivity::class.java)
                    .putExtra(RecordActivity.EXTRA_NOTE_ID, note.id)
            )
        }
    }

    override fun onPause() {
        super.onPause()
        saveNote()
    }

    private fun saveNote() {
        note.title = titleEdit.text.toString()
        note.body = bodyEdit.text.toString()
        if (note.title.isBlank() && note.body.isBlank()) {
            // Empty script: don't leave a blank entry in the list.
            NotesStore.delete(this, note.id)
        } else {
            NotesStore.save(this, note)
        }
    }
}

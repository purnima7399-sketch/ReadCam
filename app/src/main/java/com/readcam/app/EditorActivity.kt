package com.readcam.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class EditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NOTE_ID = "note_id"
    }

    private lateinit var titleEdit: EditText
    private lateinit var bodyEdit: EditText
    private lateinit var note: Note

    // Notification permission only affects whether the "Close" notification shows;
    // the floating script works either way, so we carry on whatever the answer.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { chooseFloatTarget() }

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

        findViewById<View>(R.id.floatButton).setOnClickListener { onFloatClicked() }

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

    private fun onFloatClicked() {
        saveNote()
        if (note.body.isBlank()) {
            Toast.makeText(this, "Add your script first", Toast.LENGTH_SHORT).show()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("Allow floating over other apps")
                .setMessage(
                    "To show your script on top of the camera, Meet or any other app, ReadCam needs " +
                        "\u201CDisplay over other apps\u201D.\n\nOn the next screen, turn it on for ReadCam, " +
                        "then come back and tap Float script again."
                )
                .setPositiveButton("Open settings") { _, _ ->
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    )
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            chooseFloatTarget()
        }
    }

    private fun chooseFloatTarget() {
        AlertDialog.Builder(this)
            .setTitle("Float this script")
            .setItems(
                arrayOf(
                    "Over the camera (opens Pixel Camera in video mode)",
                    "Over any app (Google Meet, etc.)",
                )
            ) { _, which ->
                if (which == 0) floatOverCamera() else floatOverAnyApp()
            }
            .show()
    }

    private fun floatOverAnyApp() {
        FloatingScriptService.start(this, note.id)
        Toast.makeText(
            this,
            "Your script will stay on top. Open Meet or any app. Tip: add the ReadCam tile to Quick Settings.",
            Toast.LENGTH_LONG,
        ).show()
        // Go to the home screen so the user can open whichever app they need.
        startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun floatOverCamera() {
        FloatingScriptService.start(this, note.id)
        try {
            // Opens the Pixel Camera app straight into video mode.
            startActivity(Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Script is floating. Now open your camera app.", Toast.LENGTH_LONG).show()
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

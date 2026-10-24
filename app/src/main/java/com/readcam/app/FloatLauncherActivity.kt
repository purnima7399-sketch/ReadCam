package com.readcam.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast

/**
 * Invisible screen used by the Quick Settings tile and the app-icon shortcut:
 * floats the last-used script over whatever app is open, then disappears.
 */
class FloatLauncherActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "First allow “Display over other apps”: open a script and tap Float script.",
                Toast.LENGTH_LONG,
            ).show()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        val noteId = FloatingScriptService.lastNoteId(this)
        if (noteId == null) {
            Toast.makeText(this, "Add a script in ReadCam first", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java))
        } else {
            FloatingScriptService.start(this, noteId)
        }
        finish()
    }
}

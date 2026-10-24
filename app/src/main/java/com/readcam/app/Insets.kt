package com.readcam.app

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Pads a root view so content stays clear of the status bar, camera cutout,
 * navigation bar and (optionally) the keyboard. Needed because Android 15+
 * draws apps edge to edge.
 */
fun View.applySystemBarPadding(includeIme: Boolean) {
    val baseLeft = paddingLeft
    val baseTop = paddingTop
    val baseRight = paddingRight
    val baseBottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        var types = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        if (includeIme) types = types or WindowInsetsCompat.Type.ime()
        val bars = insets.getInsets(types)
        v.setPadding(
            baseLeft + bars.left,
            baseTop + bars.top,
            baseRight + bars.right,
            baseBottom + bars.bottom,
        )
        WindowInsetsCompat.CONSUMED
    }
}

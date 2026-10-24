package com.readcam.app

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Shows a script in a floating, draggable, resizable panel on top of other apps,
 * so you can read it while recording in the Pixel Camera app.
 */
class FloatingScriptService : Service() {

    companion object {
        const val EXTRA_NOTE_ID = "note_id"
        private const val ACTION_STOP = "com.readcam.app.STOP_FLOAT"
        private const val CHANNEL_ID = "floating_script"
        private const val NOTIFICATION_ID = 7
        private const val PREFS = "float_prefs"
        private const val MIN_TEXT_SP = 12f
        private const val MAX_TEXT_SP = 56f

        fun start(context: Context, noteId: Long) {
            val intent = Intent(context, FloatingScriptService::class.java)
                .putExtra(EXTRA_NOTE_ID, noteId)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private var overlay: View? = null

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private var textSizeSp = 24f
    private var collapsed = false

    // Background darkness steps for the ◐ button: solid-ish, medium, see-through.
    private val alphaSteps = intArrayOf(0xE0, 0xA0, 0x60)
    private var alphaIndex = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val noteId = intent?.getLongExtra(EXTRA_NOTE_ID, -1L) ?: -1L
        val body = NotesStore.get(this, noteId)?.body.orEmpty()
        showOverlay(body)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        overlay?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        overlay = null
        super.onDestroy()
    }

    // ---------------------------------------------------------- notification

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Floating script", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, FloatingScriptService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Script is floating")
            .setContentText("Tap to close the floating script")
            .setContentIntent(stopIntent)
            .addAction(0, "Close", stopIntent)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    // --------------------------------------------------------------- overlay

    private fun showOverlay(body: String) {
        overlay?.let { existing ->
            existing.findViewById<TextView>(R.id.floatText).text = body
            existing.findViewById<ScrollView>(R.id.floatScroll).scrollTo(0, 0)
            return
        }

        val themed = ContextThemeWrapper(this, R.style.Theme_ReadCam)
        val view = LayoutInflater.from(themed).inflate(R.layout.overlay_script, null)
        val text = view.findViewById<TextView>(R.id.floatText)
        text.text = body

        textSizeSp = prefs.getFloat("text_size", 24f)
        alphaIndex = prefs.getInt("alpha", 0).coerceIn(0, alphaSteps.size - 1)
        applyTextSize(text)
        applyBackground(view)

        val dm = resources.displayMetrics
        params = WindowManager.LayoutParams(
            prefs.getInt("w", (dm.widthPixels * 0.92f).toInt()),
            prefs.getInt("h", (dm.heightPixels * 0.40f).toInt()),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = prefs.getInt("x", (dm.widthPixels * 0.04f).toInt())
        params.y = prefs.getInt("y", dp(48f))

        view.findViewById<View>(R.id.floatSmaller).setOnClickListener { changeTextSize(text, -2f) }
        view.findViewById<View>(R.id.floatBigger).setOnClickListener { changeTextSize(text, 2f) }
        view.findViewById<View>(R.id.floatOpacity).setOnClickListener {
            alphaIndex = (alphaIndex + 1) % alphaSteps.size
            prefs.edit().putInt("alpha", alphaIndex).apply()
            applyBackground(view)
        }
        view.findViewById<View>(R.id.floatCollapse).setOnClickListener { toggleCollapsed(view) }
        view.findViewById<View>(R.id.floatClose).setOnClickListener { stopSelf() }

        setupDrag(view)
        setupResize(view)
        setupPinch(view, text)

        windowManager.addView(view, params)
        overlay = view
    }

    private fun applyBackground(view: View) {
        view.background = GradientDrawable().apply {
            cornerRadius = dp(16f).toFloat()
            setColor(Color.argb(alphaSteps[alphaIndex], 0, 0, 0))
        }
    }

    private fun applyTextSize(text: TextView) {
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
    }

    private fun changeTextSize(text: TextView, delta: Float) {
        textSizeSp = (textSizeSp + delta).coerceIn(MIN_TEXT_SP, MAX_TEXT_SP)
        applyTextSize(text)
        prefs.edit().putFloat("text_size", textSizeSp).apply()
    }

    private fun toggleCollapsed(view: View) {
        collapsed = !collapsed
        val scroll = view.findViewById<View>(R.id.floatScroll)
        val grip = view.findViewById<View>(R.id.floatResize)
        if (collapsed) {
            scroll.visibility = View.GONE
            grip.visibility = View.GONE
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
        } else {
            scroll.visibility = View.VISIBLE
            grip.visibility = View.VISIBLE
            params.height = prefs.getInt("h", (resources.displayMetrics.heightPixels * 0.40f).toInt())
        }
        view.findViewById<TextView>(R.id.floatCollapse).text = if (collapsed) "▢" else "—"
        windowManager.updateViewLayout(view, params)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag(view: View) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        view.findViewById<View>(R.id.floatHeader).setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = params.x
                    startY = params.y
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (e.rawX - downX).toInt()
                    params.y = (startY + (e.rawY - downY).toInt()).coerceAtLeast(0)
                    windowManager.updateViewLayout(view, params)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    prefs.edit().putInt("x", params.x).putInt("y", params.y).apply()
                }
            }
            true
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupResize(view: View) {
        var downX = 0f
        var downY = 0f
        var startW = 0
        var startH = 0
        view.findViewById<View>(R.id.floatResize).setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startW = view.width
                    startH = view.height
                }
                MotionEvent.ACTION_MOVE -> {
                    params.width = (startW + (e.rawX - downX).toInt()).coerceAtLeast(dp(180f))
                    params.height = (startH + (e.rawY - downY).toInt()).coerceAtLeast(dp(120f))
                    windowManager.updateViewLayout(view, params)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    prefs.edit().putInt("w", params.width).putInt("h", params.height).apply()
                }
            }
            true
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPinch(view: View, text: TextView) {
        val detector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                textSizeSp = (textSizeSp * detector.scaleFactor).coerceIn(MIN_TEXT_SP, MAX_TEXT_SP)
                applyTextSize(text)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                prefs.edit().putFloat("text_size", textSizeSp).apply()
            }
        })
        view.findViewById<View>(R.id.floatScroll).setOnTouchListener { _, e ->
            detector.onTouchEvent(e)
            e.pointerCount > 1
        }
    }

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()
}

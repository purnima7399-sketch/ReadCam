package com.readcam.app

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.TileService
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Shows a script in a floating, draggable, resizable panel on top of any app
 * (Pixel Camera, Google Meet, ...). The panel turns itself to match how the
 * phone is held, even in apps that keep the screen locked in portrait.
 */
class FloatingScriptService : Service() {

    companion object {
        const val EXTRA_NOTE_ID = "note_id"
        const val ACTION_STOP = "com.readcam.app.STOP_FLOAT"
        private const val CHANNEL_ID = "floating_script"
        private const val NOTIFICATION_ID = 7
        private const val PREFS = "float_prefs"
        private const val MIN_TEXT_SP = 12f
        private const val MAX_TEXT_SP = 56f

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context, noteId: Long) {
            val intent = Intent(context, FloatingScriptService::class.java)
                .putExtra(EXTRA_NOTE_ID, noteId)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, FloatingScriptService::class.java).setAction(ACTION_STOP))
        }

        /** The script floated most recently, or the newest script if that one is gone. */
        fun lastNoteId(context: Context): Long? {
            val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("last_note", -1L)
            val notes = NotesStore.all(context)
            return notes.firstOrNull { it.id == saved }?.id ?: notes.firstOrNull()?.id
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private var container: FrameLayout? = null
    private var panel: View? = null
    private var orientationListener: OrientationEventListener? = null
    private val handler = Handler(Looper.getMainLooper())

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private var textSizeSp = 24f
    private var collapsed = false

    // Panel size measured in the panel's own (upright) frame.
    private var contentW = 0
    private var contentH = 0

    // How the phone is physically held (0 upright, 90 / 270 sideways) and the
    // extra clockwise turn currently applied to the panel.
    private var deviceDeg = 0
    private var viewRot = 0

    // Background darkness steps for the ◐ button: solid-ish, medium, see-through.
    private val alphaSteps = intArrayOf(0xE0, 0xA0, 0x60)
    private var alphaIndex = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        isRunning = true
        refreshTile()
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
        val noteId = intent?.getLongExtra(EXTRA_NOTE_ID, -1L)?.takeIf { it != -1L } ?: lastNoteId(this)
        if (noteId == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        showOverlay(noteId)
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The app underneath rotated the screen (e.g. Meet in landscape): re-fit the panel.
        handler.postDelayed({ applyLayout(anchor = null) }, 150)
    }

    override fun onDestroy() {
        orientationListener?.disable()
        container?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        container = null
        panel = null
        isRunning = false
        refreshTile()
        super.onDestroy()
    }

    private fun refreshTile() {
        try {
            TileService.requestListeningState(this, ComponentName(this, ReadCamTileService::class.java))
        } catch (_: Exception) {
        }
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

    private fun showOverlay(noteId: Long) {
        prefs.edit().putLong("last_note", noteId).apply()
        val body = NotesStore.get(this, noteId)?.body.orEmpty()

        panel?.let {
            setScript(it, body)
            return
        }

        val themed = ContextThemeWrapper(this, R.style.Theme_ReadCam)
        val view = LayoutInflater.from(themed).inflate(R.layout.overlay_script, null)
        val frame = FrameLayout(themed)
        val text = view.findViewById<TextView>(R.id.floatText)
        text.text = body

        textSizeSp = prefs.getFloat("text_size", 24f)
        alphaIndex = prefs.getInt("alpha", 0).coerceIn(0, alphaSteps.size - 1)
        applyTextSize(text)
        applyBackground(view)

        val (screenW, screenH) = screenSize()
        val shortSide = min(screenW, screenH)
        val longSide = max(screenW, screenH)
        contentW = prefs.getInt("cw", (shortSide * 0.92f).toInt())
        contentH = prefs.getInt("ch", (longSide * 0.40f).toInt())

        frame.addView(view, FrameLayout.LayoutParams(contentW, contentH))

        params = WindowManager.LayoutParams(
            contentW,
            contentH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = prefs.getInt("x", (shortSide * 0.04f).toInt())
        params.y = prefs.getInt("y", dp(48f))

        view.findViewById<View>(R.id.floatSmaller).setOnClickListener { changeTextSize(text, -2f) }
        view.findViewById<View>(R.id.floatBigger).setOnClickListener { changeTextSize(text, 2f) }
        view.findViewById<View>(R.id.floatOpacity).setOnClickListener {
            alphaIndex = (alphaIndex + 1) % alphaSteps.size
            prefs.edit().putInt("alpha", alphaIndex).apply()
            applyBackground(view)
        }
        view.findViewById<View>(R.id.floatScripts).setOnClickListener { anchor -> showScriptPicker(themed, anchor, view) }
        view.findViewById<View>(R.id.floatCollapse).setOnClickListener { toggleCollapsed(view) }
        view.findViewById<View>(R.id.floatClose).setOnClickListener { stopSelf() }

        setupDrag(view, frame)
        setupResize(view)
        setupPinch(view, text)

        windowManager.addView(frame, params)
        container = frame
        panel = view

        startOrientationTracking()
        applyLayout(anchor = null)
    }

    private fun setScript(view: View, body: String) {
        view.findViewById<TextView>(R.id.floatText).text = body
        view.findViewById<ScrollView>(R.id.floatScroll).scrollTo(0, 0)
    }

    private fun showScriptPicker(themed: Context, anchor: View, view: View) {
        val notes = NotesStore.all(this)
        if (notes.isEmpty()) return
        val menu = PopupMenu(themed, anchor)
        notes.forEachIndexed { i, note -> menu.menu.add(0, i, i, note.displayTitle()) }
        menu.setOnMenuItemClickListener { item ->
            val note = notes[item.itemId]
            prefs.edit().putLong("last_note", note.id).apply()
            setScript(view, note.body)
            true
        }
        menu.show()
    }

    // -------------------------------------------------------------- rotation

    private fun startOrientationTracking() {
        val listener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return // lying flat: keep current
                // Only switch once the phone is clearly turned (avoids flicker near 45°).
                var d = abs(orientation - deviceDeg) % 360
                d = min(d, 360 - d)
                if (d < 60) return
                val snapped = ((orientation + 45) / 90 * 90) % 360
                if (snapped == 180) return // ignore upside-down
                if (snapped != deviceDeg) {
                    deviceDeg = snapped
                    applyLayout(anchor = null)
                }
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        orientationListener = listener
    }

    private fun displayDegrees(): Int {
        val display = getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        return when (display?.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    /**
     * Sizes the window and turns the panel so its text is upright for the person holding
     * the phone. If [anchor] is given, the panel's top-left corner (in its own frame)
     * stays at that screen point — used while resizing so the grip follows the finger.
     */
    private fun applyLayout(anchor: Pair<Int, Int>?) {
        val frame = container ?: return
        val view = panel ?: return

        // Clockwise turn still needed after whatever rotation the screen itself applied.
        viewRot = ((360 - deviceDeg - displayDegrees()) % 360 + 360) % 360

        val h = if (collapsed) dp(44f) else contentH
        val lp = view.layoutParams as FrameLayout.LayoutParams
        lp.width = contentW
        lp.height = h
        view.layoutParams = lp

        val sideways = viewRot == 90 || viewRot == 270
        val winW = if (sideways) h else contentW
        val winH = if (sideways) contentW else h

        view.pivotX = contentW / 2f
        view.pivotY = h / 2f
        view.rotation = viewRot.toFloat()
        view.translationX = (winW - contentW) / 2f
        view.translationY = (winH - h) / 2f

        params.width = winW
        params.height = winH

        if (anchor != null) {
            val (ax, ay) = anchor
            when (viewRot) {
                90 -> { params.x = ax - winW; params.y = ay }
                180 -> { params.x = ax - winW; params.y = ay - winH }
                270 -> { params.x = ax; params.y = ay - winH }
                else -> { params.x = ax; params.y = ay }
            }
        }

        val (screenW, screenH) = screenSize()
        params.x = params.x.coerceIn(0, max(0, screenW - winW))
        params.y = params.y.coerceIn(0, max(0, screenH - winH))

        try {
            windowManager.updateViewLayout(frame, params)
        } catch (_: Exception) {
        }
    }

    /** Screen position of the panel's own top-left corner. */
    private fun panelOrigin(): Pair<Int, Int> = when (viewRot) {
        90 -> Pair(params.x + params.width, params.y)
        180 -> Pair(params.x + params.width, params.y + params.height)
        270 -> Pair(params.x, params.y + params.height)
        else -> Pair(params.x, params.y)
    }

    /** Converts a finger movement on screen into the panel's own (possibly turned) frame. */
    private fun toPanelFrame(dx: Int, dy: Int): Pair<Int, Int> = when (viewRot) {
        90 -> Pair(dy, -dx)
        180 -> Pair(-dx, -dy)
        270 -> Pair(-dy, dx)
        else -> Pair(dx, dy)
    }

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = windowManager.currentWindowMetrics.bounds
            Pair(b.width(), b.height())
        } else {
            val dm = resources.displayMetrics
            Pair(dm.widthPixels, dm.heightPixels)
        }
    }

    // ------------------------------------------------------------- controls

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
        val origin = panelOrigin()
        collapsed = !collapsed
        val visibility = if (collapsed) View.GONE else View.VISIBLE
        view.findViewById<View>(R.id.floatScroll).visibility = visibility
        view.findViewById<View>(R.id.floatResize).visibility = visibility
        view.findViewById<TextView>(R.id.floatCollapse).text = if (collapsed) "▢" else "—"
        applyLayout(anchor = origin)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag(view: View, frame: View) {
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
                    val (screenW, screenH) = screenSize()
                    params.x = (startX + (e.rawX - downX).toInt()).coerceIn(0, max(0, screenW - params.width))
                    params.y = (startY + (e.rawY - downY).toInt()).coerceIn(0, max(0, screenH - params.height))
                    windowManager.updateViewLayout(frame, params)
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
        var origin = Pair(0, 0)
        view.findViewById<View>(R.id.floatResize).setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startW = contentW
                    startH = contentH
                    origin = panelOrigin()
                }
                MotionEvent.ACTION_MOVE -> {
                    val (dw, dh) = toPanelFrame((e.rawX - downX).toInt(), (e.rawY - downY).toInt())
                    contentW = (startW + dw).coerceAtLeast(dp(280f))
                    contentH = (startH + dh).coerceAtLeast(dp(120f))
                    applyLayout(anchor = origin)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    prefs.edit()
                        .putInt("cw", contentW)
                        .putInt("ch", contentH)
                        .putInt("x", params.x)
                        .putInt("y", params.y)
                        .apply()
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

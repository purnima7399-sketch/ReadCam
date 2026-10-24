package com.readcam.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class RecordActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NOTE_ID = "note_id"
        private const val PREFS = "record_prefs"
        private const val KEY_TEXT_SIZE = "text_size"
        private const val KEY_CORNER = "corner"
        private const val MIN_TEXT_SP = 16f
        private const val MAX_TEXT_SP = 64f

        // Corners: 0 top-left, 1 top-right, 2 bottom-left, 3 bottom-right
        private const val DEFAULT_CORNER = 3
    }

    private lateinit var root: View
    private lateinit var textScroll: ScrollView
    private lateinit var scriptText: TextView
    private lateinit var previewCard: View
    private lateinit var previewView: PreviewView
    private lateinit var controls: View
    private lateinit var recordButton: ImageButton
    private lateinit var timerText: TextView
    private lateinit var qualityText: TextView
    private lateinit var countdownText: TextView

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private val handler = Handler(Looper.getMainLooper())
    private var countingDown = false
    private var countdownValue = 0
    private val tickRunnable = Runnable { tick() }

    private var textSizeSp = 28f
    private var corner = DEFAULT_CORNER
    private var isDragging = false

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val cameraOk = results[Manifest.permission.CAMERA] == true ||
                hasPermission(Manifest.permission.CAMERA)
            val micOk = results[Manifest.permission.RECORD_AUDIO] == true ||
                hasPermission(Manifest.permission.RECORD_AUDIO)
            if (!micOk) toast("Microphone not allowed, so videos will have no sound.")
            if (cameraOk) {
                startCamera()
            } else {
                qualityText.text = "Camera not allowed"
                toast("ReadCam needs the camera to record you.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_record)

        root = findViewById(R.id.root)
        textScroll = findViewById(R.id.textScroll)
        scriptText = findViewById(R.id.scriptText)
        previewCard = findViewById(R.id.previewCard)
        previewView = findViewById(R.id.previewView)
        controls = findViewById(R.id.controls)
        recordButton = findViewById(R.id.recordButton)
        timerText = findViewById(R.id.timerText)
        qualityText = findViewById(R.id.qualityText)
        countdownText = findViewById(R.id.countdownText)

        root.applySystemBarPadding(includeIme = false)

        // TextureView-backed preview so the rounded corners clip properly.
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER

        textSizeSp = prefs.getFloat(KEY_TEXT_SIZE, 28f)
        corner = prefs.getInt(KEY_CORNER, DEFAULT_CORNER)
        applyTextSize()

        val noteId = intent.getLongExtra(EXTRA_NOTE_ID, -1L)
        scriptText.text = NotesStore.get(this, noteId)?.body.orEmpty()

        findViewById<View>(R.id.smallerText).setOnClickListener { changeTextSize(-2f) }
        findViewById<View>(R.id.biggerText).setOnClickListener { changeTextSize(2f) }
        recordButton.setOnClickListener { toggleRecording() }

        setupPinchToResize()
        setupDraggablePreview()

        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!isDragging) placePreview(animate = false)
        }

        val needed = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (needed.all { hasPermission(it) }) startCamera() else permissionLauncher.launch(needed)
    }

    override fun onStop() {
        super.onStop()
        cancelCountdown()
        recording?.stop()
    }

    /** Volume buttons (or a Bluetooth selfie remote) start and stop recording. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.repeatCount == 0) toggleRecording()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------------------------------------------------------------- camera

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = try {
                future.get()
            } catch (e: Exception) {
                qualityText.text = "Camera unavailable"
                return@addListener
            }

            val selector = CameraSelector.DEFAULT_FRONT_CAMERA
            val info = selector.filter(provider.availableCameraInfos).firstOrNull()
            if (info == null) {
                qualityText.text = "No selfie camera found"
                return@addListener
            }

            // Best quality the selfie camera supports, trying 4K first.
            val supported = Recorder.getVideoCapabilities(info).getSupportedQualities(DynamicRange.SDR)
            val candidates = listOf(Quality.UHD, Quality.FHD, Quality.HD)
                .filter { it in supported }
                .ifEmpty { listOf(Quality.HIGHEST) }

            for (quality in candidates) {
                try {
                    val recorder = Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(quality))
                        .setTargetVideoEncodingBitRate(bitrateFor(quality))
                        .build()
                    val capture = VideoCapture.withOutput(recorder)
                    val preview = Preview.Builder().build()
                    preview.setSurfaceProvider(previewView.surfaceProvider)

                    provider.unbindAll()
                    provider.bindToLifecycle(this, selector, preview, capture)

                    videoCapture = capture
                    qualityText.text = "${labelFor(quality)} · selfie"
                    return@addListener
                } catch (e: Exception) {
                    // This quality can't run alongside the preview; try the next one down.
                }
            }
            qualityText.text = "Couldn't start camera"
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bitrateFor(q: Quality): Int = when (q) {
        Quality.UHD -> 50_000_000
        Quality.FHD -> 20_000_000
        else -> 12_000_000
    }

    private fun labelFor(q: Quality): String = when (q) {
        Quality.UHD -> "4K"
        Quality.FHD -> "1080p"
        Quality.HD -> "720p"
        else -> "Best available"
    }

    // ------------------------------------------------------------- recording

    private fun toggleRecording() {
        when {
            recording != null -> recording?.stop()
            countingDown -> cancelCountdown()
            else -> startCountdown()
        }
    }

    private fun startCountdown() {
        if (videoCapture == null) {
            toast("Camera isn't ready yet")
            return
        }
        countingDown = true
        countdownValue = 3
        recordButton.setBackgroundResource(R.drawable.rec_active)
        tick()
    }

    private fun tick() {
        if (!countingDown) return
        if (countdownValue <= 0) {
            countingDown = false
            countdownText.visibility = View.GONE
            startRecording()
            return
        }
        countdownText.text = countdownValue.toString()
        countdownText.visibility = View.VISIBLE
        countdownValue--
        handler.postDelayed(tickRunnable, 1000)
    }

    private fun cancelCountdown() {
        if (!countingDown) return
        countingDown = false
        handler.removeCallbacks(tickRunnable)
        countdownText.visibility = View.GONE
        if (recording == null) recordButton.setBackgroundResource(R.drawable.rec_idle)
    }

    private fun startRecording() {
        val capture = videoCapture ?: return
        val name = "ReadCam_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ReadCam")
        }
        val output = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        ).setContentValues(values).build()

        var pending = capture.output.prepareRecording(this, output)
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
            pending = pending.withAudioEnabled()
        }

        recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> setRecordingUi(true)
                is VideoRecordEvent.Status -> {
                    val secs = TimeUnit.NANOSECONDS.toSeconds(event.recordingStats.recordedDurationNanos)
                    timerText.text = String.format(Locale.US, "● %02d:%02d", secs / 60, secs % 60)
                }
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    setRecordingUi(false)
                    if (!event.hasError()) {
                        toast("Saved to your gallery (Movies › ReadCam)")
                    } else if (event.error == VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA) {
                        toast("Recording was too short to save")
                    } else {
                        toast("Recording ended with a problem (code ${event.error})")
                    }
                }
            }
        }
    }

    private fun setRecordingUi(on: Boolean) {
        recordButton.setBackgroundResource(if (on) R.drawable.rec_active else R.drawable.rec_idle)
        timerText.setTextColor(if (on) Color.parseColor("#FF5A4F") else Color.WHITE)
        if (!on) timerText.text = "00:00"
    }

    // ------------------------------------------------------------------ text

    private fun applyTextSize() {
        scriptText.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
    }

    private fun changeTextSize(delta: Float) {
        textSizeSp = (textSizeSp + delta).coerceIn(MIN_TEXT_SP, MAX_TEXT_SP)
        applyTextSize()
        prefs.edit().putFloat(KEY_TEXT_SIZE, textSizeSp).apply()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPinchToResize() {
        val detector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                textSizeSp = (textSizeSp * detector.scaleFactor).coerceIn(MIN_TEXT_SP, MAX_TEXT_SP)
                applyTextSize()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                prefs.edit().putFloat(KEY_TEXT_SIZE, textSizeSp).apply()
            }
        })
        textScroll.setOnTouchListener { _, ev ->
            detector.onTouchEvent(ev)
            ev.pointerCount > 1 // two fingers = resize; one finger = normal scrolling
        }
    }

    // --------------------------------------------------------- corner preview

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDraggablePreview() {
        var offsetX = 0f
        var offsetY = 0f
        previewCard.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = true
                    v.animate().cancel()
                    offsetX = v.x - ev.rawX
                    offsetY = v.y - ev.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    v.x = ev.rawX + offsetX
                    v.y = ev.rawY + offsetY
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                    val centerX = v.x + v.width / 2f
                    val centerY = v.y + v.height / 2f
                    val right = centerX > root.width / 2f
                    val bottom = centerY > controls.y / 2f
                    corner = (if (bottom) 2 else 0) + (if (right) 1 else 0)
                    prefs.edit().putInt(KEY_CORNER, corner).apply()
                    placePreview(animate = true)
                }
            }
            true
        }
    }

    private fun placePreview(animate: Boolean) {
        if (previewCard.width == 0 || root.width == 0) return
        val margin = dp(12f)
        val left = root.paddingLeft + margin
        val right = root.width - root.paddingRight - previewCard.width - margin
        val top = root.paddingTop + margin
        val bottom = controls.y - previewCard.height - margin
        val x = if (corner == 0 || corner == 2) left else right
        val y = if (corner == 0 || corner == 1) top else bottom
        if (animate) {
            previewCard.animate().x(x).y(y).setDuration(180).start()
        } else {
            previewCard.x = x
            previewCard.y = y
        }
    }

    // --------------------------------------------------------------- helpers

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}

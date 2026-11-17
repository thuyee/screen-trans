package vn.dichmanhinh.app.service

import android.app.Activity
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowManager.LayoutParams as LP
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import vn.dichmanhinh.app.App
import vn.dichmanhinh.app.R
import vn.dichmanhinh.app.data.Prefs
import vn.dichmanhinh.app.data.Util
import vn.dichmanhinh.app.engine.Block
import vn.dichmanhinh.app.engine.GoogleWeb
import vn.dichmanhinh.app.engine.Item
import vn.dichmanhinh.app.engine.OcrPackMissing
import vn.dichmanhinh.app.engine.Pipeline
import vn.dichmanhinh.app.engine.PipelineResult
import vn.dichmanhinh.app.engine.Speaker
import vn.dichmanhinh.app.ui.BoxPainter
import vn.dichmanhinh.app.ui.CameraTranslateActivity
import vn.dichmanhinh.app.ui.ClipReadActivity
import vn.dichmanhinh.app.ui.DrawBox
import vn.dichmanhinh.app.ui.ImageTranslateActivity
import vn.dichmanhinh.app.ui.MainActivity
import vn.dichmanhinh.app.ui.ProjectionRequestActivity
import vn.dichmanhinh.app.ui.RegionSelectView
import vn.dichmanhinh.app.ui.ResultView
import vn.dichmanhinh.app.ui.SettingsActivity
import vn.dichmanhinh.app.ui.StyleCfg
import vn.dichmanhinh.app.ui.UiKit
import java.net.ConnectException
import java.net.UnknownHostException
import kotlin.coroutines.resume
import kotlin.math.hypot

class BubbleService : Service() {

    companion object {
        const val ACTION_START = "vn.dichmanhinh.action.START"
        const val ACTION_PROJECTION = "vn.dichmanhinh.action.PROJECTION"
        const val ACTION_TRANSLATE = "vn.dichmanhinh.action.TRANSLATE"
        const val ACTION_REGION = "vn.dichmanhinh.action.REGION"
        const val ACTION_MENU = "vn.dichmanhinh.action.MENU"
        const val ACTION_STOP = "vn.dichmanhinh.action.STOP"
        const val ACTION_CLIP = "vn.dichmanhinh.action.CLIP"
        const val EXTRA_TEXT = "text"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_THEN = "then"
        const val EXTRA_DELAY = "delay"
        private const val NOTIF_ID = 1
        private const val COLOR_BUSY = 0xFFFFA000.toInt()
        private const val COLOR_LIVE = 0xFFD32F2F.toInt()
        private val GLYPHS = listOf("文A", "译", "A↔", "🌐", "T", "✦")

        @Volatile
        var running = false
    }

    /** Ảnh chụp (nếu có) + chữ đọc trực tiếp từ Trợ năng (nếu dùng chế độ Trợ năng). */
    private class Frame(val bmp: Bitmap?, val blocks: List<Block>?)

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val bgThread = HandlerThread("dmh-capture").also { it.start() }
    private val bgHandler by lazy { Handler(bgThread.looper) }

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs

    private var bubble: TextView? = null
    private lateinit var bubbleLp: LP
    private var menu: View? = null
    private var layer: View? = null
    private var detail: View? = null
    private var isFg = false

    private var projection: MediaProjection? = null
    private var projCb: MediaProjection.Callback? = null
    private var vDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var held: Image? = null
    private val lock = Any()

    private var job: Job? = null
    private var liveJob: Job? = null
    private var lastItems: List<Item> = emptyList()
    private var lastBoxes: List<DrawBox> = emptyList()

    private val dimRun = Runnable { bubble?.animate()?.alpha(0.3f)?.setDuration(250)?.start() }

    // Chép để dịch: khi bạn sao chép chữ ở app khác, bật ClipReadActivity (trong suốt) để đọc clipboard rồi hiện thẻ dịch.
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (prefs.tapTranslate && SystemClock.uptimeMillis() - Util.lastSelfCopy > 1500) {
            try {
                startActivity(
                    Intent(this, ClipReadActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                )
            } catch (e: Exception) {
                // hệ thống chặn mở activity từ nền: bỏ qua
            }
        }
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "bubble_size" || key == "bubble_alpha" || key == "icon_style" || key == "icon_color") {
            removeBubble()
            showBubble()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = Prefs(this)
        prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)
        getSystemService(ClipboardManager::class.java).addPrimaryClipChangedListener(clipListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // Hệ thống khởi động lại dịch vụ sau khi bị tắt ngầm: dựng lại biểu tượng nổi.
            try {
                ensureForeground()
                showBubble()
            } catch (e: Exception) {
                stopSelf()
            }
            return START_STICKY
        }
        when (intent.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, ACTION_PROJECTION -> {
                val rc = intent.getIntExtra(EXTRA_CODE, 0)
                val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
                val hasProj = rc == Activity.RESULT_OK && data != null
                goForeground(hasProj || projection != null)
                if (hasProj) attachProjection(rc, data!!)
                showBubble()
                if (intent.getBooleanExtra(EXTRA_THEN, false)) translateFull(500)
            }
            ACTION_TRANSLATE -> {
                ensureForeground()
                showBubble()
                translateFull(intent.getLongExtra(EXTRA_DELAY, 0L))
            }
            ACTION_REGION -> {
                ensureForeground()
                showBubble()
                translateRegion()
            }
            ACTION_MENU -> {
                ensureForeground()
                showBubble()
                showMenu()
            }
            ACTION_CLIP -> {
                ensureForeground()
                showBubble()
                intent.getStringExtra(EXTRA_TEXT)?.let { translateClip(it) }
            }
            else -> ensureForeground()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener)
        getSystemService(ClipboardManager::class.java).removePrimaryClipChangedListener(clipListener)
        scope.cancel()
        main.removeCallbacksAndMessages(null)
        removeLayer()
        hideMenu()
        removeBubble()
        releaseProjection()
        Speaker.stop()
        bgThread.quitSafely()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- foreground

    private fun ensureForeground() {
        if (!isFg) goForeground(projection != null)
    }

    private fun goForeground(withProjection: Boolean) {
        val type = when {
            Build.VERSION.SDK_INT >= 34 ->
                if (withProjection) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
        isFg = true
    }

    private fun servicePi(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, BubbleService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildNotification() = NotificationCompat.Builder(this, App.CHANNEL)
        .setSmallIcon(R.drawable.ic_notif)
        .setContentTitle(getString(R.string.notif_title))
        .setContentText(getString(R.string.notif_text))
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        )
        .addAction(0, getString(R.string.action_translate), servicePi(ACTION_TRANSLATE, 1))
        .addAction(0, getString(R.string.action_menu), servicePi(ACTION_MENU, 3))
        .addAction(0, getString(R.string.action_stop), servicePi(ACTION_STOP, 2))
        .build()

    // ---------------------------------------------------------------- display / capture

    private fun displaySize(): Triple<Int, Int, Int> {
        val dpi = resources.displayMetrics.densityDpi
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            Triple(b.width(), b.height(), dpi)
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            Triple(dm.widthPixels, dm.heightPixels, dm.densityDpi)
        }
    }

    private fun attachProjection(code: Int, data: Intent) {
        releaseProjection()
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = mpm.getMediaProjection(code, data)
        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                main.post {
                    releaseProjection()
                    toast(R.string.projection_stopped)
                }
            }
        }
        mp.registerCallback(cb, bgHandler)
        projCb = cb
        projection = mp
        setupDisplay()
    }

    private fun newReader(w: Int, h: Int): ImageReader =
        ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3).also { r ->
            // Luôn giữ khung hình mới nhất để chụp tức thì, không chặn bộ đệm.
            r.setOnImageAvailableListener({ rd ->
                synchronized(lock) {
                    val img = try { rd.acquireLatestImage() } catch (e: Exception) { null }
                    if (img != null) {
                        held?.close()
                        held = img
                    }
                }
            }, bgHandler)
        }

    private fun setupDisplay() {
        val (w, h, dpi) = displaySize()
        val r = newReader(w, h)
        reader = r
        vDisplay = projection?.createVirtualDisplay(
            "dmh", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, bgHandler
        )
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val vd = vDisplay ?: return
        val (w, h, dpi) = displaySize()
        val old = reader
        val r = newReader(w, h)
        vd.resize(w, h, dpi)
        vd.surface = r.surface
        reader = r
        synchronized(lock) { held?.close(); held = null }
        old?.close()
    }

    private fun releaseProjection() {
        vDisplay?.release(); vDisplay = null
        synchronized(lock) { held?.close(); held = null }
        reader?.close(); reader = null
        projCb?.let { cb -> try { projection?.unregisterCallback(cb) } catch (e: Exception) {} }
        projCb = null
        try { projection?.stop() } catch (e: Exception) {}
        projection = null
    }

    private fun imageToBitmap(img: Image): Bitmap {
        val p = img.planes[0]
        val rowPad = p.rowStride - p.pixelStride * img.width
        val wide = Bitmap.createBitmap(img.width + rowPad / p.pixelStride, img.height, Bitmap.Config.ARGB_8888)
        p.buffer.rewind()
        wide.copyPixelsFromBuffer(p.buffer)
        if (rowPad == 0) return wide
        return Bitmap.createBitmap(wide, 0, 0, img.width, img.height).also { wide.recycle() }
    }

    private suspend fun frameFromProjection(): Bitmap? {
        for (i in 0 until 10) {
            val bmp = synchronized(lock) { held?.let { imageToBitmap(it) } }
            if (bmp != null) return bmp
            delay(100)
        }
        return null
    }

    private fun accessibilityReady() = Build.VERSION.SDK_INT >= 30 &&
        CaptureAccessibilityService.instance != null && prefs.captureMethod == "auto"

    private fun canCapture() = accessibilityReady() || projection != null

    /** Chế độ Trợ năng đang được chọn VÀ dịch vụ Trợ năng đang chạy. */
    private fun accMode() = prefs.mode == "acc" && CaptureAccessibilityService.instance != null

    private suspend fun captureScreen(): Bitmap? {
        if (Build.VERSION.SDK_INT >= 30 && accessibilityReady()) {
            val acc = CaptureAccessibilityService.instance
            var bmp = acc?.screenshot()
            if (bmp == null) { // takeScreenshot bị giới hạn tần suất -> thử lại một lần
                delay(450)
                bmp = acc?.screenshot()
            }
            if (bmp != null) return bmp
        }
        return if (projection != null) frameFromProjection() else null
    }

    private suspend fun captureFrame(acc: Boolean): Frame? {
        if (acc) {
            val blocks = CaptureAccessibilityService.instance?.readScreen() ?: emptyList()
            // Ảnh chụp chỉ để lấy màu nền cho bản dịch và làm phương án dự phòng OCR (chữ trong ảnh/game).
            val bmp = if (canCapture()) captureScreen() else null
            return Frame(bmp, blocks)
        }
        val bmp = captureScreen() ?: return null
        return Frame(bmp, null)
    }

    private suspend fun translateFrame(f: Frame, region: Rect?): PipelineResult {
        val blocks = f.blocks
        if (blocks != null) {
            val res = Pipeline.runBlocks(this, blocks, region)
            val bmp = f.bmp
            if (res.items.isNotEmpty() || bmp == null) return res
            return Pipeline.run(this, bmp, region) // không có chữ hệ thống (ảnh, game) -> quay về OCR
        }
        return Pipeline.run(this, f.bmp!!, region)
    }

    private fun requestProjection(then: Boolean) {
        toast(R.string.need_projection)
        startActivity(
            Intent(this, ProjectionRequestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_THEN, then)
        )
    }

    private fun fingerprint(b: Bitmap): Int {
        val s = Bitmap.createScaledBitmap(b, 16, 28, true)
        var h = 17
        for (y in 0 until 28) for (x in 0 until 16) h = h * 31 + ((s.getPixel(x, y) ushr 3) and 0x1F1F1F)
        if (s !== b) s.recycle()
        return h
    }

    // ---------------------------------------------------------------- actions

    private fun onBubbleTap() {
        when {
            liveJob?.isActive == true -> stopLive()
            layer != null -> removeLayer()
            prefs.tapAction == "region" -> translateRegion()
            else -> translateFull()
        }
    }

    private fun onLongPress() {
        if (prefs.longPress == "region") translateRegion() else showMenu()
    }

    private fun startJob(block: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch {
            try {
                block()
            } finally {
                bubble?.visibility = View.VISIBLE
                setBubbleColor(if (liveJob?.isActive == true) COLOR_LIVE else prefs.iconColor)
            }
        }
    }

    private fun translateFull(delayMs: Long = 0) {
        stopLive()
        startJob {
            if (delayMs > 0) delay(delayMs)
            runCapture(null)
        }
    }

    private fun translateRegion() {
        stopLive()
        startJob {
            if (!accMode() && !canCapture()) { requestProjection(false); return@startJob }
            val r = selectRegion() ?: return@startJob
            runCapture(r)
        }
    }

    private suspend fun runCapture(region: Rect?) {
        if (prefs.mode == "acc" && CaptureAccessibilityService.instance == null) toast(R.string.acc_mode_need)
        val acc = accMode()
        if (!acc && !canCapture()) { requestProjection(true); return }
        removeLayer(); hideMenu()
        bubble?.visibility = View.INVISIBLE
        delay(if (acc) 120L else 240L) // chờ bong bóng biến mất khỏi khung hình
        val frame = captureFrame(acc)
        bubble?.visibility = View.VISIBLE
        if (frame == null) { toast(R.string.err_capture); return }
        setBubbleColor(COLOR_BUSY)
        try {
            val res = translateFrame(frame, region)
            res.note?.let { toast(getString(R.string.fallback_note, it.take(80))) }
            lastItems = res.items
            when {
                res.items.isEmpty() -> toast(R.string.no_text)
                prefs.resultMode == "panel" -> showPanel(res.items)
                else -> showOverlay(frame.bmp, res.items, true)
            }
            if (res.items.isNotEmpty()) {
                val all = res.items.joinToString("\n") { it.dst }
                if (prefs.autoCopy) Util.copy(this, all)
                if (prefs.autoSpeak) Speaker.speak(this, all, prefs.dst)
                if (prefs.haptics) bubble?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            toast(errorText(e))
        } finally {
            frame.bmp?.recycle()
        }
    }

    private fun errorText(e: Exception): String = when (e) {
        is UnknownHostException, is ConnectException -> getString(R.string.err_network)
        is OcrPackMissing -> e.message ?: getString(R.string.err_generic, "OCR")
        else -> getString(R.string.err_generic, e.message ?: e.javaClass.simpleName)
    }

    private fun translateClip(text: String) {
        scope.launch {
            try {
                var dst = prefs.dst
                val det = vn.dichmanhinh.app.engine.LangDetect.detect(text)
                val src = if (prefs.src != vn.dichmanhinh.app.data.Languages.AUTO) prefs.src
                else det ?: vn.dichmanhinh.app.data.Languages.AUTO
                if (src == dst) dst = if (dst == "en") "vi" else "en" // chữ đã là ngôn ngữ đích: dịch sang ngôn ngữ kia
                val out = vn.dichmanhinh.app.engine.TranslatorFactory.create(prefs)
                    .translate(listOf(text), listOf(src), dst)[0]
                showCard(text, vn.dichmanhinh.app.engine.Glossary.apply(out, prefs.glossary), true, src)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(errorText(e))
            }
        }
    }

    // ---------------------------------------------------------------- live mode

    private fun toggleLive() {
        if (liveJob?.isActive == true) stopLive() else startLive()
    }

    private fun stopLive() {
        if (liveJob == null) return
        liveJob?.cancel()
        liveJob = null
        removeLayer()
        setBubbleColor(prefs.iconColor)
    }

    private fun startLive() {
        if (!accMode() && !canCapture()) { requestProjection(false); return }
        job?.cancel()
        removeLayer(); hideMenu()
        setBubbleColor(COLOR_LIVE)
        toast(R.string.live_on)
        liveJob = scope.launch {
            var lastFp = 0
            var first = true
            while (isActive) {
                removeLayer()
                bubble?.visibility = View.INVISIBLE
                val acc = accMode()
                delay(if (acc) 120L else 240L)
                val frame = captureFrame(acc)
                bubble?.visibility = View.VISIBLE
                if (frame != null) {
                    val f = frame.bmp?.let { fingerprint(it) } ?: (frame.blocks?.hashCode() ?: 0)
                    if (!first && f == lastFp && lastBoxes.isNotEmpty()) {
                        showOverlay(frame.bmp, lastItems, false, lastBoxes)
                    } else {
                        try {
                            val res = translateFrame(frame, null)
                            lastItems = res.items
                            if (res.items.isNotEmpty()) showOverlay(frame.bmp, res.items, false) else lastBoxes = emptyList()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            toast(errorText(e))
                            stopLive()
                            return@launch
                        }
                    }
                    lastFp = f
                    first = false
                    frame.bmp?.recycle()
                }
                delay(prefs.liveInterval * 1000L)
            }
        }
    }

    // ---------------------------------------------------------------- bubble + menu

    private fun setBubbleColor(c: Int) {
        (bubble?.background as? GradientDrawable)?.setColor(c)
    }

    private fun scheduleDim() {
        main.removeCallbacks(dimRun)
        if (prefs.edgeDim) main.postDelayed(dimRun, 3000)
    }

    private fun wake() {
        main.removeCallbacks(dimRun)
        bubble?.animate()?.cancel()
        bubble?.alpha = prefs.bubbleAlpha / 100f
    }

    private fun showBubble() {
        if (bubble != null) return
        val size = UiKit.dp(this, prefs.bubbleSize)
        val tv = TextView(this).apply {
            text = GLYPHS[prefs.iconStyle.coerceIn(0, GLYPHS.size - 1)]
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            textSize = prefs.bubbleSize / 3.4f
            gravity = Gravity.CENTER
            alpha = prefs.bubbleAlpha / 100f
            background = UiKit.oval(if (liveJob?.isActive == true) COLOR_LIVE else prefs.iconColor)
        }
        val (w, h, _) = displaySize()
        val lp = LP(
            size, size, LP.TYPE_APPLICATION_OVERLAY,
            LP.FLAG_NOT_FOCUSABLE or LP.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (prefs.bubbleX >= 0) prefs.bubbleX else w - size
            y = if (prefs.bubbleY >= 0) prefs.bubbleY else h / 3
        }
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        tv.setOnTouchListener(object : View.OnTouchListener {
            var dx = 0f; var dy = 0f; var sx = 0; var sy = 0
            var moved = false; var long = false
            var lastTapAt = 0L
            val longRun = Runnable {
                if (!moved) {
                    long = true
                    tv.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onLongPress()
                }
            }
            // "Di chuyển rồi giữ yên" -> tự bật chế độ chọn vùng (nếu bật trong Cài đặt)
            val holdAfterMove = Runnable {
                if (moved && prefs.autoRegion) {
                    long = true
                    tv.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    translateRegion()
                }
            }
            val singleTap = Runnable { onBubbleTap() }

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        wake()
                        dx = e.rawX; dy = e.rawY; sx = lp.x; sy = lp.y
                        moved = false; long = false
                        main.postDelayed(longRun, 450)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val mx = e.rawX - dx
                        val my = e.rawY - dy
                        if (!moved && hypot(mx, my) > slop) {
                            moved = true
                            main.removeCallbacks(longRun)
                            hideMenu()
                        }
                        if (moved) {
                            lp.x = (sx + mx).toInt()
                            lp.y = (sy + my).toInt()
                            wm.updateViewLayout(tv, lp)
                            if (prefs.autoRegion) {
                                main.removeCallbacks(holdAfterMove)
                                main.postDelayed(holdAfterMove, 700)
                            }
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        main.removeCallbacks(longRun)
                        main.removeCallbacks(holdAfterMove)
                        if (moved) {
                            val (sw, _, _) = displaySize()
                            lp.x = if (lp.x + size / 2 < sw / 2) 0 else sw - size
                            wm.updateViewLayout(tv, lp)
                            prefs.bubbleX = lp.x
                            prefs.bubbleY = lp.y
                        } else if (!long && e.action == MotionEvent.ACTION_UP) {
                            if (prefs.doubleTapRegion) {
                                val now = SystemClock.uptimeMillis()
                                if (now - lastTapAt < 300) {
                                    // chạm đúp -> chọn vùng
                                    main.removeCallbacks(singleTap)
                                    lastTapAt = 0L
                                    translateRegion()
                                } else {
                                    lastTapAt = now
                                    main.postDelayed(singleTap, 300)
                                }
                            } else onBubbleTap()
                        }
                        scheduleDim()
                    }
                }
                return true
            }
        })
        wm.addView(tv, lp)
        bubble = tv
        bubbleLp = lp
        scheduleDim()
    }

    private fun removeBubble() {
        main.removeCallbacks(dimRun)
        bubble?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        bubble = null
    }

    private fun showMenu() {
        hideMenu()
        wake()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiKit.round(0xF2263238.toInt(), UiKit.dp(this@BubbleService, 14).toFloat())
            setPadding(0, UiKit.dp(this@BubbleService, 6), 0, UiKit.dp(this@BubbleService, 6))
            minimumWidth = UiKit.dp(this@BubbleService, 230)
        }
        fun itemText(label: String, action: () -> Unit) =
            box.addView(UiKit.menuItem(this, label) { hideMenu(); action() })
        fun item(@StringRes id: Int, action: () -> Unit) = itemText(getString(id), action)

        item(R.string.menu_full) { translateFull() }
        item(R.string.menu_region) { translateRegion() }
        item(if (liveJob?.isActive == true) R.string.menu_live_off else R.string.menu_live_on) { toggleLive() }
        item(if (prefs.mode == "acc") R.string.menu_mode_to_ocr else R.string.menu_mode_to_acc) {
            prefs.sp.edit().putString("mode", if (prefs.mode == "acc") "ocr" else "acc").apply()
            toast(if (prefs.mode == "acc") R.string.mode_now_acc else R.string.mode_now_ocr)
        }
        if (CaptureAccessibilityService.instance != null) {
            item(if (prefs.typedTranslate) R.string.menu_typed_off else R.string.menu_typed_on) {
                prefs.sp.edit().putBoolean("typed_translate", !prefs.typedTranslate).apply()
            }
        }
        item(R.string.menu_camera) {
            startActivity(Intent(this, CameraTranslateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        item(R.string.menu_image) {
            startActivity(Intent(this, ImageTranslateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        item(R.string.settings) {
            startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        item(R.string.menu_close) { stopSelf() }
        box.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) { hideMenu(); true } else false
        }
        val (w, h, _) = displaySize()
        val size = UiKit.dp(this, prefs.bubbleSize)
        val mw = UiKit.dp(this, 230)
        val lp = LP(
            LP.WRAP_CONTENT, LP.WRAP_CONTENT, LP.TYPE_APPLICATION_OVERLAY,
            LP.FLAG_NOT_FOCUSABLE or LP.FLAG_WATCH_OUTSIDE_TOUCH, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (bubbleLp.x < w / 2) bubbleLp.x + size + UiKit.dp(this@BubbleService, 8)
            else (bubbleLp.x - mw - UiKit.dp(this@BubbleService, 8)).coerceAtLeast(0)
            y = bubbleLp.y.coerceAtMost(h - UiKit.dp(this@BubbleService, 470)).coerceAtLeast(0)
        }
        wm.addView(box, lp)
        menu = box
    }

    private fun hideMenu() {
        menu?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        menu = null
    }

    // ---------------------------------------------------------------- result layers

    private fun addLayer(v: View, lp: LP) {
        removeLayer()
        wm.addView(v, lp)
        layer = v
    }

    private fun removeLayer() {
        removeDetail()
        layer?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        layer = null
    }

    private fun removeDetail() {
        detail?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        detail = null
    }

    private fun fullLp(touchable: Boolean): LP {
        val (w, h, _) = displaySize()
        var flags = LP.FLAG_NOT_FOCUSABLE or LP.FLAG_LAYOUT_IN_SCREEN or LP.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) flags = flags or LP.FLAG_NOT_TOUCHABLE
        return LP(w, h, LP.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30)
                LP.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS else LP.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private suspend fun selectRegion(): Rect? = suspendCancellableCoroutine { cont ->
        removeLayer(); hideMenu()
        val root = FrameLayout(this)
        root.addView(
            RegionSelectView(this, getString(R.string.region_hint)) { r ->
                removeLayer()
                if (cont.isActive) cont.resume(r)
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        root.addView(
            UiKit.chip(this, getString(R.string.cancel)) {
                removeLayer()
                if (cont.isActive) cont.resume(null)
            },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = UiKit.dp(this@BubbleService, 90) }
        )
        addLayer(root, fullLp(true))
        cont.invokeOnCancellation { main.post { removeLayer() } }
    }

    private fun showOverlay(bmp: Bitmap?, items: List<Item>, interactive: Boolean, boxes: List<DrawBox>? = null) {
        val drawBoxes = boxes ?: BoxPainter.prepare(bmp, items, StyleCfg.from(prefs))
        lastBoxes = drawBoxes
        val rv = ResultView(this, drawBoxes, prefs.textScale / 100f, prefs.oneTouchClose, prefs.swipeClose,
            onOutside = { removeLayer() },
            onBox = { showDetail(it) })
        val root = FrameLayout(this)
        root.addView(rv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (interactive) {
            val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val m = UiKit.dp(this, 4)
            fun add(@StringRes id: Int, a: () -> Unit) = bar.addView(
                UiKit.chip(this, getString(id), a),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(m, 0, m, 0) }
            )
            if (!prefs.hideClose) add(R.string.close) { removeLayer() }
            add(R.string.copy) { Util.copy(this, items.joinToString("\n") { it.dst }); toast(R.string.copied) }
            add(R.string.speak) { Speaker.speak(this, items.joinToString(". ") { it.dst }, prefs.dst) }
            add(R.string.list) { showPanel(items) }
            if (!prefs.hideSettings) add(R.string.settings_short) {
                removeLayer()
                startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = UiKit.dp(this@BubbleService, 90) })
        }
        addLayer(root, fullLp(interactive))
    }

    /** Chạm một khung dịch: xem nguyên văn + bản dịch, sao chép, đọc, tra từ điển (cần mạng, chỉ khi bạn bấm). */
    private fun showDetail(b: DrawBox) = showCard(b.src, b.text, false, prefs.src)

    /** Thẻ nhỏ ở đáy màn hình: nguyên văn + bản dịch + Sao chép / Đọc / Từ điển (+ Mở trong app). */
    private fun showCard(srcText: String, outText: String, openInApp: Boolean, srcLang: String) {
        removeDetail()
        val pad = UiKit.dp(this, 14)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiKit.round(0xF2202124.toInt(), UiKit.dp(this@BubbleService, 16).toFloat())
            setPadding(pad, pad, pad, pad)
        }
        card.addView(UiKit.label(this, srcText, 12f, 0xFFB0BEC5.toInt()))
        card.addView(UiKit.label(this, outText, 17f, Color.WHITE).apply {
            setPadding(0, UiKit.dp(this@BubbleService, 6), 0, UiKit.dp(this@BubbleService, 6))
        })
        val extra = UiKit.label(this, "", 13f, 0xFFCFD8DC.toInt()).apply { visibility = View.GONE }
        card.addView(ScrollView(this).apply { addView(extra) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val m = UiKit.dp(this, 4)
        fun add(label: String, a: () -> Unit) = bar.addView(
            UiKit.chip(this, label, a),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, m, m, 0) }
        )
        add(getString(R.string.copy)) { Util.copy(this, outText); toast(R.string.copied) }
        add(getString(R.string.speak)) { Speaker.speak(this, outText, prefs.dst) }
        add(getString(R.string.dictionary)) {
            extra.visibility = View.VISIBLE
            extra.text = getString(R.string.loading)
            scope.launch {
                extra.text = try {
                    GoogleWeb.format(GoogleWeb.lookup(srcText, srcLang, prefs.dst))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errorText(e)
                }
            }
        }
        if (openInApp) add(getString(R.string.open_in_app)) {
            removeDetail()
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("text", srcText)
            )
        }
        add(getString(R.string.close)) { removeDetail() }
        card.addView(bar)
        val (w, _, _) = displaySize()
        val lp = LP(
            w - UiKit.dp(this, 20), LP.WRAP_CONTENT, LP.TYPE_APPLICATION_OVERLAY,
            LP.FLAG_NOT_FOCUSABLE or LP.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = UiKit.dp(this@BubbleService, 150)
        }
        wm.addView(card, lp)
        detail = card
    }

    private fun showPanel(items: List<Item>) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiKit.round(0xF2202124.toInt(), UiKit.dp(this@BubbleService, 18).toFloat(), topOnly = true)
            setPadding(UiKit.dp(this@BubbleService, 14), UiKit.dp(this@BubbleService, 10),
                UiKit.dp(this@BubbleService, 14), UiKit.dp(this@BubbleService, 10))
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(UiKit.label(this, getString(R.string.panel_title), 15f, Color.WHITE).apply {
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val m = UiKit.dp(this, 4)
        val actions = ArrayList<Pair<Int, () -> Unit>>()
        actions.add(R.string.copy to { Util.copy(this, items.joinToString("\n") { it.dst }); toast(R.string.copied) })
        actions.add(R.string.speak to { Speaker.speak(this, items.joinToString(". ") { it.dst }, prefs.dst) })
        if (!prefs.hideClose) actions.add(R.string.close to { removeLayer() })
        actions.forEach { (id, a) ->
            head.addView(UiKit.chip(this, getString(id), a),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(m, 0, 0, 0) })
        }
        root.addView(head)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        items.forEach { item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, UiKit.dp(this@BubbleService, 8), 0, UiKit.dp(this@BubbleService, 8))
                setOnClickListener { Util.copy(this@BubbleService, item.dst); toast(R.string.copied) }
            }
            row.addView(UiKit.label(this, item.src, 12f, 0xFFB0BEC5.toInt()))
            row.addView(UiKit.label(this, item.dst, 16f, Color.WHITE))
            list.addView(row)
        }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val (_, h, _) = displaySize()
        val lp = LP(
            LP.MATCH_PARENT, (h * 0.55f).toInt(), LP.TYPE_APPLICATION_OVERLAY,
            LP.FLAG_NOT_FOCUSABLE or LP.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }
        addLayer(root, lp)
    }

    // ---------------------------------------------------------------- misc

    private fun toast(@StringRes id: Int) = Toast.makeText(this, id, Toast.LENGTH_SHORT).show()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}

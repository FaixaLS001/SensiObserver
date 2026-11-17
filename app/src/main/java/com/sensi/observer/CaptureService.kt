package com.sensi.observer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
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
import android.os.Process
import android.os.SystemClock
import android.view.Display
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.sensi.observer.engine.DeviceInfo
import com.sensi.observer.engine.GainRecord
import com.sensi.observer.engine.Layout
import com.sensi.observer.engine.Pipeline
import com.sensi.observer.engine.Scope
import com.sensi.observer.engine.SessionStore
import com.sensi.observer.engine.SwipeRun
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Serviço principal: transmite a tela do jogo (compartilhamento de tela), manda cada quadro para o
 * motor de análise (movimento da câmera, tiro, acerto) e, ao finalizar, guarda a sessão e gera o
 * relatório. A bolinha e a notificação ficam por segurança: pausar, finalizar ou descartar.
 */
class CaptureService : Service(), FloatingControls.Host {

    companion object {
        const val ACTION_START = "com.sensi.observer.START"
        const val ACTION_FINISH = "com.sensi.observer.FINISH"
        const val ACTION_DISCARD = "com.sensi.observer.DISCARD"
        const val ACTION_PAUSE = "com.sensi.observer.PAUSE"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"

        private const val CHANNEL = "observer_capture"
        private const val CHANNEL_DONE = "observer_done"
        private const val NOTIF_ID = 1
        private const val NOTIF_DONE_ID = 2

        /** Sessões com menos quadros que isto são descartadas (curtas demais para analisar). */
        private const val MIN_FRAMES = 150

        @Volatile
        var running = false

        /** true enquanto a observação está pausada pela bolinha ou pela notificação. */
        @Volatile
        var paused = false
    }

    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var displayListener: DisplayManager.DisplayListener? = null

    @Volatile
    private var controls: FloatingControls? = null

    @Volatile
    private var markers: Markers? = null

    @Volatile
    private var pipeline: Pipeline? = null

    private var store: SessionStore? = null
    private var device: DeviceInfo? = null

    @Volatile
    private var scope = 0

    private var screenW = 0
    private var screenH = 0
    private var scale = 0.4f
    private var stopping = false

    // Só a thread dos quadros mexe nestes.
    private val lay = Layout()
    private var layoutAt = 0L
    private var layoutW = 0
    private var layoutH = 0
    private var inPortrait = false

    /** Diferença (ns) entre o relógio do aparelho e o dos quadros (menor atraso visto). */
    @Volatile
    private var tsOffsetNs = Long.MAX_VALUE

    // Calibração guiada em andamento (só a thread principal mexe).
    private var calBusy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ObsSettings.init(this)
        when (intent?.action) {
            ACTION_FINISH -> finishAll(save = true, open = true)
            ACTION_DISCARD -> finishAll(save = false, open = false)
            ACTION_PAUSE -> onTogglePause()
            ACTION_START -> {
                startForegroundCompat()
                if (running) return START_NOT_STICKY
                val code = intent.getIntExtra(EXTRA_CODE, 0)
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }
                if (data == null || !startCapture(code, data)) {
                    finishAll(save = false, open = false)
                }
            }
        }
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- notificação

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Observação do jogo", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val finishPi = PendingIntent.getService(
            this, 0, Intent(this, CaptureService::class.java).setAction(ACTION_FINISH), flags
        )
        val pausePi = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_PAUSE), flags
        )
        val openPi = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_observer)
            .setColor(0xFF7C5CFF.toInt())
            .setContentTitle("Observador de Sensi")
            .setContentText(text)
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(0, if (paused) "Retomar" else "Pausar", pausePi)
            .addAction(0, "Finalizar", finishPi)
            .build()
    }

    private fun startForegroundCompat() {
        val type = if (Build.VERSION.SDK_INT >= 29) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification("Observando o combate..."), type)
    }

    private fun updateNotification() {
        if (stopping) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(if (paused) "Pausado" else "Observando o combate..."))
    }

    private fun notifyDone(id: Long) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_DONE, "Relatório pronto", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val open = Intent(this, ReportActivity::class.java)
            .putExtra(ReportActivity.EXTRA_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            this, 3, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_observer)
            .setColor(0xFF7C5CFF.toInt())
            .setContentTitle("Relatório pronto")
            .setContentText("Toque para ver a sensi recomendada.")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_DONE_ID, n)
    }

    // ---------------------------------------------------------------- iniciar

    private fun startCapture(code: Int, data: Intent): Boolean {
        val dev = ScreenInfo.deviceInfo(this)
        device = dev
        scale = ObsSettings.scalePct / 100f
        store = SessionStore(File(filesDir, "sessions"))

        val sens = IntArray(Scope.COUNT) { ObsSettings.sens(it) }
        val pipe = Pipeline(dev, sens, ObsSettings.fingers, System.currentTimeMillis())
        pipe.useStaticMask = ObsSettings.useStaticMask
        pipe.useDiagonalHit = ObsSettings.useDiagonalHit
        pipe.hit.thrHead = ObsSettings.thrHead
        pipe.hit.thrBody = ObsSettings.thrBody
        if (ObsSettings.fireCalOk) pipe.fire.setCalibration(ObsSettings.fireIdle, ObsSettings.fireThreshold)
        pipe.gains = store?.loadGains() ?: emptyList()
        pipe.scope = 0
        scope = 0
        pipeline = pipe

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = try {
            mpm.getMediaProjection(code, data)
        } catch (e: Exception) {
            null
        } ?: return false
        projection = proj

        val t = HandlerThread("observer", Process.THREAD_PRIORITY_URGENT_DISPLAY)
        t.start()
        thread = t
        handler = Handler(t.looper)

        // Obrigatório no Android 14+: registrar o callback antes de criar o display virtual.
        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                // O jogador tocou em "parar de compartilhar": guarda o que já foi observado.
                main.post { finishAll(save = true, open = true) }
            }
        }
        projectionCallback = cb
        proj.registerCallback(cb, main)

        val size = ScreenInfo.realSize(this)
        screenW = size.first
        screenH = size.second
        val capW = max(16, (screenW * scale).toInt())
        val capH = max(16, (screenH * scale).toInt())

        val r = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 4)
        r.setOnImageAvailableListener({ onFrames(it) }, handler)
        reader = r

        virtualDisplay = proj.createVirtualDisplay(
            "observer", capW, capH, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler
        )

        paused = false
        val m = Markers(this)
        m.show()
        markers = m
        if (ObsSettings.showBubble) {
            val c = FloatingControls(this, m, this)
            c.show()
            controls = c
        }
        watchRotation()
        running = true
        main.postDelayed(liveTick, 300)
        return true
    }

    private val liveTick = object : Runnable {
        override fun run() {
            if (stopping || !running) return
            controls?.updateLive()
            main.postDelayed(this, 250)
        }
    }

    // ---------------------------------------------------------------- rotação

    private fun watchRotation() {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val l = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                // Roda na mesma thread dos quadros para não misturar com a análise.
                handler?.post { reconfigure() }
            }
        }
        displayListener = l
        dm.registerDisplayListener(l, main)
    }

    private fun reconfigure() {
        if (stopping) return
        val size = ScreenInfo.realSize(this)
        if (size.first == screenW && size.second == screenH) return
        screenW = size.first
        screenH = size.second
        val capW = max(16, (screenW * scale).toInt())
        val capH = max(16, (screenH * scale).toInt())
        try {
            val old = reader
            old?.setOnImageAvailableListener(null, null)
            val r = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 4)
            r.setOnImageAvailableListener({ onFrames(it) }, handler)
            virtualDisplay?.resize(capW, capH, resources.displayMetrics.densityDpi)
            virtualDisplay?.surface = r.surface
            reader = r
            old?.close()
        } catch (e: Exception) {
            // Se falhar, continua com o que estava funcionando.
        }
        pipeline?.onResize()
        layoutAt = 0L
    }

    // ---------------------------------------------------------------- quadros

    private fun onFrames(r: ImageReader) {
        while (true) {
            val image: Image = try {
                r.acquireNextImage()
            } catch (e: Exception) {
                null
            } ?: break
            try {
                handleImage(image)
            } catch (e: Throwable) {
                // Quadro inválido (ex.: durante a rotação): ignora e espera o próximo.
            } finally {
                image.close()
            }
        }
    }

    private fun handleImage(image: Image) {
        val pipe = pipeline ?: return
        if (stopping || paused) return

        val w = image.width
        val h = image.height
        // O Free Fire roda deitado. Em pé (tela inicial, menus do celular) não há o que medir.
        if (w < h) {
            if (!inPortrait) {
                inPortrait = true
                pipe.onResize()
            }
            return
        }
        if (inPortrait) {
            inPortrait = false
            pipe.onResize()
        }

        val ts = if (image.timestamp > 0L) image.timestamp else System.nanoTime()
        val off = System.nanoTime() - ts
        if (off < tsOffsetNs) tsOffsetNs = off

        val now = SystemClock.uptimeMillis()
        if (now - layoutAt > 300L || layoutW != w || layoutH != h) {
            refreshLayout(w, h)
            layoutAt = now
            layoutW = w
            layoutH = h
        }

        val plane = image.planes[0]
        pipe.scope = scope
        pipe.onFrame(ts, plane.buffer, plane.rowStride, plane.pixelStride, lay)
    }

    /** Converte as posições marcadas (milésimos da tela) para pixels do quadro capturado. */
    private fun refreshLayout(capW: Int, capH: Int) {
        val wf = capW.toFloat()
        val hf = capH.toFloat()
        lay.capW = capW
        lay.capH = capH

        val side = min(ObsSettings.readSizePm / 1000f * hf, min(wf, hf))
        lay.readSize = side
        lay.readCx = (ObsSettings.readXPm / 1000f * wf).coerceIn(side / 2f, max(side / 2f, wf - side / 2f))
        lay.readCy = (ObsSettings.readYPm / 1000f * hf).coerceIn(side / 2f, max(side / 2f, hf - side / 2f))

        lay.aimX = ObsSettings.aimXPm / 1000f * wf
        lay.aimY = ObsSettings.aimYPm / 1000f * hf
        lay.hitHalf = ObsSettings.hitSizePm / 1000f * hf

        lay.fireX = ObsSettings.fireXPm / 1000f * wf
        lay.fireY = ObsSettings.fireYPm / 1000f * hf
        lay.fireRadius = ObsSettings.fireRadiusPm / 1000f * hf

        val mr = controls?.maskRect
        if (mr != null && screenW > 0 && screenH > 0) {
            val sx = wf / screenW
            val sy = hf / screenH
            lay.hasMask = true
            lay.maskL = (mr[0] * sx).toInt()
            lay.maskT = (mr[1] * sy).toInt()
            lay.maskR = (mr[2] * sx).toInt() + 1
            lay.maskB = (mr[3] * sy).toInt() + 1
        } else {
            lay.hasMask = false
        }
    }

    // ---------------------------------------------------------------- FloatingControls.Host

    override fun isPaused(): Boolean = paused

    override fun currentScope(): Int = scope

    override fun onTogglePause() {
        if (stopping || !running) return
        paused = !paused
        pipeline?.setPaused(paused)
        controls?.refresh()
        controls?.updateLive()
        updateNotification()
    }

    override fun onCycleScope() {
        scope = (scope + 1) % Scope.COUNT
        pipeline?.scope = scope
        toast("Mira em uso: ${Scope.of(scope).label}")
    }

    override fun onFinish() {
        main.post { finishAll(save = true, open = true) }
    }

    override fun onDiscard() {
        main.post { finishAll(save = false, open = false) }
    }

    override fun liveText(): String {
        val p = pipeline ?: return ""
        val l = p.live
        val sc = Scope.of(scope).label
        return String.format(
            Locale.US,
            "%s · %.0f fps · leitura %.0f%%\ntiro %s · acertos %d (capa %d)\nmarcador: verm +%d · branco +%d",
            sc, l.fps, l.validPct,
            if (l.firing) "ON " else "off",
            l.hitsBody + l.hitsHead, l.hitsHead,
            l.excessRed, l.excessWhite
        )
    }

    // ---------------------------------------------------------------- calibrações guiadas

    private class CalStep(
        val text: String,
        val ms: Long,
        val onStart: () -> Unit = {},
        val onEnd: () -> Unit = {}
    )

    /** Roda os passos um depois do outro, mostrando a mensagem e a contagem na bolinha. */
    private fun runSequence(steps: List<CalStep>, done: () -> Unit) {
        var i = 0
        fun begin() {
            if (stopping) return
            if (i >= steps.size) {
                done()
                return
            }
            val st = steps[i]
            st.onStart()
            val t0 = SystemClock.uptimeMillis()
            val tick = object : Runnable {
                override fun run() {
                    if (stopping) return
                    val left = st.ms - (SystemClock.uptimeMillis() - t0)
                    if (left <= 0L) {
                        st.onEnd()
                        i++
                        begin()
                        return
                    }
                    controls?.setStatus(st.text + " " + ((left + 999L) / 1000L) + "s")
                    main.postDelayed(this, 200)
                }
            }
            tick.run()
        }
        begin()
    }

    private fun endCalibration(msg: String) {
        controls?.setStatus(msg)
        main.postDelayed({
            calBusy = false
            controls?.setStatus(null)
        }, 5000)
    }

    private fun canCalibrate(): Boolean {
        if (calBusy) {
            toast("Já há uma calibração em andamento.")
            return false
        }
        if (paused) {
            toast("Retome a observação antes de calibrar.")
            return false
        }
        if (pipeline == null) return false
        calBusy = true
        return true
    }

    override fun onCalibrateFire() {
        if (!canCalibrate()) return
        val pipe = pipeline ?: return
        val steps = listOf(
            CalStep("TIRO 1/2: NÃO toque no botão de tiro.", 3000, { pipe.fireCalibrationPhase(1) }, { pipe.fireCalibrationPhase(0) }),
            CalStep("TIRO 2/2: prepare-se para SEGURAR o botão de tiro.", 2000),
            CalStep("SEGURE o botão de tiro.", 3000, { pipe.fireCalibrationPhase(2) }, { pipe.fireCalibrationPhase(0) })
        )
        runSequence(steps) {
            val r = pipe.finishFireCalibration()
            if (r != null && r.ok) {
                ObsSettings.saveFireCalibration(r.idle, r.threshold)
                endCalibration("Botão de tiro calibrado.")
            } else {
                endCalibration(r?.message ?: "Não foi possível calibrar o botão de tiro.")
            }
        }
    }

    override fun onCalibrateHit(head: Boolean) {
        if (!canCalibrate()) return
        val pipe = pipeline ?: return
        val what = if (head) "na CABEÇA" else "no CORPO"
        val steps = listOf(
            CalStep("ACERTO: mire num boneco de treino.", 3000),
            CalStep("ATIRE $what do alvo várias vezes.", 9000, { pipe.beginHitCalibration(head) })
        )
        runSequence(steps) {
            val peak = pipe.endHitCalibration()
            if (peak >= 3) {
                if (head) {
                    ObsSettings.thrHead = pipe.hit.thrHead
                    ObsSettings.hitHeadCalibrated = true
                } else {
                    ObsSettings.thrBody = pipe.hit.thrBody
                    ObsSettings.hitBodyCalibrated = true
                }
                endCalibration("Acerto ${if (head) "na cabeça" else "no corpo"} calibrado (pico $peak).")
            } else {
                endCalibration("Não vi o marcador de acerto. Confira o quadrado de '1 Mira'.")
            }
        }
    }

    override fun onCalibrateResponse() {
        val svc = ObserverAccessibilityService.instance
        if (svc == null) {
            toast("Ative a acessibilidade do app (opcional) para calibrar a resposta.")
            return
        }
        if (!canCalibrate()) return
        val pipe = pipeline ?: return
        val dev = device ?: return
        val sc = scope
        val sens = ObsSettings.sens(sc)

        val size = ScreenInfo.realSize(this)
        val w = max(size.first, size.second).toFloat()
        val h = min(size.first, size.second).toFloat()
        // Em pé o gesto não faria sentido: o jogo precisa estar deitado.
        if (size.first < size.second) {
            calBusy = false
            toast("Abra o jogo na horizontal para calibrar a resposta.")
            return
        }
        var cx = ObsSettings.dragXPm / 1000f * w - 130f
        var cy = ObsSettings.dragYPm / 1000f * h - 80f

        val moves = listOf(
            floatArrayOf(260f, 0f), floatArrayOf(-260f, 0f),
            floatArrayOf(260f, 0f), floatArrayOf(-260f, 0f),
            floatArrayOf(0f, 160f), floatArrayOf(0f, -160f)
        )
        val runs = ArrayList<SwipeRun>()

        fun finishResponse() {
            val res = pipe.estimateGain(runs)
            if (res.ok) {
                val rec = GainRecord(
                    System.currentTimeMillis(), sc, sens, dev.densityDpi, dev.widthPx, dev.heightPx,
                    res.gain, res.used, res.spread
                )
                store?.addGain(rec)
                pipe.gains = store?.loadGains() ?: emptyList()
                endCalibration(
                    String.format(Locale.US, "Resposta medida: %.2f px de câmera por px de dedo (%d arrastos).", res.gain, res.used)
                )
            } else {
                endCalibration(res.message)
            }
        }

        fun next(i: Int) {
            if (stopping) return
            if (i >= moves.size) {
                controls?.setStatus("RESPOSTA: medindo...")
                main.postDelayed({ finishResponse() }, 700)
                return
            }
            controls?.setStatus("RESPOSTA: arrastando ${i + 1}/${moves.size}. Não toque na tela.")
            val dx = moves[i][0]
            val dy = moves[i][1]
            val x0 = cx
            val y0 = cy
            svc.swipe(x0, y0, x0 + dx, y0 + dy, 300L) { ok, startNs, endNs ->
                main.post {
                    if (ok) {
                        val off = tsOffsetNs
                        val o = if (off == Long.MAX_VALUE) 0L else off
                        runs.add(
                            SwipeRun(
                                pipe.timelineMs(startNs - o), pipe.timelineMs(endNs - o),
                                dx.toDouble(), dy.toDouble()
                            )
                        )
                    }
                    cx += dx
                    cy += dy
                    main.postDelayed({ next(i + 1) }, 750)
                }
            }
        }

        runSequence(
            listOf(CalStep("RESPOSTA: fique no campo de treino, mira livre. Não toque. Começa em", 3500))
        ) { next(0) }
    }

    // ---------------------------------------------------------------- finalizar

    /**
     * Para a captura. Se [save], analisa a sessão inteira, guarda e (se [open]) abre o relatório.
     */
    private fun finishAll(save: Boolean, open: Boolean) {
        if (stopping) return
        stopping = true
        running = false
        paused = false
        main.removeCallbacks(liveTick)

        try {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            displayListener?.let { dm.unregisterDisplayListener(it) }
        } catch (e: Exception) {
        }
        displayListener = null

        reader?.setOnImageAvailableListener(null, null)
        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
        }
        virtualDisplay = null

        thread?.quitSafely()
        try {
            thread?.join(500)
        } catch (e: InterruptedException) {
        }
        thread = null
        handler = null

        try {
            reader?.close()
        } catch (e: Exception) {
        }
        reader = null

        try {
            projectionCallback?.let { projection?.unregisterCallback(it) }
            projection?.stop()
        } catch (e: Exception) {
        }
        projection = null

        controls?.hide()
        controls = null
        markers?.hide()
        markers = null

        val pipe = pipeline
        pipeline = null
        val st = store
        val dev = device

        if (!save || pipe == null || st == null) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        // A análise roda fora da thread principal; o serviço continua em primeiro plano até acabar.
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification("Analisando a sessão..."))
        Thread {
            var id = 0L
            var error = false
            try {
                if (pipe.timeline.size >= MIN_FRAMES) {
                    val summary = pipe.finish()
                    st.save(summary)
                    id = summary.id
                    if (ObsSettings.recordCsv && dev != null) {
                        val dir = getExternalFilesDir(null) ?: filesDir
                        st.writeTimelineCsv(File(dir, "sessao_$id.csv"), pipe.timeline, dev.heightPx)
                    }
                }
            } catch (e: Throwable) {
                error = true
            }
            main.post {
                if (id > 0L) {
                    notifyDone(id)
                    if (open) openReport(id)
                } else if (error) {
                    toast("Não consegui analisar a sessão.")
                } else {
                    toast("Sessão curta demais para analisar. Jogue um pouco mais.")
                }
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.start()
    }

    private fun openReport(id: Long) {
        try {
            startActivity(
                Intent(this, ReportActivity::class.java)
                    .putExtra(ReportActivity.EXTRA_ID, id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        } catch (e: Exception) {
            // A notificação "Relatório pronto" leva ao mesmo lugar.
        }
    }

    override fun onDestroy() {
        if (!stopping) finishAll(save = false, open = false)
        super.onDestroy()
    }

    private fun toast(msg: String) {
        main.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() }
    }
}

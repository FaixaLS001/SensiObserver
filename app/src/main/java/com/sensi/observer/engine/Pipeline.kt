package com.sensi.observer.engine

import java.nio.ByteBuffer
import kotlin.math.hypot

/** Números ao vivo para o painel da bolinha (lidos pela thread principal). */
class LiveStats {
    @Volatile var fps = 0f
    @Volatile var speedHs = 0f
    @Volatile var conf = 0f
    @Volatile var firing = false
    @Volatile var fireDist = 0f
    @Volatile var hitsBody = 0
    @Volatile var hitsHead = 0
    @Volatile var excessRed = 0
    @Volatile var excessWhite = 0
    @Volatile var validPct = 0f
    @Volatile var frames = 0
}

/**
 * Liga tudo: recebe cada quadro, mede o movimento da câmera, vê o botão de tiro e o marcador de
 * acerto, e guarda a linha do tempo. No fim, [finish] analisa a linha do tempo inteira.
 * Tudo é sincronizado: o serviço chama os controles (calibração, pausa) de outra thread.
 */
class Pipeline(
    private val device: DeviceInfo,
    private val sens: IntArray,
    private val fingers: Int,
    val startEpochMs: Long
) {
    companion object {
        const val N = 128
    }

    private val reader = FrameReader(N)
    private val feat = FrameFeatures(N)
    private val corr = PhaseCorrelator(N)
    private val est = MotionEstimate()
    private val staticMask = StaticMask(N)

    /** Esconde da medição o que é HUD parado (ver [StaticMask]). */
    @Volatile var useStaticMask = true

    val fire = FireDetector()
    val hit = HitDetector()
    val timeline = Timeline()
    val live = LiveStats()

    /** Usa só as diagonais (X) para o acerto; false = anel inteiro. */
    @Volatile var useDiagonalHit = true

    /** Mira em uso agora (0 = Geral ... 4 = AWM). */
    @Volatile var scope = 0

    @Volatile var gains: List<GainRecord> = emptyList()

    var paused = false
        private set

    private var t0Ns = -1L
    private var lastTMs = -1.0
    private var fpsEma = 0f
    private var speedEma = 0f
    private var validEma = 1f

    private var fireCal: FireCalibrator? = null
    private var fireCalPhase = 0

    @Synchronized
    fun setPaused(p: Boolean) {
        paused = p
        if (p) {
            corr.reset()
            staticMask.reset()
            fire.reset()
            hit.reset()
            lastTMs = -1.0
        }
    }

    /** Tamanho da tela mudou (girou o aparelho): esquece o quadro anterior. */
    @Synchronized
    fun onResize() {
        corr.reset()
        staticMask.reset()
        lastTMs = -1.0
    }

    // ---------------------------------------------------------------- quadros

    @Synchronized
    fun onFrame(tNs: Long, buf: ByteBuffer, rowStride: Int, pixelStride: Int, lay: Layout) {
        if (paused) return
        if (t0Ns < 0) t0Ns = tNs
        val tMs = (tNs - t0Ns) / 1_000_000.0

        reader.read(buf, rowStride, pixelStride, lay, feat)

        // Movimento da câmera.
        if (useStaticMask) staticMask.apply(feat.patch)
        var ok = corr.update(feat.patch, est)
        if (feat.maskedFrac > 0.5f) ok = false
        if (useStaticMask) staticMask.learn(ok, est.dx, est.dy)
        var lookX = 0f
        var lookY = 0f
        if (ok) {
            val k = lay.readSize / N / lay.capH
            // O cenário anda para um lado quando a câmera gira para o outro.
            lookX = (-est.dx * k).toFloat()
            lookY = (-est.dy * k).toFloat()
        }

        // Botão de tiro.
        var pressed = false
        if (lay.fireRadius >= 2f) {
            pressed = fire.update(feat.fireR, feat.fireG, feat.fireB)
            val c = fireCal
            if (c != null) {
                if (fireCalPhase == 1) c.addIdle(feat.fireR, feat.fireG, feat.fireB)
                if (fireCalPhase == 2) c.addPressed(feat.fireR, feat.fireG, feat.fireB)
            }
        }

        // Acerto.
        val red = if (useDiagonalHit) feat.redDiag else feat.redRing
        val white = if (useDiagonalHit) feat.whiteDiag else feat.whiteRing
        val ev = hit.update(tMs, red, white)

        var flags = (scope and 7) shl Flag.SCOPE_SHIFT
        if (pressed) flags = flags or Flag.FIRE
        if (ok) flags = flags or Flag.VALID
        val idx = timeline.add(tMs, lookX, lookY, est.confidence.toFloat(), flags)

        if (ev != HitDetector.NONE && idx >= 0) {
            val at = timeline.recentIndexNear(hit.lastEventTime, 10)
            timeline.orFlag(if (at >= 0) at else idx, if (ev == HitDetector.HEAD) Flag.HIT_HEAD else Flag.HIT_BODY)
            if (ev == HitDetector.HEAD) live.hitsHead++ else live.hitsBody++
        }

        // Painel ao vivo.
        if (lastTMs >= 0.0) {
            val dt = tMs - lastTMs
            if (dt > 0.0 && dt < 250.0) {
                fpsEma += ((1000.0 / dt).toFloat() - fpsEma) * 0.05f
                speedEma += ((hypot(lookX.toDouble(), lookY.toDouble()) / dt * 1000.0).toFloat() - speedEma) * 0.25f
            }
        }
        validEma += ((if (ok) 1f else 0f) - validEma) * 0.02f
        lastTMs = tMs
        live.fps = fpsEma
        live.speedHs = speedEma
        live.conf = est.confidence.toFloat()
        live.firing = pressed
        live.fireDist = fire.lastDistance
        live.excessRed = hit.excessRed
        live.excessWhite = hit.excessWhite
        live.validPct = validEma * 100f
        live.frames = timeline.size
    }

    // ---------------------------------------------------------------- calibrações

    /** Fase 1 = botão solto, 2 = botão apertado, 0 = parada. */
    @Synchronized
    fun fireCalibrationPhase(phase: Int) {
        if (fireCal == null) fireCal = FireCalibrator()
        fireCalPhase = phase
    }

    /** Termina a calibração do botão e, se deu certo, passa a usar as cores medidas. */
    @Synchronized
    fun finishFireCalibration(): FireCalibration? {
        val c = fireCal ?: return null
        fireCal = null
        fireCalPhase = 0
        val r = c.finish()
        if (r.ok) fire.setCalibration(r.idle, r.threshold)
        return r
    }

    /** Tempo na linha do tempo (ms) de um instante dado na mesma base de tempo dos quadros. */
    @Synchronized
    fun timelineMs(tNs: Long): Double = if (t0Ns < 0) 0.0 else (tNs - t0Ns) / 1_000_000.0

    /** Mede a resposta (câmera por dedo) a partir dos arrastos de teste já feitos. */
    @Synchronized
    fun estimateGain(runs: List<SwipeRun>): GainResult = GainEstimator.estimate(timeline, runs, device.heightPx)

    @Synchronized
    fun beginHitCalibration(head: Boolean) {
        hit.beginCalibration(head)
    }

    @Synchronized
    fun endHitCalibration(): Int = hit.endCalibration()

    // ---------------------------------------------------------------- fim

    @Synchronized
    fun finish(): SessionSummary =
        SessionAnalyzer.analyze(
            timeline, device, sens, fingers, startEpochMs, gains, fire.calibrated
        )
}

package com.sensi.observer.engine

import java.io.File

/** Texto simples (uma chave por linha) para guardar sessões. Não depende do Android. */
object SessionCodec {

    private fun d(v: Double) = if (v.isNaN()) "NaN" else java.lang.Double.toString(v)
    private fun p(s: String) = s.toDouble()

    fun encode(s: SessionSummary): String {
        val sb = StringBuilder()
        sb.append("v=1\n")
        sb.append("id=").append(s.id).append('\n')
        sb.append("dur=").append(d(s.durationMs)).append('\n')
        sb.append("active=").append(d(s.activeMs)).append('\n')
        val v = s.device
        sb.append("dev=").append(v.widthPx).append(',').append(v.heightPx).append(',').append(v.densityDpi).append(',')
            .append(v.xdpi).append(',').append(v.ydpi).append(',').append(v.refreshHz).append('\n')
        sb.append("sens=").append(s.sens.joinToString(",")).append('\n')
        sb.append("fingers=").append(s.fingers).append('\n')
        sb.append("fps=").append(d(s.fpsMean)).append(',').append(d(s.fpsP5)).append('\n')
        sb.append("valid=").append(d(s.validPct)).append('\n')
        sb.append("fireCal=").append(if (s.fireCalibrated) 1 else 0).append('\n')
        sb.append("hit=").append(if (s.hitDetected) 1 else 0).append('\n')
        sb.append("idle=").append(d(s.noteIdleJitterH)).append('\n')
        for (g in s.gains) {
            sb.append("gain=").append(gainLine(g)).append('\n')
        }
        for (x in s.scopes) {
            sb.append("scope=").append(
                listOf(
                    x.scope.toDouble(), x.skipped.toDouble(), x.acq.toDouble(), x.bursts.toDouble(), x.hits.toDouble(), x.headHits.toDouble(),
                    x.ampMed, x.peakMed, x.durMed, x.tPeakMed, x.errMean, x.overRate, x.underRate, x.corrPerAcq, x.settleMed,
                    x.stickMed, x.fireMed, x.firstHitMed, x.pull250Mean, x.pullAllMean, x.jitterIdx, x.headRate, x.hitRate, x.score
                ).joinToString(",") { d(it) }
            ).append('\n')
        }
        for (b in s.bursts) {
            sb.append("burst=").append(
                listOf(b.t0, b.durMs, b.scope.toDouble(), b.presses.toDouble(), b.hitsBody.toDouble(), b.hitsHead.toDouble(),
                    b.firstHitMs, b.stickMs, b.ampH, b.pull250H, b.pullAllH).joinToString(",") { d(it) }
            ).append('\n')
        }
        sb.append("profile=").append(s.flickProfile.joinToString(",")).append('\n')
        return sb.toString()
    }

    fun gainLine(g: GainRecord): String =
        "${g.timeMs},${g.scope},${g.sens},${g.densityDpi},${g.widthPx},${g.heightPx},${d(g.gain)},${g.swipes},${d(g.spread)}"

    fun parseGain(line: String): GainRecord? = try {
        val a = line.split(',')
        GainRecord(a[0].toLong(), a[1].toInt(), a[2].toInt(), a[3].toInt(), a[4].toInt(), a[5].toInt(), p(a[6]), a[7].toInt(), p(a[8]))
    } catch (e: Exception) {
        null
    }

    fun decode(text: String): SessionSummary? {
        try {
            var id = 0L
            var dur = 0.0
            var active = 0.0
            var dev = DeviceInfo(0, 0, 0, 0f, 0f, 60f)
            var sens = IntArray(Scope.COUNT)
            var fingers = 2
            var fpsMean = Double.NaN
            var fpsP5 = Double.NaN
            var valid = 0.0
            var fireCal = false
            var hit = false
            var idle = 0.0
            val gains = ArrayList<GainRecord>()
            val scopes = ArrayList<ScopeStats>()
            val bursts = ArrayList<Burst>()
            var profile = FloatArray(SessionAnalyzer.PROFILE_BINS)
            for (line in text.lineSequence()) {
                val i = line.indexOf('=')
                if (i <= 0) continue
                val k = line.substring(0, i)
                val v = line.substring(i + 1)
                when (k) {
                    "id" -> id = v.toLong()
                    "dur" -> dur = p(v)
                    "active" -> active = p(v)
                    "dev" -> {
                        val a = v.split(',')
                        dev = DeviceInfo(a[0].toInt(), a[1].toInt(), a[2].toInt(), a[3].toFloat(), a[4].toFloat(), a[5].toFloat())
                    }
                    "sens" -> sens = v.split(',').map { it.toInt() }.toIntArray()
                    "fingers" -> fingers = v.toInt()
                    "fps" -> {
                        val a = v.split(',')
                        fpsMean = p(a[0]); fpsP5 = p(a[1])
                    }
                    "valid" -> valid = p(v)
                    "fireCal" -> fireCal = v == "1"
                    "hit" -> hit = v == "1"
                    "idle" -> idle = p(v)
                    "gain" -> parseGain(v)?.let { gains.add(it) }
                    "scope" -> {
                        val a = v.split(',').map { p(it) }
                        scopes.add(
                            ScopeStats(
                                a[0].toInt(), a[1].toInt(), a[2].toInt(), a[3].toInt(), a[4].toInt(), a[5].toInt(),
                                a[6], a[7], a[8], a[9], a[10], a[11], a[12], a[13], a[14], a[15], a[16], a[17], a[18], a[19], a[20], a[21], a[22], a[23]
                            )
                        )
                    }
                    "burst" -> {
                        val a = v.split(',').map { p(it) }
                        bursts.add(Burst(a[0], a[1], a[2].toInt(), a[3].toInt(), a[4].toInt(), a[5].toInt(), a[6], a[7], a[8], a[9], a[10]))
                    }
                    "profile" -> profile = v.split(',').map { it.toFloat() }.toFloatArray()
                }
            }
            if (id == 0L || dev.widthPx == 0) return null
            while (sens.size < Scope.COUNT) sens = sens + 100
            return SessionSummary(id, dur, active, dev, sens, fingers, fpsMean, fpsP5, valid, fireCal, hit, gains, scopes, bursts, profile, idle)
        } catch (e: Exception) {
            return null
        }
    }
}

/** Guarda sessões e calibrações em uma pasta. */
class SessionStore(private val dir: File) {

    init {
        dir.mkdirs()
    }

    private fun file(id: Long) = File(dir, "s_$id.txt")
    private val gainFile get() = File(dir, "gain.txt")

    fun save(s: SessionSummary) {
        file(s.id).writeText(SessionCodec.encode(s))
    }

    fun loadAll(): List<SessionSummary> {
        val files = dir.listFiles { f -> f.name.startsWith("s_") && f.name.endsWith(".txt") } ?: return emptyList()
        return files.mapNotNull { f -> SessionCodec.decode(f.readText()) }.sortedBy { it.id }
    }

    fun load(id: Long): SessionSummary? = file(id).takeIf { it.exists() }?.let { SessionCodec.decode(it.readText()) }

    fun delete(id: Long) {
        file(id).delete()
    }

    fun deleteAll() {
        dir.listFiles()?.forEach { if (it.name.startsWith("s_")) it.delete() }
    }

    fun addGain(g: GainRecord) {
        gainFile.appendText(SessionCodec.gainLine(g) + "\n")
    }

    fun loadGains(): List<GainRecord> {
        val f = gainFile
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { SessionCodec.parseGain(it) }
    }

    fun clearGains() {
        gainFile.delete()
    }

    /** Escreve a linha do tempo quadro a quadro (ms a ms) em CSV. */
    fun writeTimelineCsv(file: File, tl: Timeline, heightPx: Int) {
        file.bufferedWriter().use { w ->
            w.write("t_ms,look_dx_px,look_dy_px,confianca,leitura_valida,tiro,acerto_corpo,acerto_cabeca,mira\n")
            val h = heightPx.toDouble()
            for (i in 0 until tl.size) {
                w.write(
                    "%.2f,%.3f,%.3f,%.2f,%d,%d,%d,%d,%d\n".format(
                        java.util.Locale.US, tl.t[i], tl.lx[i] * h, tl.ly[i] * h, tl.conf[i],
                        if (tl.flag(i, Flag.VALID)) 1 else 0,
                        if (tl.flag(i, Flag.FIRE)) 1 else 0,
                        if (tl.flag(i, Flag.HIT_BODY)) 1 else 0,
                        if (tl.flag(i, Flag.HIT_HEAD)) 1 else 0,
                        tl.scopeAt(i)
                    )
                )
            }
        }
    }
}

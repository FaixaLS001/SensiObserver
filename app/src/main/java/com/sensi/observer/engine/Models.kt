package com.sensi.observer.engine

/**
 * Tipos de dados do motor de análise. Nada aqui depende do Android: o motor roda igual na JVM,
 * o que permite testar a matemática fora do celular.
 *
 * Unidades: todo deslocamento da câmera é guardado em "alturas de tela" (H): pixels / altura da
 * tela. Assim os números não dependem da resolução nem da qualidade da captura.
 * Velocidades ficam em H/s e tempos em milissegundos.
 */

/** Miras do Free Fire que têm sensibilidade própria (escala 0 a 200 do jogo atual). */
enum class Scope(val id: Int, val label: String, val minSens: Int, val maxSens: Int) {
    GENERAL(0, "Geral", 56, 200),
    RED_DOT(1, "Ponto vermelho", 48, 190),
    X2(2, "Mira 2x", 44, 176),
    X4(3, "Mira 4x", 40, 156),
    AWM(4, "Mira AWM", 36, 136);

    companion object {
        const val COUNT = 5
        fun of(id: Int): Scope = values().firstOrNull { it.id == id } ?: GENERAL
    }
}

/** Dados do aparelho no momento da sessão. */
data class DeviceInfo(
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val xdpi: Float,
    val ydpi: Float,
    val refreshHz: Float
) {
    /** Mesma tela e mesmo DPI: só nesse caso duas sessões são comparáveis. */
    fun sameProfile(o: DeviceInfo): Boolean =
        widthPx == o.widthPx && heightPx == o.heightPx && densityDpi == o.densityDpi

    /** Pixels por milímetro no sentido horizontal (para converter dedo em mm). */
    fun pxPerMm(): Double {
        val d = if (xdpi > 50f) xdpi.toDouble() else densityDpi.toDouble()
        return d / 25.4
    }
}

/** Bits do campo [Timeline.flags]. */
object Flag {
    const val FIRE = 1
    const val HIT_BODY = 2
    const val HIT_HEAD = 4
    const val VALID = 8
    const val SCOPE_SHIFT = 4
    const val SCOPE_MASK = 0x70
}

/**
 * Linha do tempo quadro a quadro: tempo (ms), movimento da câmera, confiança da leitura e flags
 * (tiro, acerto no corpo/cabeça, leitura válida, mira em uso).
 *
 * lx/ly: quanto a CÂMERA girou naquele quadro, em alturas de tela. lx > 0 = olhou para a direita;
 * ly > 0 = olhou para baixo (ly < 0 = subiu a mira).
 */
class Timeline {
    companion object {
        const val MAX_FRAMES = 2_000_000
    }

    var size = 0
        private set
    var t = DoubleArray(8192)
        private set
    var lx = FloatArray(8192)
        private set
    var ly = FloatArray(8192)
        private set
    var conf = FloatArray(8192)
        private set
    var flags = ByteArray(8192)
        private set

    fun add(tMs: Double, dx: Float, dy: Float, c: Float, f: Int): Int {
        if (size >= MAX_FRAMES) return -1
        if (size == t.size) grow()
        t[size] = tMs
        lx[size] = dx
        ly[size] = dy
        conf[size] = c
        flags[size] = f.toByte()
        return size++
    }

    private fun grow() {
        val n = t.size * 2
        t = t.copyOf(n)
        lx = lx.copyOf(n)
        ly = ly.copyOf(n)
        conf = conf.copyOf(n)
        flags = flags.copyOf(n)
    }

    fun orFlag(i: Int, f: Int) {
        if (i in 0 until size) flags[i] = (flags[i].toInt() or f).toByte()
    }

    fun flag(i: Int, f: Int): Boolean = (flags[i].toInt() and f) != 0

    fun scopeAt(i: Int): Int = (flags[i].toInt() and Flag.SCOPE_MASK) ushr Flag.SCOPE_SHIFT

    /** Índice do quadro mais próximo de [tMs] entre os últimos [back] quadros (ou -1). */
    fun recentIndexNear(tMs: Double, back: Int): Int {
        var best = -1
        var bestD = Double.MAX_VALUE
        var i = size - 1
        val stop = maxOf(0, size - back)
        while (i >= stop) {
            val d = Math.abs(t[i] - tMs)
            if (d < bestD) {
                bestD = d
                best = i
            }
            i--
        }
        return best
    }
}

/** Resultado agregado de uma mira (Geral, Red Dot, 2x, 4x, AWM) em uma sessão. */
data class ScopeStats(
    val scope: Int,
    val skipped: Int = 0,        // giros rápidos demais para medir (ficam fora do erro)
    val acq: Int = 0,            // arrastos de mira medidos (flicks com ajuste)
    val bursts: Int = 0,         // sequências de tiro
    val hits: Int = 0,           // acertos detectados
    val headHits: Int = 0,       // acertos na cabeça (capa)
    val ampMed: Double = Double.NaN,      // amplitude do arrasto (H)
    val peakMed: Double = Double.NaN,     // velocidade de pico (H/s)
    val durMed: Double = Double.NaN,      // duração do arrasto (ms)
    val tPeakMed: Double = Double.NaN,    // tempo até o pico (ms)
    val errMean: Double = Double.NaN,     // erro do arrasto: + ficou curto, - passou do ponto
    val overRate: Double = Double.NaN,    // fração dos arrastos em que passou do alvo
    val underRate: Double = Double.NaN,   // fração em que ficou curto
    val corrPerAcq: Double = Double.NaN,  // correções por arrasto
    val settleMed: Double = Double.NaN,   // tempo até estabilizar depois do arrasto (ms)
    val stickMed: Double = Double.NaN,    // do fim do arrasto até o primeiro tiro (ms)
    val fireMed: Double = Double.NaN,     // duração da sequência de tiro (ms)
    val firstHitMed: Double = Double.NaN, // do início do tiro até o 1º acerto (ms)
    val pull250Mean: Double = Double.NaN, // puxada para cima nos primeiros 250 ms do tiro (H)
    val pullAllMean: Double = Double.NaN, // puxada para cima durante todo o tiro (H)
    val jitterIdx: Double = Double.NaN,   // 0 = mira firme, 1 = tremendo ao atirar
    val headRate: Double = Double.NaN,    // capa / (capa + corpo)
    val hitRate: Double = Double.NaN,     // sequências com ao menos 1 acerto
    val score: Double = Double.NaN        // nota 0 a 100
)

/** Uma sequência de tiro (uma "rajada") com o que aconteceu nela. */
data class Burst(
    val t0: Double,
    val durMs: Double,
    val scope: Int,
    val presses: Int,
    val hitsBody: Int,
    val hitsHead: Int,
    val firstHitMs: Double,   // -1 se não houve acerto
    val stickMs: Double,      // -1 se não houve arrasto antes
    val ampH: Double,         // amplitude do arrasto anterior (H), 0 se não houve
    val pull250H: Double,
    val pullAllH: Double
)

/** Registro de uma calibração de resposta (quanto a câmera gira por pixel de dedo). */
data class GainRecord(
    val timeMs: Long,
    val scope: Int,
    val sens: Int,
    val densityDpi: Int,
    val widthPx: Int,
    val heightPx: Int,
    val gain: Double,     // pixels de câmera por pixel de dedo
    val swipes: Int,
    val spread: Double    // dispersão entre os arrastos (0 = todos iguais)
)

/** Tudo o que se sabe de uma sessão de observação, já analisado. */
data class SessionSummary(
    val id: Long,                         // início da sessão (epoch ms)
    val durationMs: Double,
    val activeMs: Double,                 // tempo com leitura válida
    val device: DeviceInfo,
    val sens: IntArray,                   // sensibilidades em uso, por mira (5)
    val fingers: Int,
    val fpsMean: Double,
    val fpsP5: Double,
    val validPct: Double,                 // % dos quadros com leitura de movimento válida
    val fireCalibrated: Boolean,
    val hitDetected: Boolean,             // houve ao menos um acerto detectado
    val gains: List<GainRecord>,          // calibrações vigentes na sessão
    val scopes: List<ScopeStats>,
    val bursts: List<Burst>,
    val flickProfile: FloatArray,         // velocidade média (H/s) a cada 8 ms desde o início do arrasto
    val noteIdleJitterH: Double           // tremor medido com a mira parada (H)
) {
    fun stats(scope: Int): ScopeStats? = scopes.firstOrNull { it.scope == scope }
    val totalHits: Int get() = scopes.sumOf { it.hits }
    val totalHead: Int get() = scopes.sumOf { it.headHits }
    val totalBursts: Int get() = scopes.sumOf { it.bursts }
    val totalAcq: Int get() = scopes.sumOf { it.acq }

    /** Capa geral da sessão (cabeça / acertos), ou NaN se não houve acerto. */
    val headRate: Double
        get() = if (totalHits > 0) totalHead.toDouble() / totalHits else Double.NaN
}

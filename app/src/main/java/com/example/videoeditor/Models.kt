package com.example.videoeditor

import kotlinx.serialization.Serializable
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

fun newId(): String = UUID.randomUUID().toString()

fun fmt(ms: Long): String {
    val s = ms.coerceAtLeast(0L) / 1000
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

data class Msg(val bn: String, val en: String)

enum class P { SCALE, POSX, POSY, BLUR }
enum class Eff { TRANSFORM, FLIP_H, FLIP_V, BLUR, CROP, BW, COLOR, DENOISE, VOLUME, EQ }
enum class TP { SIZE, X, Y, OPACITY }

@Serializable
data class Keyframe(val tMs: Long, val value: Float)

@Serializable
data class Param(val base: Float, val kfs: List<Keyframe> = emptyList()) {

    fun at(tMs: Long): Float {
        if (kfs.isEmpty()) return base
        val s = kfs.sortedBy { it.tMs }
        if (tMs <= s.first().tMs) return s.first().value
        if (tMs >= s.last().tMs) return s.last().value
        for (i in 0 until s.size - 1) {
            val a = s[i]
            val b = s[i + 1]
            if (tMs >= a.tMs && tMs <= b.tMs) {
                val span = (b.tMs - a.tMs).toFloat()
                if (span <= 0f) return b.value
                val f = (tMs - a.tMs).toFloat() / span
                return a.value + (b.value - a.value) * f
            }
        }
        return base
    }

    fun hasKeyframeAt(tMs: Long): Boolean = kfs.any { abs(it.tMs - tMs) <= 60L }

    private fun upsert(tMs: Long, v: Float): List<Keyframe> {
        val rest = kfs.filter { abs(it.tMs - tMs) > 60L }
        return (rest + Keyframe(tMs, v)).sortedBy { it.tMs }
    }

    fun withValueAt(tMs: Long, v: Float): Param =
        if (kfs.isEmpty()) copy(base = v) else copy(kfs = upsert(tMs, v))

    fun toggleKeyframeAt(tMs: Long): Param {
        val cur = at(tMs)
        return if (hasKeyframeAt(tMs)) {
            val rest = kfs.filter { abs(it.tMs - tMs) > 60L }
            if (rest.isEmpty()) copy(base = cur, kfs = emptyList()) else copy(kfs = rest)
        } else {
            copy(kfs = upsert(tMs, cur))
        }
    }

    fun shifted(by: Long): Param = copy(kfs = kfs.map { Keyframe(it.tMs - by, it.value) })
}

@Serializable
data class Fx(
    val transformOn: Boolean = false,
    val scale: Param = Param(100f),
    val posX: Param = Param(0f),
    val posY: Param = Param(0f),
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val blurOn: Boolean = false,
    val blur: Param = Param(10f),
    val cropOn: Boolean = false,
    val cropL: Float = 0f,
    val cropR: Float = 0f,
    val cropT: Float = 0f,
    val cropB: Float = 0f,
    val bwOn: Boolean = false,
    val colorOn: Boolean = false,
    val bright: Float = 0f,
    val contrast: Float = 0f,
    val sat: Float = 0f,
    val exposure: Float = 0f,
    val temp: Float = 0f,
    val tint: Float = 0f,
    val denoiseOn: Boolean = false,
    val denoise: Float = 50f,
    val volOn: Boolean = false,
    val volDb: Float = 0f,
    val eqOn: Boolean = false,
    val eqFreq: Float = 1000f,
    val eqGain: Float = 0f,
    val eqQ: Float = 1f
)

/** Per-channel gains for Exposure, Temperature and Tint (shared by preview and export). */
fun Fx.rgbGains(): FloatArray {
    val ev = Math.pow(2.0, (exposure / 100f * 2f).toDouble()).toFloat()
    return floatArrayOf(ev * (1f + temp / 300f), ev * (1f - tint / 300f), ev * (1f - temp / 300f))
}

fun Fx.hasRgbGains(): Boolean = colorOn && (exposure != 0f || temp != 0f || tint != 0f)

fun Fx.param(w: P): Param = when (w) {
    P.SCALE -> scale
    P.POSX -> posX
    P.POSY -> posY
    P.BLUR -> blur
}

fun Fx.mapParam(w: P, f: (Param) -> Param): Fx = when (w) {
    P.SCALE -> copy(scale = f(scale))
    P.POSX -> copy(posX = f(posX))
    P.POSY -> copy(posY = f(posY))
    P.BLUR -> copy(blur = f(blur))
}

fun Fx.shifted(by: Long): Fx = copy(
    scale = scale.shifted(by),
    posX = posX.shifted(by),
    posY = posY.shifted(by),
    blur = blur.shifted(by)
)

/** Scale (sx, sy) and translation (tx, ty in NDC: x right, y up) used by both preview and export. */
class Geo(val sx: Float, val sy: Float, val tx: Float, val ty: Float)

fun Fx.geometry(tMs: Long): Geo {
    var s = 1f
    var tx = 0f
    var ty = 0f
    if (transformOn) {
        s = scale.at(tMs) / 100f
        tx = posX.at(tMs) / 100f * 2f
        ty = -posY.at(tMs) / 100f * 2f
    }
    return Geo(
        sx = if (flipH) -s else s,
        sy = if (flipV) -s else s,
        tx = tx,
        ty = ty
    )
}

@Serializable
data class MediaRef(
    val id: String,
    val uri: String,
    val name: String,
    val isAudio: Boolean,
    val durMs: Long
)

@Serializable
data class Clip(
    val id: String = newId(),
    val mediaId: String,
    val uri: String,
    val name: String,
    val srcDurMs: Long,
    val inMs: Long,
    val outMs: Long,
    val speed: Float = 1f,
    val trans: String = "",
    val transMs: Long = 1000L,
    val fx: Fx = Fx()
) {
    val durMs: Long get() = ((outMs - inMs) / speed).toLong().coerceAtLeast(1L)
}

@Serializable
data class TextClip(
    val id: String = newId(),
    val text: String = "Your text",
    val startMs: Long = 0L,
    val durMs: Long = 3000L,
    val font: String = "bebas_neue",
    val color: Long = 0xFFFFFFFFL,
    val sP: Param = Param(8f),
    val xP: Param = Param(0f),
    val yP: Param = Param(60f),
    val oP: Param = Param(100f),
    val bw: Boolean = false
)

fun TextClip.param(w: TP): Param = when (w) {
    TP.SIZE -> sP
    TP.X -> xP
    TP.Y -> yP
    TP.OPACITY -> oP
}

fun TextClip.mapParam(w: TP, f: (Param) -> Param): TextClip = when (w) {
    TP.SIZE -> copy(sP = f(sP))
    TP.X -> copy(xP = f(xP))
    TP.Y -> copy(yP = f(yP))
    TP.OPACITY -> copy(oP = f(oP))
}

/** Text colour as shown: grayscale when Black & White is on. */
fun TextClip.shownColor(): Long {
    if (!bw) return color
    val r = ((color shr 16) and 0xFF).toInt()
    val g = ((color shr 8) and 0xFF).toInt()
    val b = (color and 0xFF).toInt()
    val y = (0.299f * r + 0.587f * g + 0.114f * b).toInt().coerceIn(0, 255)
    return 0xFF000000L or (y.toLong() shl 16) or (y.toLong() shl 8) or y.toLong()
}

@Serializable
data class Seq(
    val id: String = newId(),
    val name: String = "Sequence 1",
    val ratio: String = "16:9",
    val fps: Int = 30,
    val video: List<Clip> = emptyList(),
    val audio: List<List<Clip>> = listOf(emptyList(), emptyList()),
    val texts: List<TextClip> = emptyList()
) {
    val totalMs: Long get() = video.sumOf { it.durMs }
    val aspect: Float
        get() = when (ratio) {
            "9:16" -> 9f / 16f
            "4:3" -> 4f / 3f
            else -> 16f / 9f
        }
    val lengthMs: Long
        get() {
            val a = audio.maxOfOrNull { t -> t.sumOf { it.durMs } } ?: 0L
            val t = texts.maxOfOrNull { it.startMs + it.durMs } ?: 0L
            return maxOf(totalMs, a, t)
        }
}

@Serializable
data class Project(
    val id: String,
    val name: String,
    val media: List<MediaRef> = emptyList(),
    val seqs: List<Seq> = listOf(Seq()),
    val modified: Long = 0L
) {
    val totalMs: Long get() = seqs.firstOrNull()?.totalMs ?: 0L
}

/** Returns (clip index, offset inside the clip on the timeline in ms). clips must not be empty. */
fun locate(clips: List<Clip>, ms: Long): Pair<Int, Long> {
    var acc = 0L
    for (i in clips.indices) {
        val d = clips[i].durMs
        if (ms < acc + d) return Pair(i, (ms - acc).coerceAtLeast(0L))
        acc += d
    }
    val last = clips.lastIndex
    return Pair(last, (clips[last].durMs - 1).coerceAtLeast(0L))
}

fun startOf(clips: List<Clip>, index: Int): Long = clips.take(index).sumOf { it.durMs }

/** Index to insert a new clip at: the clip boundary nearest to the given time. */
fun insertIndexAt(clips: List<Clip>, ms: Long): Int {
    var acc = 0L
    var best = 0
    var bestDist = abs(ms - 0L)
    for (i in clips.indices) {
        acc += clips[i].durMs
        val d = abs(ms - acc)
        if (d < bestDist) {
            bestDist = d
            best = i + 1
        }
    }
    return best
}

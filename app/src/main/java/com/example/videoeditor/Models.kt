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
enum class Eff { TRANSFORM, FLIP_H, FLIP_V, BLUR, CROP, BW, COLOR, DENOISE }

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
    val denoiseOn: Boolean = false,
    val denoise: Float = 50f
)

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
    var cs = 1f
    var cx = 0f
    var cy = 0f
    if (cropOn) {
        val wf = (1f - (cropL + cropR) / 100f).coerceAtLeast(0.1f)
        val hf = (1f - (cropT + cropB) / 100f).coerceAtLeast(0.1f)
        cs = maxOf(1f / wf, 1f / hf)
        cx = -cs * (cropL - cropR) / 100f
        cy = cs * (cropT - cropB) / 100f
    }
    val total = s * cs
    return Geo(
        sx = if (flipH) -total else total,
        sy = if (flipV) -total else total,
        tx = s * cx + tx,
        ty = s * cy + ty
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
    val sizePct: Float = 8f,
    val posX: Float = 0f,
    val posY: Float = 60f
)

@Serializable
data class Seq(
    val id: String = newId(),
    val name: String = "Sequence 1",
    val ratio: String = "16:9",
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

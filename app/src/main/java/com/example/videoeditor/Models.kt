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
enum class Eff { TRANSFORM, FLIP, BLUR, DENOISE }

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
    val blurOn: Boolean = false,
    val blur: Param = Param(10f),
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
    val fx: Fx = Fx()
) {
    val durMs: Long get() = outMs - inMs
}

@Serializable
data class Project(
    val id: String,
    val name: String,
    val seqName: String = "Sequence 1",
    val media: List<MediaRef> = emptyList(),
    val video: List<Clip> = emptyList(),
    val audio: List<Clip> = emptyList(),
    val modified: Long = 0L
) {
    val totalMs: Long get() = video.sumOf { it.durMs }
}

/** Returns (clip index, offset inside clip in ms) for a time on the sequence. clips must not be empty. */
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

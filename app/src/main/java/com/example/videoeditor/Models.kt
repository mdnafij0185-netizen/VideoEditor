package com.example.videoeditor

import kotlinx.serialization.Serializable
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

fun newId(): String = UUID.randomUUID().toString()

fun fmt(ms: Long): String {
    val s = ms.coerceAtLeast(0L) / 1000
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

data class Msg(val bn: String, val en: String)

/** Every animatable (keyframe-able) parameter of a clip or adjustment layer. */
enum class P(val label: String) {
    SCALE("Scale"), POSX("Position X"), POSY("Position Y"), ROT("Rotation"), OPACITY("Opacity"),
    BLUR("Blurriness"),
    CROP_L("Crop Left"), CROP_R("Crop Right"), CROP_T("Crop Top"), CROP_B("Crop Bottom"),
    EXPOSURE("Exposure"), BRIGHT("Brightness"), CONTRAST("Contrast"), SAT("Saturation"),
    HUE("Hue"), TEMP("Temperature"), TINT("Tint"),
    VOL("Volume")
}

enum class Eff(val title: String) {
    TRANSFORM("Transform"), FLIP_H("Horizontal Flip"), FLIP_V("Vertical Flip"),
    BLUR("Gaussian Blur"), CROP("Crop"), BW("Black & White"), INVERT("Invert"),
    COLOR("Lumetri Color"), VOLUME("Volume"), EQ("Parametric Equalizer"), DENOISE("Denoise")
}

enum class TP { SIZE, X, Y, OPACITY }

@Serializable
data class Keyframe(val tMs: Long, val value: Float, val ease: Int = 0)

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
                val f0 = (tMs - a.tMs).toFloat() / span
                val f = when (a.ease) {
                    1 -> f0 * f0 * (3f - 2f * f0)
                    2 -> 0f
                    else -> f0
                }
                return a.value + (b.value - a.value) * f
            }
        }
        return base
    }

    fun hasKeyframeAt(tMs: Long): Boolean = kfs.any { abs(it.tMs - tMs) <= 60L }

    private fun upsert(tMs: Long, v: Float): List<Keyframe> {
        val old = kfs.firstOrNull { abs(it.tMs - tMs) <= 60L }
        val rest = kfs.filter { abs(it.tMs - tMs) > 60L }
        return (rest + Keyframe(tMs, v, old?.ease ?: 0)).sortedBy { it.tMs }
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

    fun removeKeyframe(tMs: Long): Param {
        val cur = at(tMs)
        val rest = kfs.filter { it.tMs != tMs }
        return if (rest.isEmpty()) copy(base = cur, kfs = emptyList()) else copy(kfs = rest)
    }

    fun setEase(tMs: Long, ease: Int): Param =
        copy(kfs = kfs.map { if (it.tMs == tMs) it.copy(ease = ease) else it })

    fun shifted(by: Long): Param = copy(kfs = kfs.map { it.copy(tMs = it.tMs - by) })
}

@Serializable
data class Fx(
    /** Names of effects that are switched off (the effect stays but does not render). */
    val off: List<String> = emptyList(),
    val transformOn: Boolean = false,
    val scale: Param = Param(100f),
    val posX: Param = Param(0f),
    val posY: Param = Param(0f),
    val rot: Param = Param(0f),
    val opacity: Param = Param(100f),
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val blurOn: Boolean = false,
    val blur: Param = Param(10f),
    val cropOn: Boolean = false,
    val cropL: Param = Param(0f),
    val cropR: Param = Param(0f),
    val cropT: Param = Param(0f),
    val cropB: Param = Param(0f),
    val bwOn: Boolean = false,
    val invertOn: Boolean = false,
    val colorOn: Boolean = false,
    val exposure: Param = Param(0f),
    val bright: Param = Param(0f),
    val contrast: Param = Param(0f),
    val sat: Param = Param(0f),
    val hue: Param = Param(0f),
    val temp: Param = Param(0f),
    val tint: Param = Param(0f),
    val volOn: Boolean = false,
    val vol: Param = Param(0f),
    val eqOn: Boolean = false,
    val eqFreq: Float = 1000f,
    val eqGain: Float = 0f,
    val eqQ: Float = 1f,
    val denoiseOn: Boolean = false,
    val denoise: Float = 50f
)

fun Fx.flag(e: Eff): Boolean = when (e) {
    Eff.TRANSFORM -> transformOn
    Eff.FLIP_H -> flipH
    Eff.FLIP_V -> flipV
    Eff.BLUR -> blurOn
    Eff.CROP -> cropOn
    Eff.BW -> bwOn
    Eff.INVERT -> invertOn
    Eff.COLOR -> colorOn
    Eff.VOLUME -> volOn
    Eff.EQ -> eqOn
    Eff.DENOISE -> denoiseOn
}

/** True when the effect is applied and not switched off. */
fun Fx.live(e: Eff): Boolean = flag(e) && e.name !in off

fun Fx.hasAny(): Boolean = Eff.values().any { flag(it) }

fun Fx.param(w: P): Param = when (w) {
    P.SCALE -> scale
    P.POSX -> posX
    P.POSY -> posY
    P.ROT -> rot
    P.OPACITY -> opacity
    P.BLUR -> blur
    P.CROP_L -> cropL
    P.CROP_R -> cropR
    P.CROP_T -> cropT
    P.CROP_B -> cropB
    P.EXPOSURE -> exposure
    P.BRIGHT -> bright
    P.CONTRAST -> contrast
    P.SAT -> sat
    P.HUE -> hue
    P.TEMP -> temp
    P.TINT -> tint
    P.VOL -> vol
}

fun Fx.mapParam(w: P, f: (Param) -> Param): Fx = when (w) {
    P.SCALE -> copy(scale = f(scale))
    P.POSX -> copy(posX = f(posX))
    P.POSY -> copy(posY = f(posY))
    P.ROT -> copy(rot = f(rot))
    P.OPACITY -> copy(opacity = f(opacity))
    P.BLUR -> copy(blur = f(blur))
    P.CROP_L -> copy(cropL = f(cropL))
    P.CROP_R -> copy(cropR = f(cropR))
    P.CROP_T -> copy(cropT = f(cropT))
    P.CROP_B -> copy(cropB = f(cropB))
    P.EXPOSURE -> copy(exposure = f(exposure))
    P.BRIGHT -> copy(bright = f(bright))
    P.CONTRAST -> copy(contrast = f(contrast))
    P.SAT -> copy(sat = f(sat))
    P.HUE -> copy(hue = f(hue))
    P.TEMP -> copy(temp = f(temp))
    P.TINT -> copy(tint = f(tint))
    P.VOL -> copy(vol = f(vol))
}

/** Parameters that belong to each effect, in display order. */
fun paramsOf(e: Eff): List<P> = when (e) {
    Eff.TRANSFORM -> listOf(P.POSX, P.POSY, P.SCALE, P.ROT, P.OPACITY)
    Eff.BLUR -> listOf(P.BLUR)
    Eff.CROP -> listOf(P.CROP_L, P.CROP_R, P.CROP_T, P.CROP_B)
    Eff.COLOR -> listOf(P.EXPOSURE, P.BRIGHT, P.CONTRAST, P.SAT, P.HUE, P.TEMP, P.TINT)
    Eff.VOLUME -> listOf(P.VOL)
    else -> emptyList()
}

fun rangeOf(w: P): ClosedFloatingPointRange<Float> = when (w) {
    P.SCALE -> 10f..400f
    P.POSX, P.POSY -> -100f..100f
    P.ROT -> -360f..360f
    P.OPACITY -> 0f..100f
    P.BLUR -> 0f..60f
    P.CROP_L, P.CROP_R, P.CROP_T, P.CROP_B -> 0f..90f
    P.EXPOSURE, P.BRIGHT, P.CONTRAST, P.SAT, P.TEMP, P.TINT -> -100f..100f
    P.HUE -> -180f..180f
    P.VOL -> -60f..12f
}

fun unitOf(w: P): String = when (w) {
    P.SCALE, P.OPACITY, P.CROP_L, P.CROP_R, P.CROP_T, P.CROP_B -> "%"
    P.ROT, P.HUE -> "°"
    P.VOL -> " dB"
    else -> ""
}

/** Effect with every parameter and flag back at its default, keeping the on/off state. */
fun Fx.resetEffect(e: Eff): Fx = when (e) {
    Eff.TRANSFORM -> copy(scale = Param(100f), posX = Param(0f), posY = Param(0f), rot = Param(0f), opacity = Param(100f))
    Eff.FLIP_H, Eff.FLIP_V, Eff.BW, Eff.INVERT -> this
    Eff.BLUR -> copy(blur = Param(10f))
    Eff.CROP -> copy(cropL = Param(0f), cropR = Param(0f), cropT = Param(0f), cropB = Param(0f))
    Eff.COLOR -> copy(
        exposure = Param(0f), bright = Param(0f), contrast = Param(0f), sat = Param(0f),
        hue = Param(0f), temp = Param(0f), tint = Param(0f)
    )
    Eff.VOLUME -> copy(vol = Param(0f))
    Eff.EQ -> copy(eqFreq = 1000f, eqGain = 0f, eqQ = 1f)
    Eff.DENOISE -> copy(denoise = 50f)
}

fun Fx.withFlag(e: Eff, on: Boolean): Fx = when (e) {
    Eff.TRANSFORM -> copy(transformOn = on)
    Eff.FLIP_H -> copy(flipH = on)
    Eff.FLIP_V -> copy(flipV = on)
    Eff.BLUR -> copy(blurOn = on)
    Eff.CROP -> copy(cropOn = on)
    Eff.BW -> copy(bwOn = on)
    Eff.INVERT -> copy(invertOn = on)
    Eff.COLOR -> copy(colorOn = on)
    Eff.VOLUME -> copy(volOn = on)
    Eff.EQ -> copy(eqOn = on)
    Eff.DENOISE -> copy(denoiseOn = on)
}

fun Fx.shifted(by: Long): Fx {
    var f = this
    for (p in P.values()) f = f.mapParam(p) { it.shifted(by) }
    return f
}

/** Linear volume factor at a time (1 = unchanged). */
fun Fx.gainAt(tMs: Long): Float {
    if (!live(Eff.VOLUME)) return 1f
    val db = vol.at(tMs)
    if (db <= -60f) return 0f
    return 10f.pow(db / 20f)
}

/** Scale, translation (NDC: x right, y up) and rotation used by both preview and export. */
class Geo(val sx: Float, val sy: Float, val tx: Float, val ty: Float, val rot: Float)

fun Fx.geometry(tMs: Long): Geo {
    var s = 1f
    var tx = 0f
    var ty = 0f
    var r = 0f
    if (live(Eff.TRANSFORM)) {
        s = scale.at(tMs) / 100f
        tx = posX.at(tMs) / 100f * 2f
        ty = -posY.at(tMs) / 100f * 2f
        r = rot.at(tMs)
    }
    return Geo(
        sx = if (live(Eff.FLIP_H)) -s else s,
        sy = if (live(Eff.FLIP_V)) -s else s,
        tx = tx,
        ty = ty,
        rot = r
    )
}

/** Crop edges in percent: left, right, top, bottom. Null when not cropping. */
fun Fx.cropAt(tMs: Long): FloatArray? =
    if (live(Eff.CROP)) floatArrayOf(cropL.at(tMs), cropR.at(tMs), cropT.at(tMs), cropB.at(tMs)) else null

// ---------- colour: one affine matrix for exposure, temperature, tint, hue, saturation, contrast, brightness,
// invert, black & white and opacity. Preview and export both use exactly this matrix. ----------

private class Aff(val m: FloatArray, val o: FloatArray)

private fun identAff() = Aff(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), floatArrayOf(0f, 0f, 0f))

private fun Aff.then(b: Aff): Aff {
    val r = FloatArray(9)
    for (i in 0..2) for (j in 0..2) {
        var sum = 0f
        for (k in 0..2) sum += b.m[i * 3 + k] * m[k * 3 + j]
        r[i * 3 + j] = sum
    }
    val o2 = FloatArray(3)
    for (i in 0..2) {
        var sum = b.o[i]
        for (k in 0..2) sum += b.m[i * 3 + k] * o[k]
        o2[i] = sum
    }
    return Aff(r, o2)
}

private fun diagAff(r: Float, g: Float, b: Float, off: Float = 0f) =
    Aff(floatArrayOf(r, 0f, 0f, 0f, g, 0f, 0f, 0f, b), floatArrayOf(off, off, off))

private fun satAff(s: Float): Aff {
    val l = floatArrayOf(0.2126f, 0.7152f, 0.0722f)
    val m = FloatArray(9)
    for (i in 0..2) for (j in 0..2) m[i * 3 + j] = l[j] * (1f - s) + (if (i == j) s else 0f)
    return Aff(m, floatArrayOf(0f, 0f, 0f))
}

private fun hueAff(deg: Float): Aff {
    val rad = Math.toRadians(deg.toDouble())
    val c = cos(rad).toFloat()
    val s = sin(rad).toFloat()
    return Aff(
        floatArrayOf(
            0.213f + c * 0.787f - s * 0.213f, 0.715f - c * 0.715f - s * 0.715f, 0.072f - c * 0.072f + s * 0.928f,
            0.213f - c * 0.213f + s * 0.143f, 0.715f + c * 0.285f + s * 0.140f, 0.072f - c * 0.072f - s * 0.283f,
            0.213f - c * 0.213f - s * 0.787f, 0.715f - c * 0.715f + s * 0.715f, 0.072f + c * 0.928f + s * 0.072f
        ),
        floatArrayOf(0f, 0f, 0f)
    )
}

/** 12 numbers: a 3x3 matrix (row-major) followed by 3 offsets (0..1). Null when the colour is unchanged. */
fun Fx.colorAffine(t: Long): FloatArray? {
    var a = identAff()
    var any = false
    if (live(Eff.COLOR)) {
        val ev = 2.0.pow((exposure.at(t) / 100f * 2f).toDouble()).toFloat()
        val tp = temp.at(t)
        val tn = tint.at(t)
        val gr = ev * (1f + tp / 300f)
        val gg = ev * (1f - tn / 300f)
        val gb = ev * (1f - tp / 300f)
        if (gr != 1f || gg != 1f || gb != 1f) {
            a = a.then(diagAff(gr, gg, gb))
            any = true
        }
        val h = hue.at(t)
        if (h != 0f) {
            a = a.then(hueAff(h))
            any = true
        }
        val s = sat.at(t)
        if (s != 0f) {
            a = a.then(satAff((1f + s / 100f).coerceAtLeast(0f)))
            any = true
        }
        val c = contrast.at(t)
        if (c != 0f) {
            val k = 1f + c / 100f
            a = a.then(diagAff(k, k, k, 0.5f * (1f - k)))
            any = true
        }
        val b = bright.at(t)
        if (b != 0f) {
            a = a.then(diagAff(1f, 1f, 1f, b / 100f))
            any = true
        }
    }
    if (live(Eff.INVERT)) {
        a = a.then(diagAff(-1f, -1f, -1f, 1f))
        any = true
    }
    if (live(Eff.BW)) {
        a = a.then(satAff(0f))
        any = true
    }
    if (live(Eff.TRANSFORM)) {
        val op = (opacity.at(t) / 100f).coerceIn(0f, 1f)
        if (op != 1f) {
            a = a.then(diagAff(op, op, op))
            any = true
        }
    }
    if (!any) return null
    return a.m + a.o
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
    /** Position on the timeline. Only used by audio clips (video clips follow each other). */
    val startMs: Long = 0L,
    val fx: Fx = Fx()
) {
    val durMs: Long get() = ((outMs - inMs) / speed).toLong().coerceAtLeast(1L)
}

/** An adjustment layer: its effects are applied to the video beneath it while it is active. */
@Serializable
data class AdjClip(
    val id: String = newId(),
    val name: String = "Adjustment Layer",
    val startMs: Long = 0L,
    val durMs: Long = 5000L,
    val fx: Fx = Fx()
)

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
    val texts: List<TextClip> = emptyList(),
    val adj: List<AdjClip> = emptyList()
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
            val a = audio.maxOfOrNull { t -> t.maxOfOrNull { it.startMs + it.durMs } ?: 0L } ?: 0L
            val t = texts.maxOfOrNull { it.startMs + it.durMs } ?: 0L
            val j = adj.maxOfOrNull { it.startMs + it.durMs } ?: 0L
            return maxOf(totalMs, a, t, j)
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

/** Sorts audio clips by start time and removes overlaps by pushing later clips right. */
fun normalizeTrack(list: List<Clip>): List<Clip> {
    var end = 0L
    return list.sortedBy { it.startMs }.map { c ->
        val s = maxOf(c.startMs, end)
        end = s + c.durMs
        c.copy(startMs = s)
    }
}

fun Seq.normalized(): Seq = copy(audio = audio.map { normalizeTrack(it) })

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

/** Effects of all adjustment layers that are active at a time, in order. */
fun Seq.activeAdj(tMs: Long): List<Pair<AdjClip, Long>> =
    adj.filter { tMs >= it.startMs && tMs < it.startMs + it.durMs }.map { Pair(it, tMs - it.startMs) }

/** Applies colour matrix [b] after [a] (either may be null = unchanged). */
fun composeAffine(a: FloatArray?, b: FloatArray?): FloatArray? {
    if (a == null) return b
    if (b == null) return a
    val r = FloatArray(12)
    for (i in 0..2) for (j in 0..2) {
        var sum = 0f
        for (k in 0..2) sum += b[i * 3 + k] * a[k * 3 + j]
        r[i * 3 + j] = sum
    }
    for (i in 0..2) {
        var sum = b[9 + i]
        for (k in 0..2) sum += b[i * 3 + k] * a[9 + k]
        r[9 + i] = sum
    }
    return r
}

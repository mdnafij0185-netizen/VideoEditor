package com.example.videoeditor

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.GlEffect
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay
import androidx.media3.effect.TimestampWrapper
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

fun mediaItemOf(clip: Clip): MediaItem {
    val end = minOf(clip.outMs, clip.srcDurMs)
    val cfg = MediaItem.ClippingConfiguration.Builder()
        .setStartPositionMs(clip.inMs)
        .setEndPositionMs(end)
        .build()
    return MediaItem.Builder()
        .setUri(clip.uri)
        .setClippingConfiguration(cfg)
        .build()
}

/** Volume (keyframe-able), one-band parametric EQ and a simple noise gate in one audio processor. */
class ClipAudioProcessor(private val fx: Fx, private val speed: Float) : BaseAudioProcessor() {
    private var env = 0f
    private var gate = 1f
    private var channels = 2
    private var rate = 44100
    private var frames = 0L
    private val x1 = FloatArray(8)
    private val x2 = FloatArray(8)
    private val y1 = FloatArray(8)
    private val y2 = FloatArray(8)
    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channels = inputAudioFormat.channelCount.coerceIn(1, 8)
        rate = inputAudioFormat.sampleRate
        val fs: Float = rate.toFloat()
        val f0: Float = fx.eqFreq.coerceIn(20f, fs / 2f - 100f)
        val q: Float = fx.eqQ.coerceIn(0.1f, 10f)
        val aa: Float = 10.0.pow((fx.eqGain / 40f).toDouble()).toFloat()
        val w0: Float = (2.0 * PI * f0.toDouble() / fs.toDouble()).toFloat()
        val sw: Float = sin(w0.toDouble()).toFloat()
        val cw: Float = cos(w0.toDouble()).toFloat()
        val alpha: Float = sw / (2f * q)
        val a0: Float = 1f + alpha / aa
        b0 = (1f + alpha * aa) / a0
        b1 = (-2f * cw) / a0
        b2 = (1f - alpha * aa) / a0
        a1 = (-2f * cw) / a0
        a2 = (1f - alpha / aa) / a0
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        val out = replaceOutputBuffer(size)
        val tMs = (frames * 1000L / rate / speed).toLong()
        val gain = fx.gainAt(tMs)
        val useEq = fx.live(Eff.EQ)
        val useGate = fx.live(Eff.DENOISE)
        val threshold = fx.denoise.coerceIn(0f, 100f) / 100f * 3000f
        var idx = 0
        while (inputBuffer.remaining() >= 2) {
            var x = inputBuffer.short.toFloat()
            val ch = idx % channels
            idx++
            if (useEq) {
                val y = b0 * x + b1 * x1[ch] + b2 * x2[ch] - a1 * y1[ch] - a2 * y2[ch]
                x2[ch] = x1[ch]
                x1[ch] = x
                y2[ch] = y1[ch]
                y1[ch] = y
                x = y
            }
            if (useGate) {
                val a = abs(x)
                env = if (a > env) a else env * 0.999f
                val target = if (env > threshold) 1f else 0.15f
                gate += (target - gate) * 0.01f
                x *= gate
            }
            x *= gain
            out.putShort(x.toInt().coerceIn(-32768, 32767).toShort())
        }
        frames += (size / 2 / channels).toLong()
        out.flip()
    }

    override fun onFlush() {
        env = 0f
        gate = 1f
        frames = 0L
        for (i in 0 until 8) {
            x1[i] = 0f
            x2[i] = 0f
            y1[i] = 0f
            y2[i] = 0f
        }
    }
}

fun audioProcs(clip: Clip): MutableList<AudioProcessor> {
    val l = mutableListOf<AudioProcessor>()
    val fx = clip.fx
    if (fx.live(Eff.VOLUME) || fx.live(Eff.EQ) || fx.live(Eff.DENOISE)) l.add(ClipAudioProcessor(fx, clip.speed))
    if (clip.speed != 1f) {
        val sonic = SonicAudioProcessor()
        sonic.setSpeed(clip.speed)
        l.add(sonic)
    }
    return l
}

fun isMuted(fx: Fx): Boolean = fx.live(Eff.VOLUME) && fx.vol.kfs.isEmpty() && fx.vol.base <= -60f

/** Colour effect: one 4x4 matrix per frame (offsets ride in the last column because alpha is 1). */
class AffineRgb(private val fx: Fx, private val startUs: Long, private val speed: Float) : RgbMatrix {
    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        val tMs = ((presentationTimeUs - startUs) / 1000L / speed).toLong().coerceAtLeast(0L)
        val a = fx.colorAffine(tMs)
            ?: return floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        return floatArrayOf(
            a[0], a[3], a[6], 0f,
            a[1], a[4], a[7], 0f,
            a[2], a[5], a[8], 0f,
            a[9], a[10], a[11], 1f
        )
    }
}

/** A solid colour rectangle. Used for crop masks and dip transitions. */
class SolidOverlay(
    color: Int,
    private val settingsAt: (Long) -> OverlaySettings
) : BitmapOverlay() {
    private val bmp: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).also { it.eraseColor(color) }

    override fun getBitmap(presentationTimeUs: Long): Bitmap = bmp

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settingsAt(presentationTimeUs)
}

private fun bar(sx: Float, sy: Float, ax: Float, ay: Float, alpha: Float): OverlaySettings =
    OverlaySettings.Builder()
        .setBackgroundFrameAnchor(ax, ay)
        .setScale(sx.coerceAtLeast(0.001f), sy.coerceAtLeast(0.001f))
        .setAlphaScale(alpha.coerceIn(0f, 1f))
        .build()

/** Effects of one Fx stack in a fixed order. Time values are measured from [startUs]. */
fun fxEffects(fx: Fx, startUs: Long, speed: Float, outW: Int, outH: Int, withCrop: Boolean): List<Effect> {
    val list = mutableListOf<Effect>()
    fun localMs(us: Long): Long = ((us - startUs) / 1000L / speed).toLong().coerceAtLeast(0L)
    if (withCrop && fx.live(Eff.CROP)) {
        val black = android.graphics.Color.BLACK
        val bars = mutableListOf<TextureOverlay>()
        bars.add(SolidOverlay(black) { us ->
            val l = fx.cropL.at(localMs(us)) / 100f
            bar(outW * l, outH.toFloat(), -1f + l, 0f, if (l > 0f) 1f else 0f)
        })
        bars.add(SolidOverlay(black) { us ->
            val r = fx.cropR.at(localMs(us)) / 100f
            bar(outW * r, outH.toFloat(), 1f - r, 0f, if (r > 0f) 1f else 0f)
        })
        bars.add(SolidOverlay(black) { us ->
            val t = fx.cropT.at(localMs(us)) / 100f
            bar(outW.toFloat(), outH * t, 0f, 1f - t, if (t > 0f) 1f else 0f)
        })
        bars.add(SolidOverlay(black) { us ->
            val b = fx.cropB.at(localMs(us)) / 100f
            bar(outW.toFloat(), outH * b, 0f, -1f + b, if (b > 0f) 1f else 0f)
        })
        list.add(OverlayEffect(ImmutableList.copyOf(bars)))
    }
    if (fx.live(Eff.TRANSFORM) || fx.live(Eff.FLIP_H) || fx.live(Eff.FLIP_V)) {
        val aspect = outW.toFloat() / outH.toFloat()
        list.add(MatrixTransformation { us ->
            val g = fx.geometry(localMs(us))
            val m = Matrix()
            m.postScale(g.sx, g.sy)
            if (g.rot != 0f) {
                m.postScale(aspect, 1f)
                m.postRotate(-g.rot)
                m.postScale(1f / aspect, 1f)
            }
            m.postTranslate(g.tx, g.ty)
            m
        })
    }
    if (fx.live(Eff.BLUR)) list.add(BlurEffect(fx.blur, startUs, speed))
    if (fx.live(Eff.COLOR) || fx.live(Eff.BW) || fx.live(Eff.INVERT) || fx.live(Eff.TRANSFORM)) {
        list.add(AffineRgb(fx, startUs, speed))
    }
    return list
}

fun videoEffects(clip: Clip, startUs: Long, outW: Int, outH: Int): List<Effect> {
    val list = mutableListOf<Effect>()
    list.add(Presentation.createForWidthAndHeight(outW, outH, Presentation.LAYOUT_SCALE_TO_FIT))
    list.addAll(fxEffects(clip.fx, startUs, clip.speed, outW, outH, true))
    if (clip.speed != 1f) list.add(SpeedChangeEffect(clip.speed))
    return list
}

class TypefaceSpanCompat(private val tf: Typeface) : MetricAffectingSpan() {
    override fun updateMeasureState(p: TextPaint) {
        p.typeface = tf
    }

    override fun updateDrawState(p: TextPaint) {
        p.typeface = tf
    }
}

/** Text that is only visible between startMs and startMs + durMs. Size, position and opacity can be keyframed. */
class TimedText(context: Context, private val t: TextClip, private val frameH: Int) : TextOverlay() {
    private val span: SpannableString = SpannableString(t.text.ifBlank { " " }).apply {
        setSpan(ForegroundColorSpan(t.shownColor().toInt()), 0, length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        setSpan(TypefaceSpanCompat(Fonts.typeface(context, t.font)), 0, length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
    }

    override fun getText(presentationTimeUs: Long): SpannableString = span

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val ms = presentationTimeUs / 1000L
        val visible = ms >= t.startMs && ms < t.startMs + t.durMs
        val lt = (ms - t.startMs).coerceAtLeast(0L)
        val scale = (t.sP.at(lt) / 100f * frameH) / TextOverlay.TEXT_SIZE_PIXELS
        val op = (t.oP.at(lt) / 100f).coerceIn(0f, 1f)
        return OverlaySettings.Builder()
            .setAlphaScale(if (visible) op else 0f)
            .setBackgroundFrameAnchor((t.xP.at(lt) / 100f).coerceIn(-1f, 1f), (-t.yP.at(lt) / 100f).coerceIn(-1f, 1f))
            .setScale(scale, scale)
            .build()
    }
}

/** Output size for a sequence: [shortSide] is 1080, 720 or 480. */
fun outputSize(ratio: Float, shortSide: Int): Pair<Int, Int> {
    return if (ratio >= 1f) {
        val h = shortSide
        val w = ((h * ratio) / 2f).roundToInt() * 2
        Pair(w, h)
    } else {
        val w = shortSide
        val h = ((w / ratio) / 2f).roundToInt() * 2
        Pair(w, h)
    }
}

/** A silent WAV file used to fill gaps between audio clips. */
fun silenceFile(context: Context, ms: Long): File {
    val f = File(context.cacheDir, "silence_$ms.wav")
    if (f.exists() && f.length() > 44L) return f
    val rate = 44100
    val samples = (ms * rate / 1000L).toInt().coerceAtLeast(1)
    val dataLen = samples * 2
    val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    h.put("RIFF".toByteArray())
    h.putInt(36 + dataLen)
    h.put("WAVE".toByteArray())
    h.put("fmt ".toByteArray())
    h.putInt(16)
    h.putShort(1)
    h.putShort(1)
    h.putInt(rate)
    h.putInt(rate * 2)
    h.putShort(2)
    h.putShort(16)
    h.put("data".toByteArray())
    h.putInt(dataLen)
    f.outputStream().use { os ->
        os.write(h.array())
        val chunk = ByteArray(65536)
        var left = dataLen
        while (left > 0) {
            val n = minOf(left, chunk.size)
            os.write(chunk, 0, n)
            left -= n
        }
    }
    return f
}

fun runExport(
    context: Context,
    seq: Seq,
    outW: Int,
    outH: Int,
    out: File,
    onDone: () -> Unit,
    onFail: (String) -> Unit
): Transformer {
    var startMs = 0L
    val videoItems = seq.video.map { clip ->
        val b = EditedMediaItem.Builder(mediaItemOf(clip))
            .setEffects(Effects(audioProcs(clip), videoEffects(clip, startMs * 1000L, outW, outH)))
            .setFrameRate(seq.fps)
        if (isMuted(clip.fx)) b.setRemoveAudio(true)
        startMs += clip.durMs
        b.build()
    }
    val sequences = mutableListOf(EditedMediaItemSequence(videoItems))
    for (track in seq.audio) {
        val sorted = track.sortedBy { it.startMs }
        if (sorted.isEmpty()) continue
        val items = mutableListOf<EditedMediaItem>()
        var cursor = 0L
        for (clip in sorted) {
            val gap = clip.startMs - cursor
            if (gap > 50L) {
                items.add(
                    EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(silenceFile(context, gap))))
                        .setRemoveVideo(true)
                        .build()
                )
            }
            items.add(
                EditedMediaItem.Builder(mediaItemOf(clip))
                    .setRemoveVideo(true)
                    .setEffects(Effects(audioProcs(clip), emptyList()))
                    .build()
            )
            cursor = clip.startMs + clip.durMs
        }
        sequences.add(EditedMediaItemSequence(items))
    }

    val builder = Composition.Builder(sequences)
    builder.experimentalSetForceAudioTrack(true)

    val comp = mutableListOf<Effect>()
    // adjustment layers: their effects only run while the layer is active
    for (a in seq.adj) {
        val s = a.startMs * 1000L
        val e = (a.startMs + a.durMs) * 1000L
        if (e <= s) continue
        for (fxE in fxEffects(a.fx, s, 1f, outW, outH, false)) {
            if (fxE is GlEffect) comp.add(TimestampWrapper(fxE, s, e))
        }
    }
    val overlays = mutableListOf<TextureOverlay>()
    seq.texts.forEach { overlays.add(TimedText(context, it, outH)) }
    var acc = 0L
    seq.video.forEachIndexed { i, clip ->
        if (i > 0 && clip.trans.isNotEmpty()) {
            val centerUs = acc * 1000L
            val half = (clip.transMs * 1000L / 2L).coerceAtLeast(1L)
            val col = if (clip.trans == "white") android.graphics.Color.WHITE else android.graphics.Color.BLACK
            overlays.add(SolidOverlay(col) { us ->
                bar(outW.toFloat(), outH.toFloat(), 0f, 0f, 1f - abs(us - centerUs).toFloat() / half.toFloat())
            })
        }
        acc += clip.durMs
    }
    if (overlays.isNotEmpty()) comp.add(OverlayEffect(ImmutableList.copyOf(overlays)))
    if (comp.isNotEmpty()) builder.setEffects(Effects(emptyList(), comp))

    val t = Transformer.Builder(context)
        .addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                onDone()
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException
            ) {
                onFail(exportException.message ?: "unknown error")
            }
        })
        .build()
    t.start(builder.build(), out.absolutePath)
    return t
}

fun saveToGallery(context: Context, f: File): String? {
    val name = "PREdit_${System.currentTimeMillis()}.mp4"
    return try {
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/PREdit")
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)
            if (uri == null) {
                null
            } else {
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    f.inputStream().use { it.copyTo(os) }
                }
                "Movies/PREdit/$name"
            }
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "PREdit")
            dir.mkdirs()
            val dest = File(dir, name)
            f.copyTo(dest, true)
            dest.absolutePath
        }
    } catch (e: Exception) {
        null
    }
}

package com.example.videoeditor

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Typeface
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
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay
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

fun gainOf(fx: Fx): Float {
    if (!fx.volOn) return 1f
    if (fx.volDb <= -60f) return 0f
    return 10f.pow(fx.volDb / 20f)
}

/** Volume, one-band parametric EQ and a simple noise gate in a single audio processor. */
class ClipAudioProcessor(private val fx: Fx) : BaseAudioProcessor() {
    private var env = 0f
    private var gate = 1f
    private var channels = 2
    private val x1 = FloatArray(8)
    private val x2 = FloatArray(8)
    private val y1 = FloatArray(8)
    private val y2 = FloatArray(8)
    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f
    private val gain = gainOf(fx)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channels = inputAudioFormat.channelCount.coerceIn(1, 8)
        val fs: Float = inputAudioFormat.sampleRate.toFloat()
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
        val threshold = fx.denoise.coerceIn(0f, 100f) / 100f * 3000f
        var idx = 0
        while (inputBuffer.remaining() >= 2) {
            var x = inputBuffer.short.toFloat()
            val ch = idx % channels
            idx++
            if (fx.eqOn) {
                val y = b0 * x + b1 * x1[ch] + b2 * x2[ch] - a1 * y1[ch] - a2 * y2[ch]
                x2[ch] = x1[ch]
                x1[ch] = x
                y2[ch] = y1[ch]
                y1[ch] = y
                x = y
            }
            if (fx.denoiseOn) {
                val a = abs(x)
                env = if (a > env) a else env * 0.999f
                val target = if (env > threshold) 1f else 0.15f
                gate += (target - gate) * 0.01f
                x *= gate
            }
            x *= gain
            out.putShort(x.toInt().coerceIn(-32768, 32767).toShort())
        }
        out.flip()
    }

    override fun onFlush() {
        env = 0f
        gate = 1f
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
    if (fx.denoiseOn || fx.eqOn || (fx.volOn && fx.volDb != 0f)) l.add(ClipAudioProcessor(fx))
    if (clip.speed != 1f) {
        val sonic = SonicAudioProcessor()
        sonic.setSpeed(clip.speed)
        l.add(sonic)
    }
    return l
}

class ScaleRgb(private val r: Float, private val g: Float, private val b: Float) : RgbMatrix {
    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray =
        floatArrayOf(
            r, 0f, 0f, 0f,
            0f, g, 0f, 0f,
            0f, 0f, b, 0f,
            0f, 0f, 0f, 1f
        )
}

/** A solid colour rectangle. Used for crop masks and dip transitions. */
class SolidOverlay(
    color: Int,
    private val sx: Float,
    private val sy: Float,
    private val ax: Float,
    private val ay: Float,
    private val alphaAt: (Long) -> Float
) : BitmapOverlay() {
    private val bmp: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).also { it.eraseColor(color) }

    override fun getBitmap(presentationTimeUs: Long): Bitmap = bmp

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
        OverlaySettings.Builder()
            .setBackgroundFrameAnchor(ax, ay)
            .setScale(sx, sy)
            .setAlphaScale(alphaAt(presentationTimeUs).coerceIn(0f, 1f))
            .build()
}

fun videoEffects(clip: Clip, startUs: Long, outW: Int, outH: Int): List<Effect> {
    val list = mutableListOf<Effect>()
    list.add(Presentation.createForWidthAndHeight(outW, outH, Presentation.LAYOUT_SCALE_TO_FIT))
    val fx = clip.fx
    if (fx.cropOn) {
        val bars = mutableListOf<TextureOverlay>()
        val black = android.graphics.Color.BLACK
        val l = fx.cropL / 100f
        val r = fx.cropR / 100f
        val t = fx.cropT / 100f
        val b = fx.cropB / 100f
        if (l > 0f) bars.add(SolidOverlay(black, outW * l, outH.toFloat(), -1f + l, 0f) { 1f })
        if (r > 0f) bars.add(SolidOverlay(black, outW * r, outH.toFloat(), 1f - r, 0f) { 1f })
        if (t > 0f) bars.add(SolidOverlay(black, outW.toFloat(), outH * t, 0f, 1f - t) { 1f })
        if (b > 0f) bars.add(SolidOverlay(black, outW.toFloat(), outH * b, 0f, -1f + b) { 1f })
        if (bars.isNotEmpty()) list.add(OverlayEffect(ImmutableList.copyOf(bars)))
    }
    if (fx.transformOn || fx.flipH || fx.flipV) {
        list.add(MatrixTransformation { tUs ->
            val tMs = ((tUs - startUs) / 1000L / clip.speed).toLong().coerceAtLeast(0L)
            val g = fx.geometry(tMs)
            val m = Matrix()
            m.postScale(g.sx, g.sy)
            m.postTranslate(g.tx, g.ty)
            m
        })
    }
    if (fx.blurOn) list.add(BlurEffect(fx.blur, startUs, clip.speed))
    if (fx.bwOn) list.add(RgbFilter.createGrayscaleFilter())
    if (fx.colorOn) {
        if (fx.hasRgbGains()) {
            val g = fx.rgbGains()
            list.add(ScaleRgb(g[0], g[1], g[2]))
        }
        if (fx.bright != 0f) list.add(Brightness(fx.bright / 100f))
        if (fx.contrast != 0f) list.add(Contrast(fx.contrast / 100f))
        if (fx.sat != 0f) list.add(HslAdjustment.Builder().adjustSaturation(fx.sat).build())
    }
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
        if (clip.fx.volOn && clip.fx.volDb <= -60f) b.setRemoveAudio(true)
        startMs += clip.durMs
        b.build()
    }
    val sequences = mutableListOf(EditedMediaItemSequence(videoItems))
    for (track in seq.audio) {
        if (track.isNotEmpty()) {
            val items = track.map { clip ->
                EditedMediaItem.Builder(mediaItemOf(clip))
                    .setRemoveVideo(true)
                    .setEffects(Effects(audioProcs(clip), emptyList()))
                    .build()
            }
            sequences.add(EditedMediaItemSequence(items))
        }
    }

    val builder = Composition.Builder(sequences)
    val overlays = mutableListOf<TextureOverlay>()
    seq.texts.forEach { overlays.add(TimedText(context, it, outH)) }
    var acc = 0L
    seq.video.forEachIndexed { i, clip ->
        if (i > 0 && clip.trans.isNotEmpty()) {
            val centerUs = acc * 1000L
            val half = (clip.transMs * 1000L / 2L).coerceAtLeast(1L)
            val col = if (clip.trans == "white") android.graphics.Color.WHITE else android.graphics.Color.BLACK
            overlays.add(SolidOverlay(col, outW.toFloat(), outH.toFloat(), 0f, 0f) { us ->
                1f - abs(us - centerUs).toFloat() / half.toFloat()
            })
        }
        acc += clip.durMs
    }
    if (overlays.isNotEmpty()) {
        val fxs: List<Effect> = listOf(OverlayEffect(ImmutableList.copyOf(overlays)))
        builder.setEffects(Effects(emptyList(), fxs))
    }

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

package com.example.videoeditor

import android.content.ContentValues
import android.content.Context
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
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbFilter
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
import kotlin.math.abs
import kotlin.math.roundToInt

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

/** Simple noise gate: lowers very quiet sounds (hiss, hum). Not an AI denoiser. */
class NoiseGate(private val amount: Float) : BaseAudioProcessor() {
    private var env = 0f
    private var gain = 1f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        val out = replaceOutputBuffer(size)
        val threshold = amount.coerceIn(0f, 1f) * 3000f
        while (inputBuffer.remaining() >= 2) {
            val s = inputBuffer.short.toInt()
            val a = abs(s).toFloat()
            env = if (a > env) a else env * 0.999f
            val target = if (env > threshold) 1f else 0.15f
            gain += (target - gain) * 0.01f
            out.putShort((s * gain).toInt().coerceIn(-32768, 32767).toShort())
        }
        out.flip()
    }

    override fun onFlush() {
        env = 0f
        gain = 1f
    }
}

fun videoEffects(clip: Clip, startUs: Long, outW: Int, outH: Int): List<Effect> {
    val list = mutableListOf<Effect>()
    list.add(Presentation.createForWidthAndHeight(outW, outH, Presentation.LAYOUT_SCALE_TO_FIT))
    val fx = clip.fx
    if (fx.transformOn || fx.flipH || fx.flipV || fx.cropOn) {
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

/** Text that is only visible between startMs and startMs + durMs. */
class TimedText(context: Context, private val t: TextClip, private val frameH: Int) : TextOverlay() {
    private val span: SpannableString = SpannableString(t.text.ifBlank { " " }).apply {
        setSpan(ForegroundColorSpan(t.color.toInt()), 0, length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        setSpan(TypefaceSpanCompat(Fonts.typeface(context, t.font)), 0, length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
    }

    override fun getText(presentationTimeUs: Long): SpannableString = span

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val ms = presentationTimeUs / 1000L
        val visible = ms >= t.startMs && ms < t.startMs + t.durMs
        val scale = (t.sizePct / 100f * frameH) / TextOverlay.TEXT_SIZE_PIXELS
        return OverlaySettings.Builder()
            .setAlphaScale(if (visible) 1f else 0f)
            .setBackgroundFrameAnchor((t.posX / 100f).coerceIn(-1f, 1f), (-t.posY / 100f).coerceIn(-1f, 1f))
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
        val procs = mutableListOf<AudioProcessor>()
        if (clip.fx.denoiseOn) procs.add(NoiseGate(clip.fx.denoise / 100f))
        if (clip.speed != 1f) {
            val sonic = SonicAudioProcessor()
            sonic.setSpeed(clip.speed)
            procs.add(sonic)
        }
        val item = EditedMediaItem.Builder(mediaItemOf(clip))
            .setEffects(Effects(procs, videoEffects(clip, startMs * 1000L, outW, outH)))
            .build()
        startMs += clip.durMs
        item
    }
    val sequences = mutableListOf(EditedMediaItemSequence(videoItems))
    for (track in seq.audio) {
        if (track.isNotEmpty()) {
            val items = track.map { clip ->
                EditedMediaItem.Builder(mediaItemOf(clip)).setRemoveVideo(true).build()
            }
            sequences.add(EditedMediaItemSequence(items))
        }
    }

    val builder = Composition.Builder(sequences)
    if (seq.texts.isNotEmpty()) {
        val overlays: List<TextureOverlay> = seq.texts.map { TimedText(context, it, outH) }
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
    val name = "RVEDT_${System.currentTimeMillis()}.mp4"
    return try {
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/RVEDT")
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)
            if (uri == null) {
                null
            } else {
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    f.inputStream().use { it.copyTo(os) }
                }
                "Movies/RVEDT/$name"
            }
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "RVEDT")
            dir.mkdirs()
            val dest = File(dir, name)
            f.copyTo(dest, true)
            dest.absolutePath
        }
    } catch (e: Exception) {
        null
    }
}

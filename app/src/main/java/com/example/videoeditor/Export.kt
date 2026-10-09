package com.example.videoeditor

import android.content.ContentValues
import android.content.Context
import android.graphics.Matrix
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs

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

fun videoEffects(clip: Clip): List<Effect> {
    val list = mutableListOf<Effect>()
    val fx = clip.fx
    if (fx.transformOn || fx.flipH) {
        list.add(MatrixTransformation { tUs ->
            val tMs = tUs / 1000L
            val m = Matrix()
            val s = if (fx.transformOn) fx.scale.at(tMs) / 100f else 1f
            val sx = if (fx.flipH) -s else s
            m.postScale(sx, s)
            if (fx.transformOn) {
                m.postTranslate(fx.posX.at(tMs) / 100f * 2f, -fx.posY.at(tMs) / 100f * 2f)
            }
            m
        })
    }
    if (fx.blurOn) {
        list.add(BlurEffect(fx.blur))
    }
    return list
}

fun runExport(
    context: Context,
    project: Project,
    height: Int?,
    out: File,
    onDone: () -> Unit,
    onFail: (String) -> Unit
): Transformer {
    val videoItems = project.video.map { clip ->
        val procs: List<AudioProcessor> =
            if (clip.fx.denoiseOn) listOf(NoiseGate(clip.fx.denoise / 100f)) else emptyList()
        EditedMediaItem.Builder(mediaItemOf(clip))
            .setEffects(Effects(procs, videoEffects(clip)))
            .build()
    }
    val audioItems = project.audio.map { clip ->
        EditedMediaItem.Builder(mediaItemOf(clip))
            .setRemoveVideo(true)
            .build()
    }
    val sequences = mutableListOf(EditedMediaItemSequence(videoItems))
    if (audioItems.isNotEmpty()) sequences.add(EditedMediaItemSequence(audioItems))

    val builder = Composition.Builder(sequences)
    if (height != null) {
        val fxs: List<Effect> = listOf(Presentation.createForHeight(height))
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
    val name = "VideoEditor_${System.currentTimeMillis()}.mp4"
    return try {
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VideoEditor")
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)
            if (uri == null) {
                null
            } else {
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    f.inputStream().use { it.copyTo(os) }
                }
                "Movies/VideoEditor/$name"
            }
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "VideoEditor")
            dir.mkdirs()
            val dest = File(dir, name)
            f.copyTo(dest, true)
            dest.absolutePath
        }
    } catch (e: Exception) {
        null
    }
}

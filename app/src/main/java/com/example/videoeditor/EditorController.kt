package com.example.videoeditor

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class EditorController(
    private val context: Context,
    initial: Project,
    private val scope: CoroutineScope,
    startQuality: Quality
) {
    var project by mutableStateOf(initial)
    var canUndo by mutableStateOf(false)
    var canRedo by mutableStateOf(false)
    var selectedClipId by mutableStateOf<String?>(null)
    var toast by mutableStateOf<Msg?>(null)

    val player: ExoPlayer = ExoPlayer.Builder(context).build()
    val audioPlayer: ExoPlayer = ExoPlayer.Builder(context).build()
    val sourcePlayer: ExoPlayer = ExoPlayer.Builder(context).build()

    var isPlaying by mutableStateOf(false)
    var globalMs by mutableLongStateOf(0L)
    var scrubbing = false
    var quality by mutableStateOf(startQuality)

    var sourceMedia by mutableStateOf<MediaRef?>(null)
    var srcIn by mutableLongStateOf(0L)
    var srcOut by mutableLongStateOf(0L)
    var srcPos by mutableLongStateOf(0L)
    var srcPlaying by mutableStateOf(false)

    var exporting by mutableStateOf(false)
    var exportProgress by mutableFloatStateOf(0f)
    var exportMsg by mutableStateOf<Msg?>(null)
    private var transformer: Transformer? = null

    private val undoStack = ArrayDeque<Project>()
    private val redoStack = ArrayDeque<Project>()
    private var liveActive = false
    private var effectsActive = false
    private var appliedKey: Pair<Quality, Int>? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                isPlaying = isPlayingNow
                syncAudioPlay()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                syncAudioPlay()
            }

            override fun onTracksChanged(tracks: Tracks) {
                applyQuality()
            }
        })
        sourcePlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                srcPlaying = isPlayingNow
            }
        })
    }

    // ---------- history ----------
    private fun refreshUndo() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    private fun push(p: Project) {
        undoStack.addLast(p)
        if (undoStack.size > 50) undoStack.removeFirst()
        redoStack.clear()
        refreshUndo()
    }

    fun edit(t: (Project) -> Project) {
        push(project)
        project = t(project)
    }

    fun liveEdit(t: (Project) -> Project) {
        if (!liveActive) {
            push(project)
            liveActive = true
        }
        project = t(project)
    }

    fun endLive() {
        liveActive = false
    }

    fun undo() {
        val p = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(project)
        project = p
        refreshUndo()
    }

    fun redo() {
        val p = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(project)
        project = p
        refreshUndo()
    }

    // ---------- playlists ----------
    fun rebuildVideo() {
        val clips = project.video
        if (clips.isEmpty()) {
            player.clearMediaItems()
            globalMs = 0L
            return
        }
        val keep = globalMs.coerceAtMost(project.totalMs - 1).coerceAtLeast(0L)
        val (i, o) = locate(clips, keep)
        player.setMediaItems(clips.map { mediaItemOf(it) }, i, o)
        player.prepare()
        player.playWhenReady = false
        appliedKey = null
        applyQuality()
    }

    fun rebuildAudio() {
        val a = project.audio
        if (a.isEmpty()) {
            audioPlayer.clearMediaItems()
            return
        }
        val totalA = a.sumOf { it.durMs }
        val keep = globalMs.coerceAtMost(totalA - 1).coerceAtLeast(0L)
        val (i, o) = locate(a, keep)
        audioPlayer.setMediaItems(a.map { mediaItemOf(it) }, i, o)
        audioPlayer.prepare()
        syncAudioPlay()
    }

    private fun syncAudioPlay() {
        val totalA = project.audio.sumOf { it.durMs }
        audioPlayer.playWhenReady = player.playWhenReady &&
                player.playbackState != Player.STATE_ENDED &&
                project.audio.isNotEmpty() &&
                globalMs < totalA
    }

    // ---------- preview quality ----------
    fun setQuality(q: Quality) {
        quality = q
        applyQuality()
    }

    fun applyQuality() {
        val baseH = player.videoFormat?.height ?: 0
        val key = Pair(quality, baseH)
        if (appliedKey == key) return
        appliedKey = key
        if (quality == Quality.FULL) {
            if (effectsActive) {
                player.setVideoEffects(emptyList())
                effectsActive = false
            }
        } else {
            val h = if (baseH > 0) baseH else 1080
            val target = maxOf(144, (h / quality.div) / 2 * 2)
            player.setVideoEffects(listOf(Presentation.createForHeight(target)))
            effectsActive = true
        }
    }

    // ---------- transport ----------
    fun tick() {
        if (!scrubbing) {
            val clips = project.video
            if (clips.isEmpty()) {
                globalMs = 0L
            } else if (player.mediaItemCount == clips.size) {
                val idx = player.currentMediaItemIndex.coerceIn(0, clips.size - 1)
                val start = clips.take(idx).sumOf { it.durMs }
                val within = player.currentPosition.coerceIn(0L, clips[idx].durMs)
                globalMs = (start + within).coerceAtMost(project.totalMs)
            }
        }
        if (sourceMedia != null) {
            srcPos = sourcePlayer.currentPosition
            if (srcPlaying && srcPos >= srcOut) sourcePlayer.pause()
        }
    }

    fun seekGlobal(ms: Long) {
        val total = project.totalMs
        if (project.video.isEmpty() || total <= 0L) return
        val t = ms.coerceIn(0L, total - 1)
        globalMs = t
        if (player.mediaItemCount == project.video.size) {
            val (i, o) = locate(project.video, t)
            player.seekTo(i, o)
        }
        seekAudio(t)
    }

    private fun seekAudio(t: Long) {
        val a = project.audio
        if (a.isEmpty() || audioPlayer.mediaItemCount != a.size) return
        val totalA = a.sumOf { it.durMs }
        if (t >= totalA) {
            audioPlayer.playWhenReady = false
            return
        }
        val (i, o) = locate(a, t)
        audioPlayer.seekTo(i, o)
        syncAudioPlay()
    }

    fun togglePlay() {
        if (project.video.isEmpty()) return
        val playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED
        if (playing) {
            player.pause()
            audioPlayer.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) seekGlobal(0L)
            player.play()
            syncAudioPlay()
        }
    }

    // ---------- source ----------
    fun openSource(m: MediaRef) {
        player.pause()
        audioPlayer.pause()
        sourceMedia = m
        srcIn = 0L
        srcOut = m.durMs
        srcPos = 0L
        sourcePlayer.setMediaItem(MediaItem.fromUri(m.uri))
        sourcePlayer.prepare()
        sourcePlayer.playWhenReady = true
    }

    fun toggleSourcePlay() {
        if (sourceMedia == null) return
        if (sourcePlayer.isPlaying) {
            sourcePlayer.pause()
        } else {
            if (sourcePlayer.currentPosition >= srcOut - 50) sourcePlayer.seekTo(srcIn)
            sourcePlayer.play()
        }
    }

    fun addSourceToTimeline() {
        val m = sourceMedia ?: return
        val a = srcIn.coerceIn(0L, m.durMs)
        val b = srcOut.coerceIn(0L, m.durMs)
        if (b - a < 200L) {
            toast = Msg("নির্বাচিত অংশ খুব ছোট", "Selected range is too short")
            return
        }
        val clip = Clip(mediaId = m.id, uri = m.uri, name = m.name, srcDurMs = m.durMs, inMs = a, outMs = b)
        edit { p ->
            if (m.isAudio) p.copy(audio = p.audio + clip) else p.copy(video = p.video + clip)
        }
        selectedClipId = clip.id
        toast = Msg("টাইমলাইনে যোগ হয়েছে", "Added to timeline")
    }

    // ---------- clip operations ----------
    fun selectedVideoClip(): Clip? = project.video.find { it.id == selectedClipId }

    fun selectedAnyClip(): Clip? =
        project.video.find { it.id == selectedClipId } ?: project.audio.find { it.id == selectedClipId }

    fun localTime(clip: Clip): Long {
        val start = project.video.takeWhile { it.id != clip.id }.sumOf { it.durMs }
        return (globalMs - start).coerceIn(0L, clip.durMs)
    }

    fun splitAtPlayhead() {
        val clips = project.video
        if (clips.isEmpty()) return
        val (i, off) = locate(clips, globalMs.coerceAtMost(project.totalMs - 1).coerceAtLeast(0L))
        val c = clips[i]
        if (off < 300L || c.durMs - off < 300L) {
            toast = Msg("প্লে-হেড ক্লিপের ভেতরে নিন", "Move the playhead inside a clip")
            return
        }
        val cut = c.inMs + off
        val left = c.copy(outMs = cut)
        val right = c.copy(id = newId(), inMs = cut, fx = c.fx.shifted(off))
        edit { p ->
            p.copy(video = p.video.flatMapIndexed { idx, cl ->
                if (idx == i) listOf(left, right) else listOf(cl)
            })
        }
        selectedClipId = right.id
    }

    fun duplicateSelected() {
        val c = selectedAnyClip()
        if (c == null) {
            toast = Msg("আগে একটা ক্লিপ সিলেক্ট করুন", "Select a clip first")
            return
        }
        val copy = c.copy(id = newId())
        edit { p ->
            if (p.video.any { it.id == c.id }) {
                p.copy(video = p.video.flatMap { if (it.id == c.id) listOf(it, copy) else listOf(it) })
            } else {
                p.copy(audio = p.audio.flatMap { if (it.id == c.id) listOf(it, copy) else listOf(it) })
            }
        }
        selectedClipId = copy.id
    }

    fun deleteSelected() {
        val c = selectedAnyClip()
        if (c == null) {
            toast = Msg("আগে একটা ক্লিপ সিলেক্ট করুন", "Select a clip first")
            return
        }
        edit { p -> p.copy(video = p.video.filter { it.id != c.id }, audio = p.audio.filter { it.id != c.id }) }
        selectedClipId = null
    }

    fun trimSelected(newIn: Long, newOut: Long) {
        val c = selectedAnyClip() ?: return
        edit { p ->
            p.copy(
                video = p.video.map { if (it.id == c.id) it.copy(inMs = newIn, outMs = newOut) else it },
                audio = p.audio.map { if (it.id == c.id) it.copy(inMs = newIn, outMs = newOut) else it }
            )
        }
    }

    fun renameSequence(name: String) {
        edit { p -> p.copy(seqName = name.ifBlank { "Sequence 1" }) }
    }

    // ---------- effects ----------
    private fun updateVideoClip(id: String, f: (Clip) -> Clip): (Project) -> Project = { p ->
        p.copy(video = p.video.map { if (it.id == id) f(it) else it })
    }

    fun addEffect(e: Eff) {
        val id = selectedVideoClip()?.id
        if (id == null) {
            toast = Msg(
                "আগে টাইমলাইনে একটা ভিডিও ক্লিপ সিলেক্ট করুন",
                "Select a video clip on the timeline first"
            )
            return
        }
        edit(updateVideoClip(id) { cl ->
            cl.copy(
                fx = when (e) {
                    Eff.TRANSFORM -> cl.fx.copy(transformOn = true)
                    Eff.FLIP -> cl.fx.copy(flipH = true)
                    Eff.BLUR -> cl.fx.copy(blurOn = true)
                    Eff.DENOISE -> cl.fx.copy(denoiseOn = true)
                }
            )
        })
        toast = Msg("ইফেক্ট যোগ হয়েছে, Controls প্যানেলে দেখুন", "Effect added. See the Controls panel")
    }

    fun removeEffect(e: Eff) {
        val id = selectedVideoClip()?.id ?: return
        edit(updateVideoClip(id) { cl ->
            cl.copy(
                fx = when (e) {
                    Eff.TRANSFORM -> cl.fx.copy(
                        transformOn = false,
                        scale = Param(100f),
                        posX = Param(0f),
                        posY = Param(0f)
                    )
                    Eff.FLIP -> cl.fx.copy(flipH = false)
                    Eff.BLUR -> cl.fx.copy(blurOn = false, blur = Param(10f))
                    Eff.DENOISE -> cl.fx.copy(denoiseOn = false)
                }
            )
        })
    }

    fun setParam(w: P, v: Float, t: Long) {
        val id = selectedVideoClip()?.id ?: return
        liveEdit(updateVideoClip(id) { cl -> cl.copy(fx = cl.fx.mapParam(w) { it.withValueAt(t, v) }) })
    }

    fun toggleKeyframe(w: P, t: Long) {
        val id = selectedVideoClip()?.id ?: return
        edit(updateVideoClip(id) { cl -> cl.copy(fx = cl.fx.mapParam(w) { it.toggleKeyframeAt(t) }) })
    }

    fun setDenoise(v: Float) {
        val id = selectedVideoClip()?.id ?: return
        liveEdit(updateVideoClip(id) { cl -> cl.copy(fx = cl.fx.copy(denoise = v)) })
    }

    // ---------- export ----------
    fun startExport(height: Int?) {
        if (project.video.isEmpty()) {
            toast = Msg("টাইমলাইনে কোনো ভিডিও নেই", "The timeline has no video")
            return
        }
        player.pause()
        audioPlayer.pause()
        val f = File(context.cacheDir, "export_${System.currentTimeMillis()}.mp4")
        exportMsg = null
        exportProgress = 0f
        try {
            transformer = runExport(
                context, project, height, f,
                onDone = {
                    scope.launch {
                        val path = withContext(Dispatchers.IO) { saveToGallery(context, f) }
                        f.delete()
                        exporting = false
                        exportMsg = if (path != null) {
                            Msg("সেভ হয়েছে: $path", "Saved: $path")
                        } else {
                            Msg("গ্যালারিতে সেভ করা যায়নি", "Could not save to gallery")
                        }
                    }
                },
                onFail = { err ->
                    f.delete()
                    exporting = false
                    exportMsg = Msg("এক্সপোর্ট ব্যর্থ: $err", "Export failed: $err")
                }
            )
            exporting = true
        } catch (e: Exception) {
            exporting = false
            exportMsg = Msg("এক্সপোর্ট শুরু হয়নি: ${e.message}", "Could not start export: ${e.message}")
        }
    }

    fun cancelExport() {
        transformer?.cancel()
        exporting = false
        exportMsg = Msg("এক্সপোর্ট বাতিল হয়েছে", "Export cancelled")
    }

    fun pollProgress() {
        val t = transformer ?: return
        val h = ProgressHolder()
        if (t.getProgress(h) == Transformer.PROGRESS_STATE_AVAILABLE) {
            exportProgress = h.progress / 100f
        }
    }

    fun release() {
        try {
            transformer?.cancel()
        } catch (e: Exception) {
        }
        player.release()
        audioPlayer.release()
        sourcePlayer.release()
    }
}

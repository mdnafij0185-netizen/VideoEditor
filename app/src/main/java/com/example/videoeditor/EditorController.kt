package com.example.videoeditor

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
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

class DragState(val media: MediaRef, val pos: Offset)

class EditorController(
    private val context: Context,
    initial: Project,
    private val scope: CoroutineScope,
    startQuality: Quality
) {
    var project by mutableStateOf(initial)
    var activeIdx by mutableIntStateOf(0)
    val seq: Seq get() = project.seqs[activeIdx.coerceIn(0, project.seqs.size - 1)]

    var canUndo by mutableStateOf(false)
    var canRedo by mutableStateOf(false)
    var selectedClipId by mutableStateOf<String?>(null)
    var selectedTextId by mutableStateOf<String?>(null)
    var toast by mutableStateOf<Msg?>(null)
    var razor by mutableStateOf(false)
    var zoom by mutableFloatStateOf(60f)
    var drag by mutableStateOf<DragState?>(null)

    // timeline geometry (set by the timeline view, used for drag & drop)
    var tlLeft = 0f
    var tlTop = 0f
    var tlW = 0f
    var tlH = 0f
    var scrollPx = 0
    var density = 1f

    val player: ExoPlayer = ExoPlayer.Builder(context).build()
    val audioPlayers: List<ExoPlayer> = listOf(
        ExoPlayer.Builder(context).build(),
        ExoPlayer.Builder(context).build()
    )
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
    private var liveDeadline = 0L
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
        liveDeadline = SystemClock.uptimeMillis() + 600L
        project = t(project)
    }

    fun endLive() {
        liveActive = false
        liveDeadline = 0L
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

    private fun upd(t: (Seq) -> Seq): (Project) -> Project = { p ->
        val i = activeIdx.coerceIn(0, p.seqs.size - 1)
        p.copy(seqs = p.seqs.mapIndexed { idx, s -> if (idx == i) t(s) else s })
    }

    fun editSeq(t: (Seq) -> Seq) = edit(upd(t))
    private fun liveSeq(t: (Seq) -> Seq) = liveEdit(upd(t))

    private fun mapClips(s: Seq, f: (Clip) -> List<Clip>): Seq =
        s.copy(video = s.video.flatMap(f), audio = s.audio.map { track -> track.flatMap(f) })

    // ---------- playlists ----------
    fun rebuildVideo() {
        val clips = seq.video
        if (clips.isEmpty()) {
            player.clearMediaItems()
            globalMs = 0L
            return
        }
        val keep = globalMs.coerceAtMost(seq.totalMs - 1).coerceAtLeast(0L)
        val (i, o) = locate(clips, keep)
        player.setMediaItems(clips.map { mediaItemOf(it) }, i, (o * clips[i].speed).toLong())
        player.prepare()
        player.playWhenReady = false
        appliedKey = null
        applyQuality()
    }

    fun rebuildAudio(track: Int) {
        val a = seq.audio.getOrNull(track) ?: emptyList()
        val p = audioPlayers[track]
        if (a.isEmpty()) {
            p.clearMediaItems()
            return
        }
        val totalA = a.sumOf { it.durMs }
        val (i, o) = locate(a, globalMs.coerceAtMost(totalA - 1).coerceAtLeast(0L))
        p.setMediaItems(a.map { mediaItemOf(it) }, i, o)
        p.prepare()
        syncAudioPlay()
    }

    private fun syncAudioPlay() {
        for (t in 0..1) {
            val a = seq.audio.getOrNull(t) ?: emptyList()
            val totalA = a.sumOf { it.durMs }
            audioPlayers[t].playWhenReady = player.playWhenReady &&
                    player.playbackState != Player.STATE_ENDED &&
                    a.isNotEmpty() &&
                    globalMs < totalA
        }
    }

    // ---------- preview quality ----------
    fun changeQuality(q: Quality) {
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
        if (liveDeadline != 0L && SystemClock.uptimeMillis() > liveDeadline) endLive()
        if (!scrubbing) {
            val clips = seq.video
            if (clips.isEmpty()) {
                if (!isPlaying) globalMs = globalMs.coerceAtMost(seq.lengthMs)
            } else if (player.mediaItemCount == clips.size) {
                val idx = player.currentMediaItemIndex.coerceIn(0, clips.size - 1)
                val sp = clips[idx].speed
                if (player.playbackParameters.speed != sp) player.setPlaybackSpeed(sp)
                val within = (player.currentPosition / sp).toLong().coerceIn(0L, clips[idx].durMs)
                globalMs = (startOf(clips, idx) + within).coerceAtMost(seq.totalMs)
            }
        }
        val vc = seq.video
        if (vc.isNotEmpty() && player.mediaItemCount == vc.size) {
            val vi = player.currentMediaItemIndex.coerceIn(0, vc.size - 1)
            val g = gainOf(vc[vi].fx).coerceAtMost(1f)
            if (player.volume != g) player.volume = g
        }
        for (k in 0..1) {
            val ac = seq.audio.getOrNull(k) ?: continue
            val ap = audioPlayers[k]
            if (ac.isNotEmpty() && ap.mediaItemCount == ac.size) {
                val ai = ap.currentMediaItemIndex.coerceIn(0, ac.size - 1)
                val g = gainOf(ac[ai].fx).coerceAtMost(1f)
                if (ap.volume != g) ap.volume = g
            }
        }
        if (sourceMedia != null) {
            srcPos = sourcePlayer.currentPosition
            if (srcPlaying && srcPos >= srcOut) sourcePlayer.pause()
        }
    }

    fun seekGlobal(ms: Long) {
        val clips = seq.video
        if (clips.isEmpty()) {
            globalMs = ms.coerceAtLeast(0L)
            return
        }
        val t = ms.coerceIn(0L, (seq.totalMs - 1).coerceAtLeast(0L))
        globalMs = t
        if (player.mediaItemCount == clips.size) {
            val (i, o) = locate(clips, t)
            player.seekTo(i, (o * clips[i].speed).toLong())
        }
        for (k in 0..1) seekAudio(k, t)
    }

    private fun seekAudio(track: Int, t: Long) {
        val a = seq.audio.getOrNull(track) ?: return
        val p = audioPlayers[track]
        if (a.isEmpty() || p.mediaItemCount != a.size) return
        val totalA = a.sumOf { it.durMs }
        if (t >= totalA) {
            p.playWhenReady = false
            return
        }
        val (i, o) = locate(a, t)
        p.seekTo(i, o)
        syncAudioPlay()
    }

    fun togglePlay() {
        if (seq.video.isEmpty()) return
        val playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED
        if (playing) {
            player.pause()
            audioPlayers.forEach { it.pause() }
        } else {
            if (player.playbackState == Player.STATE_ENDED) seekGlobal(0L)
            player.play()
            syncAudioPlay()
        }
    }

    fun pauseAll() {
        player.pause()
        audioPlayers.forEach { it.pause() }
        sourcePlayer.pause()
    }

    // ---------- source ----------
    fun openSource(m: MediaRef) {
        pauseAll()
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

    private fun place(clip: Clip, isAudio: Boolean, track: Int, atMs: Long?) {
        editSeq { s ->
            if (isAudio) {
                val t = track.coerceIn(0, s.audio.size - 1)
                val list = s.audio[t]
                val idx = if (atMs == null) list.size else insertIndexAt(list, atMs)
                s.copy(audio = s.audio.mapIndexed { i, l ->
                    if (i == t) l.toMutableList().apply { add(idx, clip) } else l
                })
            } else {
                val idx = if (atMs == null) s.video.size else insertIndexAt(s.video, atMs)
                s.copy(video = s.video.toMutableList().apply { add(idx, clip) })
            }
        }
        selectClip(clip.id)
    }

    fun addMedia(m: MediaRef, audioTrack: Int = 0, atMs: Long? = null) {
        val clip = Clip(
            mediaId = m.id, uri = m.uri, name = m.name,
            srcDurMs = m.durMs, inMs = 0L, outMs = m.durMs
        )
        place(clip, m.isAudio, audioTrack, atMs)
    }

    fun addSourceToTimeline() {
        val m = sourceMedia ?: return
        val a = srcIn.coerceIn(0L, m.durMs)
        val b = srcOut.coerceIn(0L, m.durMs)
        if (b - a < 200L) {
            toast = Msg("নির্বাচিত অংশ খুব ছোট", "Selected range is too short")
            return
        }
        val clip = Clip(
            mediaId = m.id, uri = m.uri, name = m.name,
            srcDurMs = m.durMs, inMs = a, outMs = b
        )
        place(clip, m.isAudio, 0, null)
        toast = Msg("টাইমলাইনে যোগ হয়েছে", "Added to timeline")
    }

    /** Called when a media item is dropped on the timeline at screen position p. */
    fun dropMedia(m: MediaRef, p: Offset) {
        if (p.x < tlLeft || p.x > tlLeft + tlW || p.y < tlTop || p.y > tlTop + tlH) return
        val xContent = p.x - tlLeft + scrollPx
        val ms = (xContent / (zoom * density) * 1000f).toLong()
        val yDp = (p.y - tlTop) / density
        val track = if (yDp > TL_AUDIO2_TOP_DP) 1 else 0
        addMedia(m, track, ms)
    }

    // ---------- selection ----------
    fun selectClip(id: String) {
        selectedClipId = id
        selectedTextId = null
    }

    fun selectText(id: String) {
        selectedTextId = id
        selectedClipId = null
    }

    fun selectedVideoClip(): Clip? = seq.video.find { it.id == selectedClipId }

    fun findClip(id: String): Clip? =
        seq.video.find { it.id == id } ?: seq.audio.flatten().find { it.id == id }

    fun selectedAnyClip(): Clip? = selectedClipId?.let { findClip(it) }

    fun selectedText(): TextClip? = seq.texts.find { it.id == selectedTextId }

    fun localTime(clip: Clip): Long {
        val idx = seq.video.indexOfFirst { it.id == clip.id }
        if (idx < 0) return 0L
        return (globalMs - startOf(seq.video, idx)).coerceIn(0L, clip.durMs)
    }

    // ---------- clip operations ----------
    fun splitClipAt(id: String, off: Long) {
        val c = findClip(id) ?: return
        if (off < 300L || c.durMs - off < 300L) {
            toast = Msg("ক্লিপের মাঝখানে কাটুন", "Cut away from the clip edges")
            return
        }
        val cutSrc = c.inMs + (off * c.speed).toLong()
        val left = c.copy(outMs = cutSrc)
        val right = c.copy(id = newId(), inMs = cutSrc, fx = c.fx.shifted(off))
        editSeq { s -> mapClips(s) { if (it.id == id) listOf(left, right) else listOf(it) } }
        selectClip(right.id)
    }

    fun splitAtPlayhead() {
        val clips = seq.video
        if (clips.isEmpty()) return
        val (i, off) = locate(clips, globalMs.coerceIn(0L, (seq.totalMs - 1).coerceAtLeast(0L)))
        splitClipAt(clips[i].id, off)
    }

    fun duplicateClip(id: String) {
        val c = findClip(id) ?: return
        val copy = c.copy(id = newId())
        editSeq { s -> mapClips(s) { if (it.id == id) listOf(it, copy) else listOf(it) } }
        selectClip(copy.id)
    }

    fun deleteClip(id: String) {
        editSeq { s -> mapClips(s) { if (it.id == id) emptyList() else listOf(it) } }
        if (selectedClipId == id) selectedClipId = null
    }

    fun duplicateSelected() {
        val id = selectedClipId
        if (id == null) {
            toast = Msg("আগে একটা ক্লিপ সিলেক্ট করুন", "Select a clip first")
            return
        }
        duplicateClip(id)
    }

    fun deleteSelected() {
        val tid = selectedTextId
        if (tid != null) {
            deleteText(tid)
            return
        }
        val id = selectedClipId
        if (id == null) {
            toast = Msg("আগে একটা ক্লিপ সিলেক্ট করুন", "Select a clip first")
            return
        }
        deleteClip(id)
    }

    fun trimSelected(newIn: Long, newOut: Long) {
        val id = selectedClipId ?: return
        editSeq { s ->
            mapClips(s) { if (it.id == id) listOf(it.copy(inMs = newIn, outMs = newOut)) else listOf(it) }
        }
    }

    fun setSpeed(id: String, sp: Float) {
        editSeq { s ->
            mapClips(s) { if (it.id == id) listOf(it.copy(speed = sp.coerceIn(0.1f, 8f))) else listOf(it) }
        }
    }

    // ---------- effects ----------
    private fun liveFx(f: (Fx) -> Fx) {
        val id = selectedClipId ?: return
        liveSeq { sq -> mapClips(sq) { if (it.id == id) listOf(it.copy(fx = f(it.fx))) else listOf(it) } }
    }

    private fun editFx(f: (Fx) -> Fx) {
        val id = selectedClipId ?: return
        editSeq { sq -> mapClips(sq) { if (it.id == id) listOf(it.copy(fx = f(it.fx))) else listOf(it) } }
    }

    fun addEffect(e: Eff) {
        val tx = selectedText()
        if (tx != null) {
            if (e == Eff.BW) {
                updateText(tx.id, false) { it.copy(bw = true) }
                toast = Msg("টেক্সটে সাদা-কালো যোগ হয়েছে", "Black & White added to text")
            } else {
                toast = Msg(
                    "টেক্সটের Size/Position/Opacity টেক্সট প্যানেলে আছে",
                    "Text Size/Position/Opacity are in the Text panel"
                )
            }
            return
        }
        val sel = selectedAnyClip()
        if (sel == null) {
            toast = Msg("আগে টাইমলাইনে একটা ক্লিপ সিলেক্ট করুন", "Select a clip on the timeline first")
            return
        }
        val audioEffect = e == Eff.DENOISE || e == Eff.VOLUME || e == Eff.EQ
        val isVideo = seq.video.any { it.id == sel.id }
        if (!audioEffect && !isVideo) {
            toast = Msg("এই ইফেক্ট শুধু ভিডিও ক্লিপে", "This effect needs a video clip")
            return
        }
        editFx { fx ->
            when (e) {
                Eff.TRANSFORM -> fx.copy(transformOn = true)
                Eff.FLIP_H -> fx.copy(flipH = true)
                Eff.FLIP_V -> fx.copy(flipV = true)
                Eff.BLUR -> fx.copy(blurOn = true)
                Eff.CROP -> fx.copy(cropOn = true)
                Eff.BW -> fx.copy(bwOn = true)
                Eff.COLOR -> fx.copy(colorOn = true)
                Eff.DENOISE -> fx.copy(denoiseOn = true)
                Eff.VOLUME -> fx.copy(volOn = true)
                Eff.EQ -> fx.copy(eqOn = true)
            }
        }
        toast = Msg("ইফেক্ট যোগ হয়েছে, Controls প্যানেলে দেখুন", "Effect added. See the Controls panel")
    }

    fun removeEffect(e: Eff) {
        val tx = selectedText()
        if (tx != null) {
            if (e == Eff.BW) updateText(tx.id, false) { it.copy(bw = false) }
            return
        }
        editFx { fx ->
            when (e) {
                Eff.TRANSFORM -> fx.copy(transformOn = false, scale = Param(100f), posX = Param(0f), posY = Param(0f))
                Eff.FLIP_H -> fx.copy(flipH = false)
                Eff.FLIP_V -> fx.copy(flipV = false)
                Eff.BLUR -> fx.copy(blurOn = false, blur = Param(10f))
                Eff.CROP -> fx.copy(cropOn = false, cropL = 0f, cropR = 0f, cropT = 0f, cropB = 0f)
                Eff.BW -> fx.copy(bwOn = false)
                Eff.COLOR -> fx.copy(colorOn = false, bright = 0f, contrast = 0f, sat = 0f, exposure = 0f, temp = 0f, tint = 0f)
                Eff.DENOISE -> fx.copy(denoiseOn = false)
                Eff.VOLUME -> fx.copy(volOn = false, volDb = 0f)
                Eff.EQ -> fx.copy(eqOn = false, eqGain = 0f)
            }
        }
    }

    fun muteClipAudio(id: String) {
        editSeq { sq ->
            mapClips(sq) {
                if (it.id == id) listOf(it.copy(fx = it.fx.copy(volOn = true, volDb = -80f))) else listOf(it)
            }
        }
        toast = Msg("অডিও বন্ধ হয়েছে (Controls-এ Volume বাড়ালে ফিরবে)", "Audio muted (raise Volume in Controls to undo)")
    }

    fun setParam(w: P, v: Float, t: Long) = liveFx { it.mapParam(w) { p -> p.withValueAt(t, v) } }
    fun toggleKeyframe(w: P, t: Long) = editFx { it.mapParam(w) { p -> p.toggleKeyframeAt(t) } }
    fun setDenoise(v: Float) = liveFx { it.copy(denoise = v) }
    fun setVolume(v: Float) = liveFx { it.copy(volDb = v) }
    fun setEq(freq: Float? = null, gain: Float? = null, q: Float? = null) = liveFx {
        it.copy(eqFreq = freq ?: it.eqFreq, eqGain = gain ?: it.eqGain, eqQ = q ?: it.eqQ)
    }
    fun setCrop(l: Float? = null, r: Float? = null, t: Float? = null, b: Float? = null) = liveFx {
        it.copy(
            cropL = l ?: it.cropL, cropR = r ?: it.cropR,
            cropT = t ?: it.cropT, cropB = b ?: it.cropB
        )
    }

    fun setColor(
        bright: Float? = null,
        contrast: Float? = null,
        sat: Float? = null,
        exposure: Float? = null,
        temp: Float? = null,
        tint: Float? = null
    ) = liveFx {
        it.copy(
            bright = bright ?: it.bright,
            contrast = contrast ?: it.contrast,
            sat = sat ?: it.sat,
            exposure = exposure ?: it.exposure,
            temp = temp ?: it.temp,
            tint = tint ?: it.tint
        )
    }

    /** Pinch / drag on the preview: moves or zooms the selected text or video clip. */
    fun previewGesture(zoomBy: Float, panXPct: Float, panYPct: Float) {
        val tc = selectedText()
        if (tc != null) {
            val lt = (globalMs - tc.startMs).coerceIn(0L, tc.durMs)
            updateText(tc.id, true) {
                it.copy(
                    xP = it.xP.withValueAt(lt, (it.xP.at(lt) + panXPct * 2f).coerceIn(-100f, 100f)),
                    yP = it.yP.withValueAt(lt, (it.yP.at(lt) + panYPct * 2f).coerceIn(-100f, 100f)),
                    sP = it.sP.withValueAt(lt, (it.sP.at(lt) * zoomBy).coerceIn(2f, 30f))
                )
            }
            liveDeadline = SystemClock.uptimeMillis() + 600L
            return
        }
        val cl = selectedVideoClip() ?: return
        val t = localTime(cl)
        liveFx { fx ->
            fx.copy(
                transformOn = true,
                scale = fx.scale.withValueAt(t, (fx.scale.at(t) * zoomBy).coerceIn(10f, 400f)),
                posX = fx.posX.withValueAt(t, (fx.posX.at(t) + panXPct).coerceIn(-100f, 100f)),
                posY = fx.posY.withValueAt(t, (fx.posY.at(t) + panYPct).coerceIn(-100f, 100f))
            )
        }
    }

    // ---------- text ----------
    fun addText() {
        val t = TextClip(startMs = globalMs)
        editSeq { it.copy(texts = it.texts + t) }
        selectText(t.id)
    }

    fun updateText(id: String, live: Boolean, f: (TextClip) -> TextClip) {
        val change: (Seq) -> Seq = { s -> s.copy(texts = s.texts.map { if (it.id == id) f(it) else it }) }
        if (live) liveSeq(change) else editSeq(change)
    }

    fun deleteText(id: String) {
        editSeq { s -> s.copy(texts = s.texts.filter { it.id != id }) }
        if (selectedTextId == id) selectedTextId = null
    }

    fun duplicateText(id: String) {
        val t = seq.texts.find { it.id == id } ?: return
        val copy = t.copy(id = newId(), startMs = t.startMs + t.durMs)
        editSeq { it.copy(texts = it.texts + copy) }
        selectText(copy.id)
    }

    fun textLocalTime(t: TextClip): Long = (globalMs - t.startMs).coerceIn(0L, t.durMs)

    fun setTextParam(id: String, w: TP, v: Float, lt: Long) =
        updateText(id, true) { it.mapParam(w) { p -> p.withValueAt(lt, v) } }

    fun toggleTextKeyframe(id: String, w: TP, lt: Long) =
        updateText(id, false) { it.mapParam(w) { p -> p.toggleKeyframeAt(lt) } }

    // ---------- transitions ----------
    fun setTransition(clipId: String, kind: String) {
        editSeq { sq ->
            mapClips(sq) { if (it.id == clipId) listOf(it.copy(trans = kind, transMs = 1000L)) else listOf(it) }
        }
    }

    // ---------- sequences ----------
    fun addSequence(name: String, ratio: String, fps: Int) {
        val s = Seq(
            name = name.ifBlank { "Sequence ${project.seqs.size + 1}" },
            ratio = ratio,
            fps = fps.coerceIn(1, 120)
        )
        edit { it.copy(seqs = it.seqs + s) }
        switchSeq(project.seqs.size - 1)
    }

    fun switchSeq(i: Int) {
        pauseAll()
        activeIdx = i.coerceIn(0, project.seqs.size - 1)
        globalMs = 0L
        selectedClipId = null
        selectedTextId = null
    }

    fun renameSeq(name: String) {
        editSeq { it.copy(name = name.ifBlank { "Sequence" }) }
    }

    fun deleteSeq() {
        if (project.seqs.size <= 1) return
        val i = activeIdx
        edit { p -> p.copy(seqs = p.seqs.filterIndexed { idx, _ -> idx != i }) }
        switchSeq(0)
    }

    // ---------- export ----------
    fun startExport(shortSide: Int) {
        val s = seq
        if (s.video.isEmpty()) {
            toast = Msg("টাইমলাইনে কোনো ভিডিও নেই", "The timeline has no video")
            return
        }
        pauseAll()
        val (w, h) = outputSize(s.aspect, shortSide)
        val f = File(context.cacheDir, "export_${System.currentTimeMillis()}.mp4")
        exportMsg = null
        exportProgress = 0f
        try {
            transformer = runExport(
                context, s, w, h, f,
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
        audioPlayers.forEach { it.release() }
        sourcePlayer.release()
    }
}

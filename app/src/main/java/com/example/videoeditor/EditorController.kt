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
import kotlin.math.abs

class DragState(val media: MediaRef, val pos: Offset)

/** One animated parameter of the selected object, as shown in the Keyframes panel. */
class PEntry(
    val label: String,
    val param: Param,
    val originMs: Long,
    val edit: ((Param) -> Param) -> Unit
)

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
    var selectedAdjId by mutableStateOf<String?>(null)
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
    var scrollYpx = 0
    var density = 1f

    val player: ExoPlayer = ExoPlayer.Builder(context).build()
    private val audioPool = HashMap<String, ExoPlayer>()
    private val audioSig = HashMap<String, String>()
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
        s.copy(video = s.video.flatMap(f), audio = s.audio.map { track -> normalizeTrack(track.flatMap(f)) })

    // ---------- video playlist ----------
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

    // ---------- audio: one player per audio clip, started when the playhead reaches it ----------
    fun syncAudioPool() {
        val clips = seq.audio.flatten()
        val ids = clips.map { it.id }.toSet()
        val gone = audioPool.keys.filter { it !in ids }
        for (id in gone) {
            audioPool.remove(id)?.release()
            audioSig.remove(id)
        }
        for (c in clips) {
            val sig = c.uri + "|" + c.inMs + "|" + c.outMs
            val p = audioPool.getOrPut(c.id) { ExoPlayer.Builder(context).build() }
            if (audioSig[c.id] != sig) {
                audioSig[c.id] = sig
                p.setMediaItem(mediaItemOf(c))
                p.prepare()
                p.playWhenReady = false
            }
        }
    }

    private fun updateAudio() {
        val playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED && seq.video.isNotEmpty()
        for (c in seq.audio.flatten()) {
            val p = audioPool[c.id] ?: continue
            val local = globalMs - c.startMs
            val inside = local >= 0L && local < c.durMs
            val g = c.fx.gainAt(local.coerceAtLeast(0L)).coerceAtMost(1f)
            if (p.volume != g) p.volume = g
            if (playing && inside) {
                if (!p.playWhenReady) {
                    p.seekTo(local)
                    p.playWhenReady = true
                } else if (abs(p.currentPosition - local) > 300L) {
                    p.seekTo(local)
                }
            } else if (p.playWhenReady) {
                p.playWhenReady = false
            }
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
                val g = clips[idx].fx.gainAt(within).coerceAtMost(1f)
                if (player.volume != g) player.volume = g
            }
        }
        updateAudio()
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
    }

    fun togglePlay() {
        if (seq.video.isEmpty()) return
        val playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED
        if (playing) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) seekGlobal(0L)
            player.play()
        }
    }

    fun pauseAll() {
        player.pause()
        audioPool.values.forEach { it.playWhenReady = false }
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
                val t = track.coerceIn(0, (s.audio.size - 1).coerceAtLeast(0))
                val list = s.audio.getOrNull(t) ?: emptyList()
                val start = (atMs ?: (list.maxOfOrNull { it.startMs + it.durMs } ?: 0L)).coerceAtLeast(0L)
                val placed = clip.copy(startMs = start)
                if (s.audio.isEmpty()) {
                    s.copy(audio = listOf(listOf(placed)))
                } else {
                    s.copy(audio = s.audio.mapIndexed { i, l -> if (i == t) normalizeTrack(l + placed) else l })
                }
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
        val rowsY = (p.y - tlTop) / density - TL_RULER_DP + scrollYpx / density
        val n = seq.audio.size
        val track = if (n == 0) 0 else ((rowsY - TL_AUDIO_TOP_DP) / TL_AUDIO_DP).toInt().coerceIn(0, n - 1)
        addMedia(m, track, ms)
        if (m.isAudio && n == 0) toast = Msg("নতুন অডিও ট্র্যাক তৈরি হয়েছে", "A new audio track was created")
    }

    // ---------- selection ----------
    fun selectClip(id: String) {
        selectedClipId = id
        selectedTextId = null
        selectedAdjId = null
    }

    fun selectText(id: String) {
        selectedTextId = id
        selectedClipId = null
        selectedAdjId = null
    }

    fun selectAdj(id: String) {
        selectedAdjId = id
        selectedClipId = null
        selectedTextId = null
    }

    fun selectedVideoClip(): Clip? = seq.video.find { it.id == selectedClipId }

    fun findClip(id: String): Clip? =
        seq.video.find { it.id == id } ?: seq.audio.flatten().find { it.id == id }

    fun selectedAnyClip(): Clip? = selectedClipId?.let { findClip(it) }

    fun selectedText(): TextClip? = seq.texts.find { it.id == selectedTextId }

    fun selectedAdj(): AdjClip? = seq.adj.find { it.id == selectedAdjId }

    /** The effect stack of the selected clip or adjustment layer. */
    fun selectedFx(): Fx? = selectedAdj()?.fx ?: selectedAnyClip()?.fx

    fun clipStartMs(clip: Clip): Long {
        val i = seq.video.indexOfFirst { it.id == clip.id }
        return if (i >= 0) startOf(seq.video, i) else clip.startMs
    }

    fun localTime(clip: Clip): Long = (globalMs - clipStartMs(clip)).coerceIn(0L, clip.durMs)

    /** Time of the playhead inside the selected clip or adjustment layer. */
    fun selectedLocalTime(): Long {
        val a = selectedAdj()
        if (a != null) return (globalMs - a.startMs).coerceIn(0L, a.durMs)
        val c = selectedAnyClip() ?: return 0L
        return localTime(c)
    }

    fun selectedOriginMs(): Long {
        val a = selectedAdj()
        if (a != null) return a.startMs
        val t = selectedText()
        if (t != null) return t.startMs
        val c = selectedAnyClip() ?: return 0L
        return clipStartMs(c)
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
        val right = c.copy(id = newId(), inMs = cutSrc, startMs = c.startMs + off, fx = c.fx.shifted(off))
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
        val copy = c.copy(id = newId(), startMs = c.startMs + c.durMs)
        editSeq { s -> mapClips(s) { if (it.id == id) listOf(it, copy) else listOf(it) } }
        selectClip(copy.id)
    }

    fun deleteClip(id: String) {
        editSeq { s -> mapClips(s) { if (it.id == id) emptyList() else listOf(it) } }
        if (selectedClipId == id) selectedClipId = null
    }

    fun duplicateSelected() {
        val tid = selectedTextId
        if (tid != null) {
            duplicateText(tid)
            return
        }
        val aid = selectedAdjId
        if (aid != null) {
            duplicateAdj(aid)
            return
        }
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
        val aid = selectedAdjId
        if (aid != null) {
            deleteAdj(aid)
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

    /** Detaches the audio of a video clip onto its own audio track at the same time. */
    fun unlinkAudio(id: String) {
        val idx = seq.video.indexOfFirst { it.id == id }
        if (idx < 0) return
        val c = seq.video[idx]
        val start = startOf(seq.video, idx)
        val a = Clip(
            mediaId = c.mediaId, uri = c.uri, name = c.name, srcDurMs = c.srcDurMs,
            inMs = c.inMs, outMs = c.outMs, speed = c.speed, startMs = start,
            fx = Fx(
                volOn = c.fx.volOn, vol = c.fx.vol, eqOn = c.fx.eqOn, eqFreq = c.fx.eqFreq,
                eqGain = c.fx.eqGain, eqQ = c.fx.eqQ, denoiseOn = c.fx.denoiseOn, denoise = c.fx.denoise
            )
        )
        editSeq { s ->
            val free = s.audio.indexOfFirst { tr -> tr.none { o -> o.startMs < start + a.durMs && o.startMs + o.durMs > start } }
            val audio = if (free >= 0) {
                s.audio.mapIndexed { i, l -> if (i == free) normalizeTrack(l + a) else l }
            } else {
                s.audio + listOf(listOf(a))
            }
            s.copy(
                audio = audio,
                video = s.video.map {
                    if (it.id == id) it.copy(fx = it.fx.copy(volOn = true, vol = Param(-80f), off = it.fx.off - Eff.VOLUME.name)) else it
                }
            )
        }
        toast = Msg("অডিও আলাদা হয়েছে (Unlink)", "Audio unlinked onto its own track")
    }

    // ---------- audio tracks ----------
    fun addAudioTrack() {
        editSeq { it.copy(audio = it.audio + listOf(emptyList<Clip>())) }
    }

    fun removeAudioTrack(i: Int) {
        editSeq { s -> s.copy(audio = s.audio.filterIndexed { idx, _ -> idx != i }) }
    }

    private fun snapCandidates(ignoreId: String): List<Long> {
        val s = seq
        val out = mutableListOf(0L, globalMs)
        var acc = 0L
        s.video.forEach {
            out.add(acc)
            acc += it.durMs
            out.add(acc)
        }
        s.audio.flatten().filter { it.id != ignoreId }.forEach {
            out.add(it.startMs)
            out.add(it.startMs + it.durMs)
        }
        s.texts.filter { it.id != ignoreId }.forEach {
            out.add(it.startMs)
            out.add(it.startMs + it.durMs)
        }
        s.adj.filter { it.id != ignoreId }.forEach {
            out.add(it.startMs)
            out.add(it.startMs + it.durMs)
        }
        return out
    }

    /** Snaps a start time so the start or the end lands on a nearby edge (within about 10 px). */
    fun snapStart(start: Long, dur: Long, ignoreId: String): Long {
        val thr = (10f / (zoom * density) * 1000f).toLong().coerceAtLeast(30L)
        var best = start
        var bestD = thr + 1
        for (c in snapCandidates(ignoreId)) {
            val d1 = abs(c - start)
            if (d1 < bestD) {
                bestD = d1
                best = c
            }
            val d2 = abs(c - (start + dur))
            if (d2 < bestD) {
                bestD = d2
                best = c - dur
            }
        }
        return best.coerceAtLeast(0L)
    }

    /** Moves an audio clip by deltaMs and optionally to a neighbouring audio track. */
    fun moveAudioClip(id: String, newStart: Long, trackDelta: Int) {
        liveSeq { s ->
            var from = -1
            var clip: Clip? = null
            s.audio.forEachIndexed { i, tr -> tr.find { it.id == id }?.let { from = i; clip = it } }
            val c = clip
            if (c == null || from < 0) {
                s
            } else {
                val to = (from + trackDelta).coerceIn(0, s.audio.size - 1)
                val moved = c.copy(startMs = newStart.coerceAtLeast(0L))
                s.copy(audio = s.audio.mapIndexed { i, tr ->
                    when {
                        i == to -> normalizeTrack(tr.filter { it.id != id } + moved)
                        i == from -> tr.filter { it.id != id }
                        else -> tr
                    }
                })
            }
        }
    }

    fun moveText(id: String, newStart: Long) {
        liveSeq { s -> s.copy(texts = s.texts.map { if (it.id == id) it.copy(startMs = newStart.coerceAtLeast(0L)) else it }) }
    }

    fun moveAdj(id: String, newStart: Long) {
        liveSeq { s -> s.copy(adj = s.adj.map { if (it.id == id) it.copy(startMs = newStart.coerceAtLeast(0L)) else it }) }
    }

    fun setAdjDuration(id: String, dur: Long) {
        liveSeq { s -> s.copy(adj = s.adj.map { if (it.id == id) it.copy(durMs = dur.coerceAtLeast(200L)) else it }) }
    }

    // ---------- adjustment layers ----------
    fun addAdjustment() {
        val dur = if (seq.totalMs > 0L) minOf(5000L, seq.totalMs) else 5000L
        val a = AdjClip(startMs = globalMs.coerceAtLeast(0L), durMs = dur)
        editSeq { it.copy(adj = it.adj + a) }
        selectAdj(a.id)
        toast = Msg("Adjustment Layer যোগ হয়েছে, Effects থেকে ইফেক্ট দিন", "Adjustment layer added. Add effects from Effects")
    }

    fun duplicateAdj(id: String) {
        val a = seq.adj.find { it.id == id } ?: return
        val copy = a.copy(id = newId(), startMs = a.startMs + a.durMs)
        editSeq { it.copy(adj = it.adj + copy) }
        selectAdj(copy.id)
    }

    fun deleteAdj(id: String) {
        editSeq { s -> s.copy(adj = s.adj.filter { it.id != id }) }
        if (selectedAdjId == id) selectedAdjId = null
    }

    // ---------- effects (clips and adjustment layers share one effect stack) ----------
    private fun mutFx(live: Boolean, f: (Fx) -> Fx) {
        val aid = selectedAdjId
        val cid = selectedClipId
        val change: (Seq) -> Seq = when {
            aid != null -> { s -> s.copy(adj = s.adj.map { if (it.id == aid) it.copy(fx = f(it.fx)) else it }) }
            cid != null -> { s -> mapClips(s) { if (it.id == cid) listOf(it.copy(fx = f(it.fx))) else listOf(it) } }
            else -> return
        }
        if (live) liveSeq(change) else editSeq(change)
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
        val adj = selectedAdj()
        val clip = selectedAnyClip()
        if (adj == null && clip == null) {
            toast = Msg("আগে টাইমলাইনে একটা ক্লিপ বা Adjustment Layer সিলেক্ট করুন", "Select a clip or adjustment layer first")
            return
        }
        val audioEffect = e == Eff.DENOISE || e == Eff.VOLUME || e == Eff.EQ
        val isVideo = clip != null && seq.video.any { it.id == clip.id }
        if (adj != null && (audioEffect || e == Eff.CROP)) {
            toast = Msg("এই ইফেক্ট Adjustment Layer-এ চলে না", "This effect is not available on an adjustment layer")
            return
        }
        if (clip != null && !audioEffect && !isVideo) {
            toast = Msg("এই ইফেক্ট শুধু ভিডিও ক্লিপে", "This effect needs a video clip")
            return
        }
        mutFx(false) { it.withFlag(e, true).let { f -> f.copy(off = f.off - e.name) } }
        toast = Msg("ইফেক্ট যোগ হয়েছে, Controls প্যানেলে দেখুন", "Effect added. See the Controls panel")
    }

    fun removeEffect(e: Eff) {
        val tx = selectedText()
        if (tx != null) {
            if (e == Eff.BW) updateText(tx.id, false) { it.copy(bw = false) }
            return
        }
        mutFx(false) { it.resetEffect(e).withFlag(e, false).let { f -> f.copy(off = f.off - e.name) } }
    }

    fun resetEffect(e: Eff) = mutFx(false) { it.resetEffect(e) }

    fun setEffectEnabled(e: Eff, enabled: Boolean) = mutFx(false) { f ->
        f.copy(off = if (enabled) f.off - e.name else (f.off - e.name) + e.name)
    }

    fun muteClipAudio(id: String) {
        editSeq { sq ->
            mapClips(sq) {
                if (it.id == id) listOf(it.copy(fx = it.fx.copy(volOn = true, vol = Param(-80f), off = it.fx.off - Eff.VOLUME.name))) else listOf(it)
            }
        }
        toast = Msg("অডিও বন্ধ হয়েছে (Controls-এ Volume বাড়ালে ফিরবে)", "Audio muted (raise Volume in Controls to undo)")
    }

    fun setParam(w: P, v: Float, t: Long) = mutFx(true) { it.mapParam(w) { p -> p.withValueAt(t, v) } }
    fun toggleKeyframe(w: P, t: Long) = mutFx(false) { it.mapParam(w) { p -> p.toggleKeyframeAt(t) } }
    fun setDenoise(v: Float) = mutFx(true) { it.copy(denoise = v) }
    fun setEq(freq: Float? = null, gain: Float? = null, q: Float? = null) = mutFx(true) {
        it.copy(eqFreq = freq ?: it.eqFreq, eqGain = gain ?: it.eqGain, eqQ = q ?: it.eqQ)
    }

    /** Pinch / drag on the preview: moves or zooms the selected text, clip or adjustment layer. */
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
        if (selectedAdjId == null) {
            val cl = selectedVideoClip() ?: return
            if (cl.id.isEmpty()) return
        }
        val t = selectedLocalTime()
        mutFx(true) { fx ->
            fx.copy(
                transformOn = true,
                scale = fx.scale.withValueAt(t, (fx.scale.at(t) * zoomBy).coerceIn(10f, 400f)),
                posX = fx.posX.withValueAt(t, (fx.posX.at(t) + panXPct).coerceIn(-100f, 100f)),
                posY = fx.posY.withValueAt(t, (fx.posY.at(t) + panYPct).coerceIn(-100f, 100f))
            )
        }
    }

    /** Animated parameters of the selected object (for the Keyframes panel). */
    fun paramEntries(): List<PEntry> {
        val out = mutableListOf<PEntry>()
        val tx = selectedText()
        if (tx != null) {
            for (w in TP.values()) {
                val p = tx.param(w)
                if (p.kfs.isNotEmpty()) {
                    out.add(PEntry("Text " + w.name.lowercase(), p, tx.startMs) { f ->
                        updateText(tx.id, false) { it.mapParam(w, f) }
                    })
                }
            }
            return out
        }
        val fx = selectedFx() ?: return out
        val origin = selectedOriginMs()
        for (w in P.values()) {
            val p = fx.param(w)
            if (p.kfs.isNotEmpty()) {
                out.add(PEntry(w.label, p, origin) { f -> mutFx(false) { it.mapParam(w, f) } })
            }
        }
        return out
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
        selectedAdjId = null
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
        audioPool.values.forEach { it.release() }
        audioPool.clear()
        sourcePlayer.release()
    }
}

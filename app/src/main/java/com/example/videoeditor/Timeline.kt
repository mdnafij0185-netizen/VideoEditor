package com.example.videoeditor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

const val TL_RULER_DP = 22f
const val TL_ADJ_DP = 30f
const val TL_TEXT_DP = 28f
const val TL_VIDEO_DP = 44f
const val TL_VAUDIO_DP = 30f
const val TL_AUDIO_DP = 34f
const val TL_FOOT_DP = 30f

/** Distance in dp from the top of the rows area to the first audio row. */
const val TL_AUDIO_TOP_DP = TL_ADJ_DP + TL_TEXT_DP + TL_VIDEO_DP + TL_VAUDIO_DP

private val TL_GUTTER = 56.dp

private fun item(t: String, a: () -> Unit): Pair<String, () -> Unit> = Pair(t, a)

@Composable
private fun GutterRow(label: String, h: Float, items: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Box(
        Modifier
            .height(h.dp)
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { open = true }, onLongPress = { open = true })
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Text(label, fontSize = 10.sp, color = TextLo, modifier = Modifier.padding(start = 6.dp), maxLines = 1)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { (t, a) ->
                DropdownMenuItem(
                    text = { Text(t) },
                    onClick = {
                        open = false
                        a()
                    }
                )
            }
        }
    }
}

@Composable
fun TimelineView(c: EditorController, modifier: Modifier, onSpeed: (String) -> Unit, onTrim: (String) -> Unit) {
    val density = LocalDensity.current
    val hs = rememberScrollState()
    val vs = rememberScrollState()
    var menuFor by remember { mutableStateOf<String?>(null) }
    var viewportW by remember { mutableIntStateOf(0) }
    val seq = c.seq
    val zoom = c.zoom
    val ppsPx = with(density) { zoom.dp.toPx() }
    val lengthMs = maxOf(seq.lengthMs, 1000L)
    val contentPx = maxOf(lengthMs / 1000f * ppsPx + viewportW * 0.6f, viewportW.toFloat())
    val contentW = with(density) { contentPx.toDp() }
    val nAudio = seq.audio.size
    val rowsH = TL_AUDIO_TOP_DP + TL_AUDIO_DP * nAudio + TL_FOOT_DP + 40f

    SideEffect {
        c.scrollPx = hs.value
        c.scrollYpx = vs.value
        c.density = density.density
    }

    LaunchedEffect(c.isPlaying, c.zoom) {
        while (c.isPlaying) {
            val x = (c.globalMs / 1000f * ppsPx).toInt()
            if (x < hs.value || x > hs.value + viewportW - 40) {
                hs.scrollTo((x - viewportW / 3).coerceAtLeast(0))
            }
            delay(120)
        }
    }

    Row(
        modifier
            .fillMaxWidth()
            .background(Panel)
    ) {
        Column(Modifier.width(TL_GUTTER)) {
            Spacer(Modifier.height(TL_RULER_DP.dp))
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(vs)
            ) {
                GutterRow("Adjust", TL_ADJ_DP, listOf(item("Add Adjustment Layer") { c.addAdjustment() }))
                GutterRow("Text", TL_TEXT_DP, listOf(item("Add Text") { c.addText() }))
                GutterRow(
                    "Video", TL_VIDEO_DP,
                    listOf(
                        item("Add Adjustment Layer") { c.addAdjustment() },
                        item(tr("ভিডিও লেয়ার ২, ৩... (পরের আপডেটে)", "More video layers (next update)")) {
                            c.toast = Msg("একাধিক ভিডিও লেয়ার পরের আপডেটে আসছে", "More video layers are coming in the next update")
                        }
                    )
                )
                GutterRow("V-Audio", TL_VAUDIO_DP, listOf(item("Add Audio Track") { c.addAudioTrack() }))
                for (i in 0 until nAudio) {
                    GutterRow(
                        "Audio " + (i + 1), TL_AUDIO_DP,
                        listOf(
                            item("Add Audio Track") { c.addAudioTrack() },
                            item("Delete This Track") { c.removeAudioTrack(i) }
                        )
                    )
                }
                GutterRow("+ Audio", TL_FOOT_DP, listOf(item("Add Audio Track") { c.addAudioTrack() }))
                Spacer(Modifier.height(40.dp))
            }
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .onSizeChanged { viewportW = it.width }
                .onGloballyPositioned {
                    val p = it.positionInRoot()
                    c.tlLeft = p.x
                    c.tlTop = p.y
                    c.tlW = it.size.width.toFloat()
                    c.tlH = it.size.height.toFloat()
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.size >= 2) {
                                val z = event.calculateZoom()
                                if (z != 1f) {
                                    c.zoom = (c.zoom * z).coerceIn(12f, 300f)
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .horizontalScroll(hs)
        ) {
            Box(
                Modifier
                    .width(contentW)
                    .fillMaxHeight()
            ) {
                Column(Modifier.fillMaxHeight()) {
                    // ruler: tap or drag to move the playhead
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(TL_RULER_DP.dp)
                            .background(Color(0xFF0D1426))
                            .pointerInput(ppsPx) {
                                detectTapGestures { o -> c.seekGlobal((o.x / ppsPx * 1000f).toLong()) }
                            }
                            .pointerInput(ppsPx) {
                                detectDragGestures(
                                    onDragStart = { o ->
                                        c.scrubbing = true
                                        c.seekGlobal((o.x / ppsPx * 1000f).toLong())
                                    },
                                    onDrag = { ch, _ -> c.seekGlobal((ch.position.x / ppsPx * 1000f).toLong()) },
                                    onDragEnd = { c.scrubbing = false },
                                    onDragCancel = { c.scrubbing = false }
                                )
                            }
                    ) {
                        val step = if (zoom >= 120f) 1 else if (zoom >= 50f) 2 else if (zoom >= 25f) 5 else 10
                        val maxSec = (lengthMs / 1000L).toInt() + 30
                        var s = 0
                        while (s <= maxSec) {
                            Text(
                                fmt(s * 1000L),
                                fontSize = 9.sp,
                                color = TextLo,
                                modifier = Modifier.offset(x = (s * zoom).dp + 3.dp, y = 4.dp)
                            )
                            Box(
                                Modifier
                                    .offset(x = (s * zoom).dp)
                                    .width(1.dp)
                                    .height(TL_RULER_DP.dp)
                                    .background(Line)
                            )
                            s += step
                        }
                    }

                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(vs)
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(rowsH.dp)
                        ) {
                            // adjustment layers
                            seq.adj.forEach { a ->
                                val sel = c.selectedAdjId == a.id
                                val startState = rememberUpdatedState(a.startMs)
                                Box(
                                    Modifier
                                        .offset(x = (a.startMs / 1000f * zoom).dp, y = 2.dp)
                                        .width((a.durMs / 1000f * zoom).dp)
                                        .height((TL_ADJ_DP - 4f).dp)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(Color(0xFF7C4DFF))
                                        .then(if (sel) Modifier.border(2.dp, Color.White, RoundedCornerShape(5.dp)) else Modifier)
                                        .pointerInput(a.id) {
                                            detectTapGestures(
                                                onTap = { c.selectAdj(a.id) },
                                                onLongPress = {
                                                    c.selectAdj(a.id)
                                                    menuFor = a.id
                                                }
                                            )
                                        }
                                        .pointerInput(a.id) {
                                            var base = 0L
                                            var acc = 0f
                                            detectDragGestures(
                                                onDragStart = {
                                                    c.selectAdj(a.id)
                                                    base = startState.value
                                                    acc = 0f
                                                },
                                                onDrag = { ch, d ->
                                                    ch.consume()
                                                    acc += d.x
                                                    val raw = base + (acc / ppsPx * 1000f).toLong()
                                                    c.moveAdj(a.id, c.snapStart(raw, a.durMs, a.id))
                                                },
                                                onDragEnd = { c.endLive() },
                                                onDragCancel = { c.endLive() }
                                            )
                                        }
                                ) {
                                    Text(a.name, fontSize = 10.sp, maxLines = 1, color = Color.White, modifier = Modifier.padding(4.dp))
                                    val times = P.values().flatMap { a.fx.param(it).kfs }.map { it.tMs }.distinct()
                                    if (sel) {
                                        times.forEach { t ->
                                            Box(
                                                Modifier
                                                    .offset(x = (t / 1000f * zoom).dp - 4.dp, y = (TL_ADJ_DP - 16f).dp)
                                                    .size(8.dp)
                                                    .rotate(45f)
                                                    .background(KeyColor)
                                            )
                                        }
                                    }
                                    DropdownMenu(expanded = menuFor == a.id, onDismissRequest = { menuFor = null }) {
                                        DropdownMenuItem(
                                            text = { Text(tr("ডুপ্লিকেট", "Duplicate")) },
                                            onClick = {
                                                menuFor = null
                                                c.duplicateAdj(a.id)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(tr("ডিলিট", "Delete")) },
                                            onClick = {
                                                menuFor = null
                                                c.deleteAdj(a.id)
                                            }
                                        )
                                    }
                                }
                            }

                            // text row
                            seq.texts.forEach { t ->
                                val sel = c.selectedTextId == t.id
                                val startState = rememberUpdatedState(t.startMs)
                                Box(
                                    Modifier
                                        .offset(x = (t.startMs / 1000f * zoom).dp, y = TL_ADJ_DP.dp + 2.dp)
                                        .width((t.durMs / 1000f * zoom).dp)
                                        .height((TL_TEXT_DP - 4f).dp)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(TrackText)
                                        .then(if (sel) Modifier.border(2.dp, Color.White, RoundedCornerShape(5.dp)) else Modifier)
                                        .pointerInput(t.id) {
                                            detectTapGestures(
                                                onTap = { c.selectText(t.id) },
                                                onLongPress = {
                                                    c.selectText(t.id)
                                                    menuFor = t.id
                                                }
                                            )
                                        }
                                        .pointerInput(t.id) {
                                            var base = 0L
                                            var acc = 0f
                                            detectDragGestures(
                                                onDragStart = {
                                                    c.selectText(t.id)
                                                    base = startState.value
                                                    acc = 0f
                                                },
                                                onDrag = { ch, d ->
                                                    ch.consume()
                                                    acc += d.x
                                                    val raw = base + (acc / ppsPx * 1000f).toLong()
                                                    c.moveText(t.id, c.snapStart(raw, t.durMs, t.id))
                                                },
                                                onDragEnd = { c.endLive() },
                                                onDragCancel = { c.endLive() }
                                            )
                                        }
                                ) {
                                    Text(t.text, fontSize = 10.sp, maxLines = 1, color = Color.White, modifier = Modifier.padding(4.dp))
                                    DropdownMenu(expanded = menuFor == t.id, onDismissRequest = { menuFor = null }) {
                                        DropdownMenuItem(
                                            text = { Text(tr("ডুপ্লিকেট", "Duplicate")) },
                                            onClick = {
                                                menuFor = null
                                                c.duplicateText(t.id)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(tr("ডিলিট", "Delete")) },
                                            onClick = {
                                                menuFor = null
                                                c.deleteText(t.id)
                                            }
                                        )
                                    }
                                }
                            }

                            // video row (+ the audio that belongs to each video clip + transition buttons)
                            var acc = 0L
                            seq.video.forEachIndexed { vi, clip ->
                                ClipBlock(
                                    c, clip, acc, TL_ADJ_DP + TL_TEXT_DP, TL_VIDEO_DP, TrackVideo,
                                    true, -1, ppsPx, menuFor, { menuFor = it }, onSpeed, onTrim
                                )
                                val vaY = TL_ADJ_DP + TL_TEXT_DP + TL_VIDEO_DP
                                Box(
                                    Modifier
                                        .offset(x = (acc / 1000f * zoom).dp, y = vaY.dp + 2.dp)
                                        .width((clip.durMs / 1000f * zoom).dp)
                                        .height((TL_VAUDIO_DP - 4f).dp)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(TrackAudio.copy(alpha = 0.65f))
                                        .then(if (c.selectedClipId == clip.id) Modifier.border(2.dp, Color.White, RoundedCornerShape(5.dp)) else Modifier)
                                        .pointerInput(clip.id) {
                                            detectTapGestures(
                                                onTap = { c.selectClip(clip.id) },
                                                onLongPress = {
                                                    c.selectClip(clip.id)
                                                    menuFor = "a:" + clip.id
                                                }
                                            )
                                        }
                                ) {
                                    Text(
                                        "♪ " + clip.name.substringBeforeLast('.'),
                                        fontSize = 9.sp,
                                        maxLines = 1,
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                    )
                                    DropdownMenu(expanded = menuFor == "a:" + clip.id, onDismissRequest = { menuFor = null }) {
                                        DropdownMenuItem(
                                            text = { Text(tr("আনলিংক (অডিও আলাদা করুন)", "Unlink audio")) },
                                            onClick = {
                                                menuFor = null
                                                c.unlinkAudio(clip.id)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(tr("অডিও বন্ধ (Mute)", "Mute audio")) },
                                            onClick = {
                                                menuFor = null
                                                c.muteClipAudio(clip.id)
                                            }
                                        )
                                    }
                                }
                                acc += clip.durMs
                                if (vi < seq.video.size - 1) {
                                    val next = seq.video[vi + 1]
                                    val hasT = next.trans.isNotEmpty()
                                    Box(
                                        Modifier
                                            .offset(x = (acc / 1000f * zoom).dp - 9.dp, y = (TL_ADJ_DP + TL_TEXT_DP + 13f).dp)
                                            .size(18.dp)
                                            .clip(CircleShape)
                                            .background(if (hasT) KeyColor else Panel2)
                                            .border(1.dp, if (hasT) KeyColor else TextLo, CircleShape)
                                            .pointerInput(next.id) {
                                                detectTapGestures(onTap = { menuFor = "t:" + next.id })
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("⇄", fontSize = 10.sp, color = if (hasT) Color.Black else TextLo)
                                        DropdownMenu(expanded = menuFor == "t:" + next.id, onDismissRequest = { menuFor = null }) {
                                            DropdownMenuItem(
                                                text = { Text(tr("ট্রানজিশন নেই", "No transition")) },
                                                onClick = {
                                                    menuFor = null
                                                    c.setTransition(next.id, "")
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Dip to Black") },
                                                onClick = {
                                                    menuFor = null
                                                    c.setTransition(next.id, "black")
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Dip to White") },
                                                onClick = {
                                                    menuFor = null
                                                    c.setTransition(next.id, "white")
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            // audio rows: clips can be dragged left/right and up/down between tracks
                            seq.audio.forEachIndexed { ti, track ->
                                val y = TL_AUDIO_TOP_DP + TL_AUDIO_DP * ti
                                track.forEach { clip ->
                                    ClipBlock(
                                        c, clip, clip.startMs, y, TL_AUDIO_DP,
                                        if (ti % 2 == 0) TrackAudio else TrackAudio2,
                                        false, ti, ppsPx, menuFor, { menuFor = it }, onSpeed, onTrim
                                    )
                                }
                            }

                            if (seq.lengthMs == 0L) {
                                Text(
                                    tr("মিডিয়া ধরে টেনে এখানে ছাড়ুন", "Drag media here"),
                                    color = TextLo,
                                    fontSize = 12.sp,
                                    modifier = Modifier.offset(x = 12.dp, y = (TL_ADJ_DP + TL_TEXT_DP + 12f).dp)
                                )
                            }
                        }
                    }
                }

                // playhead
                Box(
                    Modifier
                        .offset(x = (c.globalMs / 1000f * zoom).dp)
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(PlayheadColor)
                )
            }
        }
    }
}

@Composable
private fun ClipBlock(
    c: EditorController,
    clip: Clip,
    startMs: Long,
    yDp: Float,
    hDp: Float,
    color: Color,
    isVideo: Boolean,
    audioTrack: Int,
    ppsPx: Float,
    menuFor: String?,
    onMenu: (String?) -> Unit,
    onSpeed: (String) -> Unit,
    onTrim: (String) -> Unit
) {
    val zoom = c.zoom
    val sel = c.selectedClipId == clip.id
    val startState = rememberUpdatedState(startMs)
    val trackState = rememberUpdatedState(audioTrack)
    val rowPx = TL_AUDIO_DP * c.density
    Box(
        Modifier
            .offset(x = (startMs / 1000f * zoom).dp, y = yDp.dp + 2.dp)
            .width((clip.durMs / 1000f * zoom).dp)
            .height((hDp - 4f).dp)
            .clip(RoundedCornerShape(5.dp))
            .background(color)
            .then(if (sel) Modifier.border(2.dp, Color.White, RoundedCornerShape(5.dp)) else Modifier)
            .pointerInput(clip.id, c.razor) {
                detectTapGestures(
                    onTap = { o ->
                        if (c.razor) {
                            c.splitClipAt(clip.id, (o.x / size.width * clip.durMs).toLong())
                        } else {
                            c.selectClip(clip.id)
                        }
                    },
                    onLongPress = {
                        c.selectClip(clip.id)
                        onMenu(clip.id)
                    }
                )
            }
            .then(
                if (!isVideo) {
                    Modifier.pointerInput(clip.id) {
                        var base = 0L
                        var accX = 0f
                        var accY = 0f
                        detectDragGestures(
                            onDragStart = {
                                c.selectClip(clip.id)
                                base = startState.value
                                accX = 0f
                                accY = 0f
                            },
                            onDrag = { ch, d ->
                                ch.consume()
                                accX += d.x
                                accY += d.y
                                val raw = base + (accX / ppsPx * 1000f).toLong()
                                val delta = (accY / rowPx).roundToInt()
                                c.moveAudioClip(clip.id, c.snapStart(raw, clip.durMs, clip.id), delta)
                                if (delta != 0) accY -= delta * rowPx
                            },
                            onDragEnd = { c.endLive() },
                            onDragCancel = { c.endLive() }
                        )
                    }
                } else {
                    Modifier
                }
            )
    ) {
        val label = clip.name.substringBeforeLast('.') + if (clip.speed != 1f) "  ${clip.speed}x" else ""
        Text(label, fontSize = 10.sp, maxLines = 1, color = Color.White, modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp))
        if (sel) {
            val times = P.values().flatMap { clip.fx.param(it).kfs }.map { it.tMs }.distinct()
            times.forEach { t ->
                Box(
                    Modifier
                        .offset(x = (t / 1000f * zoom).dp - 4.dp, y = (hDp - 18f).dp)
                        .size(8.dp)
                        .rotate(45f)
                        .background(KeyColor)
                )
            }
        }
        DropdownMenu(expanded = menuFor == clip.id, onDismissRequest = { onMenu(null) }) {
            if (isVideo) {
                DropdownMenuItem(
                    text = { Text(tr("স্পিড / ডিউরেশন", "Speed / Duration")) },
                    onClick = {
                        onMenu(null)
                        onSpeed(clip.id)
                    }
                )
                DropdownMenuItem(
                    text = { Text(tr("আনলিংক অডিও", "Unlink audio")) },
                    onClick = {
                        onMenu(null)
                        c.unlinkAudio(clip.id)
                    }
                )
            }
            DropdownMenuItem(
                text = { Text(tr("ট্রিম", "Trim")) },
                onClick = {
                    onMenu(null)
                    onTrim(clip.id)
                }
            )
            DropdownMenuItem(
                text = { Text(tr("প্লে-হেডে স্প্লিট", "Split at playhead")) },
                onClick = {
                    onMenu(null)
                    c.splitClipAt(clip.id, c.globalMs - startState.value)
                }
            )
            DropdownMenuItem(
                text = { Text(tr("ডুপ্লিকেট", "Duplicate")) },
                onClick = {
                    onMenu(null)
                    c.duplicateClip(clip.id)
                }
            )
            DropdownMenuItem(
                text = { Text(tr("ডিলিট", "Delete")) },
                onClick = {
                    onMenu(null)
                    c.deleteClip(clip.id)
                }
            )
        }
    }
}

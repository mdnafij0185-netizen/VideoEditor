package com.example.videoeditor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

val TL_RULER = 22.dp
val TL_TEXT = 28.dp
val TL_VIDEO = 44.dp
val TL_AUDIO = 34.dp
val TL_GUTTER = 54.dp

/** Distance in dp from the top of the timeline to the top of the second audio row. */
const val TL_AUDIO2_TOP_DP = 128f

@Composable
private fun GutterLabel(label: String, h: Dp) {
    Box(Modifier.height(h).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Text(label, fontSize = 10.sp, color = TextLo, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
fun TimelineView(c: EditorController, height: Dp, onSpeed: (String) -> Unit, onTrim: (String) -> Unit) {
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    var menuFor by remember { mutableStateOf<String?>(null) }
    var viewportW by remember { mutableIntStateOf(0) }
    val seq = c.seq
    val zoom = c.zoom
    val ppsPx = with(density) { zoom.dp.toPx() }
    val lengthMs = maxOf(seq.lengthMs, 1000L)
    val contentPx = maxOf(lengthMs / 1000f * ppsPx + viewportW * 0.6f, viewportW.toFloat())
    val contentW = with(density) { contentPx.toDp() }

    SideEffect {
        c.scrollPx = scroll.value
        c.density = density.density
    }

    LaunchedEffect(c.isPlaying, c.zoom) {
        while (c.isPlaying) {
            val x = (c.globalMs / 1000f * ppsPx).toInt()
            if (x < scroll.value || x > scroll.value + viewportW - 40) {
                scroll.scrollTo((x - viewportW / 3).coerceAtLeast(0))
            }
            delay(120)
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .height(height)
            .background(Panel)
    ) {
        Column(Modifier.width(TL_GUTTER)) {
            Spacer(Modifier.height(TL_RULER))
            GutterLabel("Text", TL_TEXT)
            GutterLabel("Video", TL_VIDEO)
            GutterLabel("Audio 1", TL_AUDIO)
            GutterLabel("Audio 2", TL_AUDIO)
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
                .horizontalScroll(scroll)
        ) {
            Box(Modifier.width(contentW).fillMaxHeight()) {
                // ruler: tap or drag to move the playhead
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(TL_RULER)
                        .background(Color(0xFF1B1E24))
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
                                .height(TL_RULER)
                                .background(Line)
                        )
                        s += step
                    }
                }

                // text row
                seq.texts.forEach { t ->
                    val sel = c.selectedTextId == t.id
                    Box(
                        Modifier
                            .offset(x = (t.startMs / 1000f * zoom).dp, y = TL_RULER + 2.dp)
                            .width((t.durMs / 1000f * zoom).dp)
                            .height(TL_TEXT - 4.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(TrackText)
                            .then(if (sel) Modifier.border(2.dp, Color.White, RoundedCornerShape(5.dp)) else Modifier)
                            .pointerInput(t.id) {
                                detectTapGestures(
                                    onTap = { c.selectText(t.id) },
                                    onLongPress = { menuFor = t.id }
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

                // video row
                var acc = 0L
                seq.video.forEach { clip ->
                    ClipBlock(c, clip, acc, TL_RULER + TL_TEXT, TL_VIDEO, TrackVideo, true, menuFor, { menuFor = it }, onSpeed, onTrim)
                    acc += clip.durMs
                }
                // audio rows
                seq.audio.forEachIndexed { ti, track ->
                    var a = 0L
                    val y = TL_RULER + TL_TEXT + TL_VIDEO + TL_AUDIO * ti
                    track.forEach { clip ->
                        ClipBlock(c, clip, a, y, TL_AUDIO, if (ti == 0) TrackAudio else TrackAudio2, false, menuFor, { menuFor = it }, onSpeed, onTrim)
                        a += clip.durMs
                    }
                }

                if (seq.lengthMs == 0L) {
                    Text(
                        tr("মিডিয়া ধরে টেনে এখানে ছাড়ুন", "Drag media here"),
                        color = TextLo,
                        fontSize = 12.sp,
                        modifier = Modifier.offset(x = 12.dp, y = TL_RULER + TL_TEXT + 12.dp)
                    )
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
    y: Dp,
    h: Dp,
    color: Color,
    isVideo: Boolean,
    menuFor: String?,
    onMenu: (String?) -> Unit,
    onSpeed: (String) -> Unit,
    onTrim: (String) -> Unit
) {
    val zoom = c.zoom
    val sel = c.selectedClipId == clip.id
    Box(
        Modifier
            .offset(x = (startMs / 1000f * zoom).dp, y = y + 2.dp)
            .width((clip.durMs / 1000f * zoom).dp)
            .height(h - 4.dp)
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
    ) {
        val label = clip.name.substringBeforeLast('.') + if (clip.speed != 1f) "  ${clip.speed}x" else ""
        Text(label, fontSize = 10.sp, maxLines = 1, color = Color.White, modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp))
        if (sel && isVideo) {
            val fx = clip.fx
            val times = (fx.scale.kfs + fx.posX.kfs + fx.posY.kfs + fx.blur.kfs).map { it.tMs }.distinct()
            times.forEach { t ->
                Box(
                    Modifier
                        .offset(x = (t / 1000f * zoom).dp - 4.dp, y = h - 18.dp)
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
                    val start = startMs
                    c.splitClipAt(clip.id, c.globalMs - start)
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

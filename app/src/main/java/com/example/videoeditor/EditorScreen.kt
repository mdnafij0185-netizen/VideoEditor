@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.videoeditor

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

enum class Tab { SOURCE, SEQ }
enum class PanelTab { PROJECT, EFFECTS, CONTROLS, EXPORT }

@Composable
fun EditorScreen(initial: Project, store: ProjectStore, device: Device, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val bn = LocalBn.current
    val c = remember { EditorController(ctx, initial, scope, recommended(device)) }
    var tab by remember { mutableStateOf(Tab.SEQ) }
    var panel by remember { mutableStateOf(PanelTab.PROJECT) }
    var showTrim by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            store.save(c.project)
            c.release()
        }
    }
    BackHandler { onBack() }

    LaunchedEffect(c.project) {
        delay(500)
        store.save(c.project)
    }
    val vSig = c.project.video.map { Triple(it.uri, it.inMs, it.outMs) }
    val aSig = c.project.audio.map { Triple(it.uri, it.inMs, it.outMs) }
    LaunchedEffect(vSig) { c.rebuildVideo() }
    LaunchedEffect(aSig) { c.rebuildAudio() }
    LaunchedEffect(Unit) {
        while (true) {
            c.tick()
            delay(50)
        }
    }
    LaunchedEffect(c.exporting) {
        while (c.exporting) {
            c.pollProgress()
            delay(300)
        }
    }
    LaunchedEffect(c.toast) {
        val m = c.toast
        if (m != null) {
            Toast.makeText(ctx, if (bn) m.bn else m.en, Toast.LENGTH_SHORT).show()
            c.toast = null
        }
    }
    LaunchedEffect(tab) {
        if (tab == Tab.SEQ) {
            c.sourcePlayer.pause()
        } else {
            c.player.pause()
            c.audioPlayer.pause()
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch {
            val refs = withContext(Dispatchers.IO) { uris.mapNotNull { importMedia(ctx, it) } }
            if (refs.isNotEmpty()) c.edit { p -> p.copy(media = p.media + refs) }
            if (refs.size < uris.size) {
                c.toast = Msg("কিছু ফাইল ইমপোর্ট করা যায়নি", "Some files could not be imported")
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(BG)
            .systemBarsPadding()
    ) {
        // top bar
        Row(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = null) }
            Text(c.project.name, modifier = Modifier.weight(1f), maxLines = 1, fontSize = 15.sp)
            IconButton(onClick = { c.undo() }, enabled = c.canUndo) {
                Icon(Icons.Filled.Undo, contentDescription = "Undo")
            }
            IconButton(onClick = { c.redo() }, enabled = c.canRedo) {
                Icon(Icons.Filled.Redo, contentDescription = "Redo")
            }
        }

        // source / sequence tabs
        Row(Modifier.padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = tab == Tab.SOURCE,
                onClick = { tab = Tab.SOURCE },
                label = { Text(tr("সোর্স", "Source")) }
            )
            FilterChip(
                selected = tab == Tab.SEQ,
                onClick = {
                    if (tab == Tab.SEQ) showRename = true else tab = Tab.SEQ
                },
                label = { Text(c.project.seqName) }
            )
        }

        PreviewArea(c, tab == Tab.SOURCE, Modifier.weight(1f))
        Transport(c, tab == Tab.SOURCE)

        if (tab == Tab.SOURCE) {
            SourceControls(c)
        } else {
            TimelineArea(c, onTrim = {
                if (c.selectedAnyClip() == null) {
                    c.toast = Msg("আগে একটা ক্লিপ সিলেক্ট করুন", "Select a clip first")
                } else {
                    showTrim = true
                }
            })
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(190.dp)
                .background(PANEL)
        ) {
            when (panel) {
                PanelTab.PROJECT -> ProjectPanel(
                    c,
                    onImport = { types -> picker.launch(types) },
                    onOpenSource = {
                        c.openSource(it)
                        tab = Tab.SOURCE
                    }
                )
                PanelTab.EFFECTS -> EffectsPanel(c)
                PanelTab.CONTROLS -> ControlsPanel(c)
                PanelTab.EXPORT -> ExportPanel(c)
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .background(Color(0xFF111111)),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            PanelButton(Icons.Filled.Folder, tr("প্রজেক্ট", "Project"), panel == PanelTab.PROJECT) {
                panel = PanelTab.PROJECT
            }
            PanelButton(Icons.Filled.AutoFixHigh, tr("ইফেক্ট", "Effects"), panel == PanelTab.EFFECTS) {
                panel = PanelTab.EFFECTS
            }
            PanelButton(Icons.Filled.Tune, tr("কন্ট্রোল", "Controls"), panel == PanelTab.CONTROLS) {
                panel = PanelTab.CONTROLS
            }
            PanelButton(Icons.Filled.SaveAlt, tr("এক্সপোর্ট", "Export"), panel == PanelTab.EXPORT) {
                panel = PanelTab.EXPORT
            }
        }
    }

    val sel = c.selectedAnyClip()
    if (showTrim && sel != null) {
        TrimDialog(
            clip = sel,
            onDismiss = { showTrim = false },
            onApply = { a, b ->
                c.trimSelected(a, b)
                showTrim = false
            }
        )
    }
    if (showRename) {
        var name by remember { mutableStateOf(c.project.seqName) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text(tr("সিকোয়েন্সের নাম", "Sequence name")) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    c.renameSequence(name)
                    showRename = false
                }) { Text(tr("ঠিক আছে", "OK")) }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) { Text(tr("বাতিল", "Cancel")) }
            }
        )
    }
}

@Composable
fun PanelButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val col = if (active) Color(0xFF7FB0FF) else Color(0xFFAAAAAA)
    Column(
        Modifier
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, tint = col, modifier = Modifier.size(22.dp))
        Text(label, color = col, fontSize = 11.sp)
    }
}

@Composable
fun PreviewArea(c: EditorController, sourceMode: Boolean, modifier: Modifier) {
    var qMenu by remember { mutableStateOf(false) }
    val clips = c.project.video
    var sx = 1f
    var sy = 1f
    var tx = 0f
    var ty = 0f
    var blur = 0f
    if (!sourceMode && clips.isNotEmpty()) {
        val (i, off) = locate(clips, c.globalMs.coerceAtMost(c.project.totalMs - 1).coerceAtLeast(0L))
        val fx = clips[i].fx
        val s = if (fx.transformOn) fx.scale.at(off) / 100f else 1f
        sx = if (fx.flipH) -s else s
        sy = s
        if (fx.transformOn) {
            tx = fx.posX.at(off) / 100f
            ty = fx.posY.at(off) / 100f
        }
        if (fx.blurOn) blur = fx.blur.at(off)
    }
    val hasContent = if (sourceMode) c.sourceMedia != null else clips.isNotEmpty()
    val audioOnly = sourceMode && c.sourceMedia?.isAudio == true
    val activePlayer = if (sourceMode) c.sourcePlayer else c.player

    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .background(Color(0xFF1C1C1C))
            .clipToBounds(),
        contentAlignment = Alignment.Center
    ) {
        if (hasContent && !audioOnly) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    (LayoutInflater.from(ctx).inflate(R.layout.player_view, null) as PlayerView).apply {
                        player = activePlayer
                    }
                },
                update = { v ->
                    v.player = activePlayer
                    v.scaleX = sx
                    v.scaleY = sy
                    v.translationX = tx * v.width
                    v.translationY = ty * v.height
                    if (Build.VERSION.SDK_INT >= 31) {
                        if (blur > 0.5f) {
                            val r = blur * 0.4f
                            v.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
                        } else {
                            v.setRenderEffect(null)
                        }
                    }
                }
            )
        } else if (audioOnly) {
            Icon(Icons.Filled.MusicNote, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(48.dp))
        } else {
            Text(
                if (sourceMode) tr("প্রজেক্ট প্যানেলে ভিডিওতে ডাবল ট্যাপ করুন", "Double tap a clip in the Project panel")
                else tr("প্রজেক্ট প্যানেল থেকে ভিডিও যোগ করুন", "Add clips from the Project panel"),
                color = Color.Gray,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(16.dp)
            )
        }
        if (!sourceMode) {
            Box(Modifier.align(Alignment.BottomEnd)) {
                Text(
                    c.quality.label + " ▾",
                    fontSize = 12.sp,
                    modifier = Modifier
                        .padding(6.dp)
                        .background(Color(0xCC262626), RoundedCornerShape(8.dp))
                        .clickable { qMenu = true }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                )
                DropdownMenu(expanded = qMenu, onDismissRequest = { qMenu = false }) {
                    Quality.values().forEach { q ->
                        DropdownMenuItem(
                            text = { Text(q.label) },
                            onClick = {
                                c.setQuality(q)
                                qMenu = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun Transport(c: EditorController, source: Boolean) {
    val playing = if (source) c.srcPlaying else c.isPlaying
    val pos = if (source) c.srcPos else c.globalMs
    val total = if (source) (c.sourceMedia?.durMs ?: 0L) else c.project.totalMs
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(fmt(pos) + " / " + fmt(total), fontSize = 12.sp, color = Color.LightGray, modifier = Modifier.width(90.dp))
        Spacer(Modifier.weight(1f))
        IconButton(onClick = {
            if (source) c.sourcePlayer.seekTo((c.sourcePlayer.currentPosition - 10000L).coerceAtLeast(0L))
            else c.seekGlobal(c.globalMs - 10000L)
        }) { Icon(Icons.Filled.Replay10, contentDescription = null) }
        FilledIconButton(
            onClick = { if (source) c.toggleSourcePlay() else c.togglePlay() },
            modifier = Modifier.size(46.dp)
        ) {
            Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = null)
        }
        IconButton(onClick = {
            if (source) c.sourcePlayer.seekTo(c.sourcePlayer.currentPosition + 10000L)
            else c.seekGlobal(c.globalMs + 10000L)
        }) { Icon(Icons.Filled.Forward10, contentDescription = null) }
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.width(90.dp))
    }
}

@Composable
fun Strip(c: EditorController, clips: List<Clip>, timelineMs: Long, base: Color, label: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(Color(0xFF1A1A1A), RoundedCornerShape(6.dp))
    ) {
        clips.forEachIndexed { i, clip ->
            val sel = c.selectedClipId == clip.id
            Box(
                Modifier
                    .weight(clip.durMs.toFloat().coerceAtLeast(1f))
                    .fillMaxHeight()
                    .padding(1.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(base)
                    .then(if (sel) Modifier.border(2.dp, Color.White, RoundedCornerShape(4.dp)) else Modifier)
                    .clickable { c.selectedClipId = clip.id }
            ) {
                Text(label + (i + 1), fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(4.dp))
            }
        }
        val rest = timelineMs - clips.sumOf { it.durMs }
        if (rest > 0L) Spacer(Modifier.weight(rest.toFloat()))
    }
}

@Composable
fun TimelineArea(c: EditorController, onTrim: () -> Unit) {
    val total = c.project.totalMs
    val totalA = c.project.audio.sumOf { it.durMs }
    val timeline = maxOf(total, totalA)
    Column(Modifier.padding(horizontal = 10.dp)) {
        if (timeline == 0L) {
            Text(
                tr("টাইমলাইন খালি", "Timeline is empty"),
                color = Color.Gray,
                fontSize = 12.sp,
                modifier = Modifier
                    .height(72.dp)
                    .padding(top = 8.dp)
            )
        } else {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val w = maxWidth
                Column {
                    Strip(c, c.project.video, timeline, Color(0xFF3A5F9E), "V")
                    Spacer(Modifier.height(4.dp))
                    Strip(c, c.project.audio, timeline, Color(0xFF2F6B4F), "A")
                }
                val frac = (c.globalMs.toFloat() / timeline.toFloat()).coerceIn(0f, 1f)
                Box(
                    Modifier
                        .offset(x = w * frac)
                        .width(2.dp)
                        .height(68.dp)
                        .background(Color.White)
                )
            }
        }
        Slider(
            value = if (total > 0L) (c.globalMs.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f,
            onValueChange = {
                c.scrubbing = true
                c.seekGlobal((it * total).toLong())
            },
            onValueChangeFinished = { c.scrubbing = false },
            enabled = total > 0L,
            modifier = Modifier.height(32.dp)
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            ToolButton(Icons.Filled.Crop, tr("ট্রিম", "Trim")) { onTrim() }
            ToolButton(Icons.Filled.ContentCut, tr("স্প্লিট", "Split")) { c.splitAtPlayhead() }
            ToolButton(Icons.Filled.ContentCopy, tr("ডুপ্লিকেট", "Duplicate")) { c.duplicateSelected() }
            ToolButton(Icons.Filled.Delete, tr("ডিলিট", "Delete")) { c.deleteSelected() }
        }
    }
}

@Composable
fun ToolButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Text(label, fontSize = 11.sp)
    }
}

@Composable
fun SourceControls(c: EditorController) {
    val m = c.sourceMedia
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
    ) {
        if (m == null) {
            Text(
                tr("কোনো সোর্স খোলা নেই", "No source opened"),
                color = Color.Gray,
                fontSize = 12.sp,
                modifier = Modifier
                    .height(100.dp)
                    .padding(top = 12.dp)
            )
        } else {
            val dur = m.durMs.toFloat()
            Slider(
                value = (c.srcPos.toFloat() / dur).coerceIn(0f, 1f),
                onValueChange = {
                    c.srcPos = (it * dur).toLong()
                    c.sourcePlayer.seekTo(c.srcPos)
                },
                modifier = Modifier.height(32.dp)
            )
            RangeSlider(
                value = c.srcIn.toFloat()..c.srcOut.toFloat(),
                onValueChange = { r ->
                    c.srcIn = r.start.toLong()
                    c.srcOut = r.endInclusive.toLong()
                },
                valueRange = 0f..dur,
                modifier = Modifier.height(32.dp)
            )
            Text(
                "In " + fmt(c.srcIn) + "   Out " + fmt(c.srcOut),
                fontSize = 12.sp,
                color = Color.LightGray
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(onClick = {
                    c.srcIn = minOf(c.srcPos, c.srcOut - 200L).coerceAtLeast(0L)
                }) { Text(tr("শুরু ঠিক", "Set In"), fontSize = 12.sp) }
                OutlinedButton(onClick = {
                    c.srcOut = maxOf(c.srcPos, c.srcIn + 200L).coerceAtMost(m.durMs)
                }) { Text(tr("শেষ ঠিক", "Set Out"), fontSize = 12.sp) }
                Button(onClick = { c.addSourceToTimeline() }) {
                    Text(tr("টাইমলাইনে যোগ", "Add to Timeline"), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun TrimDialog(clip: Clip, onDismiss: () -> Unit, onApply: (Long, Long) -> Unit) {
    var range by remember { mutableStateOf(clip.inMs.toFloat()..clip.outMs.toFloat()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("ট্রিম", "Trim")) },
        text = {
            Column {
                RangeSlider(
                    value = range,
                    onValueChange = { range = it },
                    valueRange = 0f..clip.srcDurMs.toFloat()
                )
                Text(fmt(range.start.toLong()) + " - " + fmt(range.endInclusive.toLong()))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val a = range.start.toLong()
                val b = range.endInclusive.toLong()
                if (b - a >= 200L) onApply(a, b)
            }) { Text(tr("ঠিক আছে", "OK")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("বাতিল", "Cancel")) } }
    )
}

@Composable
fun ProjectPanel(c: EditorController, onImport: (Array<String>) -> Unit, onOpenSource: (MediaRef) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Text(
            tr(
                "ফাঁকা জায়গায় ডাবল ট্যাপ: ফোল্ডার · টিপে ধরুন: ইমপোর্ট · ক্লিপে ডাবল ট্যাপ: সোর্সে খুলুন",
                "Double tap empty area: browse · Hold: import · Double tap a clip: open in Source"
            ),
            color = Color.Gray,
            fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { onImport(arrayOf("video/*", "audio/*")) },
                        onLongPress = { menu = true }
                    )
                }
        ) {
            if (c.project.media.isEmpty()) {
                Text(
                    tr("এখানে ডাবল ট্যাপ করে ভিডিও/অডিও আনুন", "Double tap here to import video/audio"),
                    color = Color.Gray,
                    fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(84.dp),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(c.project.media, key = { it.id }) { m ->
                    MediaCell(c, m, onOpenSource)
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(tr("ভিডিও ইমপোর্ট", "Import Video")) },
                    onClick = {
                        menu = false
                        onImport(arrayOf("video/*"))
                    }
                )
                DropdownMenuItem(
                    text = { Text(tr("অডিও ইমপোর্ট", "Import Audio")) },
                    onClick = {
                        menu = false
                        onImport(arrayOf("audio/*"))
                    }
                )
            }
        }
    }
}

@Composable
fun MediaCell(c: EditorController, m: MediaRef, onOpenSource: (MediaRef) -> Unit) {
    var remove by remember { mutableStateOf(false) }
    Box(
        Modifier
            .height(56.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (m.isAudio) Color(0xFF2F6B4F) else Color(0xFF2A2A2A))
            .pointerInput(m.id) {
                detectTapGestures(
                    onDoubleTap = { onOpenSource(m) },
                    onLongPress = { remove = true }
                )
            }
    ) {
        if (m.isAudio) {
            Icon(
                Icons.Filled.MusicNote,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(22.dp)
            )
        } else {
            Thumb(m.uri, Modifier.fillMaxSize())
        }
        Text(
            fmt(m.durMs),
            fontSize = 10.sp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .background(Color(0x99000000))
                .padding(horizontal = 3.dp)
        )
        DropdownMenu(expanded = remove, onDismissRequest = { remove = false }) {
            DropdownMenuItem(
                text = { Text(tr("প্রজেক্ট থেকে সরান", "Remove from project")) },
                onClick = {
                    remove = false
                    c.edit { p -> p.copy(media = p.media.filter { it.id != m.id }) }
                }
            )
        }
    }
}

@Composable
fun EffectsPanel(c: EditorController) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(8.dp)
    ) {
        Text(
            tr(
                "ক্লিপ সিলেক্ট করে ইফেক্টে ডাবল ট্যাপ করুন (অথবা + চাপুন)",
                "Select a clip, then double tap an effect (or press +)"
            ),
            color = Color.Gray,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(4.dp))
        EffectRow(c, "Transform", tr("পজিশন, স্কেল", "Position, scale"), Eff.TRANSFORM)
        EffectRow(c, "Horizontal Flip", tr("ডান-বাম উল্টানো", "Mirror left-right"), Eff.FLIP)
        EffectRow(c, "Gaussian Blur", tr("ঝাপসা করা", "Blur the picture"), Eff.BLUR)
        EffectRow(c, "Denoise", tr("অডিওর হিস/গুনগুন কমানো", "Reduce hiss/hum in audio"), Eff.DENOISE)
    }
}

@Composable
fun EffectRow(c: EditorController, title: String, sub: String, e: Eff) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .background(Color(0xFF1F1F1F), RoundedCornerShape(8.dp))
            .pointerInput(e) { detectTapGestures(onDoubleTap = { c.addEffect(e) }) }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp)
            Text(sub, fontSize = 10.sp, color = Color.Gray)
        }
        IconButton(onClick = { c.addEffect(e) }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Add, contentDescription = null)
        }
    }
}

@Composable
fun KeyframeButton(active: Boolean, animated: Boolean, onClick: () -> Unit) {
    val col = if (active) Color(0xFFFFC107) else if (animated) Color(0xFF7FB0FF) else Color(0xFF888888)
    Box(
        Modifier
            .size(32.dp)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(11.dp)
                .rotate(45f)
                .background(if (active) col else Color.Transparent)
                .border(1.5.dp, col)
        )
    }
}

@Composable
fun ParamRow(
    c: EditorController,
    label: String,
    param: Param,
    range: ClosedFloatingPointRange<Float>,
    unit: String,
    t: Long,
    which: P
) {
    val v = param.at(t).coerceIn(range.start, range.endInclusive)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(34.dp)) {
        Text(label, fontSize = 12.sp, modifier = Modifier.width(76.dp))
        Slider(
            value = v,
            onValueChange = { c.setParam(which, it, t) },
            onValueChangeFinished = { c.endLive() },
            valueRange = range,
            modifier = Modifier.weight(1f)
        )
        Text(v.roundToInt().toString() + unit, fontSize = 11.sp, modifier = Modifier.width(48.dp), textAlign = TextAlign.End)
        KeyframeButton(param.hasKeyframeAt(t), param.kfs.isNotEmpty()) { c.toggleKeyframe(which, t) }
    }
}

@Composable
fun SectionHeader(title: String, onRemove: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(title, fontSize = 13.sp, modifier = Modifier.weight(1f))
        IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
fun ControlsPanel(c: EditorController) {
    val clip = c.selectedVideoClip()
    if (clip == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                tr("টাইমলাইনে একটা ভিডিও ক্লিপ সিলেক্ট করুন", "Select a video clip on the timeline"),
                color = Color.Gray,
                fontSize = 13.sp
            )
        }
    } else {
        val fx = clip.fx
        val t = c.localTime(clip)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            if (!fx.transformOn && !fx.flipH && !fx.blurOn && !fx.denoiseOn) {
                Text(
                    tr("এই ক্লিপে কোনো ইফেক্ট নেই। ইফেক্ট প্যানেল থেকে যোগ করুন।", "No effects on this clip. Add one from the Effects panel."),
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }
            if (fx.transformOn) {
                SectionHeader("Transform") { c.removeEffect(Eff.TRANSFORM) }
                ParamRow(c, "Scale", fx.scale, 10f..400f, "%", t, P.SCALE)
                ParamRow(c, "Position X", fx.posX, -100f..100f, "", t, P.POSX)
                ParamRow(c, "Position Y", fx.posY, -100f..100f, "", t, P.POSY)
            }
            if (fx.flipH) {
                SectionHeader("Horizontal Flip") { c.removeEffect(Eff.FLIP) }
            }
            if (fx.blurOn) {
                SectionHeader("Gaussian Blur") { c.removeEffect(Eff.BLUR) }
                ParamRow(c, "Blurriness", fx.blur, 0f..60f, "", t, P.BLUR)
            }
            if (fx.denoiseOn) {
                SectionHeader("Denoise") { c.removeEffect(Eff.DENOISE) }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(34.dp)) {
                    Text("Amount", fontSize = 12.sp, modifier = Modifier.width(76.dp))
                    Slider(
                        value = fx.denoise,
                        onValueChange = { c.setDenoise(it) },
                        onValueChangeFinished = { c.endLive() },
                        valueRange = 0f..100f,
                        modifier = Modifier.weight(1f)
                    )
                    Text(fx.denoise.roundToInt().toString(), fontSize = 11.sp, modifier = Modifier.width(48.dp), textAlign = TextAlign.End)
                }
            }
        }
    }
}

@Composable
fun ExportPanel(c: EditorController) {
    var res by remember { mutableStateOf(0) }
    val labels = listOf("Original", "1080p", "720p", "480p")
    val heights = listOf<Int?>(null, 1080, 720, 480)
    val bn = LocalBn.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(10.dp)
    ) {
        Text(tr("এক্সপোর্ট রেজোলিউশন", "Export resolution"), fontSize = 12.sp, color = Color.Gray)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            labels.forEachIndexed { i, l ->
                FilterChip(selected = res == i, onClick = { res = i }, label = { Text(l, fontSize = 12.sp) })
            }
        }
        Spacer(Modifier.height(6.dp))
        if (c.exporting) {
            LinearProgressIndicator(progress = { c.exportProgress }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = { c.cancelExport() }) { Text(tr("বাতিল", "Cancel")) }
        } else {
            Button(onClick = { c.startExport(heights[res]) }) {
                Icon(Icons.Filled.SaveAlt, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(tr("এক্সপোর্ট করুন", "Export"))
            }
        }
        val m = c.exportMsg
        if (m != null) {
            Spacer(Modifier.height(6.dp))
            Text(if (bn) m.bn else m.en, fontSize = 12.sp)
        }
    }
}

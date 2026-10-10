@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.videoeditor

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.LayoutInflater
import android.view.TextureView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

enum class Tab { SOURCE, SEQ }
enum class PanelTab { PROJECT, EFFECTS, CONTROLS, TEXT, EXPORT }

@Composable
fun EditorScreen(
    initial: Project,
    store: ProjectStore,
    startQuality: Quality,
    tlHeight: Dp,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val bn = LocalBn.current
    val c = remember { EditorController(ctx, initial, scope, startQuality) }
    var tab by remember { mutableStateOf(Tab.SEQ) }
    var panel by remember { mutableStateOf(PanelTab.PROJECT) }
    var panelOpen by remember { mutableStateOf(true) }
    var trimId by remember { mutableStateOf<String?>(null) }
    var speedId by remember { mutableStateOf<String?>(null) }
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
    val seq = c.seq
    val vSig = seq.video.map { Triple(it.uri, it.inMs, it.outMs) }
    val a0Sig = (seq.audio.getOrNull(0) ?: emptyList()).map { Triple(it.uri, it.inMs, it.outMs) }
    val a1Sig = (seq.audio.getOrNull(1) ?: emptyList()).map { Triple(it.uri, it.inMs, it.outMs) }
    LaunchedEffect(seq.id, vSig) { c.rebuildVideo() }
    LaunchedEffect(seq.id, a0Sig) { c.rebuildAudio(0) }
    LaunchedEffect(seq.id, a1Sig) { c.rebuildAudio(1) }
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
        if (tab == Tab.SEQ) c.sourcePlayer.pause() else c.pauseAll()
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

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Bg)
                .systemBarsPadding()
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = null) }
                Text(c.project.name, modifier = Modifier.weight(1f), maxLines = 1, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                IconButton(onClick = { c.undo() }, enabled = c.canUndo, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Filled.Undo, contentDescription = "Undo", modifier = Modifier.size(26.dp))
                }
                IconButton(onClick = { c.redo() }, enabled = c.canRedo, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Filled.Redo, contentDescription = "Redo", modifier = Modifier.size(26.dp))
                }
            }

            SeqStrip(c, tab, onTab = { tab = it }, onRename = { showRename = true })
            PreviewArea(c, tab == Tab.SOURCE, Modifier.weight(1f))
            TransportRow(c, tab == Tab.SOURCE)

            if (tab == Tab.SOURCE) {
                SourceControls(c)
            } else {
                TimelineView(c, tlHeight, onSpeed = { speedId = it }, onTrim = { trimId = it })
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    ToolButton(Icons.Filled.Crop, tr("ট্রিম", "Trim")) {
                        val id = c.selectedClipId
                        if (id == null) c.toast = Msg("আগে একটা ক্লিপ সিলেক্ট করুন", "Select a clip first") else trimId = id
                    }
                    ToolButton(Icons.Filled.VerticalSplit, tr("স্প্লিট", "Split")) { c.splitAtPlayhead() }
                    ToolButton(Icons.Filled.ContentCopy, tr("ডুপ্লিকেট", "Duplicate")) { c.duplicateSelected() }
                    ToolButton(Icons.Filled.Delete, tr("ডিলিট", "Delete")) { c.deleteSelected() }
                }
            }

            if (panelOpen) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                        .background(Panel)
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
                        PanelTab.TEXT -> TextPanel(c)
                        PanelTab.EXPORT -> ExportPanel(c)
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .background(Color(0xFF101216)),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                fun pick(p: PanelTab) {
                    if (panel == p) panelOpen = !panelOpen else {
                        panel = p
                        panelOpen = true
                    }
                }
                PanelButton(Icons.Filled.Folder, tr("প্রজেক্ট", "Project"), panel == PanelTab.PROJECT && panelOpen) { pick(PanelTab.PROJECT) }
                PanelButton(Icons.Filled.AutoFixHigh, tr("ইফেক্ট", "Effects"), panel == PanelTab.EFFECTS && panelOpen) { pick(PanelTab.EFFECTS) }
                PanelButton(Icons.Filled.Tune, tr("কন্ট্রোল", "Controls"), panel == PanelTab.CONTROLS && panelOpen) { pick(PanelTab.CONTROLS) }
                PanelButton(Icons.Filled.TextFields, tr("টেক্সট", "Text"), panel == PanelTab.TEXT && panelOpen) { pick(PanelTab.TEXT) }
                PanelButton(Icons.Filled.SaveAlt, tr("এক্সপোর্ট", "Export"), panel == PanelTab.EXPORT && panelOpen) { pick(PanelTab.EXPORT) }
            }
        }

        val d = c.drag
        if (d != null) {
            Box(
                Modifier
                    .offset { IntOffset(d.pos.x.roundToInt() - 40, d.pos.y.roundToInt() - 70) }
                    .size(80.dp, 52.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Panel2)
                    .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (d.media.isAudio) {
                    Icon(Icons.Filled.MusicNote, contentDescription = null)
                } else {
                    Thumb(d.media.uri, Modifier.fillMaxSize())
                }
            }
        }
    }

    val tid = trimId
    val trimClip = if (tid != null) c.findClip(tid) else null
    if (trimClip != null) {
        TrimDialog(
            clip = trimClip,
            onDismiss = { trimId = null },
            onApply = { a, b ->
                c.selectClip(trimClip.id)
                c.trimSelected(a, b)
                trimId = null
            }
        )
    }
    val sid = speedId
    val speedClip = if (sid != null) c.findClip(sid) else null
    if (speedClip != null) {
        SpeedDialog(
            clip = speedClip,
            onDismiss = { speedId = null },
            onApply = {
                c.setSpeed(speedClip.id, it)
                speedId = null
            }
        )
    }
    if (showRename) {
        var name by remember { mutableStateOf(c.seq.name) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text(tr("সিকোয়েন্সের নাম", "Sequence name")) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    c.renameSeq(name)
                    showRename = false
                }) { Text(tr("ঠিক আছে", "OK")) }
            },
            dismissButton = {
                Row {
                    if (c.project.seqs.size > 1) {
                        TextButton(onClick = {
                            c.deleteSeq()
                            showRename = false
                        }) { Text(tr("সিকোয়েন্স ডিলিট", "Delete sequence")) }
                    }
                    TextButton(onClick = { showRename = false }) { Text(tr("বাতিল", "Cancel")) }
                }
            }
        )
    }
}

@Composable
fun PanelButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val col = if (active) MaterialTheme.colorScheme.primary else TextLo
    Column(
        Modifier
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, tint = col, modifier = Modifier.size(22.dp))
        Text(label, color = col, fontSize = 11.sp)
    }
}

@Composable
fun ToolButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Text(label, fontSize = 10.sp, color = TextLo)
    }
}

@Composable
fun SeqStrip(c: EditorController, tab: Tab, onTab: (Tab) -> Unit, onRename: () -> Unit) {
    var plus by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = tab == Tab.SOURCE,
            onClick = { onTab(Tab.SOURCE) },
            label = { Text(tr("সোর্স", "Source")) }
        )
        c.project.seqs.forEachIndexed { i, s ->
            FilterChip(
                selected = tab == Tab.SEQ && c.activeIdx == i,
                onClick = {
                    if (tab == Tab.SEQ && c.activeIdx == i) {
                        onRename()
                    } else {
                        c.switchSeq(i)
                        onTab(Tab.SEQ)
                    }
                },
                label = { Text(s.name + " · " + s.ratio) }
            )
        }
        Box {
            IconButton(onClick = { plus = true }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Add, contentDescription = null)
            }
            DropdownMenu(expanded = plus, onDismissRequest = { plus = false }) {
                listOf("9:16", "16:9", "4:3").forEach { r ->
                    DropdownMenuItem(
                        text = { Text(tr("নতুন সিকোয়েন্স ", "New sequence ") + r) },
                        onClick = {
                            plus = false
                            c.addSequence(r)
                            onTab(Tab.SEQ)
                        }
                    )
                }
            }
        }
    }
}

private fun colorMatrixFor(fx: Fx): ColorMatrix? {
    val adj = fx.colorOn && (fx.bright != 0f || fx.contrast != 0f || fx.sat != 0f)
    if (!fx.bwOn && !adj) return null
    val m = ColorMatrix()
    if (adj) {
        val s = ColorMatrix()
        s.setSaturation((1f + fx.sat / 100f).coerceAtLeast(0f))
        m.postConcat(s)
        val k = 1f + fx.contrast / 100f
        val b = fx.bright / 100f * 255f + 128f * (1f - k)
        m.postConcat(
            ColorMatrix(
                floatArrayOf(
                    k, 0f, 0f, 0f, b,
                    0f, k, 0f, 0f, b,
                    0f, 0f, k, 0f, b,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
    }
    if (fx.bwOn) {
        val g = ColorMatrix()
        g.setSaturation(0f)
        m.postConcat(g)
    }
    return m
}

@Composable
fun PreviewArea(c: EditorController, sourceMode: Boolean, modifier: Modifier) {
    val seq = c.seq
    val density = LocalDensity.current
    var qMenu by remember { mutableStateOf(false) }
    val clips = seq.video

    var geoV = Geo(1f, 1f, 0f, 0f)
    var blurV = 0f
    var cmV: ColorMatrix? = null
    var keyV = "-"
    if (!sourceMode && clips.isNotEmpty()) {
        val (i, off) = locate(clips, c.globalMs.coerceIn(0L, (seq.totalMs - 1).coerceAtLeast(0L)))
        val fx = clips[i].fx
        geoV = fx.geometry(off)
        if (fx.blurOn) blurV = fx.blur.at(off)
        cmV = colorMatrixFor(fx)
        keyV = "${fx.bwOn}|${fx.colorOn}|${fx.bright}|${fx.contrast}|${fx.sat}|${(blurV * 2).toInt()}"
    }
    val geo = geoV
    val blur = blurV
    val cm = cmV
    val key = keyV
    val hasContent = if (sourceMode) c.sourceMedia != null else clips.isNotEmpty()
    val audioOnly = sourceMode && c.sourceMedia?.isAudio == true
    val activePlayer = if (sourceMode) c.sourcePlayer else c.player

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        val boxMod = if (sourceMode) {
            Modifier.fillMaxSize()
        } else {
            Modifier.aspectRatio(seq.aspect, matchHeightConstraintsFirst = (maxWidth / maxHeight) > seq.aspect)
        }
        Box(
            boxMod
                .background(Color.Black)
                .clipToBounds()
                .pointerInput(sourceMode) {
                    if (!sourceMode) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            c.previewGesture(zoom, pan.x / size.width * 100f, pan.y / size.height * 100f)
                        }
                    }
                },
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
                        v.scaleX = geo.sx
                        v.scaleY = geo.sy
                        v.translationX = geo.tx / 2f * v.width
                        v.translationY = -geo.ty / 2f * v.height
                        if (v.tag != key) {
                            v.tag = key
                            val tv = v.videoSurfaceView as? TextureView
                            if (cm != null) {
                                val paint = Paint()
                                paint.colorFilter = ColorMatrixColorFilter(cm)
                                tv?.setLayerPaint(paint)
                            } else {
                                tv?.setLayerPaint(null)
                            }
                            if (Build.VERSION.SDK_INT >= 31) {
                                if (blur > 0.5f) {
                                    val r = blur * 0.4f
                                    v.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
                                } else {
                                    v.setRenderEffect(null)
                                }
                            }
                        }
                    }
                )
            } else if (audioOnly) {
                Icon(Icons.Filled.MusicNote, contentDescription = null, tint = TextLo, modifier = Modifier.size(48.dp))
            } else {
                Text(
                    if (sourceMode) tr("প্রজেক্ট প্যানেলে ক্লিপে ডাবল ট্যাপ করুন", "Double tap a clip in the Project panel")
                    else tr("প্রজেক্ট প্যানেল থেকে ভিডিও টেনে টাইমলাইনে দিন", "Drag a clip from the Project panel to the timeline"),
                    color = TextLo,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(16.dp)
                )
            }

            if (!sourceMode) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val wPx = with(density) { maxWidth.toPx() }
                    val hPx = with(density) { maxHeight.toPx() }
                    seq.texts.forEach { t ->
                        val inRange = c.globalMs >= t.startMs && c.globalMs < t.startMs + t.durMs
                        val sel = c.selectedTextId == t.id
                        if (inRange || sel) {
                            Text(
                                t.text,
                                color = Color(t.color),
                                fontSize = with(density) { (t.sizePct / 100f * hPx).toSp() },
                                fontFamily = Fonts.family(t.font),
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .offset {
                                        IntOffset(
                                            (t.posX / 100f * wPx / 2f).roundToInt(),
                                            (t.posY / 100f * hPx / 2f).roundToInt()
                                        )
                                    }
                                    .alpha(if (inRange) 1f else 0.35f)
                                    .then(if (sel) Modifier.border(1.dp, Color.White.copy(alpha = 0.7f)) else Modifier)
                            )
                        }
                    }
                }
                Box(Modifier.align(Alignment.BottomEnd)) {
                    Text(
                        c.quality.label + " ▾",
                        fontSize = 12.sp,
                        modifier = Modifier
                            .padding(6.dp)
                            .background(Color(0xCC1F2229), RoundedCornerShape(8.dp))
                            .clickable { qMenu = true }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                    DropdownMenu(expanded = qMenu, onDismissRequest = { qMenu = false }) {
                        Quality.values().forEach { q ->
                            DropdownMenuItem(
                                text = { Text(q.label) },
                                onClick = {
                                    c.changeQuality(q)
                                    qMenu = false
                                }
                            )
                        }
                    }
                }
                Text(
                    seq.ratio,
                    fontSize = 10.sp,
                    color = TextLo,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                )
            }
        }
    }
}

@Composable
fun TransportRow(c: EditorController, source: Boolean) {
    val playing = if (source) c.srcPlaying else c.isPlaying
    val pos = if (source) c.srcPos else c.globalMs
    val total = if (source) (c.sourceMedia?.durMs ?: 0L) else c.seq.totalMs
    Row(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(fmt(pos) + " / " + fmt(total), fontSize = 11.sp, color = TextLo, modifier = Modifier.width(78.dp))
        Spacer(Modifier.weight(1f))
        IconButton(onClick = {
            if (source) c.sourcePlayer.seekTo((c.sourcePlayer.currentPosition - 10000L).coerceAtLeast(0L))
            else c.seekGlobal(c.globalMs - 10000L)
        }, modifier = Modifier.size(38.dp)) { Icon(Icons.Filled.Replay10, contentDescription = null) }
        FilledIconButton(
            onClick = { if (source) c.toggleSourcePlay() else c.togglePlay() },
            modifier = Modifier.size(44.dp)
        ) {
            Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = null)
        }
        IconButton(onClick = {
            if (source) c.sourcePlayer.seekTo(c.sourcePlayer.currentPosition + 10000L)
            else c.seekGlobal(c.globalMs + 10000L)
        }, modifier = Modifier.size(38.dp)) { Icon(Icons.Filled.Forward10, contentDescription = null) }
        Spacer(Modifier.weight(1f))
        if (!source) {
            IconButton(onClick = { c.razor = !c.razor }, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Filled.ContentCut,
                    contentDescription = "Razor",
                    tint = if (c.razor) MaterialTheme.colorScheme.primary else TextLo
                )
            }
            IconButton(onClick = { c.zoom = (c.zoom / 1.4f).coerceAtLeast(12f) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.ZoomOut, contentDescription = null, tint = TextLo)
            }
            IconButton(onClick = { c.zoom = (c.zoom * 1.4f).coerceAtMost(300f) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.ZoomIn, contentDescription = null, tint = TextLo)
            }
        } else {
            Spacer(Modifier.width(78.dp))
        }
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
                color = TextLo,
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
            Text("In " + fmt(c.srcIn) + "   Out " + fmt(c.srcOut), fontSize = 12.sp, color = TextLo)
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
fun SpeedDialog(clip: Clip, onDismiss: () -> Unit, onApply: (Float) -> Unit) {
    var sp by remember { mutableFloatStateOf(clip.speed) }
    val newDur = ((clip.outMs - clip.inMs) / sp).toLong()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("স্পিড / ডিউরেশন", "Speed / Duration")) },
        text = {
            Column {
                Text(String.format(Locale.US, "%.2fx", sp), fontSize = 18.sp)
                Slider(value = sp, onValueChange = { sp = it }, valueRange = 0.25f..4f)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(0.5f, 1f, 1.5f, 2f, 3f).forEach { p ->
                        FilterChip(
                            selected = abs(sp - p) < 0.01f,
                            onClick = { sp = p },
                            label = { Text(p.toString() + "x", fontSize = 12.sp) }
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(tr("নতুন ডিউরেশন: ", "New duration: ") + fmt(newDur))
            }
        },
        confirmButton = { TextButton(onClick = { onApply(sp) }) { Text(tr("ঠিক আছে", "OK")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("বাতিল", "Cancel")) } }
    )
}

@Composable
fun ProjectPanel(c: EditorController, onImport: (Array<String>) -> Unit, onOpenSource: (MediaRef) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Text(
            tr(
                "ফাঁকা জায়গায় ডাবল ট্যাপ: ফোল্ডার · টিপে ধরুন: মেনু · ক্লিপে ডাবল ট্যাপ: সোর্স · ক্লিপ ধরে টাইমলাইনে টানুন",
                "Double tap empty area: browse · Hold: menu · Double tap clip: Source · Hold & drag clip to timeline"
            ),
            color = TextLo,
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
                    color = TextLo,
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
                listOf("9:16", "16:9", "4:3").forEach { r ->
                    DropdownMenuItem(
                        text = { Text(tr("সিকোয়েন্স তৈরি ", "Create Sequence ") + r) },
                        onClick = {
                            menu = false
                            c.addSequence(r)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun MediaCell(c: EditorController, m: MediaRef, onOpenSource: (MediaRef) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var cellPos by remember { mutableStateOf(Offset.Zero) }
    var moved by remember { mutableFloatStateOf(0f) }
    Box(
        Modifier
            .height(56.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (m.isAudio) TrackAudio else Panel2)
            .onGloballyPositioned { cellPos = it.positionInRoot() }
            .pointerInput(m.id) {
                detectTapGestures(onDoubleTap = { onOpenSource(m) })
            }
            .pointerInput(m.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { o ->
                        moved = 0f
                        c.drag = DragState(m, cellPos + o)
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        moved += amount.getDistance()
                        val cur = c.drag
                        if (cur != null) c.drag = DragState(m, cur.pos + amount)
                    },
                    onDragEnd = {
                        val d = c.drag
                        c.drag = null
                        if (d != null) {
                            if (moved < 16f) menu = true else c.dropMedia(m, d.pos)
                        }
                    },
                    onDragCancel = { c.drag = null }
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
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(tr("টাইমলাইনে যোগ করুন", "Add to Timeline")) },
                onClick = {
                    menu = false
                    c.addMedia(m)
                }
            )
            DropdownMenuItem(
                text = { Text(tr("সোর্সে খুলুন", "Open in Source")) },
                onClick = {
                    menu = false
                    onOpenSource(m)
                }
            )
            DropdownMenuItem(
                text = { Text(tr("প্রজেক্ট থেকে সরান", "Remove from project")) },
                onClick = {
                    menu = false
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
                "টাইমলাইনে ক্লিপ সিলেক্ট করে ইফেক্টে ডাবল ট্যাপ করুন (অথবা + চাপুন)",
                "Select a clip, then double tap an effect (or press +)"
            ),
            color = TextLo,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(4.dp))
        EffectRow(c, "Transform", tr("পজিশন, স্কেল", "Position, scale"), Eff.TRANSFORM)
        EffectRow(c, "Horizontal Flip", tr("ডান-বাম উল্টানো", "Mirror left-right"), Eff.FLIP_H)
        EffectRow(c, "Vertical Flip", tr("উপর-নিচ উল্টানো", "Mirror top-bottom"), Eff.FLIP_V)
        EffectRow(c, "Gaussian Blur", tr("ঝাপসা করা (এক্সপোর্টে সব ফোনে)", "Blur the picture"), Eff.BLUR)
        EffectRow(c, "Crop", tr("চারদিক থেকে কেটে জুম", "Cut edges and zoom"), Eff.CROP)
        EffectRow(c, "Black & White", tr("সাদা-কালো", "Grayscale"), Eff.BW)
        EffectRow(c, "Brightness & Contrast", tr("উজ্জ্বলতা, কনট্রাস্ট, স্যাচুরেশন", "Brightness, contrast, saturation"), Eff.COLOR)
        EffectRow(c, "Denoise", tr("অডিওর হিস/গুনগুন কমানো", "Reduce hiss/hum in audio"), Eff.DENOISE)
    }
}

@Composable
fun EffectRow(c: EditorController, title: String, sub: String, e: Eff) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .background(Panel2, RoundedCornerShape(8.dp))
            .pointerInput(e) { detectTapGestures(onDoubleTap = { c.addEffect(e) }) }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp)
            Text(sub, fontSize = 10.sp, color = TextLo)
        }
        IconButton(onClick = { c.addEffect(e) }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Add, contentDescription = null)
        }
    }
}

@Composable
fun KeyframeButton(active: Boolean, animated: Boolean, onClick: () -> Unit) {
    val col = if (active) KeyColor else if (animated) Color(0xFF7FB0FF) else Color(0xFF8A909B)
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
        Text(label, fontSize = 12.sp, modifier = Modifier.width(80.dp))
        Slider(
            value = v,
            onValueChange = { c.setParam(which, it, t) },
            onValueChangeFinished = { c.endLive() },
            valueRange = range,
            modifier = Modifier.weight(1f)
        )
        Text(v.roundToInt().toString() + unit, fontSize = 11.sp, modifier = Modifier.width(46.dp), textAlign = TextAlign.End)
        KeyframeButton(param.hasKeyframeAt(t), param.kfs.isNotEmpty()) { c.toggleKeyframe(which, t) }
    }
}

@Composable
fun StaticRow(
    c: EditorController,
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    unit: String,
    onChange: (Float) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(34.dp)) {
        Text(label, fontSize = 12.sp, modifier = Modifier.width(80.dp))
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            onValueChangeFinished = { c.endLive() },
            valueRange = range,
            modifier = Modifier.weight(1f)
        )
        Text(value.roundToInt().toString() + unit, fontSize = 11.sp, modifier = Modifier.width(46.dp), textAlign = TextAlign.End)
        Spacer(Modifier.width(32.dp))
    }
}

@Composable
fun SectionHeader(title: String, onRemove: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
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
                color = TextLo,
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
            Text(clip.name.substringBeforeLast('.'), fontSize = 11.sp, color = TextLo, maxLines = 1)
            if (!fx.transformOn && !fx.flipH && !fx.flipV && !fx.blurOn && !fx.cropOn && !fx.bwOn && !fx.colorOn && !fx.denoiseOn) {
                Text(
                    tr(
                        "এই ক্লিপে কোনো ইফেক্ট নেই। ইফেক্ট প্যানেল থেকে যোগ করুন, অথবা প্রিভিউতে আঙুলে জুম/সরান।",
                        "No effects yet. Add one from Effects, or pinch/drag on the preview."
                    ),
                    color = TextLo,
                    fontSize = 12.sp
                )
            }
            if (fx.transformOn) {
                SectionHeader("Transform") { c.removeEffect(Eff.TRANSFORM) }
                ParamRow(c, "Scale", fx.scale, 10f..400f, "%", t, P.SCALE)
                ParamRow(c, "Position X", fx.posX, -100f..100f, "", t, P.POSX)
                ParamRow(c, "Position Y", fx.posY, -100f..100f, "", t, P.POSY)
            }
            if (fx.flipH) SectionHeader("Horizontal Flip") { c.removeEffect(Eff.FLIP_H) }
            if (fx.flipV) SectionHeader("Vertical Flip") { c.removeEffect(Eff.FLIP_V) }
            if (fx.blurOn) {
                SectionHeader("Gaussian Blur") { c.removeEffect(Eff.BLUR) }
                ParamRow(c, "Blurriness", fx.blur, 0f..60f, "", t, P.BLUR)
            }
            if (fx.cropOn) {
                SectionHeader("Crop") { c.removeEffect(Eff.CROP) }
                StaticRow(c, "Left", fx.cropL, 0f..45f, "%") { c.setCrop(l = it) }
                StaticRow(c, "Right", fx.cropR, 0f..45f, "%") { c.setCrop(r = it) }
                StaticRow(c, "Top", fx.cropT, 0f..45f, "%") { c.setCrop(t = it) }
                StaticRow(c, "Bottom", fx.cropB, 0f..45f, "%") { c.setCrop(b = it) }
            }
            if (fx.bwOn) SectionHeader("Black & White") { c.removeEffect(Eff.BW) }
            if (fx.colorOn) {
                SectionHeader("Brightness & Contrast") { c.removeEffect(Eff.COLOR) }
                StaticRow(c, "Brightness", fx.bright, -100f..100f, "") { c.setColor(bright = it) }
                StaticRow(c, "Contrast", fx.contrast, -100f..100f, "") { c.setColor(contrast = it) }
                StaticRow(c, "Saturation", fx.sat, -100f..100f, "") { c.setColor(sat = it) }
            }
            if (fx.denoiseOn) {
                SectionHeader("Denoise") { c.removeEffect(Eff.DENOISE) }
                StaticRow(c, "Amount", fx.denoise, 0f..100f, "") { c.setDenoise(it) }
            }
        }
    }
}

@Composable
fun TextPanel(c: EditorController) {
    val t = c.selectedText()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(10.dp)
    ) {
        Button(onClick = { c.addText() }) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(tr("টেক্সট যোগ করুন (প্লে-হেডে)", "Add Text (at playhead)"))
        }
        if (t == null) {
            Spacer(Modifier.height(8.dp))
            Text(
                tr(
                    "টেক্সট যোগ করুন, অথবা টাইমলাইনের Text সারিতে একটা টেক্সট চাপুন। প্রিভিউতে আঙুলে টেনে সরানো ও চিমটি দিয়ে বড়-ছোট করা যায়।",
                    "Add a text, or tap one in the Text row. Drag on the preview to move it and pinch to resize."
                ),
                color = TextLo,
                fontSize = 12.sp
            )
        } else {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = t.text,
                onValueChange = { v -> c.updateText(t.id, true) { it.copy(text = v) } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(tr("লেখা", "Text")) }
            )
            Spacer(Modifier.height(8.dp))
            Text(tr("ফন্ট", "Font"), fontSize = 12.sp, color = TextLo)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(Fonts.list) { f ->
                    FilterChip(
                        selected = t.font == f.key,
                        onClick = { c.updateText(t.id, false) { it.copy(font = f.key) } },
                        label = { Text(f.label, fontFamily = Fonts.family(f.key), fontSize = 14.sp) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(tr("রঙ", "Color"), fontSize = 12.sp, color = TextLo)
            ColorPickerBox(Color(t.color)) { col ->
                c.updateText(t.id, true) { it.copy(color = col.toArgb().toLong() and 0xFFFFFFFFL) }
            }
            Spacer(Modifier.height(8.dp))
            StaticRow(c, "Size", t.sizePct, 2f..30f, "") { v -> c.updateText(t.id, true) { it.copy(sizePct = v) } }
            StaticRow(c, "Position X", t.posX, -100f..100f, "") { v -> c.updateText(t.id, true) { it.copy(posX = v) } }
            StaticRow(c, "Position Y", t.posY, -100f..100f, "") { v -> c.updateText(t.id, true) { it.copy(posY = v) } }
            val maxS = maxOf(60f, c.seq.lengthMs / 1000f + 10f)
            StaticRow(c, "Start", t.startMs / 1000f, 0f..maxS, "s") { v -> c.updateText(t.id, true) { it.copy(startMs = (v * 1000f).toLong()) } }
            StaticRow(c, "Duration", t.durMs / 1000f, 0.5f..30f, "s") { v -> c.updateText(t.id, true) { it.copy(durMs = (v * 1000f).toLong()) } }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { c.duplicateText(t.id) }) { Text(tr("ডুপ্লিকেট", "Duplicate")) }
                OutlinedButton(onClick = { c.deleteText(t.id) }) { Text(tr("ডিলিট", "Delete")) }
            }
        }
    }
}

@Composable
fun ExportPanel(c: EditorController) {
    var res by remember { mutableStateOf(0) }
    val labels = listOf("1080p", "720p", "480p")
    val sides = listOf(1080, 720, 480)
    val bn = LocalBn.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(10.dp)
    ) {
        Text(
            tr("সিকোয়েন্স: ", "Sequence: ") + c.seq.name + " · " + c.seq.ratio,
            fontSize = 12.sp,
            color = TextLo
        )
        Spacer(Modifier.height(4.dp))
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
            Button(onClick = { c.startExport(sides[res]) }) {
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

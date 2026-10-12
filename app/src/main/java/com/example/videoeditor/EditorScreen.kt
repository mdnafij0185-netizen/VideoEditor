@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.videoeditor

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Switch
import androidx.compose.ui.text.input.KeyboardType
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
enum class PanelTab { MEDIA, EFFECTS, CONTROLS, KEYFRAMES, TEXT, INFO, EXPORT }

@Composable
fun EditorScreen(
    initial: Project,
    store: ProjectStore,
    startQuality: Quality,
    tlHeight: Dp,
    panelHeight: Dp,
    panelFirst: Boolean,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val bn = LocalBn.current
    val c = remember { EditorController(ctx, initial, scope, startQuality) }
    var tab by remember { mutableStateOf(Tab.SEQ) }
    var panel by remember { mutableStateOf(PanelTab.MEDIA) }
    var panelOpen by remember { mutableStateOf(true) }
    var trimId by remember { mutableStateOf<String?>(null) }
    var speedId by remember { mutableStateOf<String?>(null) }
    var showRename by remember { mutableStateOf(false) }
    var showNewSeq by remember { mutableStateOf(false) }
    var panelDp by remember { mutableStateOf(panelHeight.value) }
    val dens = LocalDensity.current.density
    val screenH = LocalConfiguration.current.screenHeightDp
    // the preview keeps a fixed size; only the timeline grows or shrinks
    val previewH = (screenH * 0.30f).coerceIn(170f, 300f).dp

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
    val aSig = seq.audio.flatten().map { Triple(it.id + it.uri, it.inMs, it.outMs) }
    LaunchedEffect(seq.id, vSig) { c.rebuildVideo() }
    LaunchedEffect(seq.id, aSig) { c.syncAudioPool() }
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
            // top bar
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = null)
                }
                Text("PR", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = MaterialTheme.colorScheme.primary)
                Text("Edit", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Spacer(Modifier.width(10.dp))
                Text(
                    c.project.name + " ▾",
                    modifier = Modifier
                        .weight(1f)
                        .background(Panel2, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    maxLines = 1,
                    fontSize = 12.sp,
                    color = TextLo
                )
                IconButton(onClick = { c.undo() }, enabled = c.canUndo, modifier = Modifier.size(42.dp)) {
                    Icon(Icons.Filled.Undo, contentDescription = "Undo", modifier = Modifier.size(24.dp))
                }
                IconButton(onClick = { c.redo() }, enabled = c.canRedo, modifier = Modifier.size(42.dp)) {
                    Icon(Icons.Filled.Redo, contentDescription = "Redo", modifier = Modifier.size(24.dp))
                }
                IconButton(
                    onClick = {
                        panel = PanelTab.EXPORT
                        panelOpen = true
                    },
                    modifier = Modifier.size(42.dp)
                ) {
                    Icon(Icons.Filled.SaveAlt, contentDescription = "Export", tint = MaterialTheme.colorScheme.primary)
                }
            }

            SeqStrip(c, tab, onTab = { tab = it }, onRename = { showRename = true }, onNew = { showNewSeq = true })
            PreviewArea(c, tab == Tab.SOURCE, Modifier.fillMaxWidth().height(previewH))
            TransportRow(c, tab == Tab.SOURCE)

            val timelineBlock: @Composable ColumnScope.() -> Unit = {
                if (tab == Tab.SOURCE) {
                    Box(Modifier.weight(1f)) { SourceControls(c) }
                } else {
                    TimelineView(c, Modifier.weight(1f).heightIn(min = 110.dp), onSpeed = { speedId = it }, onTrim = { trimId = it })
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
                        LayerMenuButton(c)
                    }
                }
            }
            val panelBlock: @Composable ColumnScope.() -> Unit = {
                if (panelOpen) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(18.dp)
                            .background(Color(0xFF0A0F1E))
                            .pointerInput(panelFirst) {
                                detectVerticalDragGestures { change, dy ->
                                    change.consume()
                                    val d = dy / dens
                                    panelDp = (if (panelFirst) panelDp + d else panelDp - d).coerceIn(110f, 460f)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            Modifier
                                .width(44.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(TextLo)
                        )
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(panelDp.dp)
                            .background(Panel)
                    ) {
                        when (panel) {
                            PanelTab.MEDIA -> ProjectPanel(
                                c,
                                onImport = { types -> picker.launch(types) },
                                onNewSeq = { showNewSeq = true },
                                onOpenSource = {
                                    c.openSource(it)
                                    tab = Tab.SOURCE
                                }
                            )
                            PanelTab.EFFECTS -> EffectsPanel(c)
                            PanelTab.CONTROLS -> ControlsPanel(c, onAddEffect = { panel = PanelTab.EFFECTS })
                            PanelTab.KEYFRAMES -> KeyframesPanel(c)
                            PanelTab.TEXT -> TextPanel(c)
                            PanelTab.INFO -> InfoPanel(c)
                            PanelTab.EXPORT -> ExportPanel(c)
                        }
                    }
                }
            }
            if (panelFirst) {
                this.panelBlock()
                this.timelineBlock()
            } else {
                this.timelineBlock()
                this.panelBlock()
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .background(Color(0xFF080C18)),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                fun pick(p: PanelTab) {
                    if (panel == p) panelOpen = !panelOpen else {
                        panel = p
                        panelOpen = true
                    }
                }
                PanelButton(Icons.Filled.Folder, tr("মিডিয়া", "Media"), panel == PanelTab.MEDIA && panelOpen) { pick(PanelTab.MEDIA) }
                PanelButton(Icons.Filled.AutoFixHigh, tr("ইফেক্ট", "Effects"), panel == PanelTab.EFFECTS && panelOpen) { pick(PanelTab.EFFECTS) }
                PanelButton(Icons.Filled.Tune, tr("কন্ট্রোল", "Controls"), panel == PanelTab.CONTROLS && panelOpen) { pick(PanelTab.CONTROLS) }
                PanelButton(Icons.Filled.Timeline, tr("কিফ্রেম", "Keyframes"), panel == PanelTab.KEYFRAMES && panelOpen) { pick(PanelTab.KEYFRAMES) }
                PanelButton(Icons.Filled.TextFields, tr("টেক্সট", "Text"), panel == PanelTab.TEXT && panelOpen) { pick(PanelTab.TEXT) }
                PanelButton(Icons.Filled.Info, tr("ইনফো", "Info"), panel == PanelTab.INFO && panelOpen) { pick(PanelTab.INFO) }
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

    if (showNewSeq) {
        NewSequenceDialog(
            defaultName = "Sequence " + (c.project.seqs.size + 1),
            onDismiss = { showNewSeq = false },
            onCreate = { n, r, f ->
                c.addSequence(n, r, f)
                tab = Tab.SEQ
                showNewSeq = false
            }
        )
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
fun LayerMenuButton(c: EditorController) {
    var open by remember { mutableStateOf(false) }
    Box {
        ToolButton(Icons.Filled.Add, tr("লেয়ার", "Layer")) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Add Adjustment Layer") },
                onClick = {
                    open = false
                    c.addAdjustment()
                }
            )
            DropdownMenuItem(
                text = { Text("Add Text") },
                onClick = {
                    open = false
                    c.addText()
                }
            )
            DropdownMenuItem(
                text = { Text("Add Audio Track") },
                onClick = {
                    open = false
                    c.addAudioTrack()
                }
            )
        }
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
fun SeqStrip(c: EditorController, tab: Tab, onTab: (Tab) -> Unit, onRename: () -> Unit, onNew: () -> Unit) {
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
                label = { Text(s.name + " · " + s.ratio + " · " + s.fps + "fps") }
            )
        }
        IconButton(onClick = onNew, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Add, contentDescription = null)
        }
    }
}

private fun colorMatrixOf(a: FloatArray): ColorMatrix = ColorMatrix(
    floatArrayOf(
        a[0], a[1], a[2], 0f, a[9] * 255f,
        a[3], a[4], a[5], 0f, a[10] * 255f,
        a[6], a[7], a[8], 0f, a[11] * 255f,
        0f, 0f, 0f, 1f, 0f
    )
)

@Composable
fun PreviewArea(c: EditorController, sourceMode: Boolean, modifier: Modifier) {
    val seq = c.seq
    val density = LocalDensity.current
    var qMenu by remember { mutableStateOf(false) }
    val clips = seq.video

    var geoV = Geo(1f, 1f, 0f, 0f, 0f)
    var blurV = 0f
    var affV: FloatArray? = null
    var cropV: FloatArray? = null
    if (!sourceMode && clips.isNotEmpty()) {
        val tt = c.globalMs.coerceIn(0L, (seq.totalMs - 1).coerceAtLeast(0L))
        val (i, off) = locate(clips, tt)
        val fx = clips[i].fx
        geoV = fx.geometry(off)
        if (fx.live(Eff.BLUR)) blurV = fx.blur.at(off)
        affV = fx.colorAffine(off)
        cropV = fx.cropAt(off)
        // adjustment layers act on the whole picture while they are active
        for ((a, lt) in seq.activeAdj(c.globalMs)) {
            val g2 = a.fx.geometry(lt)
            geoV = Geo(
                geoV.sx * g2.sx, geoV.sy * g2.sy,
                g2.sx * geoV.tx + g2.tx, g2.sy * geoV.ty + g2.ty,
                geoV.rot + g2.rot
            )
            if (a.fx.live(Eff.BLUR)) blurV = maxOf(blurV, a.fx.blur.at(lt))
            affV = composeAffine(affV, a.fx.colorAffine(lt))
        }
    }
    val geo = geoV
    val blur = blurV
    val aff = affV
    val crop = cropV
    val key = if (aff == null) "-|" + (blur * 2).toInt() else aff.joinToString(",") { String.format(Locale.US, "%.3f", it) } + "|" + (blur * 2).toInt()
    val hasContent = if (sourceMode) c.sourceMedia != null else clips.isNotEmpty()
    val audioOnly = sourceMode && c.sourceMedia?.isAudio == true
    val activePlayer = if (sourceMode) c.sourcePlayer else c.player

    BoxWithConstraints(
        modifier.padding(horizontal = 10.dp, vertical = 4.dp),
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
                        v.rotation = geo.rot
                        v.translationX = geo.tx / 2f * v.width
                        v.translationY = -geo.ty / 2f * v.height
                        if (crop != null) {
                            v.clipBounds = Rect(
                                (crop[0] / 100f * v.width).toInt(),
                                (crop[2] / 100f * v.height).toInt(),
                                (v.width - crop[1] / 100f * v.width).toInt(),
                                (v.height - crop[3] / 100f * v.height).toInt()
                            )
                        } else {
                            v.clipBounds = null
                        }
                        if (v.tag != key) {
                            v.tag = key
                            val tv = v.videoSurfaceView as? TextureView
                            if (aff != null) {
                                val paint = Paint()
                                paint.colorFilter = ColorMatrixColorFilter(colorMatrixOf(aff))
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
                    else tr("মিডিয়া প্যানেল থেকে ভিডিও টেনে টাইমলাইনে দিন", "Drag a clip from Media to the timeline"),
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
                            val lt = (c.globalMs - t.startMs).coerceIn(0L, t.durMs)
                            val sz = t.sP.at(lt)
                            val px = t.xP.at(lt)
                            val py = t.yP.at(lt)
                            val op = (t.oP.at(lt) / 100f).coerceIn(0f, 1f)
                            Text(
                                t.text,
                                color = Color(t.shownColor()),
                                fontSize = with(density) { (sz / 100f * hPx).toSp() },
                                fontFamily = Fonts.family(t.font),
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .offset {
                                        IntOffset(
                                            (px / 100f * wPx / 2f).roundToInt(),
                                            (py / 100f * hPx / 2f).roundToInt()
                                        )
                                    }
                                    .alpha(if (inRange) op else 0.35f)
                                    .then(if (sel) Modifier.border(1.dp, Color.White.copy(alpha = 0.7f)) else Modifier)
                            )
                        }
                    }
                }
                var transAlpha = 0f
                var transWhite = false
                var tAcc = 0L
                seq.video.forEachIndexed { i, cl ->
                    if (i > 0 && cl.trans.isNotEmpty()) {
                        val a = 1f - abs(c.globalMs - tAcc).toFloat() / (cl.transMs / 2f)
                        if (a > transAlpha) {
                            transAlpha = a
                            transWhite = cl.trans == "white"
                        }
                    }
                    tAcc += cl.durMs
                }
                if (transAlpha > 0f) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background((if (transWhite) Color.White else Color.Black).copy(alpha = transAlpha.coerceIn(0f, 1f)))
                    )
                }
                Box(Modifier.align(Alignment.BottomEnd)) {
                    Text(
                        c.quality.label + " ▾",
                        fontSize = 12.sp,
                        modifier = Modifier
                            .padding(6.dp)
                            .background(Color(0xCC172036), RoundedCornerShape(8.dp))
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
fun ProjectPanel(
    c: EditorController,
    onImport: (Array<String>) -> Unit,
    onNewSeq: () -> Unit,
    onOpenSource: (MediaRef) -> Unit
) {
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
                DropdownMenuItem(
                    text = { Text(tr("নতুন সিকোয়েন্স...", "New Sequence...")) },
                    onClick = {
                        menu = false
                        onNewSeq()
                    }
                )
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

private class EffInfo(val e: Eff, val desc: String, val category: Int)

@Composable
fun EffectsPanel(c: EditorController) {
    var q by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf(0) }
    val adj = c.selectedAdj()
    val clip = c.selectedAnyClip()
    val isVideoClip = clip != null && c.seq.video.any { it.id == clip.id }
    val all = listOf(
        EffInfo(Eff.TRANSFORM, tr("পজিশন, স্কেল, রোটেশন, ওপাসিটি (কিফ্রেম)", "Position, scale, rotation, opacity (keyframes)"), 1),
        EffInfo(Eff.FLIP_H, tr("ডান-বাম উল্টানো", "Mirror left-right"), 1),
        EffInfo(Eff.FLIP_V, tr("উপর-নিচ উল্টানো", "Mirror top-bottom"), 1),
        EffInfo(Eff.BLUR, tr("গাউসিয়ান ব্লার (কিফ্রেম)", "Gaussian blur (keyframes)"), 1),
        EffInfo(Eff.CROP, tr("চারদিক কেটে কালো (কিফ্রেম)", "Cut edges to black (keyframes)"), 1),
        EffInfo(Eff.COLOR, tr("এক্সপোজার, কনট্রাস্ট, স্যাচুরেশন, হিউ, তাপমাত্রা, টিন্ট (কিফ্রেম)", "Exposure, contrast, saturation, hue, temperature, tint (keyframes)"), 1),
        EffInfo(Eff.BW, tr("সাদা-কালো", "Grayscale"), 1),
        EffInfo(Eff.INVERT, tr("রং উল্টানো", "Invert colours"), 1),
        EffInfo(Eff.VOLUME, tr("ভলিউম dB (কিফ্রেম)", "Volume in dB (keyframes)"), 2),
        EffInfo(Eff.EQ, tr("ফ্রিকোয়েন্সি, গেইন, Q", "Frequency, gain, Q"), 2),
        EffInfo(Eff.DENOISE, tr("হিস/গুনগুন কমানো", "Reduce hiss/hum"), 2)
    )
    val shown = all.filter {
        (cat == 0 || it.category == cat) &&
                (q.isBlank() || it.e.title.contains(q, ignoreCase = true) || it.desc.contains(q, ignoreCase = true))
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedTextField(
                value = q,
                onValueChange = { q = it },
                singleLine = true,
                placeholder = { Text(tr("ইফেক্ট খুঁজুন...", "Search effects..."), fontSize = 12.sp) },
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
            )
            Button(onClick = { c.addAdjustment() }) {
                Text(tr("Adjustment Layer", "Adjustment Layer"), fontSize = 11.sp, maxLines = 1)
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf("All", "Video", "Audio").forEachIndexed { i, l ->
                FilterChip(selected = cat == i, onClick = { cat = i }, label = { Text(l, fontSize = 12.sp) })
            }
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp)
        ) {
            if (c.selectedTextId != null) {
                Text(tr("টেক্সট সিলেক্ট করা: শুধু Black & White চলে, বাকিটা টেক্সট প্যানেলে", "Text selected: only Black & White applies; the rest is in Text"), fontSize = 11.sp, color = TextLo)
            } else if (adj == null && clip == null) {
                Text(tr("আগে টাইমলাইনে ক্লিপ বা Adjustment Layer সিলেক্ট করুন", "Select a clip or adjustment layer first"), fontSize = 11.sp, color = TextLo)
            }
            shown.forEach { info ->
                val audio = info.category == 2
                val ok = when {
                    c.selectedTextId != null -> info.e == Eff.BW
                    adj != null -> !audio && info.e != Eff.CROP
                    clip != null -> audio || isVideoClip
                    else -> false
                }
                EffectRow(c, info.e.title, info.desc, info.e, ok)
            }
        }
    }
}

@Composable
fun EffectRow(c: EditorController, title: String, sub: String, e: Eff, compatible: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .alpha(if (compatible) 1f else 0.45f)
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
fun ValueText(value: Float, unit: String, range: ClosedFloatingPointRange<Float>, onSet: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Text(
        value.roundToInt().toString() + unit,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.End,
        modifier = Modifier
            .width(54.dp)
            .clickable { open = true }
    )
    if (open) {
        var txt by remember { mutableStateOf(String.format(Locale.US, "%.1f", value)) }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(tr("মান লিখুন", "Type a value")) },
            text = {
                OutlinedTextField(
                    value = txt,
                    onValueChange = { txt = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    label = { Text(String.format(Locale.US, "%.0f ~ %.0f", range.start, range.endInclusive)) }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val v = txt.toFloatOrNull()
                    if (v != null) onSet(v.coerceIn(range.start, range.endInclusive))
                    open = false
                }) { Text(tr("ঠিক আছে", "OK")) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(tr("বাতিল", "Cancel")) } }
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
        ValueText(v, unit, range) {
            c.setParam(which, it, t)
            c.endLive()
        }
        KeyframeButton(param.hasKeyframeAt(t), param.kfs.isNotEmpty()) { c.toggleKeyframe(which, t) }
    }
}

@Composable
fun TextParamRow(
    c: EditorController,
    tc: TextClip,
    label: String,
    which: TP,
    range: ClosedFloatingPointRange<Float>,
    unit: String
) {
    val lt = c.textLocalTime(tc)
    val param = tc.param(which)
    val v = param.at(lt).coerceIn(range.start, range.endInclusive)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(34.dp)) {
        Text(label, fontSize = 12.sp, modifier = Modifier.width(80.dp))
        Slider(
            value = v,
            onValueChange = { c.setTextParam(tc.id, which, it, lt) },
            onValueChangeFinished = { c.endLive() },
            valueRange = range,
            modifier = Modifier.weight(1f)
        )
        ValueText(v, unit, range) {
            c.setTextParam(tc.id, which, it, lt)
            c.endLive()
        }
        KeyframeButton(param.hasKeyframeAt(lt), param.kfs.isNotEmpty()) { c.toggleTextKeyframe(tc.id, which, lt) }
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
        ValueText(value, unit, range) {
            onChange(it)
            c.endLive()
        }
        Spacer(Modifier.width(32.dp))
    }
}

@Composable
fun NewSequenceDialog(defaultName: String, onDismiss: () -> Unit, onCreate: (String, String, Int) -> Unit) {
    var name by remember { mutableStateOf(defaultName) }
    var ratio by remember { mutableStateOf("16:9") }
    var fps by remember { mutableStateOf("30") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("নতুন সিকোয়েন্স", "New Sequence")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(tr("নাম", "Name")) }
                )
                Spacer(Modifier.height(8.dp))
                Text(tr("অ্যাসপেক্ট রেশিও", "Aspect ratio"), fontSize = 12.sp, color = TextLo)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("9:16", "16:9", "4:3").forEach { r ->
                        FilterChip(selected = ratio == r, onClick = { ratio = r }, label = { Text(r) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(tr("ফ্রেম রেট (FPS)", "Frame rate (FPS)"), fontSize = 12.sp, color = TextLo)
                OutlinedTextField(
                    value = fps,
                    onValueChange = { fps = it.filter { ch -> ch.isDigit() }.take(3) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(24, 25, 30, 50, 60).forEach { f ->
                        FilterChip(selected = fps == f.toString(), onClick = { fps = f.toString() }, label = { Text(f.toString()) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name, ratio, fps.toIntOrNull() ?: 30) }) { Text(tr("তৈরি করুন", "Create")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("বাতিল", "Cancel")) } }
    )
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
fun EffectSection(c: EditorController, e: Eff, fx: Fx, t: Long) {
    var open by remember(e) { mutableStateOf(true) }
    val enabled = e.name !in fx.off
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .background(Panel2, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp)
    ) {
        Row(Modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (open) "▾" else "▸",
                fontSize = 14.sp,
                modifier = Modifier
                    .clickable { open = !open }
                    .padding(end = 8.dp)
            )
            Text(
                e.title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .weight(1f)
                    .clickable { open = !open }
            )
            Switch(
                checked = enabled,
                onCheckedChange = { c.setEffectEnabled(e, it) },
                modifier = Modifier.scale(0.7f)
            )
            IconButton(onClick = { c.resetEffect(e) }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Filled.Refresh, contentDescription = "Reset", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = { c.removeEffect(e) }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Remove", modifier = Modifier.size(18.dp))
            }
        }
        if (open) {
            for (w in paramsOf(e)) {
                ParamRow(c, w.label, fx.param(w), rangeOf(w), unitOf(w), t, w)
            }
            when (e) {
                Eff.EQ -> {
                    StaticRow(c, "Frequency", fx.eqFreq, 20f..12000f, " Hz") { c.setEq(freq = it) }
                    StaticRow(c, "Gain", fx.eqGain, -18f..18f, " dB") { c.setEq(gain = it) }
                    StaticRow(c, "Q", fx.eqQ, 0.1f..10f, "") { c.setEq(q = it) }
                }
                Eff.DENOISE -> StaticRow(c, "Amount", fx.denoise, 0f..100f, "") { c.setDenoise(it) }
                Eff.FLIP_H, Eff.FLIP_V, Eff.BW, Eff.INVERT ->
                    Text(tr("কোনো প্যারামিটার নেই", "No parameters"), fontSize = 11.sp, color = TextLo, modifier = Modifier.padding(bottom = 6.dp))
                else -> {}
            }
        }
    }
}

@Composable
fun ControlsPanel(c: EditorController, onAddEffect: () -> Unit) {
    val tx = c.selectedText()
    val fx = c.selectedFx()
    val adj = c.selectedAdj()
    val clip = c.selectedAnyClip()
    if (tx != null) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(tx.text, fontSize = 11.sp, color = TextLo, maxLines = 1)
            TextEffectRows(c, tx)
        }
    } else if (fx == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                tr("টাইমলাইনে একটা ক্লিপ, টেক্সট বা Adjustment Layer সিলেক্ট করুন", "Select a clip, text or adjustment layer on the timeline"),
                color = TextLo,
                fontSize = 13.sp
            )
        }
    } else {
        val t = c.selectedLocalTime()
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                if (adj != null) adj.name else (clip?.name ?: "").substringBeforeLast('.'),
                fontSize = 11.sp,
                color = TextLo,
                maxLines = 1
            )
            if (!fx.hasAny()) {
                Text(
                    tr(
                        "এই অবজেক্টে কোনো ইফেক্ট নেই। নিচের বোতামে ইফেক্ট যোগ করুন, অথবা প্রিভিউতে আঙুলে জুম/সরান।",
                        "No effects yet. Add one below, or pinch/drag on the preview."
                    ),
                    color = TextLo,
                    fontSize = 12.sp
                )
            }
            Eff.values().forEach { e ->
                if (fx.flag(e)) EffectSection(c, e, fx, t)
            }
            Spacer(Modifier.height(6.dp))
            Button(onClick = onAddEffect, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(tr("ইফেক্ট যোগ করুন", "Add Effect"))
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
fun TextEffectRows(c: EditorController, tx: TextClip) {
    Text("Text Transform", fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
    TextParamRow(c, tx, "Size", TP.SIZE, 2f..30f, "")
    TextParamRow(c, tx, "Position X", TP.X, -100f..100f, "")
    TextParamRow(c, tx, "Position Y", TP.Y, -100f..100f, "")
    TextParamRow(c, tx, "Opacity", TP.OPACITY, 0f..100f, "%")
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(40.dp)) {
        Text("Black & White", fontSize = 12.sp, modifier = Modifier.weight(1f))
        Switch(checked = tx.bw, onCheckedChange = { v -> c.updateText(tx.id, false) { it.copy(bw = v) } })
    }
}

@Composable
fun KeyframesPanel(c: EditorController) {
    val entries = c.paramEntries()
    var selIdx by remember { mutableStateOf(0) }
    var selT by remember { mutableStateOf(-1L) }
    if (entries.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                tr(
                    "কোনো কিফ্রেম নেই। Controls প্যানেলে প্যারামিটারের পাশের ◇ চাপুন।",
                    "No keyframes yet. Tap the ◇ next to a parameter in Controls."
                ),
                color = TextLo,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(16.dp)
            )
        }
    } else {
        val idx = selIdx.coerceIn(0, entries.size - 1)
        val en = entries[idx]
        val local = c.globalMs - en.originMs
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(10.dp)
        ) {
            entries.forEachIndexed { i, e ->
                Text(e.label + "  (" + e.param.kfs.size + ")", fontSize = 12.sp, color = if (i == idx) MaterialTheme.colorScheme.primary else TextLo)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    e.param.kfs.forEach { kf ->
                        FilterChip(
                            selected = i == idx && selT == kf.tMs,
                            onClick = {
                                selIdx = i
                                selT = kf.tMs
                                c.seekGlobal(e.originMs + kf.tMs)
                            },
                            label = { Text(fmt(kf.tMs) + " · " + kf.value.roundToInt(), fontSize = 11.sp) }
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = {
                    val p = en.param.kfs.lastOrNull { it.tMs < local - 30L }
                    if (p != null) {
                        selT = p.tMs
                        c.seekGlobal(en.originMs + p.tMs)
                    }
                }) { Text(tr("আগের", "Prev"), fontSize = 12.sp) }
                OutlinedButton(onClick = {
                    val n = en.param.kfs.firstOrNull { it.tMs > local + 30L }
                    if (n != null) {
                        selT = n.tMs
                        c.seekGlobal(en.originMs + n.tMs)
                    }
                }) { Text(tr("পরের", "Next"), fontSize = 12.sp) }
                OutlinedButton(onClick = {
                    en.edit { p -> p.toggleKeyframeAt(local.coerceAtLeast(0L)) }
                }) { Text("◇ " + tr("এখানে", "Here"), fontSize = 12.sp) }
            }
            val kf = en.param.kfs.find { it.tMs == selT }
            if (kf != null) {
                Spacer(Modifier.height(8.dp))
                Text(tr("ইন্টারপোলেশন", "Interpolation"), fontSize = 12.sp, color = TextLo)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("Linear", "Ease In/Out", "Hold").forEachIndexed { k, l ->
                        FilterChip(
                            selected = kf.ease == k,
                            onClick = { en.edit { p -> p.setEase(kf.tMs, k) } },
                            label = { Text(l, fontSize = 11.sp) }
                        )
                    }
                }
                OutlinedButton(onClick = {
                    en.edit { p -> p.removeKeyframe(kf.tMs) }
                    selT = -1L
                }) { Text(tr("কিফ্রেম ডিলিট", "Delete Keyframe"), fontSize = 12.sp) }
            }
        }
    }
}

@Composable
fun InfoPanel(c: EditorController) {
    val s = c.seq
    val clip = c.selectedAnyClip()
    val adj = c.selectedAdj()
    val tx = c.selectedText()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(10.dp)
    ) {
        Text(tr("সিলেকশন", "Selection"), fontSize = 12.sp, color = TextLo)
        when {
            adj != null -> {
                Text(adj.name, fontSize = 14.sp)
                val maxS = maxOf(60f, s.lengthMs / 1000f + 10f)
                StaticRow(c, "Start", adj.startMs / 1000f, 0f..maxS, "s") { v -> c.moveAdj(adj.id, (v * 1000f).toLong()) }
                StaticRow(c, "Duration", adj.durMs / 1000f, 0.2f..maxS, "s") { v -> c.setAdjDuration(adj.id, (v * 1000f).toLong()) }
            }
            tx != null -> {
                Text(tx.text, fontSize = 14.sp, maxLines = 1)
                Text("Start " + fmt(tx.startMs) + "   Duration " + fmt(tx.durMs), fontSize = 12.sp, color = TextLo)
            }
            clip != null -> {
                Text(clip.name, fontSize = 14.sp, maxLines = 1)
                Text(
                    "In " + fmt(clip.inMs) + "   Out " + fmt(clip.outMs) + "   Speed " + clip.speed + "x",
                    fontSize = 12.sp,
                    color = TextLo
                )
                Text("Duration " + fmt(clip.durMs), fontSize = 12.sp, color = TextLo)
                if (s.video.none { it.id == clip.id }) {
                    val maxS = maxOf(60f, s.lengthMs / 1000f + 10f)
                    StaticRow(c, "Start", clip.startMs / 1000f, 0f..maxS, "s") { v -> c.moveAudioClip(clip.id, (v * 1000f).toLong(), 0) }
                }
            }
            else -> Text(tr("কিছু সিলেক্ট করা নেই", "Nothing selected"), fontSize = 12.sp, color = TextLo)
        }
        Spacer(Modifier.height(10.dp))
        Text(tr("প্রজেক্ট", "Project"), fontSize = 12.sp, color = TextLo)
        Text(c.project.name + " · " + c.project.seqs.size + " sequence(s)", fontSize = 13.sp)
        Text(s.name + " · " + s.ratio + " · " + s.fps + " fps · " + fmt(s.lengthMs), fontSize = 12.sp, color = TextLo)
        Text(
            "Video " + s.video.size + " · Audio tracks " + s.audio.size + " · Text " + s.texts.size + " · Adjustment " + s.adj.size,
            fontSize = 12.sp,
            color = TextLo
        )
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
            TextEffectRows(c, t)
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

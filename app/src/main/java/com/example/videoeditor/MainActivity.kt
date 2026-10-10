package com.example.videoeditor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val store = remember { ProjectStore(ctx) }
    val device = remember { deviceInfo(ctx) }
    var lang by remember { mutableStateOf(prefs.lang) }
    var accent by remember { mutableStateOf(prefs.accent) }
    var qualityPref by remember { mutableStateOf(prefs.quality) }
    var tlPref by remember { mutableStateOf(prefs.timeline) }
    var open by remember { mutableStateOf<Project?>(null) }

    MaterialTheme(colorScheme = rvedtColors(Accents[accent.coerceIn(0, Accents.size - 1)])) {
        CompositionLocalProvider(LocalBn provides (lang == "bn")) {
            Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
                val p = open
                if (p == null) {
                    HomeScreen(
                        store = store,
                        device = device,
                        lang = lang,
                        accent = accent,
                        qualityPref = qualityPref,
                        tlPref = tlPref,
                        onLang = {
                            lang = it
                            prefs.lang = it
                        },
                        onAccent = {
                            accent = it
                            prefs.accent = it
                        },
                        onQuality = {
                            qualityPref = it
                            prefs.quality = it
                        },
                        onTimeline = {
                            tlPref = it
                            prefs.timeline = it
                        },
                        onOpen = { open = it }
                    )
                } else {
                    key(p.id) {
                        val tlH: Dp = when (tlPref) {
                            0 -> 168.dp
                            2 -> 230.dp
                            else -> 190.dp
                        }
                        EditorScreen(p, store, qualityFromPref(qualityPref, device), tlH, onBack = { open = null })
                    }
                }
            }
        }
    }
}

@Composable
fun HomeScreen(
    store: ProjectStore,
    device: Device,
    lang: String,
    accent: Int,
    qualityPref: Int,
    tlPref: Int,
    onLang: (String) -> Unit,
    onAccent: (Int) -> Unit,
    onQuality: (Int) -> Unit,
    onTimeline: (Int) -> Unit,
    onOpen: (Project) -> Unit
) {
    var projects by remember { mutableStateOf(store.list()) }
    var showSettings by remember { mutableStateOf(false) }
    var showNew by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<Project?>(null) }
    val dateFmt = remember { SimpleDateFormat("d MMM yyyy", Locale.getDefault()) }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("RVEDT", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = { showSettings = true }) {
                Icon(Icons.Filled.Settings, contentDescription = tr("সেটিংস", "Settings"))
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { showNew = true },
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(tr("নতুন প্রজেক্ট", "New Project"), fontSize = 16.sp)
        }
        Spacer(Modifier.height(20.dp))
        Text(tr("সাম্প্রতিক প্রজেক্ট", "Recent projects"), color = TextLo, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        if (projects.isEmpty()) {
            Text(tr("এখনও কোনো প্রজেক্ট নেই", "No projects yet"), color = TextLo)
        }
        LazyColumn {
            items(projects, key = { it.id }) { p ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .background(Panel2, RoundedCornerShape(12.dp))
                        .clickable { onOpen(p) }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val first = p.seqs.firstOrNull()?.video?.firstOrNull()
                    if (first != null) {
                        Thumb(
                            first.uri,
                            Modifier
                                .size(width = 72.dp, height = 48.dp)
                                .background(Color(0xFF2A2A2A), RoundedCornerShape(6.dp))
                        )
                    } else {
                        Box(
                            Modifier
                                .size(width = 72.dp, height = 48.dp)
                                .background(Color(0xFF2A2A2A), RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Movie, contentDescription = null, tint = TextLo)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontSize = 15.sp, maxLines = 1)
                        Text(
                            dateFmt.format(Date(p.modified)) + " · " + fmt(p.totalMs),
                            color = TextLo,
                            fontSize = 12.sp
                        )
                    }
                    IconButton(onClick = { toDelete = p }) {
                        Icon(Icons.Filled.Delete, contentDescription = tr("ডিলিট", "Delete"), tint = TextLo)
                    }
                }
            }
        }
    }

    if (showNew) {
        var name by remember { mutableStateOf("Project ${projects.size + 1}") }
        AlertDialog(
            onDismissRequest = { showNew = false },
            title = { Text(tr("নতুন প্রজেক্ট", "New Project")) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(tr("প্রজেক্টের নাম", "Project name")) }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val p = Project(id = newId(), name = name.ifBlank { "Project" })
                    store.save(p)
                    showNew = false
                    onOpen(p)
                }) { Text(tr("তৈরি করুন", "Create")) }
            },
            dismissButton = {
                TextButton(onClick = { showNew = false }) { Text(tr("বাতিল", "Cancel")) }
            }
        )
    }

    val del = toDelete
    if (del != null) {
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(tr("প্রজেক্ট ডিলিট করবেন?", "Delete project?")) },
            text = { Text(del.name) },
            confirmButton = {
                TextButton(onClick = {
                    store.delete(del.id)
                    projects = store.list()
                    toDelete = null
                }) { Text(tr("ডিলিট", "Delete")) }
            },
            dismissButton = {
                TextButton(onClick = { toDelete = null }) { Text(tr("বাতিল", "Cancel")) }
            }
        )
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text(tr("সেটিংস", "Settings")) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(tr("ভাষা", "Language"), color = TextLo, fontSize = 12.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = lang == "bn", onClick = { onLang("bn") })
                        Text("বাংলা")
                        Spacer(Modifier.width(16.dp))
                        RadioButton(selected = lang == "en", onClick = { onLang("en") })
                        Text("English")
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(tr("থিমের রং", "Accent color"), color = TextLo, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Accents.forEachIndexed { i, col ->
                            Box(
                                Modifier
                                    .size(32.dp)
                                    .background(col, CircleShape)
                                    .border(if (accent == i) 3.dp else 0.dp, Color.White, CircleShape)
                                    .clickable { onAccent(i) }
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(tr("ডিফল্ট প্রিভিউ কোয়ালিটি", "Default preview quality"), color = TextLo, fontSize = 12.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("Auto", "Full", "1/2", "1/4", "1/8").forEachIndexed { i, l ->
                            FilterChip(selected = qualityPref == i, onClick = { onQuality(i) }, label = { Text(l, fontSize = 12.sp) })
                        }
                    }
                    Text(
                        tr("Auto এই ফোনে: ", "Auto on this phone: ") + recommended(device).label,
                        fontSize = 11.sp,
                        color = TextLo
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(tr("টাইমলাইনের উচ্চতা", "Timeline height"), color = TextLo, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(tr("ছোট", "Compact"), tr("মাঝারি", "Normal"), tr("বড়", "Large")).forEachIndexed { i, l ->
                            FilterChip(selected = tlPref == i, onClick = { onTimeline(i) }, label = { Text(l, fontSize = 12.sp) })
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(tr("এই ফোন", "This phone"), color = TextLo, fontSize = 12.sp)
                    Text(
                        "RAM: " + String.format(Locale.US, "%.1f", device.ramGb) + " GB · CPU: " +
                                device.cores + " cores · Android API " + device.sdk,
                        fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettings = false }) { Text(tr("ঠিক আছে", "OK")) }
            }
        )
    }
}

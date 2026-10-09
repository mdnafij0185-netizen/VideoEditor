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
import androidx.compose.material3.darkColorScheme
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

val BG = Color(0xFF0D0D0D)
val PANEL = Color(0xFF161616)
val ACCENT = Color(0xFF2F5FA8)

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
    var open by remember { mutableStateOf<Project?>(null) }

    MaterialTheme(colorScheme = darkColorScheme()) {
        CompositionLocalProvider(LocalBn provides (lang == "bn")) {
            Surface(modifier = Modifier.fillMaxSize(), color = BG) {
                val p = open
                if (p == null) {
                    HomeScreen(
                        store = store,
                        device = device,
                        lang = lang,
                        onLang = {
                            lang = it
                            prefs.lang = it
                        },
                        onOpen = { open = it }
                    )
                } else {
                    key(p.id) {
                        EditorScreen(p, store, device, onBack = { open = null })
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
    onLang: (String) -> Unit,
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
            Text(tr("ভিডিও এডিটর", "Video Editor"), fontSize = 22.sp, fontWeight = FontWeight.Medium)
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
        Text(tr("সাম্প্রতিক প্রজেক্ট", "Recent projects"), color = Color.Gray, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        if (projects.isEmpty()) {
            Text(tr("এখনও কোনো প্রজেক্ট নেই", "No projects yet"), color = Color.Gray)
        }
        LazyColumn {
            items(projects, key = { it.id }) { p ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .background(Color(0xFF1A1A1A), RoundedCornerShape(12.dp))
                        .clickable { onOpen(p) }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val first = p.video.firstOrNull()
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
                            Icon(Icons.Filled.Movie, contentDescription = null, tint = Color.Gray)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontSize = 15.sp, maxLines = 1)
                        Text(
                            dateFmt.format(Date(p.modified)) + " · " + fmt(p.totalMs),
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                    }
                    IconButton(onClick = { toDelete = p }) {
                        Icon(Icons.Filled.Delete, contentDescription = tr("ডিলিট", "Delete"), tint = Color.Gray)
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
                Column {
                    Text(tr("ভাষা", "Language"), color = Color.Gray, fontSize = 12.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = lang == "bn", onClick = { onLang("bn") })
                        Text("বাংলা")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = lang == "en", onClick = { onLang("en") })
                        Text("English")
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(tr("এই ফোন", "This phone"), color = Color.Gray, fontSize = 12.sp)
                    Text(
                        "RAM: " + String.format(Locale.US, "%.1f", device.ramGb) + " GB · CPU: " +
                                device.cores + " cores · Android API " + device.sdk,
                        fontSize = 12.sp
                    )
                    Text(
                        tr("প্রিভিউ কোয়ালিটি (অটো): ", "Auto preview quality: ") + recommended(device).label,
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

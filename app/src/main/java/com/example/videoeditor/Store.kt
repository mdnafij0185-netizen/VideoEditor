package com.example.videoeditor

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

val LocalBn = compositionLocalOf { true }

@Composable
fun tr(bn: String, en: String): String = if (LocalBn.current) bn else en

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var lang: String
        get() = sp.getString("lang", "bn") ?: "bn"
        set(v) {
            sp.edit().putString("lang", v).apply()
        }
    var accent: Int
        get() = sp.getInt("accent", 0)
        set(v) {
            sp.edit().putInt("accent", v).apply()
        }

    /** 0 = auto, 1 = Full, 2 = 1/2, 3 = 1/4, 4 = 1/8 */
    var quality: Int
        get() = sp.getInt("quality", 1)
        set(v) {
            sp.edit().putInt("quality", v).apply()
        }

    /** 0 = compact, 1 = normal, 2 = large */
    var timeline: Int
        get() = sp.getInt("timeline", 1)
        set(v) {
            sp.edit().putInt("timeline", v).apply()
        }
}

class ProjectStore(context: Context) {
    private val dir = File(context.filesDir, "projects").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun save(p: Project) {
        try {
            val text = json.encodeToString(p.copy(modified = System.currentTimeMillis()))
            File(dir, "${p.id}.json").writeText(text)
        } catch (e: Exception) {
        }
    }

    fun list(): List<Project> {
        val files = dir.listFiles() ?: return emptyList()
        return files.mapNotNull { f ->
            try {
                val p = json.decodeFromString<Project>(f.readText())
                if (p.seqs.isEmpty()) p.copy(seqs = listOf(Seq())) else p
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { it.modified }
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
    }
}

data class Device(val ramGb: Float, val cores: Int, val sdk: Int)

fun deviceInfo(context: Context): Device {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val mi = ActivityManager.MemoryInfo()
    am.getMemoryInfo(mi)
    return Device(
        ramGb = mi.totalMem / 1073741824f,
        cores = Runtime.getRuntime().availableProcessors(),
        sdk = android.os.Build.VERSION.SDK_INT
    )
}

enum class Quality(val label: String, val div: Int) {
    FULL("Full", 1), HALF("1/2", 2), QUARTER("1/4", 4), EIGHTH("1/8", 8)
}

fun qualityFromPref(i: Int, d: Device): Quality = when (i) {
    0 -> recommended(d)
    1 -> Quality.FULL
    2 -> Quality.HALF
    3 -> Quality.QUARTER
    else -> Quality.EIGHTH
}

fun recommended(d: Device): Quality = when {
    d.ramGb < 3f -> Quality.QUARTER
    d.ramGb < 5f -> Quality.HALF
    else -> Quality.FULL
}

fun importMedia(context: Context, uri: Uri): MediaRef? {
    try {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (e: Exception) {
    }
    val mime = context.contentResolver.getType(uri) ?: ""
    var name = "media"
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) name = c.getString(i) ?: name
        }
    } catch (e: Exception) {
    }
    val r = MediaMetadataRetriever()
    return try {
        r.setDataSource(context, uri)
        val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val hasVideo = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
        val isAudio = when {
            mime.startsWith("audio/") -> true
            mime.startsWith("video/") -> false
            else -> !hasVideo
        }
        if (dur <= 0L) null else MediaRef(newId(), uri.toString(), name, isAudio, dur)
    } catch (e: Exception) {
        null
    } finally {
        try {
            r.release()
        } catch (e: Exception) {
        }
    }
}

object Thumbs {
    private val cache = LruCache<String, Bitmap>(40)

    fun get(context: Context, uri: String): Bitmap? {
        cache.get(uri)?.let { return it }
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, Uri.parse(uri))
            val b = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (b != null) {
                val h = (160f * b.height / b.width).toInt().coerceAtLeast(1)
                val s = Bitmap.createScaledBitmap(b, 160, h, true)
                cache.put(uri, s)
                s
            } else null
        } catch (e: Exception) {
            null
        } finally {
            try {
                r.release()
            } catch (e: Exception) {
            }
        }
    }
}

@Composable
fun Thumb(uri: String, modifier: Modifier) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) { Thumbs.get(ctx, uri) }
    }
    val b = bmp
    if (b != null) {
        Image(
            bitmap = b.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    } else {
        Box(modifier.background(Color(0xFF2A2A2A)))
    }
}

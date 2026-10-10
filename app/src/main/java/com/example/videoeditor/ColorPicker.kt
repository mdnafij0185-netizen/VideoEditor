package com.example.videoeditor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Swatches = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFFFF3B30, 0xFFFF9500, 0xFFFFD60A,
    0xFF34C759, 0xFF00C7BE, 0xFF0A84FF, 0xFF5E5CE6, 0xFFFF2D92
)

private fun hsvOf(c: Color): FloatArray {
    val out = FloatArray(3)
    android.graphics.Color.colorToHSV(c.toArgb(), out)
    return out
}

private fun hexOf(c: Color): String =
    String.format("#%06X", c.toArgb() and 0xFFFFFF)

/** Colour picker like Premiere's: saturation/brightness box, hue bar, hex field, swatches. */
@Composable
fun ColorPickerBox(color: Color, onColor: (Color) -> Unit) {
    val init = remember { hsvOf(color) }
    var h by remember { mutableFloatStateOf(init[0]) }
    var s by remember { mutableFloatStateOf(init[1]) }
    var v by remember { mutableFloatStateOf(init[2]) }
    var hex by remember { mutableStateOf(hexOf(color)) }

    fun push() {
        val c = Color.hsv(h.coerceIn(0f, 359.9f), s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))
        hex = hexOf(c)
        onColor(c)
    }

    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(110.dp)
                .clip(RoundedCornerShape(8.dp))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { o ->
                            s = (o.x / size.width).coerceIn(0f, 1f)
                            v = 1f - (o.y / size.height).coerceIn(0f, 1f)
                            push()
                        },
                        onDrag = { change, _ ->
                            s = (change.position.x / size.width).coerceIn(0f, 1f)
                            v = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                            push()
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures { o ->
                        s = (o.x / size.width).coerceIn(0f, 1f)
                        v = 1f - (o.y / size.height).coerceIn(0f, 1f)
                        push()
                    }
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(brush = Brush.horizontalGradient(listOf(Color.White, Color.hsv(h.coerceIn(0f, 359.9f), 1f, 1f))))
                drawRect(brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                val c = Offset(s * size.width, (1f - v) * size.height)
                drawCircle(Color.Black, radius = 9.dp.toPx(), center = c, style = Stroke(3.dp.toPx()))
                drawCircle(Color.White, radius = 8.dp.toPx(), center = c, style = Stroke(2.dp.toPx()))
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(18.dp)
                .clip(RoundedCornerShape(9.dp))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { o ->
                            h = (o.x / size.width).coerceIn(0f, 1f) * 359.9f
                            push()
                        },
                        onDrag = { change, _ ->
                            h = (change.position.x / size.width).coerceIn(0f, 1f) * 359.9f
                            push()
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures { o ->
                        h = (o.x / size.width).coerceIn(0f, 1f) * 359.9f
                        push()
                    }
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val stops = (0..6).map { Color.hsv(it * 60f, 1f, 1f) }
                drawRect(brush = Brush.horizontalGradient(stops))
                val x = (h / 359.9f) * size.width
                drawCircle(Color.White, radius = 8.dp.toPx(), center = Offset(x, size.height / 2f), style = Stroke(2.5.dp.toPx()))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(1.dp, Line, CircleShape)
            )
            OutlinedTextField(
                value = hex,
                onValueChange = { txt ->
                    hex = txt
                    val clean = txt.trim().removePrefix("#")
                    if (clean.length == 6) {
                        val n = clean.toLongOrNull(16)
                        if (n != null) {
                            val c = Color(0xFF000000L or n)
                            val a = hsvOf(c)
                            h = a[0]
                            s = a[1]
                            v = a[2]
                            onColor(c)
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.width(120.dp),
                label = { Text("HEX", fontSize = 10.sp) }
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Swatches.forEach { sw ->
                val c = Color(sw)
                Box(
                    Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(c)
                        .border(1.dp, Line, CircleShape)
                        .clickable {
                            val a = hsvOf(c)
                            h = a[0]
                            s = a[1]
                            v = a[2]
                            push()
                        }
                )
            }
        }
    }
}

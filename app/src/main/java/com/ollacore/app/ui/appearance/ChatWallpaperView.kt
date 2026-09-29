package com.ollacore.app.ui.appearance

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ollacore.app.data.model.ChatWallpaper

/**
 * Renders any [ChatWallpaper] — theme cards, chat background, wallpaper
 * screen all share this, so previews always match the real chat.
 * Painters are deterministic (seeded by wallpaper hash): the card preview
 * and the full-screen background draw identically.
 */
@Composable
fun ChatWallpaperView(
    wallpaper: ChatWallpaper,
    modifier: Modifier = Modifier,
    clipShape: androidx.compose.ui.graphics.Shape = RectangleShape
) {
    when (wallpaper) {
        is ChatWallpaper.Photo -> {
            AsyncImage(
                model = wallpaper.uri,
                contentDescription = "Custom wallpaper",
                contentScale = ContentScale.Crop,
                modifier = modifier.clip(clipShape)
            )
        }
        is ChatWallpaper.Solid -> {
            Box(modifier = modifier.clip(clipShape).background(wallpaper.color))
        }
        else -> {
            val seed = remember(wallpaper) { wallpaper.hashCode() }
            val schemeBase = MaterialTheme.colorScheme.background
            val schemeInk = MaterialTheme.colorScheme.primary.copy(alpha = 0.09f)
            Canvas(modifier = modifier.clip(clipShape)) {
                when (wallpaper) {
                    is ChatWallpaper.Doodle -> drawDoodle(
                        seed, wallpaper.base ?: schemeBase, wallpaper.ink ?: schemeInk
                    )
                    is ChatWallpaper.GradientWash -> drawGradientWash(wallpaper)
                    is ChatWallpaper.PastelBlobs -> drawPastelBlobs(seed, wallpaper)
                    is ChatWallpaper.Floral -> drawFloral(seed, wallpaper)
                    is ChatWallpaper.SkyNature -> drawSkyNature(seed, wallpaper)
                    is ChatWallpaper.DarkLeaves -> drawDarkLeaves(seed, wallpaper)
                    is ChatWallpaper.Waves -> drawWaves(seed, wallpaper)
                    else -> Unit
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDoodle(
    seed: Int, base: Color, ink: Color
) {
    drawRect(base)
    val rnd = kotlin.random.Random(seed)
    val step = 76.dp.toPx()
    var row = 0
    var y = step / 2
    val stroke = 1.4.dp.toPx()
    while (y < size.height) {
        var x = step / 2 + (if (row % 2 == 1) step / 2 else 0f)
        while (x < size.width) {
            when (rnd.nextInt(5)) {
                0 -> drawCircle(ink, radius = 5.dp.toPx(), center = Offset(x, y), style = Stroke(stroke))
                1 -> {
                    val h = 7.dp.toPx()
                    drawLine(ink, Offset(x - h, y), Offset(x + h, y), strokeWidth = stroke)
                    drawLine(ink, Offset(x, y - h), Offset(x, y + h), strokeWidth = stroke)
                }
                2 -> drawArc(
                    ink, 20f, 260f, false,
                    topLeft = Offset(x - 6.dp.toPx(), y - 6.dp.toPx()),
                    size = Size(12.dp.toPx(), 12.dp.toPx()), style = Stroke(stroke)
                )
                3 -> drawRoundRect(
                    ink,
                    topLeft = Offset(x - 5.dp.toPx(), y - 5.dp.toPx()),
                    size = Size(10.dp.toPx(), 10.dp.toPx()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                    style = Stroke(stroke)
                )
                else -> drawCircle(ink, radius = 1.6.dp.toPx(), center = Offset(x, y))
            }
            x += step + rnd.nextInt(-8, 9).dp.toPx()
            y += rnd.nextInt(-6, 7).dp.toPx().coerceIn(-size.height, size.height)
        }
        y = (row + 1) * step + step / 2
        row++
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGradientWash(
    w: ChatWallpaper.GradientWash
) {
    drawRect(Brush.verticalGradient(w.colors))
    val r = size.minDimension
    drawCircle(
        Brush.radialGradient(listOf(w.blob.copy(alpha = 0.55f), w.blob.copy(alpha = 0f)), radius = r * 0.55f),
        radius = r * 0.55f, center = Offset(size.width * 0.85f, size.height * 0.12f)
    )
    drawCircle(
        Brush.radialGradient(listOf(w.blob.copy(alpha = 0.4f), w.blob.copy(alpha = 0f)), radius = r * 0.5f),
        radius = r * 0.5f, center = Offset(size.width * 0.1f, size.height * 0.9f)
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPastelBlobs(
    seed: Int, w: ChatWallpaper.PastelBlobs
) {
    drawRect(w.base)
    val rnd = kotlin.random.Random(seed)
    w.blobs.forEachIndexed { i, c ->
        val r = size.minDimension * (0.35f + rnd.nextFloat() * 0.25f)
        drawCircle(
            c.copy(alpha = 0.75f), radius = r,
            center = Offset(
                size.width * (0.15f + 0.7f * ((i * 0.37f + rnd.nextFloat() * 0.2f) % 1f)),
                size.height * (0.12f + 0.76f * ((i * 0.53f + rnd.nextFloat() * 0.2f) % 1f))
            )
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFloral(
    seed: Int, w: ChatWallpaper.Floral
) {
    drawRect(w.base)
    val rnd = kotlin.random.Random(seed)
    repeat(5) { i ->
        val baseX = size.width * (0.1f + 0.2f * i) + rnd.nextFloat() * 20.dp.toPx()
        val baseY = size.height
        val topX = baseX + (rnd.nextFloat() - 0.5f) * size.width * 0.3f
        val topY = size.height * (0.15f + rnd.nextFloat() * 0.3f)
        // Stem.
        drawLine(
            w.leaf.copy(alpha = 0.8f),
            Offset(baseX, baseY), Offset(topX, topY), strokeWidth = 3.dp.toPx()
        )
        // Petals around the stem top.
        repeat(6) { p ->
            val ang = (p / 6f) * 360f + rnd.nextFloat() * 20f
            rotate(ang, pivot = Offset(topX, topY)) {
                drawOval(
                    w.petal.copy(alpha = 0.85f),
                    topLeft = Offset(topX - 7.dp.toPx(), topY - 22.dp.toPx()),
                    size = Size(14.dp.toPx(), 24.dp.toPx())
                )
            }
        }
        drawCircle(w.petal, radius = 5.dp.toPx(), center = Offset(topX, topY))
        // Leaves.
        rotate(-30f + rnd.nextFloat() * 60f, pivot = Offset(baseX, size.height * 0.7f)) {
            drawOval(
                w.leaf.copy(alpha = 0.7f),
                topLeft = Offset(baseX - 6.dp.toPx(), size.height * 0.7f - 18.dp.toPx()),
                size = Size(12.dp.toPx(), 36.dp.toPx())
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSkyNature(
    seed: Int, w: ChatWallpaper.SkyNature
) {
    drawRect(Brush.verticalGradient(listOf(w.top, w.bottom)))
    // Sun glow.
    drawCircle(
        Brush.radialGradient(
            listOf(Color.White.copy(alpha = 0.7f), Color.White.copy(alpha = 0f)),
            radius = size.minDimension * 0.3f
        ),
        radius = size.minDimension * 0.3f,
        center = Offset(size.width * 0.75f, size.height * 0.2f)
    )
    // Canopy blobs along top + bottom edges.
    val rnd = kotlin.random.Random(seed)
    repeat(14) { i ->
        val topEdge = i % 2 == 0
        val cx = size.width * (rnd.nextFloat())
        val cy = if (topEdge) -size.minDimension * 0.05f else size.height + size.minDimension * 0.05f
        drawCircle(
            w.canopy.copy(alpha = 0.85f),
            radius = size.minDimension * (0.12f + rnd.nextFloat() * 0.12f),
            center = Offset(cx, cy)
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDarkLeaves(
    seed: Int, w: ChatWallpaper.DarkLeaves
) {
    drawRect(w.base)
    val rnd = kotlin.random.Random(seed)
    repeat(7) { i ->
        val cx = size.width * (0.1f + 0.8f * ((i * 0.31f + rnd.nextFloat() * 0.15f) % 1f))
        val cy = size.height * (0.08f + 0.84f * ((i * 0.47f + rnd.nextFloat() * 0.15f) % 1f))
        val rw = size.minDimension * (0.16f + rnd.nextFloat() * 0.14f)
        val rh = rw * 1.9f
        rotate(-25f + rnd.nextFloat() * 50f, pivot = Offset(cx, cy)) {
            drawOval(w.leaf.copy(alpha = 0.9f), topLeft = Offset(cx - rw / 2, cy - rh / 2), size = Size(rw, rh))
            drawLine(w.vein.copy(alpha = 0.9f), Offset(cx, cy - rh / 2), Offset(cx, cy + rh / 2), strokeWidth = 2.dp.toPx())
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWaves(
    seed: Int, w: ChatWallpaper.Waves
) {
    drawRect(w.base)
    val rnd = kotlin.random.Random(seed)
    repeat(6) { i ->
        val y = size.height * (0.05f + 0.18f * i) + rnd.nextFloat() * 20.dp.toPx()
        rotate(-12f, pivot = Offset(size.width / 2, y)) {
            drawRoundRect(
                w.band.copy(alpha = 0.28f + 0.08f * (i % 3)),
                topLeft = Offset(-size.width * 0.1f, y),
                size = Size(size.width * 1.2f, size.minDimension * (0.06f + rnd.nextFloat() * 0.05f)),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(40.dp.toPx(), 40.dp.toPx())
            )
        }
    }
}

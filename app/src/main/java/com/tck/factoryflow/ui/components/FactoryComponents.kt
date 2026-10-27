package com.tck.factoryflow.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.BitmapFactory
import java.io.File

private val TckBlue = Color(0xFF0756A4)
private val TckCyan = Color(0xFF13A7D8)
private val TckNavy = Color(0xFF102B4E)
private val TckGold = Color(0xFFD6A62D)
private val TckSurface = Color(0xFFF1F7FC)

/** Reusable metric card for real, persisted dashboard values. */
@Composable
fun DashboardMetricCard(
    title: String,
    value: String,
    supportingText: String? = null,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.TrendingUp
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(icon, contentDescription = null, tint = TckBlue, modifier = Modifier.size(21.dp))
            Text(title, style = MaterialTheme.typography.labelMedium, color = TckBlue)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = TckNavy)
            if (!supportingText.isNullOrBlank()) {
                Text(supportingText, style = MaterialTheme.typography.bodySmall, color = TckNavy.copy(alpha = 0.7f))
            }
        }
    }
}

/** Loads a scaled bitmap from internal file storage for product thumbnails. */
@Composable
fun rememberProductBitmap(path: String?): ImageBitmap? {
    if (path.isNullOrBlank()) return null
    return remember(path) {
        runCatching {
            val file = File(path)
            if (!file.exists()) return@runCatching null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (maxSide / (sample * 2) >= 160) {
                sample *= 2
            }
            val decode = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(file.absolutePath, decode)?.asImageBitmap()
        }.getOrNull()
    }
}

/** Consistent bottle silhouette used across product, production and dispatch surfaces. */
@Composable
fun BottleTypeIcon(
    type: String,
    modifier: Modifier = Modifier,
    tint: Color = TckBlue,
    iconName: String = "bottle_default"
) {
    val normalized = (type + " " + iconName).lowercase()
    val isWater = normalized.contains("water")
    val isSoda = normalized.contains("soda") || normalized.contains("cola") || normalized.contains("carbonated")
    val isSquare = normalized.contains("square") || normalized.contains("cosmetic")
    val isMilk = normalized.contains("milk") || normalized.contains("dairy") || normalized.contains("jug")
    val isCan = normalized.contains("can") || normalized.contains("jerry") || normalized.contains("agro")
    val isSpray = normalized.contains("spray") || normalized.contains("trigger")
    val isPump = normalized.contains("pump") || normalized.contains("lotion") || normalized.contains("dispenser")
    val isPharma = normalized.contains("pharma") || normalized.contains("pill") || normalized.contains("tablet")
    val isDropper = normalized.contains("dropper") || normalized.contains("serum") || normalized.contains("pipette")
    val isJar = normalized.contains("jar") || normalized.contains("pot") || normalized.contains("cream")
    val isOil = normalized.contains("oil") || normalized.contains("lubricant") || normalized.contains("ribbed")
    val isGallon = normalized.contains("gallon") || normalized.contains("5l") || normalized.contains("canister")

    val isGreen = normalized.contains("green")
    val isMilkWhite = normalized.contains("white")

    val bottleColor = when {
        isGreen -> Color(0xFF16804A)
        isMilkWhite -> Color(0xFF64748B)
        isSoda -> Color(0xFF0284C7)
        isSpray -> Color(0xFF0D9488)
        isPump -> Color(0xFF7C3AED)
        isPharma -> Color(0xFFDC2626)
        isJar -> Color(0xFFD97706)
        isOil -> Color(0xFFB45309)
        isCan -> Color(0xFF475569)
        isDropper -> Color(0xFF4F46E5)
        else -> tint
    }

    Canvas(modifier.size(38.dp)) {
        val w = size.width
        val h = size.height
        val center = w * 0.5f

        when {
            isJar -> {
                // Short, wide cylindrical jar with flat screw lid
                val jarLeft = w * 0.16f
                val jarRight = w * 0.84f
                val jarTop = h * 0.35f
                val jarBottom = h * 0.88f
                val body = Path().apply {
                    moveTo(jarLeft + w * 0.05f, jarTop)
                    lineTo(jarRight - w * 0.05f, jarTop)
                    quadraticTo(jarRight, jarTop, jarRight, jarTop + h * 0.05f)
                    lineTo(jarRight, jarBottom - h * 0.05f)
                    quadraticTo(jarRight, jarBottom, jarRight - w * 0.05f, jarBottom)
                    lineTo(jarLeft + w * 0.05f, jarBottom)
                    quadraticTo(jarLeft, jarBottom, jarLeft, jarBottom - h * 0.05f)
                    lineTo(jarLeft, jarTop + h * 0.05f)
                    quadraticTo(jarLeft, jarTop, jarLeft + w * 0.05f, jarTop)
                    close()
                }
                drawPath(body, bottleColor.copy(alpha = 0.18f))
                drawPath(body, bottleColor, style = Stroke(width = 1.8.dp.toPx()))
                // Wide lid
                drawRoundRect(
                    color = bottleColor,
                    topLeft = Offset(w * 0.12f, h * 0.22f),
                    size = Size(w * 0.76f, h * 0.13f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
                )
            }
            isMilk -> {
                // Dairy jug with molded handle on right side
                val jugLeft = w * 0.20f
                val jugRight = w * 0.72f
                val neckLeft = center - w * 0.10f
                val neckRight = center + w * 0.10f
                val body = Path().apply {
                    moveTo(neckLeft, h * 0.22f)
                    lineTo(neckLeft, h * 0.32f)
                    quadraticTo(neckLeft, h * 0.38f, jugLeft, h * 0.44f)
                    lineTo(jugLeft, h * 0.85f)
                    quadraticTo(jugLeft, h * 0.90f, jugLeft + w * 0.08f, h * 0.90f)
                    lineTo(jugRight - w * 0.08f, h * 0.90f)
                    quadraticTo(jugRight, h * 0.90f, jugRight, h * 0.85f)
                    lineTo(jugRight, h * 0.44f)
                    quadraticTo(jugRight, h * 0.38f, neckRight, h * 0.32f)
                    lineTo(neckRight, h * 0.22f)
                    close()
                }
                drawPath(body, bottleColor.copy(alpha = 0.18f))
                drawPath(body, bottleColor, style = Stroke(width = 1.8.dp.toPx()))
                // Handle loop
                val handle = Path().apply {
                    moveTo(jugRight, h * 0.46f)
                    quadraticTo(w * 0.92f, h * 0.50f, w * 0.92f, h * 0.65f)
                    quadraticTo(w * 0.92f, h * 0.80f, jugRight, h * 0.82f)
                }
                drawPath(handle, bottleColor, style = Stroke(width = 2.dp.toPx()))
                // Cap
                drawRoundRect(
                    color = bottleColor,
                    topLeft = Offset(center - w * 0.12f, h * 0.10f),
                    size = Size(w * 0.24f, h * 0.12f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
                )
            }
            isCan -> {
                // Jerry Can / Agro rectangular industrial can with top bridge handle
                val canLeft = w * 0.18f
                val canRight = w * 0.82f
                val canTop = h * 0.35f
                val canBottom = h * 0.88f
                val body = Path().apply {
                    moveTo(canLeft + w * 0.06f, canTop)
                    lineTo(canRight - w * 0.06f, canTop)
                    quadraticTo(canRight, canTop, canRight, canTop + h * 0.05f)
                    lineTo(canRight, canBottom - h * 0.05f)
                    quadraticTo(canRight, canBottom, canRight - w * 0.06f, canBottom)
                    lineTo(canLeft + w * 0.06f, canBottom)
                    quadraticTo(canLeft, canBottom, canLeft, canBottom - h * 0.05f)
                    lineTo(canLeft, canTop + h * 0.05f)
                    quadraticTo(canLeft, canTop, canLeft + w * 0.06f, canTop)
                    close()
                }
                drawPath(body, bottleColor.copy(alpha = 0.18f))
                drawPath(body, bottleColor, style = Stroke(width = 1.8.dp.toPx()))
                // Top handle bridge
                val handle = Path().apply {
                    moveTo(canLeft + w * 0.15f, canTop)
                    lineTo(canLeft + w * 0.15f, h * 0.18f)
                    lineTo(canRight - w * 0.25f, h * 0.18f)
                    lineTo(canRight - w * 0.25f, canTop)
                }
                drawPath(handle, bottleColor, style = Stroke(width = 2.dp.toPx()))
                // Spout cap on top right
                drawRoundRect(
                    color = bottleColor,
                    topLeft = Offset(canRight - w * 0.24f, h * 0.22f),
                    size = Size(w * 0.18f, h * 0.13f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
                )
            }
            isSpray -> {
                // Trigger spray bottle with sideways trigger nozzle
                val sprayLeft = w * 0.28f
                val sprayRight = w * 0.72f
                val neckLeft = center - w * 0.08f
                val neckRight = center + w * 0.08f
                val body = Path().apply {
                    moveTo(neckLeft, h * 0.38f)
                    lineTo(neckLeft, h * 0.44f)
                    quadraticTo(neckLeft, h * 0.50f, sprayLeft, h * 0.54f)
                    lineTo(sprayLeft, h * 0.88f)
                    lineTo(sprayRight, h * 0.88f)
                    lineTo(sprayRight, h * 0.54f)
                    quadraticTo(neckRight, h * 0.50f, neckRight, h * 0.44f)
                    lineTo(neckRight, h * 0.38f)
                    close()
                }
                drawPath(body, bottleColor.copy(alpha = 0.18f))
                drawPath(body, bottleColor, style = Stroke(width = 1.8.dp.toPx()))
                // Trigger spray mechanism head
                val sprayHead = Path().apply {
                    moveTo(center - w * 0.25f, h * 0.22f) // nozzle tip
                    lineTo(center + w * 0.22f, h * 0.22f)
                    lineTo(center + w * 0.18f, h * 0.36f)
                    lineTo(center - w * 0.12f, h * 0.36f)
                    lineTo(center - w * 0.18f, h * 0.28f)
                    close()
                }
                drawPath(sprayHead, bottleColor)
                // Trigger lever
                drawLine(bottleColor, Offset(center - w * 0.10f, h * 0.36f), Offset(center - w * 0.20f, h * 0.48f), strokeWidth = 2.dp.toPx())
            }
            isPump -> {
                // Lotion dispenser pump with T-shaped plunger
                val pumpLeft = w * 0.28f
                val pumpRight = w * 0.72f
                val neckLeft = center - w * 0.09f
                val neckRight = center + w * 0.09f
                val body = Path().apply {
                    moveTo(neckLeft, h * 0.35f)
                    lineTo(neckLeft, h * 0.42f)
                    quadraticTo(neckLeft, h * 0.48f, pumpLeft, h * 0.52f)
                    lineTo(pumpLeft, h * 0.88f)
                    lineTo(pumpRight, h * 0.88f)
                    lineTo(pumpRight, h * 0.52f)
                    quadraticTo(neckRight, h * 0.48f, neckRight, h * 0.42f)
                    lineTo(neckRight, h * 0.35f)
                    close()
                }
                drawPath(body, bottleColor.copy(alpha = 0.18f))
                drawPath(body, bottleColor, style = Stroke(width = 1.8.dp.toPx()))
                // Pump collar
                drawRect(bottleColor, Offset(center - w * 0.12f, h * 0.30f), Size(w * 0.24f, h * 0.06f))
                // Stem
                drawRect(bottleColor, Offset(center - w * 0.035f, h * 0.18f), Size(w * 0.07f, h * 0.12f))
                // Horizontal dispenser actuator with spout
                val actuator = Path().apply {
                    moveTo(center - w * 0.30f, h * 0.18f) // spout
                    lineTo(center + w * 0.16f, h * 0.16f)
                    lineTo(center + w * 0.16f, h * 0.23f)
                    lineTo(center - w * 0.22f, h * 0.23f)
                    close()
                }
                drawPath(actuator, bottleColor)
            }
            isPharma -> {
                // Cylindrical medicine pill bottle with wide child-resistant cap
                val pharmaLeft = w * 0.24f
                val pharmaRight = w * 0.76f
                val neckLeft = center - w * 0.18f
                val neckRight = center + w * 0.18f
                val body = Path().apply {
                    moveTo(neckLeft, h * 0.28f)
                    lineTo(neckLeft, h * 0.34f)
                    quadraticTo(neckLeft, h * 0.38f, pharmaLeft, h * 0.42f)
                    lineTo(pharmaLeft, h * 0.88f)
                    lineTo(pharmaRight, h * 0.88f)
                    lineTo(pharmaRight, h * 0.42f)
                    quadraticTo(neckRight, h * 0.38f, neckRight, h * 0.34f)
                    lineTo(neckRight, h * 0.28f)
                    close()
                }
                drawPath(body, bottleColor.copy(alpha = 0.18f))
                drawPath(body, bottleColor, style = Stroke(width = 1.8.dp.toPx()))
                // Wide ribbed safety cap
                drawRoundRect(
                    color = bottleColor,
                    topLeft = Offset(center - w * 0.22f, h * 0.12f),
                    size = Size(w * 0.44f, h * 0.16f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
                )
            }
            isDropper -> {
                // Boston round dropper bottle with pipette rubber bulb
                val dropLeft = w * 0.28f
                val dropRight = w * 0.72f
                val neckLeft = center - w * 0.08f
                val neckRight = center + w * 0.08f
                val body = Path().apply {
                    moveTo(neckLeft, h * 0.42f)
                    lineTo(neckLeft, h * 0.48f)
                    quadraticTo(neckLeft, h * 0.54f, dropLeft, h * 0.58f)
                    lineTo(dropLeft, h * 0.88f)
                    lineTo(dropRight, h * 0.88f)
                    lineTo(dropRight, h * 0.58f)
                    quadraticTo(neckRight, h * 0.54f, neckRight, h * 0.48f)
                    lineTo(neckRight, h * 0.42f)
                    close()
                }
                drawPath(body, bottleColor.copy(alpha = 0.18f))
                drawPath(body, bottleColor, style = Stroke(width = 1.8.dp.toPx()))
                // Collar
                drawRect(bottleColor, Offset(center - w * 0.11f, h * 0.34f), Size(w * 0.22f, h * 0.08f))
                // Rubber bulb
                val bulb = Path().apply {
                    moveTo(center - w * 0.08f, h * 0.34f)
                    quadraticTo(center - w * 0.12f, h * 0.16f, center, h * 0.12f)
                    quadraticTo(center + w * 0.12f, h * 0.16f, center + w * 0.08f, h * 0.34f)
                    close()
                }
                drawPath(bulb, bottleColor)
            }
            isSquare -> {
                // Square / flat rectangular profile bottle with clean chamfered shoulders
                val sqLeft = w * 0.24f
                val sqRight = w * 0.76f
                val neckLeft = center - w * 0.09f
                val neckRight = center + w * 0.09f
                val body = Path().apply {
                    moveTo(neckLeft, h * 0.24f)
                    lineTo(neckLeft, h * 0.30f)
                    lineTo(sqLeft, h * 0.38f)
                    lineTo(sqLeft, h * 0.88f)
                    lineTo(sqRight, h * 0.88f)
                    lineTo(sqRight, h * 0.38f)
                    lineTo(neckRight, h * 0.30f)
                    lineTo(neckRight, h * 0.24f)
                    close()
                }
                drawPath(body, bottleColor.copy(alpha = 0.18f))
                drawPath(body, bottleColor, style = Stroke(width = 1.8.dp.toPx()))
                // Modern flat cap
                drawRoundRect(
                    color = bottleColor,
                    topLeft = Offset(center - w * 0.11f, h * 0.12f),
                    size = Size(w * 0.22f, h * 0.12f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx())
                )
            }
            isSoda -> {
                // Soda / Cola contoured waist with petaloid base
                val sodaLeft = w * 0.26f
                val sodaRight = w * 0.74f
                val neckLeft = center - w * 0.08f
                val neckRight = center + w * 0.08f
                val body = Path().apply {
                    moveTo(neckLeft, h * 0.20f)
                    lineTo(neckLeft, h * 0.30f)
                    quadraticTo(neckLeft, h * 0.38f, sodaLeft, h * 0.44f)
                    quadraticTo(center - w * 0.16f, h * 0.60f, sodaLeft, h * 0.76f)
                    lineTo(sodaLeft, h * 0.86f)
                    lineTo(sodaRight, h * 0.86f)
                    lineTo(sodaRight, h * 0.76f)
                    quadraticTo(center + w * 0.16f, h * 0.60f, sodaRight, h * 0.44f)
                    quadraticTo(neckRight, h * 0.38f, neckRight, h * 0.30f)
                    lineTo(neckRight, h * 0.20f)
                    close()
                }
                drawPath(body, bottleColor.copy(alpha = 0.18f))
                drawPath(body, bottleColor, style = Stroke(width = 1.8.dp.toPx()))
                // Petaloid base feet notches
                drawLine(bottleColor, Offset(center - w * 0.12f, h * 0.86f), Offset(center - w * 0.12f, h * 0.91f), strokeWidth = 2.dp.toPx())
                drawLine(bottleColor, Offset(center, h * 0.86f), Offset(center, h * 0.91f), strokeWidth = 2.dp.toPx())
                drawLine(bottleColor, Offset(center + w * 0.12f, h * 0.86f), Offset(center + w * 0.12f, h * 0.91f), strokeWidth = 2.dp.toPx())
                // Cap
                drawRoundRect(
                    color = bottleColor,
                    topLeft = Offset(center - w * 0.10f, h * 0.08f),
                    size = Size(w * 0.20f, h * 0.12f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
                )
            }
            isOil -> {
                // Ribbed edible / engine oil bottle with pourer spout
                val oilLeft = w * 0.26f
                val oilRight = w * 0.74f
                val neckLeft = center - w * 0.07f
                val neckRight = center + w * 0.07f
                val body = Path().apply {
                    moveTo(neckLeft, h * 0.22f)
                    lineTo(neckLeft, h * 0.30f)
                    lineTo(oilLeft, h * 0.42f)
                    lineTo(oilLeft, h * 0.88f)
                    lineTo(oilRight, h * 0.88f)
                    lineTo(oilRight, h * 0.42f)
                    lineTo(neckRight, h * 0.30f)
                    lineTo(neckRight, h * 0.22f)
                    close()
                }
                drawPath(body, bottleColor.copy(alpha = 0.18f))
                drawPath(body, bottleColor, style = Stroke(width = 1.8.dp.toPx()))
                // Horizontal ribs for grip
                drawLine(bottleColor.copy(alpha = 0.5f), Offset(oilLeft + w * 0.06f, h * 0.52f), Offset(oilRight - w * 0.06f, h * 0.52f), strokeWidth = 1.5.dp.toPx())
                drawLine(bottleColor.copy(alpha = 0.5f), Offset(oilLeft + w * 0.06f, h * 0.62f), Offset(oilRight - w * 0.06f, h * 0.62f), strokeWidth = 1.5.dp.toPx())
                drawLine(bottleColor.copy(alpha = 0.5f), Offset(oilLeft + w * 0.06f, h * 0.72f), Offset(oilRight - w * 0.06f, h * 0.72f), strokeWidth = 1.5.dp.toPx())
                // Pourer cap
                drawRoundRect(
                    color = bottleColor,
                    topLeft = Offset(center - w * 0.09f, h * 0.10f),
                    size = Size(w * 0.18f, h * 0.12f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
                )
            }
            isGallon -> {
                // 5L Gallon container with molded neck ring
                val galLeft = w * 0.20f
                val galRight = w * 0.80f
                val neckLeft = center - w * 0.14f
                val neckRight = center + w * 0.14f
                val body = Path().apply {
                    moveTo(neckLeft, h * 0.26f)
                    lineTo(neckLeft, h * 0.32f)
                    quadraticTo(neckLeft, h * 0.38f, galLeft, h * 0.44f)
                    lineTo(galLeft, h * 0.88f)
                    lineTo(galRight, h * 0.88f)
                    lineTo(galRight, h * 0.44f)
                    quadraticTo(neckRight, h * 0.38f, neckRight, h * 0.32f)
                    lineTo(neckRight, h * 0.26f)
                    close()
                }
                drawPath(body, bottleColor.copy(alpha = 0.18f))
                drawPath(body, bottleColor, style = Stroke(width = 1.8.dp.toPx()))
                // Heavy ring collar
                drawRoundRect(
                    color = bottleColor,
                    topLeft = Offset(center - w * 0.18f, h * 0.24f),
                    size = Size(w * 0.36f, h * 0.07f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
                )
                // Cap
                drawRoundRect(
                    color = bottleColor,
                    topLeft = Offset(center - w * 0.14f, h * 0.12f),
                    size = Size(w * 0.28f, h * 0.12f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
                )
            }
            else -> {
                // Classic Mineral Water bottle with contoured grip and ribs
                val waterLeft = w * 0.28f
                val waterRight = w * 0.72f
                val neckLeft = center - w * 0.09f
                val neckRight = center + w * 0.09f
                val body = Path().apply {
                    moveTo(neckLeft, h * 0.18f)
                    lineTo(neckLeft, h * 0.28f)
                    quadraticTo(neckLeft, h * 0.36f, waterLeft, h * 0.42f)
                    lineTo(waterLeft, h * 0.88f)
                    lineTo(waterRight, h * 0.88f)
                    lineTo(waterRight, h * 0.42f)
                    quadraticTo(neckRight, h * 0.36f, neckRight, h * 0.28f)
                    lineTo(neckRight, h * 0.18f)
                    close()
                }
                drawPath(body, bottleColor.copy(alpha = 0.18f))
                drawPath(body, bottleColor, style = Stroke(width = 1.8.dp.toPx()))
                // Horizontal grip ribs
                drawLine(bottleColor.copy(alpha = 0.45f), Offset(waterLeft + w * 0.04f, h * 0.54f), Offset(waterRight - w * 0.04f, h * 0.54f), strokeWidth = 1.4.dp.toPx())
                drawLine(bottleColor.copy(alpha = 0.45f), Offset(waterLeft + w * 0.04f, h * 0.62f), Offset(waterRight - w * 0.04f, h * 0.62f), strokeWidth = 1.4.dp.toPx())
                // Screw cap
                drawRoundRect(
                    color = bottleColor,
                    topLeft = Offset(center - w * 0.11f, h * 0.08f),
                    size = Size(w * 0.22f, h * 0.11f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
                )
            }
        }
    }
}

/** Thumbnail showing either the user-uploaded bottle image or a styled vector icon. */
@Composable
fun ProductThumbnail(
    imageUri: String = "",
    iconName: String = "bottle_default",
    colour: String = "Clear",
    bottleType: String = "",
    size: androidx.compose.ui.unit.Dp = 44.dp,
    modifier: Modifier = Modifier
) {
    val bitmap = rememberProductBitmap(imageUri)
    if (bitmap != null) {
        Box(
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, Color(0xFFDCE7F1), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = "Bottle Custom Image",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    } else {
        Surface(
            modifier = modifier.size(size),
            shape = RoundedCornerShape(10.dp),
            color = Color(0xFFF1F7FC),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFDCE7F1))
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(3.dp)) {
                BottleTypeIcon(type = if (bottleType.isNotBlank()) bottleType else colour, iconName = iconName, modifier = Modifier.size(size - 6.dp))
            }
        }
    }
}

/** Product tile with clean specifications and active/deactivated badge. */
@Composable
fun BottleProductCard(
    name: String,
    capacity: String,
    packSizeLabel: String,
    active: Boolean,
    bottleType: String = "water",
    iconName: String = "bottle_default",
    imageUri: String = "",
    colour: String = "Clear",
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    Card(
        modifier = modifier,
        onClick = onClick ?: {},
        enabled = onClick != null,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFDCE7F1))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ProductThumbnail(imageUri = imageUri, iconName = iconName, colour = colour, bottleType = bottleType, size = 46.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(name, fontWeight = FontWeight.SemiBold, color = TckNavy)
                Text(
                    text = "$capacity · $colour" + if (packSizeLabel.isNotBlank() && !packSizeLabel.contains("not configured", true)) " · $packSizeLabel" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = TckBlue
                )
            }
            Surface(
                color = if (active) Color(0xFFE8F7EE) else Color(0xFFF1F5F9),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    if (active) "Active" else "Deactivated",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (active) Color(0xFF16804A) else Color.Gray
                )
            }
        }
    }
}

data class BottlePresetOption(
    val id: String,
    val title: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val color: Color
)

@Composable
fun BottleIconPicker(
    selectedIcon: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val presets = listOf(
        BottlePresetOption("bottle_water", "Water Bottle", Icons.Default.WaterDrop, Color(0xFF0756A4)),
        BottlePresetOption("bottle_soda", "Soda / Cola", Icons.Default.LocalBar, Color(0xFF0284C7)),
        BottlePresetOption("bottle_square", "Square Bottle", Icons.Default.CropSquare, Color(0xFF2563EB)),
        BottlePresetOption("bottle_milk", "Milk / Juice Jug", Icons.Default.Egg, Color(0xFF64748B)),
        BottlePresetOption("bottle_can", "Jerry Can / Agro", Icons.Default.OilBarrel, Color(0xFF475569)),
        BottlePresetOption("bottle_spray", "Trigger Spray", Icons.Default.Shower, Color(0xFF0D9488)),
        BottlePresetOption("bottle_pump", "Lotion Pump", Icons.Default.CleanHands, Color(0xFF7C3AED)),
        BottlePresetOption("bottle_pharma", "Pharma Pill Jar", Icons.Default.Medication, Color(0xFFDC2626)),
        BottlePresetOption("bottle_dropper", "Dropper Serum", Icons.Default.Science, Color(0xFF4F46E5)),
        BottlePresetOption("bottle_jar", "Cosmetic / Jar", Icons.Default.Kitchen, Color(0xFFD97706)),
        BottlePresetOption("bottle_oil", "Ribbed Oil Bottle", Icons.Default.FormatColorFill, Color(0xFFB45309)),
        BottlePresetOption("bottle_gallon", "5L Gallon Can", Icons.Default.LocalDrink, Color(0xFF0369A1))
    )

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Choose bottle icon & style", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TckNavy)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            items(presets, key = { it.id }) { preset ->
                val isSelected = selectedIcon == preset.id || (selectedIcon.isBlank() && preset.id == "bottle_water")
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) preset.color.copy(alpha = 0.12f) else Color.White,
                    border = androidx.compose.foundation.BorderStroke(
                        if (isSelected) 2.dp else 1.dp,
                        if (isSelected) preset.color else Color(0xFFDCE7F1)
                    ),
                    modifier = Modifier.clickable { onSelect(preset.id) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        BottleTypeIcon(type = preset.title, iconName = preset.id, tint = preset.color, modifier = Modifier.size(24.dp))
                        Text(
                            preset.title,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) TckNavy else Color(0xFF6B7E93)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun QuantityInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    allowDecimal: Boolean = false,
    supportingText: String? = null,
    enabled: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = { input ->
            val filtered = if (allowDecimal) input.filter { it.isDigit() || it == '.' }.let { raw ->
                val firstDot = raw.indexOf('.')
                if (firstDot < 0) raw else raw.substring(0, firstDot + 1) + raw.substring(firstDot + 1).replace(".", "")
            } else input.filter(Char::isDigit)
            onValueChange(filtered)
        },
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        supportingText = supportingText?.let { { Text(it) } },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = if (allowDecimal) KeyboardType.Decimal else KeyboardType.Number)
    )
}

/** Displays the exact floor/remainder result; invalid pack configuration is never guessed. */
@Composable
fun PackCalculationCard(
    eligibleBottles: Int,
    bottlesPerPack: Int?,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Automatic pack calculation", fontWeight = FontWeight.SemiBold, color = TckNavy)
            if (bottlesPerPack == null || bottlesPerPack <= 0) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.WarningAmber, contentDescription = null, tint = Color(0xFF9B5B00))
                    Text("Pack size not configured. Set a valid positive bottles-per-pack value to calculate packs.", color = Color(0xFF9B5B00), style = MaterialTheme.typography.bodySmall)
                }
            } else if (eligibleBottles < 0) {
                Text("Eligible bottle quantity cannot be negative.", color = MaterialTheme.colorScheme.error)
            } else {
                val packs = eligibleBottles / bottlesPerPack
                val loose = eligibleBottles % bottlesPerPack
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Complete packs", style = MaterialTheme.typography.labelMedium, color = TckBlue)
                        Text(packs.toString(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = TckNavy)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Loose bottles", style = MaterialTheme.typography.labelMedium, color = TckBlue)
                        Text(loose.toString(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = TckNavy)
                    }
                }
                Text("$eligibleBottles bottles ÷ $bottlesPerPack per pack", style = MaterialTheme.typography.bodySmall, color = TckNavy.copy(alpha = .7f))
            }
        }
    }
}

@Composable
fun StockMovementRow(
    title: String,
    quantity: String,
    unit: String,
    timestamp: String,
    source: String,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Inventory2, contentDescription = null, tint = TckBlue, modifier = Modifier.size(19.dp))
            Text(title, Modifier.weight(1f).padding(start = 8.dp), fontWeight = FontWeight.SemiBold, color = TckNavy)
            Text("$quantity $unit", fontWeight = FontWeight.Bold, color = TckBlue)
        }
        Text("$timestamp · $source", style = MaterialTheme.typography.bodySmall, color = TckNavy.copy(alpha = .65f))
    }
}

@Composable
fun DispatchItemRow(
    productName: String,
    packs: Int,
    looseBottles: Int,
    modifier: Modifier = Modifier
) {
    Row(modifier.fillMaxWidth().background(TckSurface, RoundedCornerShape(10.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        BottleTypeIcon(productName, modifier = Modifier.size(30.dp))
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(productName, fontWeight = FontWeight.SemiBold, color = TckNavy)
            Text("$packs complete packs · $looseBottles loose bottles", style = MaterialTheme.typography.bodySmall, color = TckBlue)
        }
    }
}

@Composable
fun DateRangeSelector(
    fromDate: String,
    toDate: String,
    onFromDateChange: (String) -> Unit,
    onToDateChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = TckBlue)
            Text("Report date range", fontWeight = FontWeight.SemiBold, color = TckNavy)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = fromDate,
                onValueChange = onFromDateChange,
                modifier = Modifier.weight(1f),
                label = { Text("From (YYYY-MM-DD)") },
                singleLine = true
            )
            OutlinedTextField(
                value = toDate,
                onValueChange = onToDateChange,
                modifier = Modifier.weight(1f),
                label = { Text("To (YYYY-MM-DD)") },
                singleLine = true
            )
        }
    }
}

@Composable
fun ComparisonMetric(
    label: String,
    currentValue: String,
    previousValue: String,
    absoluteDifference: String,
    percentageChange: String?,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = TckBlue)
            Text(currentValue, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TckNavy)
            Text("Previous: $previousValue", style = MaterialTheme.typography.bodySmall, color = TckNavy.copy(alpha = .7f))
            HorizontalDivider(color = TckSurface)
            Text("Difference: $absoluteDifference", style = MaterialTheme.typography.bodySmall, color = TckNavy)
            Text(percentageChange ?: "Percentage change unavailable (zero or missing baseline)", style = MaterialTheme.typography.labelSmall, color = TckBlue)
        }
    }
}

@Composable
fun ChartCard(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(modifier = modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = TckNavy)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TckNavy.copy(alpha = .7f))
            content()
        }
    }
}

@Composable
fun ApprovalStatusChip(status: String, modifier: Modifier = Modifier) {
    val normalized = status.lowercase()
    val (label, tint, icon) = when (normalized) {
        "approved", "posted", "synced" -> Triple(status, Color(0xFF16804A), Icons.Default.CheckCircle)
        "pending", "awaiting approval" -> Triple(status, Color(0xFF9B5B00), Icons.Default.WarningAmber)
        "failed", "rejected", "conflict" -> Triple(status, Color(0xFFB3261E), Icons.Default.ErrorOutline)
        else -> Triple(status, TckBlue, Icons.Default.Refresh)
    }
    Surface(modifier = modifier, color = tint.copy(alpha = .10f), shape = RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
        }
    }
}

@Composable
fun EmptyState(title: String, description: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.Inventory2, contentDescription = null, tint = TckCyan, modifier = Modifier.size(34.dp))
        Text(title, fontWeight = FontWeight.SemiBold, color = TckNavy)
        Text(description, style = MaterialTheme.typography.bodySmall, color = TckNavy.copy(alpha = .7f))
        if (actionLabel != null && onAction != null) TextButton(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
fun ErrorState(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(30.dp))
        Text("Something went wrong", fontWeight = FontWeight.SemiBold, color = TckNavy)
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        if (onRetry != null) Button(onClick = onRetry) { Icon(Icons.Default.Refresh, contentDescription = null); Spacer(Modifier.size(6.dp)); Text("Retry") }
    }
}

@Composable
fun LoadingState(label: String = "Loading…", modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(color = TckBlue)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TckNavy)
    }
}

@Composable
fun ConfirmationDialog(
    title: String,
    message: String,
    confirmLabel: String = "Confirm",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { Button(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun StartupErrorScreen(
    error: String,
    onRetry: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().background(Color(0xFFF1F7FC)),
        color = Color(0xFFF1F7FC)
    ) {
        Column(
            modifier = Modifier.padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.White,
                shadowElevation = 2.dp,
                modifier = Modifier.size(64.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = Color(0xFFB42318), modifier = Modifier.size(36.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("TCK Bottle Manufacturing", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = TckNavy)
            Text("DATABASE INITIALIZATION ERROR", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TckBlue, letterSpacing = 1.2.sp)
            Spacer(Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The application could not open or migrate its local database safely.", fontWeight = FontWeight.SemiBold, color = TckNavy, fontSize = 13.sp)
                    Text(error, style = MaterialTheme.typography.bodySmall, color = Color(0xFFB42318))
                    Text("Your existing factory records have been preserved without destructive changes.", fontSize = 11.sp, color = TckNavy.copy(alpha = 0.7f))
                }
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onRetry,
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Retry Startup")
            }
        }
    }
}


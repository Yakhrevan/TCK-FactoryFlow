package com.tck.factoryflow.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

/** Consistent bottle silhouette used across product, production and dispatch surfaces. */
@Composable
fun BottleTypeIcon(
    type: String,
    modifier: Modifier = Modifier,
    tint: Color = TckBlue
) {
    val normalized = type.lowercase()
    val isGreen = normalized.contains("green")
    val isMilkWhite = normalized.contains("milk") || normalized.contains("white")
    val isLarge = normalized.contains("1 l") || normalized.contains("1l") || normalized.contains("litre") || normalized.contains("liter")
    val isSmall = normalized.contains("200") || normalized.contains("mini")
    val isSlim = normalized.contains("slim") || normalized.contains("sport")
    val isSoda = normalized.contains("soda") || normalized.contains("carbonated")
    val bottleColor = when {
        isGreen -> Color(0xFF27965B)
        isMilkWhite -> Color(0xFF9AAFC0)
        else -> tint
    }
    Canvas(modifier.size(38.dp)) {
        val w = size.width
        val h = size.height
        val center = w * .5f
        val bodyLeft = when {
            isLarge -> w * .25f
            isSlim -> w * .39f
            isSmall -> w * .34f
            else -> w * .30f
        }
        val bodyRight = w - bodyLeft
        val neckHalf = if (isSoda) w * .085f else w * .105f
        val neckLeft = center - neckHalf
        val neckRight = center + neckHalf
        val shoulderY = if (isLarge) h * .30f else h * .34f
        val bottom = h * .93f
        val shoulderInset = if (isLarge) w * .035f else w * .015f
        val body = Path().apply {
            moveTo(neckLeft, h * .20f)
            lineTo(neckLeft, h * .31f)
            quadraticTo(neckLeft, h * .36f, bodyLeft + shoulderInset, shoulderY)
            if (isSoda) {
                quadraticTo(bodyLeft, h * .43f, bodyLeft, h * .49f)
            } else {
                quadraticTo(bodyLeft, h * .40f, bodyLeft, h * .47f)
            }
            lineTo(bodyLeft, bottom - h * .055f)
            quadraticTo(bodyLeft, bottom, center, bottom)
            quadraticTo(bodyRight, bottom, bodyRight, bottom - h * .055f)
            lineTo(bodyRight, h * .47f)
            if (isSoda) {
                quadraticTo(bodyRight, h * .43f, bodyRight - shoulderInset, shoulderY)
            } else {
                quadraticTo(bodyRight, h * .40f, bodyRight - shoulderInset, shoulderY)
            }
            quadraticTo(neckRight, h * .36f, neckRight, h * .31f)
            lineTo(neckRight, h * .20f)
            close()
        }
        drawPath(body, bottleColor.copy(alpha = if (isMilkWhite) .16f else .13f))
        drawPath(body, bottleColor.copy(alpha = if (isMilkWhite) .9f else .95f), style = Stroke(width = 1.65.dp.toPx()))
        // Capacity-specific moulding details help distinguish product silhouettes at small sizes.
        if (isLarge) {
            drawLine(bottleColor.copy(alpha = .45f), Offset(bodyLeft + w * .035f, h * .59f), Offset(bodyRight - w * .035f, h * .59f), strokeWidth = 1.dp.toPx())
            drawLine(bottleColor.copy(alpha = .45f), Offset(bodyLeft + w * .035f, h * .64f), Offset(bodyRight - w * .035f, h * .64f), strokeWidth = 1.dp.toPx())
        } else if (isSmall) {
            drawLine(bottleColor.copy(alpha = .4f), Offset(bodyLeft + w * .035f, h * .63f), Offset(bodyRight - w * .035f, h * .63f), strokeWidth = 1.dp.toPx())
        }
        drawRoundRect(
            color = if (isGreen) Color(0xFF176C42) else TckBlue,
            topLeft = Offset(center - w * .115f, h * .08f),
            size = Size(w * .23f, h * .13f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
        )
        if (isMilkWhite) {
            drawRoundRect(
                color = Color.White.copy(alpha = .8f),
                topLeft = Offset(bodyLeft + w * .07f, h * .49f),
                size = Size(w * .055f, h * .27f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
            )
        }
    }
}

/** Product tile; callers supply product configuration and should not invent pack sizes. */
@Composable
fun BottleProductCard(
    name: String,
    capacity: String,
    packSizeLabel: String,
    active: Boolean,
    bottleType: String = "water",
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val cardModifier = if (onClick == null) modifier else modifier
    Card(
        modifier = cardModifier,
        onClick = onClick ?: {},
        enabled = onClick != null,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            BottleTypeIcon(bottleType)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(name, fontWeight = FontWeight.SemiBold, color = TckNavy)
                Text(capacity, style = MaterialTheme.typography.bodySmall, color = TckBlue)
                Text(packSizeLabel, style = MaterialTheme.typography.labelSmall, color = if (packSizeLabel.contains("not configured", true)) Color(0xFF9B5B00) else TckNavy.copy(alpha = .7f))
            }
            Text(if (active) "Active" else "Inactive", style = MaterialTheme.typography.labelSmall, color = if (active) Color(0xFF16804A) else Color.Gray)
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

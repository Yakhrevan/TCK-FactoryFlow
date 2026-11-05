package com.tck.factoryflow.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tck.factoryflow.ui.theme.TCKColors
import com.tck.factoryflow.ui.theme.TCKShapes
import com.tck.factoryflow.ui.theme.TCKSpacing
import com.tck.factoryflow.ui.theme.TCKTypography

/**
 * Standardized Semantic Status Badge.
 * High contrast, legible, and non-garish. Never relies on color alone.
 */
@Composable
fun TCKStatusBadge(
    status: String,
    modifier: Modifier = Modifier
) {
    val clean = status.trim().uppercase()
    val (bgColor, textColor, icon) = when {
        clean in setOf("RUNNING", "ACTIVE", "COMPLETED", "POSTED", "PASS", "APPROVED", "OPEN") ->
            Triple(TCKColors.SuccessLight, TCKColors.Success, Icons.Default.CheckCircle)

        clean in setOf("PLANNED", "IN_PROGRESS", "PENDING", "MORNING") ->
            Triple(TCKColors.PrimaryLight, TCKColors.Primary, Icons.Default.Schedule)

        clean in setOf("STOPPED", "LOW_STOCK", "PARTIALLY_DISPATCHED", "MAINTENANCE", "DRAFT") ->
            Triple(TCKColors.WarningLight, TCKColors.Warning, Icons.Default.WarningAmber)

        clean in setOf("DOWN", "CRITICAL", "FAIL", "REVERSED", "CANCELLED", "CLOSED") ->
            Triple(TCKColors.ErrorLight, TCKColors.Error, Icons.Default.ErrorOutline)

        else ->
            Triple(TCKColors.SurfaceVariant, TCKColors.TextSecondary, Icons.Default.Info)
    }

    Surface(
        modifier = modifier,
        shape = TCKShapes.pill,
        color = bgColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, textColor.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(11.dp)
            )
            Text(
                text = clean,
                style = TCKTypography.badgeText,
                color = textColor
            )
        }
    }
}

/**
 * World-Class Operational KPI Metric Card.
 * High information density, high contrast, clean typography.
 */
@Composable
fun TCKMetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    trend: String? = null,
    isPositiveTrend: Boolean? = null,
    icon: ImageVector? = null,
    accentColor: Color = TCKColors.Primary,
    onClick: (() -> Unit)? = null
) {
    Surface(
        modifier = modifier.then(
            if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
        ),
        shape = TCKShapes.medium,
        color = TCKColors.Surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, TCKColors.Border),
        tonalElevation = 1.dp
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            // Subtle left accent strip
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(accentColor)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = label.uppercase(),
                        style = TCKTypography.labelSmall,
                        color = TCKColors.TextMuted,
                        letterSpacing = 1.sp
                    )
                    if (icon != null) {
                        Surface(
                            shape = TCKShapes.small,
                            color = accentColor.copy(alpha = 0.10f),
                            modifier = Modifier.size(26.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = accentColor,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = value,
                        style = TCKTypography.metricLarge,
                        color = TCKColors.TextPrimary
                    )
                    if (!unit.isNullOrBlank()) {
                        Text(
                            text = unit,
                            style = TCKTypography.labelSmall,
                            color = TCKColors.TextMuted,
                            modifier = Modifier.padding(bottom = 3.dp)
                        )
                    }
                }

                if (!trend.isNullOrBlank()) {
                    val trendColor = when (isPositiveTrend) {
                        true -> TCKColors.Success
                        false -> TCKColors.Error
                        null -> TCKColors.TextMuted
                    }
                    Text(
                        text = trend,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = trendColor
                    )
                }
            }
        }
    }
}

/**
 * Target vs Actual Progress Visualization Card.
 */
@Composable
fun TCKProgressCard(
    title: String,
    target: Int,
    actual: Int,
    modifier: Modifier = Modifier,
    unit: String = "bottles"
) {
    val progress = if (target > 0) (actual.toFloat() / target.toFloat()).coerceIn(0f, 1f) else 0f
    val percentage = (progress * 100).toInt()

    Surface(
        modifier = modifier,
        shape = TCKShapes.medium,
        color = TCKColors.Surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, TCKColors.Border)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = title.uppercase(),
                        style = TCKTypography.labelSmall,
                        color = TCKColors.TextMuted,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "$actual / $target $unit",
                        style = TCKTypography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TCKColors.TextPrimary
                    )
                }
                Surface(
                    shape = TCKShapes.pill,
                    color = if (percentage >= 100) TCKColors.SuccessLight else TCKColors.PrimaryLight
                ) {
                    Text(
                        text = "$percentage%",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = TCKTypography.badgeText,
                        color = if (percentage >= 100) TCKColors.Success else TCKColors.Primary
                    )
                }
            }

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(TCKShapes.pill),
                color = if (percentage >= 100) TCKColors.Success else TCKColors.Primary,
                trackColor = TCKColors.Border
            )
        }
    }
}

/**
 * Standardized Page Header with contextual subtitle and optional primary action.
 */
@Composable
fun TCKPageHeader(
    tag: String,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    actionButton: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = tag.uppercase(),
                style = TCKTypography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = TCKColors.Primary,
                letterSpacing = 1.4.sp
            )
            Text(
                text = title,
                style = TCKTypography.headlineLarge,
                color = TCKColors.Navy
            )
            Text(
                text = subtitle,
                style = TCKTypography.bodySmall,
                color = TCKColors.TextMuted
            )
        }
        if (actionButton != null) {
            actionButton()
        }
    }
}

/**
 * Standardized Empty State Component.
 * Explains what is missing and provides a clear next step.
 */
@Composable
fun TCKEmptyState(
    title: String,
    description: String,
    icon: ImageVector = Icons.Default.Inbox,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = TCKShapes.medium,
        color = TCKColors.Surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, TCKColors.Border)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(TCKSpacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(TCKSpacing.md)
        ) {
            Surface(
                shape = TCKShapes.pill,
                color = TCKColors.SurfaceVariant,
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = TCKColors.TextMuted,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = title,
                    style = TCKTypography.headlineMedium,
                    color = TCKColors.Navy
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = description,
                    style = TCKTypography.bodyMedium,
                    color = TCKColors.TextSecondary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
            if (!actionLabel.isNullOrBlank() && onAction != null) {
                Button(
                    onClick = onAction,
                    shape = TCKShapes.small,
                    colors = ButtonDefaults.buttonColors(containerColor = TCKColors.Primary)
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(actionLabel, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * Standardized Loading Skeleton Placeholder.
 */
@Composable
fun TCKLoadingSkeleton(
    modifier: Modifier = Modifier,
    height: Dp = 80.dp
) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "skeletonAlpha"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(TCKShapes.medium)
            .background(TCKColors.Border.copy(alpha = alpha))
    )
}

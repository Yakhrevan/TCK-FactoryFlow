package com.tck.factoryflow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tck.factoryflow.ui.theme.TCKColors
import com.tck.factoryflow.ui.theme.TCKShapes
import com.tck.factoryflow.ui.theme.TCKSpacing
import com.tck.factoryflow.ui.theme.TCKTypography

/**
 * Premium Industrial Numeric Input with tactile steppers and live remaining inventory preview.
 */
@Composable
fun TCKNumericInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    unit: String,
    modifier: Modifier = Modifier,
    availableBalance: Double? = null,
    stepSizes: List<Int> = listOf(1, 10),
    isDecimal: Boolean = false,
    minVal: Double = 0.0,
    maxVal: Double = Double.MAX_VALUE
) {
    val numericVal = value.toDoubleOrNull() ?: 0.0
    val isOverdraft = availableBalance != null && numericVal > availableBalance
    val remainingAfter = if (availableBalance != null) availableBalance - numericVal else null

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(TCKSpacing.xs)
    ) {
        // Label & Balance Summary
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label.uppercase(),
                style = TCKTypography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = TCKColors.TextMuted,
                letterSpacing = 1.sp
            )
            if (availableBalance != null) {
                Text(
                    text = "Available: ${if (isDecimal) String.format(java.util.Locale.US, "%.1f", availableBalance) else availableBalance.toInt()} $unit",
                    style = TCKTypography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = TCKColors.Primary
                )
            }
        }

        // Main Input with steppers
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = TCKShapes.medium,
            color = TCKColors.Background,
            border = androidx.compose.foundation.BorderStroke(
                1.5.dp,
                if (isOverdraft) TCKColors.Error else TCKColors.BorderStrong
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Decrement Button
                IconButton(
                    onClick = {
                        val current = value.toDoubleOrNull() ?: 0.0
                        val next = (current - stepSizes.first()).coerceAtLeast(minVal)
                        onValueChange(if (isDecimal) String.format(java.util.Locale.US, "%.1f", next) else next.toInt().toString())
                    },
                    modifier = Modifier
                        .size(38.dp)
                        .background(TCKColors.Surface, TCKShapes.small)
                        .border(1.dp, TCKColors.Border, TCKShapes.small)
                ) {
                    Icon(Icons.Default.Remove, contentDescription = "Decrease", tint = TCKColors.Navy)
                }

                // Number Display & TextField
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    TextField(
                        value = value,
                        onValueChange = { input ->
                            val clean = if (isDecimal) {
                                input.filter { it.isDigit() || it == '.' }
                            } else {
                                input.filter { it.isDigit() }
                            }
                            onValueChange(clean)
                        },
                        textStyle = TCKTypography.headlineLarge.copy(
                            textAlign = TextAlign.Center,
                            color = if (isOverdraft) TCKColors.Error else TCKColors.TextPrimary
                        ),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (isDecimal) KeyboardType.Decimal else KeyboardType.Number
                        ),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        )
                    )
                }

                Text(
                    text = unit,
                    style = TCKTypography.labelLarge,
                    color = TCKColors.TextMuted,
                    modifier = Modifier.padding(end = 8.dp)
                )

                // Increment Button
                IconButton(
                    onClick = {
                        val current = value.toDoubleOrNull() ?: 0.0
                        val next = (current + stepSizes.first()).coerceAtMost(maxVal)
                        onValueChange(if (isDecimal) String.format(java.util.Locale.US, "%.1f", next) else next.toInt().toString())
                    },
                    modifier = Modifier
                        .size(38.dp)
                        .background(TCKColors.Primary, TCKShapes.small)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Increase", tint = TCKColors.Surface)
                }
            }
        }

        // Live Balance Preview
        if (availableBalance != null && remainingAfter != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = TCKShapes.small,
                color = if (isOverdraft) TCKColors.ErrorLight else TCKColors.SurfaceVariant
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isOverdraft) "Stock Overdraft!" else "Remaining after action:",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isOverdraft) TCKColors.Error else TCKColors.TextSecondary
                    )
                    Text(
                        text = "${if (isDecimal) String.format(java.util.Locale.US, "%.1f", remainingAfter) else remainingAfter.toInt()} $unit",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isOverdraft) TCKColors.Error else TCKColors.Navy
                    )
                }
            }
        }
    }
}

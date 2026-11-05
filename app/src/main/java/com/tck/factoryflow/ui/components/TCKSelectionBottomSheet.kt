package com.tck.factoryflow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tck.factoryflow.ui.theme.TCKColors
import com.tck.factoryflow.ui.theme.TCKShapes
import com.tck.factoryflow.ui.theme.TCKSpacing
import com.tck.factoryflow.ui.theme.TCKTypography

/**
 * World-Class Generic Searchable Selection Bottom Sheet.
 * Solves the critical industrial problem of selecting from large product, customer, machine, and material datasets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> TCKSelectionBottomSheet(
    title: String,
    subtitle: String,
    items: List<T>,
    selectedItem: T?,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    searchFilter: (item: T, query: String) -> Boolean,
    itemContent: @Composable (item: T, isSelected: Boolean) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val filtered = remember(searchQuery, items) {
        if (searchQuery.isBlank()) items
        else items.filter { searchFilter(it, searchQuery.trim()) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = TCKShapes.bottomSheet,
        containerColor = TCKColors.Surface,
        dragHandle = { BottomSheetDefaults.DragHandle(color = TCKColors.BorderStrong) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = TCKSpacing.lg)
                .padding(bottom = TCKSpacing.xxl)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = title,
                        style = TCKTypography.headlineMedium,
                        color = TCKColors.Navy
                    )
                    Text(
                        text = "$subtitle (${filtered.size} of ${items.size})",
                        style = TCKTypography.bodySmall,
                        color = TCKColors.TextMuted
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TCKColors.TextMuted)
                }
            }

            Spacer(Modifier.height(TCKSpacing.md))

            // Search Bar with one-tap clear
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                shape = TCKShapes.medium,
                placeholder = { Text("Search by name, code, SKU or details…", fontSize = 13.sp) },
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, tint = TCKColors.TextMuted)
                },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search", tint = TCKColors.TextMuted)
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = TCKColors.Primary,
                    unfocusedBorderColor = TCKColors.Border,
                    focusedContainerColor = TCKColors.Background,
                    unfocusedContainerColor = TCKColors.Background
                )
            )

            Spacer(Modifier.height(TCKSpacing.md))

            // Items List
            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.SearchOff, null, tint = TCKColors.TextMuted, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.height(TCKSpacing.sm))
                        Text("No matching items found", style = TCKTypography.titleMedium, color = TCKColors.TextSecondary)
                        Text("Try a different search term.", style = TCKTypography.bodySmall, color = TCKColors.TextMuted)
                        if (searchQuery.isNotBlank()) {
                            TextButton(onClick = { searchQuery = "" }) {
                                Text("Clear Search Filter")
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(TCKSpacing.sm)
                ) {
                    items(filtered) { item ->
                        val isSelected = item == selectedItem
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelect(item)
                                    onDismiss()
                                },
                            shape = TCKShapes.medium,
                            color = if (isSelected) TCKColors.PrimaryLight else TCKColors.Surface,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isSelected) TCKColors.Primary else TCKColors.Border
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.weight(1f)) {
                                    itemContent(item, isSelected)
                                }
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Selected",
                                        tint = TCKColors.Primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

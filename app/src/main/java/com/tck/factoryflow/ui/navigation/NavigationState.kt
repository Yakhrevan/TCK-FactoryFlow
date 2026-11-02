package com.tck.factoryflow.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.graphics.vector.ImageVector
import com.tck.factoryflow.R

enum class PageTab(val stringResId: Int, val icon: ImageVector) {
    HOME(R.string.tab_home, Icons.Default.Dashboard),
    PRODUCTS(R.string.tab_products, Icons.Default.Inventory2),
    PRODUCTION(R.string.tab_production, Icons.Default.PrecisionManufacturing),
    PACKING(R.string.tab_packing, Icons.Default.AllInbox),
    STOCK(R.string.tab_stock, Icons.Default.Warehouse),
    DISPATCH(R.string.tab_dispatch, Icons.Default.LocalShipping),
    ELECTRICITY(R.string.tab_electricity, Icons.Default.Bolt),
    REPORTS(R.string.tab_reports, Icons.Default.Assessment),
    CUSTOMERS(R.string.tab_customers, Icons.Default.People),
    MACHINES(R.string.tab_machines, Icons.Default.Build),
    QUALITY(R.string.tab_quality, Icons.Default.FactCheck),
    PLANNING(R.string.tab_planning, Icons.Default.CalendarMonth),
    ORDERS(R.string.tab_orders, Icons.Default.ReceiptLong),
    MOVEMENTS(R.string.tab_movements, Icons.Default.SyncAlt),
    AUDIT(R.string.tab_audit, Icons.Default.History),
    STAFF(R.string.tab_staff, Icons.Default.Badge),
    SYNC(R.string.tab_sync, Icons.Default.CloudSync)
}

/**
 * Handles back-navigation properly using Jetpack Compose BackHandler.
 * Prevents accidental app exit when deep in sub-screens or dialogs.
 */


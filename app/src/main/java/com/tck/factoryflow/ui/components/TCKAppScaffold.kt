package com.tck.factoryflow.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tck.factoryflow.R
import com.tck.factoryflow.auth.FactorySession
import com.tck.factoryflow.ui.theme.TCKColors
import com.tck.factoryflow.ui.theme.TCKShapes
import com.tck.factoryflow.ui.theme.TCKSpacing
import com.tck.factoryflow.ui.theme.TCKTypography

/**
 * World-Class Industrial Top Bar for TCK FactoryFlow.
 * Displays organization identity, live cloud connectivity, role badge, and fast navigation drawer access.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TCKTopBar(
    session: FactorySession?,
    isCloudOnline: Boolean,
    onMenuClick: () -> Unit,
    onRoleClick: () -> Unit,
    onSyncClick: () -> Unit,
    onLogoutClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = TCKColors.Surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, TCKColors.Border),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = TCKSpacing.md, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: Menu & Brand Identity
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                IconButton(
                    onClick = onMenuClick,
                    modifier = Modifier
                        .size(38.dp)
                        .background(TCKColors.Background, TCKShapes.small)
                        .border(1.dp, TCKColors.Border, TCKShapes.small)
                ) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = "Open Navigation Menu",
                        tint = TCKColors.Navy,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Surface(
                    shape = TCKShapes.small,
                    color = Color.White,
                    border = androidx.compose.foundation.BorderStroke(1.dp, TCKColors.Border),
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(2.dp)) {
                        Image(
                            painter = painterResource(R.drawable.tck_brand),
                            contentDescription = "TCK Brand Logo",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "TCK FACTORYFLOW",
                            style = TCKTypography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = TCKColors.Navy,
                            fontSize = 14.sp
                        )
                        // Live cloud connectivity pulse dot
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if (isCloudOnline) TCKColors.Success else TCKColors.Warning)
                        )
                    }
                    Text(
                        text = "${session?.organisationId ?: "tck-factory-01"} · ${session?.factoryIds?.firstOrNull() ?: "factory-01"}",
                        style = TCKTypography.labelSmall,
                        color = TCKColors.TextMuted,
                        fontSize = 10.sp
                    )
                }
            }

            // Right: Role Badge & Quick Actions
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Role Badge Pill
                val roleName = session?.role?.uppercase() ?: "ADMIN"
                val (roleBg, roleText) = when (session?.role?.lowercase()) {
                    "admin", "owner" -> Pair(Color(0xFFEDE9FE), TCKColors.RoleAdmin)
                    "manager" -> Pair(Color(0xFFE0F2FE), TCKColors.RoleManager)
                    "supervisor" -> Pair(Color(0xFFFEF3C7), TCKColors.RoleSupervisor)
                    "operator", "production_staff" -> Pair(Color(0xFFDCFCE7), TCKColors.RoleOperator)
                    "quality_inspector", "inspector" -> Pair(Color(0xFFCCFBF1), TCKColors.RoleQuality)
                    "dispatch_staff", "dispatch" -> Pair(Color(0xFFFFEDD5), TCKColors.RoleDispatch)
                    else -> Pair(TCKColors.SurfaceVariant, TCKColors.Navy)
                }

                Surface(
                    shape = TCKShapes.pill,
                    color = roleBg,
                    modifier = Modifier.clickable(onClick = onRoleClick)
                ) {
                    Text(
                        text = roleName,
                        style = TCKTypography.badgeText,
                        color = roleText,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                    )
                }

                IconButton(
                    onClick = onSyncClick,
                    modifier = Modifier
                        .size(34.dp)
                        .background(TCKColors.Background, TCKShapes.small)
                        .border(1.dp, TCKColors.Border, TCKShapes.small)
                ) {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = "Trigger Cloud Sync",
                        tint = TCKColors.Primary,
                        modifier = Modifier.size(17.dp)
                    )
                }

                IconButton(
                    onClick = onLogoutClick,
                    modifier = Modifier
                        .size(34.dp)
                        .background(TCKColors.ErrorLight, TCKShapes.small)
                        .border(1.dp, TCKColors.Error.copy(alpha = 0.2f), TCKShapes.small)
                ) {
                    Icon(
                        imageVector = Icons.Default.Logout,
                        contentDescription = "Sign Out",
                        tint = TCKColors.Error,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

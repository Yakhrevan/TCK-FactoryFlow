package com.tck.factoryflow.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tck.factoryflow.R

private val Blue = Color(0xFF0756A4)
private val Navy = Color(0xFF102B4E)
private val Page = Color(0xFFF1F7FC)
private val Muted = Color(0xFF6B7E93)
private val Border = Color(0xFFDCE7F1)

private data class PortalDef(
    val id: String,
    val title: String,
    val subtitle: String,
    val hintEmail: String,
    val icon: ImageVector,
    val color: Color,
    val gradient: Brush
)

@Composable
fun StartupLoginScreen(
    firebaseConfigured: Boolean,
    onFirebaseSignIn: (email: String, password: String, onError: (String) -> Unit) -> Unit,
    onOfflineSignIn: (portalId: String, email: String, pass: String, onError: (String) -> Unit) -> Unit
) {
    var selectedPortalIndex by remember { mutableIntStateOf(0) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val portals = listOf(
        PortalDef(
            id = "admin",
            title = "Admin Portal",
            subtitle = "Master Governance, Staff Access & Backups",
            hintEmail = "admin@tckfactory.com",
            icon = Icons.Default.AdminPanelSettings,
            color = Color(0xFF6366F1),
            gradient = Brush.horizontalGradient(listOf(Color(0xFF4F46E5), Color(0xFF7C3AED)))
        ),
        PortalDef(
            id = "manager",
            title = "Manager Portal",
            subtitle = "Floor Operations, Planning, Maintenance & QC",
            hintEmail = "manager@tckfactory.com",
            icon = Icons.Default.SupervisorAccount,
            color = Color(0xFF0284C7),
            gradient = Brush.horizontalGradient(listOf(Color(0xFF0284C7), Color(0xFF0369A1)))
        ),
        PortalDef(
            id = "staff",
            title = "Staff Portal",
            subtitle = "Production, Packing & Dispatch Logging",
            hintEmail = "staff@tckfactory.com",
            icon = Icons.Default.Badge,
            color = Color(0xFF0D9488),
            gradient = Brush.horizontalGradient(listOf(Color(0xFF0D9488), Color(0xFF0F766E)))
        )
    )

    val currentPortal = portals[selectedPortalIndex]

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Page
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp)
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header Logo
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = Color.White,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                    shadowElevation = 3.dp,
                    modifier = Modifier.size(68.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(6.dp)) {
                        androidx.compose.foundation.Image(
                            painter = androidx.compose.ui.res.painterResource(R.drawable.tck_brand),
                            contentDescription = "TCK Logo",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("TCK FactoryFlow", fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, color = Navy)
                    Text("SMART BOTTLE MANUFACTURING SYSTEM", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Blue, letterSpacing = 1.3.sp)
                }

                // Server Status Indicator
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (firebaseConfigured) Color(0xFFE8F7F0) else Color(0xFFFFF4E5),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (firebaseConfigured) Color(0xFF86EFAC) else Color(0xFFFFD8A8))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Box(
                            Modifier
                                .size(9.dp)
                                .background(
                                    if (firebaseConfigured) Color(0xFF16804A) else Color(0xFFD97706),
                                    RoundedCornerShape(5.dp)
                                )
                        )
                        Text(
                            if (firebaseConfigured) "Server Connected · Realtime Cloud Sync" else "Offline Mode · Local Storage Active",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (firebaseConfigured) Color(0xFF16804A) else Color(0xFFB45309)
                        )
                    }
                }

                // 3 Separate Portals Selector (Admin / Manager / Staff)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    portals.forEachIndexed { index, portal ->
                        val isSelected = selectedPortalIndex == index
                        Surface(
                            onClick = {
                                selectedPortalIndex = index
                                errorMessage = null
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            color = if (isSelected) portal.color.copy(alpha = 0.12f) else Color.White,
                            border = androidx.compose.foundation.BorderStroke(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) portal.color else Border
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 12.dp, horizontal = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    portal.icon,
                                    null,
                                    tint = if (isSelected) portal.color else Muted,
                                    modifier = Modifier.size(22.dp)
                                )
                                Text(
                                    portal.title.replace(" Portal", ""),
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                    color = if (isSelected) portal.color else Navy
                                )
                            }
                        }
                    }
                }

                // Main Credential Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // Portal Badge Banner
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = currentPortal.color.copy(alpha = 0.10f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    Modifier
                                        .size(36.dp)
                                        .background(currentPortal.color, RoundedCornerShape(10.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(currentPortal.icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(currentPortal.title.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = currentPortal.color)
                                    Text(currentPortal.subtitle, fontSize = 10.sp, color = Muted, lineHeight = 13.sp)
                                }
                            }
                        }

                        // Error Banner
                        AnimatedVisibility(visible = errorMessage != null) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFFFEE2E2),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFCA5A5)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Default.ErrorOutline, null, tint = Color(0xFFDC2626), modifier = Modifier.size(20.dp))
                                    Text(
                                        errorMessage.orEmpty(),
                                        fontSize = 11.sp,
                                        color = Color(0xFF991B1B),
                                        lineHeight = 15.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }

                        // Email / Staff ID Input
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it; errorMessage = null },
                            label = { Text("${currentPortal.title.replace(" Portal", "")} Email / ID") },
                            placeholder = { Text(currentPortal.hintEmail) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            leadingIcon = { Icon(Icons.Default.Email, null, tint = currentPortal.color) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                            shape = RoundedCornerShape(12.dp)
                        )

                        // Password Input
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it; errorMessage = null },
                            label = { Text("Password") },
                            placeholder = { Text("••••••••") },
                            singleLine = true,
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            leadingIcon = { Icon(Icons.Default.Lock, null, tint = currentPortal.color) },
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (passwordVisible) "Hide password" else "Show password",
                                        tint = Muted
                                    )
                                }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                if (email.isNotBlank() && password.isNotBlank() && !isLoading) {
                                    isLoading = true
                                    val performSignIn = if (firebaseConfigured) {
                                        { onFirebaseSignIn(email.trim(), password) { err -> isLoading = false; errorMessage = err } }
                                    } else {
                                        { onOfflineSignIn(currentPortal.id, email.trim(), password) { err -> isLoading = false; errorMessage = err } }
                                    }
                                    performSignIn()
                                }
                            }),
                            shape = RoundedCornerShape(12.dp)
                        )

                        // Submit Sign In Button
                        Button(
                            onClick = {
                                if (email.isBlank() || password.isBlank()) {
                                    errorMessage = "Please enter both Email/ID and Password."
                                    return@Button
                                }
                                isLoading = true
                                if (firebaseConfigured) {
                                    onFirebaseSignIn(email.trim(), password) { err ->
                                        // If firebase sign-in fails due to offline/network or local admin
                                        onOfflineSignIn(currentPortal.id, email.trim(), password) { offlineErr ->
                                            isLoading = false
                                            errorMessage = "$err ($offlineErr)"
                                        }
                                    }
                                } else {
                                    onOfflineSignIn(currentPortal.id, email.trim(), password) { err ->
                                        isLoading = false
                                        errorMessage = err
                                    }
                                }
                            },
                            enabled = !isLoading,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = currentPortal.color)
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Login, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Sign In to ${currentPortal.title}", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        // Footer Guidance Note
                        Surface(
                            color = Page,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.Info, null, tint = Muted, modifier = Modifier.size(16.dp))
                                Text(
                                    when (selectedPortalIndex) {
                                        0 -> "Admin credentials have master control. Staff accounts and modules are configured in Staff Management."
                                        1 -> "Manager credentials give access to scheduling, production line control, machine maintenance & reports."
                                        else -> "Staff credentials access floor tasks as assigned by the Administrator. Contact Admin for login details."
                                    },
                                    fontSize = 11.sp,
                                    color = Muted,
                                    lineHeight = 14.sp
                                )
                            }
                        }
                    }
                }

                Text(
                    "TCK FactoryFlow · Enterprise Manufacturing System",
                    fontSize = 10.sp,
                    color = Muted
                )
            }
        }
    }
}

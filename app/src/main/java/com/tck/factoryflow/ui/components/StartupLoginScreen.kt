package com.tck.factoryflow.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Color
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
private val AdminColor = Color(0xFF4F46E5)

@Composable
fun StartupLoginScreen(
    isOnline: Boolean,
    onFirebaseSignIn: (email: String, password: String, onError: (String) -> Unit) -> Unit,
    onRetryConnection: () -> Unit
) {
    var email by remember { mutableStateOf("admin@tckfactory.com") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

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
                    .widthIn(max = 480.dp)
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                // Header Logo
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = Color.White,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                    shadowElevation = 3.dp,
                    modifier = Modifier.size(72.dp)
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
                    Text("TCK FactoryFlow", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Navy)
                    Text("100% ONLINE CLOUD MANUFACTURING SYSTEM", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Blue, letterSpacing = 1.3.sp)
                }

                // Server Status Indicator
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (isOnline) Color(0xFFE8F7F0) else Color(0xFFFEE2E2),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (isOnline) Color(0xFF86EFAC) else Color(0xFFFCA5A5))
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
                                    if (isOnline) Color(0xFF16804A) else Color(0xFFDC2626),
                                    RoundedCornerShape(5.dp)
                                )
                        )
                        Text(
                            if (isOnline) "Realtime Cloud Connected · Firebase Active" else "No Internet Connection · Cloud Access Required",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isOnline) Color(0xFF16804A) else Color(0xFF991B1B)
                        )
                    }
                }

                if (!isOnline) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFECACA))
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.CloudOff, null, tint = Color(0xFFDC2626))
                                Text("Internet Connection Required", fontWeight = FontWeight.Bold, color = Color(0xFF991B1B), fontSize = 14.sp)
                            }
                            Text(
                                "TCK FactoryFlow operates strictly in 100% Online Mode. All production records, pack inventories, and dispatches are committed directly to the central cloud server. Please connect to Wi-Fi or mobile data.",
                                fontSize = 11.sp,
                                color = Color(0xFF7F1D1D),
                                lineHeight = 16.sp
                            )
                            Button(
                                onClick = onRetryConnection,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                            ) {
                                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Check Connection Again", fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                            .padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Admin Header Banner
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = AdminColor.copy(alpha = 0.08f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    Modifier
                                        .size(38.dp)
                                        .background(AdminColor, RoundedCornerShape(10.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.AdminPanelSettings, null, tint = Color.White, modifier = Modifier.size(22.dp))
                                }
                                Column(Modifier.weight(1f)) {
                                    Text("CLOUD ADMINISTRATOR LOGIN", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = AdminColor)
                                    Text("Full Server Governance & Live Operations", fontSize = 10.sp, color = Muted)
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

                        // Email Input
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it; errorMessage = null },
                            label = { Text("Email Address") },
                            placeholder = { Text("admin@tckfactory.com") },
                            singleLine = true,
                            enabled = isOnline && !isLoading,
                            modifier = Modifier.fillMaxWidth(),
                            leadingIcon = { Icon(Icons.Default.Email, null, tint = AdminColor) },
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
                            enabled = isOnline && !isLoading,
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            leadingIcon = { Icon(Icons.Default.Lock, null, tint = AdminColor) },
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
                                if (isOnline && email.isNotBlank() && password.isNotBlank() && !isLoading) {
                                    isLoading = true
                                    onFirebaseSignIn(email.trim(), password) { err ->
                                        isLoading = false
                                        errorMessage = err
                                    }
                                }
                            }),
                            shape = RoundedCornerShape(12.dp)
                        )

                        // Submit Sign In Button
                        Button(
                            onClick = {
                                if (email.isBlank() || password.isBlank()) {
                                    errorMessage = "Please enter both Email and Password."
                                    return@Button
                                }
                                isLoading = true
                                onFirebaseSignIn(email.trim(), password) { err ->
                                    isLoading = false
                                    errorMessage = err
                                }
                            },
                            enabled = isOnline && !isLoading,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AdminColor)
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.CloudSync, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Sign In to Factory Cloud", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        // Guidance note
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
                                    "Administrator has complete master control over all machines, production, stock, dispatches and backups.",
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

package com.tck.factoryflow.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tck.factoryflow.R
import com.tck.factoryflow.ui.theme.TCKColors
import com.tck.factoryflow.ui.theme.TCKShapes
import com.tck.factoryflow.ui.theme.TCKSpacing
import com.tck.factoryflow.ui.theme.TCKTypography

/**
 * Minimal, Professional, Enterprise Login Screen for TCK FactoryFlow.
 * Clean, distraction-free, and contains only essential authentication elements.
 */
@Composable
fun StartupLoginScreen(
    isOnline: Boolean,
    onFirebaseSignIn: (email: String, password: String, onError: (String) -> Unit) -> Unit,
    onRetryConnection: () -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = TCKColors.Background
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
                    .widthIn(max = 420.dp)
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(TCKSpacing.lg)
            ) {
                // TCK Brand Header
                Surface(
                    shape = TCKShapes.medium,
                    color = Color.White,
                    border = androidx.compose.foundation.BorderStroke(1.dp, TCKColors.Border),
                    shadowElevation = 2.dp,
                    modifier = Modifier.size(68.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(6.dp)) {
                        Image(
                            painter = painterResource(R.drawable.tck_brand),
                            contentDescription = "TCK Brand Logo",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "TCK FactoryFlow",
                        style = TCKTypography.headlineLarge,
                        color = TCKColors.Navy
                    )
                    Text(
                        text = "Manufacturing Execution System",
                        style = TCKTypography.bodySmall,
                        color = TCKColors.TextMuted
                    )
                }

                // Main Login Card
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = TCKShapes.large,
                    color = Color.White,
                    border = androidx.compose.foundation.BorderStroke(1.dp, TCKColors.Border),
                    tonalElevation = 2.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Offline Notice (Shown only when disconnected)
                        if (!isOnline) {
                            Surface(
                                shape = TCKShapes.small,
                                color = TCKColors.WarningLight,
                                border = androidx.compose.foundation.BorderStroke(1.dp, TCKColors.Warning.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.WifiOff,
                                        contentDescription = null,
                                        tint = TCKColors.Warning,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = "No internet connection. Please connect to Wi-Fi or mobile network to sign in.",
                                        style = TCKTypography.bodySmall,
                                        color = TCKColors.TextPrimary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = onRetryConnection,
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = "Retry Connection",
                                            tint = TCKColors.Primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Sanitized User-Friendly Error Banner
                        AnimatedVisibility(visible = errorMessage != null) {
                            Surface(
                                shape = TCKShapes.small,
                                color = TCKColors.ErrorLight,
                                border = androidx.compose.foundation.BorderStroke(1.dp, TCKColors.Error.copy(alpha = 0.3f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ErrorOutline,
                                        contentDescription = null,
                                        tint = TCKColors.Error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = errorMessage.orEmpty(),
                                        style = TCKTypography.bodySmall,
                                        color = TCKColors.Error,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }

                        // Email / User Identifier
                        OutlinedTextField(
                            value = email,
                            onValueChange = {
                                email = it
                                errorMessage = null
                            },
                            label = { Text("Email or Username") },
                            placeholder = { Text("operator@tck.com") },
                            singleLine = true,
                            enabled = isOnline && !isLoading,
                            modifier = Modifier.fillMaxWidth(),
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    tint = TCKColors.Primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Email,
                                imeAction = ImeAction.Next
                            ),
                            shape = TCKShapes.small
                        )

                        // Password Input
                        OutlinedTextField(
                            value = password,
                            onValueChange = {
                                password = it
                                errorMessage = null
                            },
                            label = { Text("Password") },
                            placeholder = { Text("••••••••") },
                            singleLine = true,
                            enabled = isOnline && !isLoading,
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = TCKColors.Primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (passwordVisible) "Hide password" else "Show password",
                                        tint = TCKColors.TextMuted,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(onDone = {
                                if (isOnline && email.isNotBlank() && password.isNotBlank() && !isLoading) {
                                    isLoading = true
                                    onFirebaseSignIn(email.trim(), password) { err ->
                                        isLoading = false
                                        errorMessage = sanitizeAuthError(err)
                                    }
                                }
                            }),
                            shape = TCKShapes.small
                        )

                        // Action Button
                        Button(
                            onClick = {
                                if (email.isBlank() || password.isBlank()) {
                                    errorMessage = "Please enter your email and password."
                                    return@Button
                                }
                                isLoading = true
                                onFirebaseSignIn(email.trim(), password) { err ->
                                    isLoading = false
                                    errorMessage = sanitizeAuthError(err)
                                }
                            },
                            enabled = isOnline && !isLoading,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = TCKShapes.small,
                            colors = ButtonDefaults.buttonColors(containerColor = TCKColors.Primary)
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(
                                    text = "Sign In",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                Text(
                    text = "TCK Packaging Solutions · Factory Edition",
                    style = TCKTypography.labelSmall,
                    color = TCKColors.TextMuted
                )
            }
        }
    }
}

/**
 * Sanitizes technical Firebase/network exceptions into clean, professional user feedback.
 */
private fun sanitizeAuthError(rawError: String): String {
    val lower = rawError.lowercase()
    return when {
        "password" in lower || "user-not-found" in lower || "invalid-credential" in lower || "wrong-password" in lower ->
            "Incorrect email or password. Please try again."
        "network" in lower || "timeout" in lower || "offline" in lower || "connect" in lower ->
            "Network error. Please check your internet connection."
        "too-many-requests" in lower ->
            "Too many failed attempts. Please wait a few moments and try again."
        else ->
            "Unable to sign in. Please check your credentials and try again."
    }
}

package com.tck.factoryflow.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * TCK FactoryFlow Industrial Design Tokens.
 * Refined palette blending industrial operational clarity with modern enterprise SaaS aesthetics.
 */
object TCKColors {
    // Primary Brand - Refined Industrial Blue
    val Primary = Color(0xFF0284C7)           // Sky 600
    val PrimaryDark = Color(0xFF0369A1)       // Sky 700
    val PrimaryLight = Color(0xFFE0F2FE)      // Sky 100
    val PrimaryContainer = Color(0xFFBAE6FD)  // Sky 200

    // Industrial Navy - Structure & Foundations
    val Navy = Color(0xFF0F172A)              // Slate 900
    val NavySurface = Color(0xFF1E293B)       // Slate 800
    val NavySubtle = Color(0xFF334155)        // Slate 700

    // Canvas & Surfaces
    val Background = Color(0xFFF8FAFC)        // Slate 50
    val Surface = Color(0xFFFFFFFF)           // Pure White
    val SurfaceVariant = Color(0xFFF1F5F9)    // Slate 100
    val SurfaceHighlight = Color(0xFFF8FAFC)  // Slate 50

    // Text & Typography
    val TextPrimary = Color(0xFF0F172A)       // WCAG AAA against White / Background
    val TextSecondary = Color(0xFF475569)     // Slate 600
    val TextMuted = Color(0xFF64748B)         // Slate 500
    val TextOnPrimary = Color(0xFFFFFFFF)

    // Borders & Hairlines
    val Border = Color(0xFFE2E8F0)            // Slate 200 (Clean 1dp card borders)
    val BorderStrong = Color(0xFFCBD5E1)      // Slate 300 (Input / active borders)

    // Semantics & Statuses
    val Success = Color(0xFF16A34A)           // Green 600
    val SuccessLight = Color(0xFFDCFCE7)      // Green 100
    val SuccessBorder = Color(0xFF86EFAC)     // Green 300

    val Warning = Color(0xFFD97706)           // Amber 600
    val WarningLight = Color(0xFFFEF3C7)      // Amber 100
    val WarningBorder = Color(0xFFFCD34D)     // Amber 300

    val Error = Color(0xFFDC2626)             // Red 600
    val ErrorLight = Color(0xFFFEE2E2)        // Red 100
    val ErrorBorder = Color(0xFFFCA5A5)       // Red 300

    val Info = Color(0xFF0284C7)              // Sky 600
    val InfoLight = Color(0xFFE0F2FE)         // Sky 100
    val InfoBorder = Color(0xFF7DD3FC)        // Sky 300

    // Role Accent Colors
    val RoleAdmin = Color(0xFF7C3AED)         // Purple 600
    val RoleManager = Color(0xFF0284C7)       // Sky 600
    val RoleSupervisor = Color(0xFFD97706)    // Amber 600
    val RoleOperator = Color(0xFF16A34A)      // Green 600
    val RoleQuality = Color(0xFF0D9488)       // Teal 600
    val RoleDispatch = Color(0xFFEA580C)      // Orange 600
}

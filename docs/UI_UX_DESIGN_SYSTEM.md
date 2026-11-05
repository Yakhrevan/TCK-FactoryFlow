# TCK FactoryFlow — Industrial Design System Specification
**Version:** 2.0 (Premium Industrial ERP / MES)  
**Platform:** Android (Jetpack Compose + Material 3)  
**Foundation:** TCK Refined Industrial Blue & Slate Navy  
**Updated:** November 2026

---

## 1. Design Philosophy

TCK FactoryFlow's design language merges the **clarity and operational focus of industrial HMIs** with the **refinement, typography, and micro-interactions of world-class enterprise SaaS** (Linear, Stripe, Apple HIG).

### Core Pillars
1. **Operational Clarity:** High contrast, legible numbers, unmistakable statuses.
2. **Shopfloor Ergonomics:** Minimum 48dp touch targets, clear tactile steppers, comfortable high-contrast readability under bright factory lighting.
3. **Information Density with Breathing Room:** Structured grid spacing without visual clutter; cards group cohesive thoughts.
4. **Predictability:** Consistent interaction patterns for selection, numeric entry, and confirmation across all 17 factory modules.

---

## 2. Color Palette & Semantic Tokens

```
==============================================================================
TOKEN NAME             HEX VALUE     ROLE & USAGE
==============================================================================
--tck-primary          #0284C7       Primary action, active tab, key brand elements
--tck-primary-dark     #0369A1       Pressed state, high-contrast borders
--tck-primary-light    #E0F2FE       Primary soft background, active pill fill
--tck-primary-container#BAE6FD       Selected item highlight container

--tck-navy             #0F172A       Deep industrial navy, primary headings, top brand
--tck-navy-surface     #1E293B       Elevated dark cards, dark headers, drawer hero
--tck-navy-subtle      #334155       Secondary titles, strong borders

--tck-background       #F8FAFC       App screen canvas background (Slate 50)
--tck-surface          #FFFFFF       Card containers, dialogs, bottom sheets
--tck-surface-variant  #F1F5F9       Subtle section background, metric card fills
--tck-surface-highlight#F8FAFC       Hover / tap feedback surface

--tck-text-primary     #0F172A       High-contrast body & header text (WCAG AAA)
--tck-text-secondary   #475569       Supporting labels, secondary text
--tck-text-muted       #64748B       Timestamps, units, subtle captions
--tck-text-on-primary  #FFFFFF       Text on primary blue buttons and headers

--tck-border           #E2E8F0       Default hairline container border (1.dp)
--tck-border-strong    #CBD5E1       Focused input border, card dividers

--tck-success          #16A34A       Good bottles, completed plans, positive yields
--tck-success-light    #DCFCE7       Success pill background, good batch highlight
--tck-warning          #D97706       Low stock alerts, pending approvals, maintenance
--tck-warning-light    #FEF3C7       Warning banner container, caution pills
--tck-error            #DC2626       Rejections, breakdown stops, validation errors
--tck-error-light      #FEE2E2       Error callouts, reject rate critical containers
--tck-info             #0284C7       Informational notes, audit stamps, tips
--tck-info-light       #E0F2FE       Info callout background
==============================================================================
```

---

## 3. Typography Scale (`TCKTypography`)

Typography is tuned for industrial readability. Numeric metrics use tabular numbers with prominent weights for instant legibility at arm's length on tablets and phones.

| Style Name | Size (sp) | Weight | Line Height | Usage |
|---|---|---|---|---|
| `displayMetric` | 32.sp | ExtraBold (800) | 38.sp | Primary KPI values (e.g. `24,500`) |
| `headlineLarge` | 24.sp | Bold (700) | 30.sp | Page titles, major section anchors |
| `headlineMedium` | 18.sp | Bold (700) | 24.sp | Card titles, dialog headers |
| `titleMedium` | 15.sp | SemiBold (600) | 20.sp | List item headers, form section labels |
| `bodyLarge` | 14.sp | Normal (400) | 20.sp | Primary readable text, input values |
| `bodyMedium` | 13.sp | Normal (400) | 18.sp | Descriptions, list item metadata |
| `labelLarge` | 12.sp | Bold (700) | 16.sp | Button text, chip labels |
| `labelSmall` | 10.sp | SemiBold (600) | 14.sp | Badges, status pills, category tags |
| `metricUnit` | 12.sp | SemiBold (600) | 16.sp | Units trailing numbers (`kWh`, `packs`, `KG`) |

---

## 4. Spacing Grid (`TCKSpacing`)

Strict 4dp-based spacing grid:

```kotlin
object TCKSpacing {
    val xxs = 2.dp   // Hairline margins, compact pill padding
    val xs = 4.dp    // Icon-text spacing, tight groupings
    val sm = 8.dp    // Form field gap, list item internal padding
    val md = 12.dp   // Card internal padding, action button gaps
    val lg = 16.dp   // Screen horizontal margins, card-to-card gap
    val xl = 20.dp   // Section-to-section spacing
    val xxl = 24.dp  // Major screen division, bottom navigation clearance
    val xxxl = 32.dp // Header hero division
}
```

---

## 5. Shape & Elevation (`TCKShapes`)

Restrained corner radii prevent the interface from looking toy-like while maintaining soft, modern enterprise ergonomics.

```kotlin
object TCKShapes {
    val pill = RoundedCornerShape(50)          // Status tags, filter chips
    val small = RoundedCornerShape(8.dp)       // Buttons, text fields, mini badges
    val medium = RoundedCornerShape(14.dp)     // Metric cards, list item surfaces
    val large = RoundedCornerShape(18.dp)      // Hero command cards, dialogs
    val sheet = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp) // Bottom sheets
}
```

**Elevation Policy:**
- Avoid heavy drop shadows.
- Prefer 1.dp hairline borders (`TCKColors.Border`) on clean white surfaces.
- For modal elements (Bottom sheets, Dialogs, Dropdowns), use 4.dp to 8.dp subtle ambient shadows.

---

## 6. Core Component Specifications

### 6.1 `TCKAppScaffold` & `TCKTopBar`
- **Branding Header:** Displays TCK logo, factory name (`factory-01`), live cloud connection indicator (pulsing green dot for online, amber for sync pending).
- **Active Role Pill:** Visually clear badge with color coding (Admin = Purple, Manager = Sky, Supervisor = Amber, Operator = Emerald).
- **Navigation Menu Icon:** Opens the comprehensive industrial slide drawer.
- **Contextual Actions:** Quick sync trigger and user account details.

### 6.2 `TCKMetricCard`
- **Visual Anatomy:**
  - Icon in soft circular container.
  - Subtitle label in uppercase tracking (`TODAY'S PRODUCTION`).
  - Large tabular number (`34,250`) with inline unit (`bottles`).
  - Bottom auxiliary bar: Comparison context or trend tag (`+12.4% vs target` or `98.2% Yield`).
  - Subtle left-edge colored indicator strip for quick scanning.

### 6.3 `TCKSelectionBottomSheet` (Intelligent Picker)
- Replaces cramped dropdown menus for Products, Machines, Customers, and Materials.
- **Features:**
  - Header with title, close button, and item count.
  - Immediate auto-focused search bar with one-tap clear button.
  - Active selection highlighted with checkmark and blue tint.
  - Large touch rows (min height 54dp).
  - Empty search state with actionable "Clear filter" button.

### 6.4 `TCKNumericInput` (Industrial Stepper & Balance Preview)
- Large display of input quantity with inline units (`Packs`, `Bottles`, `KG`).
- Quick stepper buttons (`-10`, `-1`, `+1`, `+10` or `+24`, `+48`).
- **Live Inventory Impact Preview:**
  - Displays: $\text{Available: } 5,000 \longrightarrow \text{Remaining after action: } 3,800$.
  - Turns warning amber/red if requested quantity exceeds available balance.

### 6.5 `TCKStatusBadge`
- Semantic status pills with icon and high-contrast text:
  - `RUNNING` / `ACTIVE` / `COMPLETED` / `PASS`: Green background & text.
  - `PLANNED` / `IN_PROGRESS` / `PENDING`: Blue/Cyan background & text.
  - `STOPPED` / `LOW_STOCK` / `PARTIALLY_DISPATCHED`: Amber background & text.
  - `DOWN` / `CRITICAL` / `FAIL` / `REVERSED`: Red background & text.

### 6.6 `TCKEmptyState`
- Clean icon inside subtle circular surface.
- Clear title: "No production runs logged today".
- Friendly explanation: "Start a production run for the active shift to record output."
- Primary Action button: "Start Production Run".

---

## 7. Motion & Micro-Interactions

1. **Transitions:**
   - Screen cross-fades with subtle 150ms ease-out alpha.
   - Bottom sheets slide smoothly from bottom with 250ms spring easing.
2. **Interactive Feedback:**
   - Tactile button presses with bounded ripple.
   - Stepper increments update numbers with animated content scale.
3. **Restraint:**
   - Zero distracting bouncing or looping animations that waste battery or delay shopfloor operations.

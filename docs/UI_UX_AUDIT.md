# TCK FactoryFlow — Comprehensive UI/UX Design Audit
**Audit Date:** November 2026  
**Auditor:** Principal UI/UX Architect & Android Design Specialist  
**Target:** Factory ERP & Manufacturing Execution System (MES)  
**Repository:** `~/TCK-FactoryFlow`

---

## 1. Executive Summary

TCK FactoryFlow is a robust, cloud-authoritative ERP & MES application backed by Firebase Authentication, Cloudflare Workers, and Firestore, with Room offline read-only caching. Functionally, the business logic and transaction integrity are sound. 

However, from an **industrial UI/UX perspective**, the application has grown organically into a monolithic screen structure (`MainActivity.kt` with >4,600 lines) with several critical design and usability limitations:
1. **Visual Hierarchy & Clutter:** Heavy reliance on ad-hoc colored boxes, random container colors, and inconsistent fonts makes long shifts tiring on tablet and phone screens.
2. **Selection Friction:** Product, machine, and customer selection frequently relies on small dropdown menus or basic pickers that do not scale well when a factory has dozens of SKUs or hundreds of customer accounts.
3. **Numeric Entry Ergonomics:** High-volume shopfloor data entry (bottles produced, packs boxed, kilograms received) lacks large touch targets, stepper buttons, and instant remaining balance previews.
4. **Dashboard Experience:** The Home screen contains valuable KPIs but lacks an operational command center structure with clean progressive disclosure and shift contextualization.
5. **Component Duplication:** Ad-hoc custom Card and Surface definitions are scattered across the codebase instead of adhering to a centralized, tokenized Design System.

---

## 2. Screen-by-Screen Detailed Audit

### 2.1 Navigation & App Shell
- **Current State:** A 6-item bottom navigation bar (`Home, Production, Packing, Stock, Dispatch, Reports`) paired with an extensive slide-in menu for the other 11 modules (`Customers, Machines, Quality, Planning, Orders, Movements, Audit, Staff, Sync, Electricity, Products`).
- **Issues Identified:**
  - Active tab indicators use hardcoded blue values that clash with the top app bar.
  - The top bar has a crowded logout button and role indicator without a quick organization/factory switch context.
  - Sub-screen navigation relies on modifying `dialog` string variables rather than an expressive navigation pattern.
- **Recommended Improvements:**
  - Implement `TCKAppScaffold` and `TCKTopBar` with unified branding, high-contrast role badges, and cloud connectivity pulse dots.
  - Streamline bottom navigation with modern Material 3 indicators and smooth transitions.
  - Enhance `FullSlideMenuScreen` into an organized Industrial Navigation Drawer with role-based filtering.

### 2.2 Dashboard / Home Command Center (`PageTab.HOME`)
- **Current State:** Displays today's production totals, quick order simulation, and a list of metrics.
- **Issues Identified:**
  - Lack of shift context (Shift A / Shift B / Night Shift).
  - Quick order calculator is visually disconnected from actual sales order creation.
  - Metrics lack clear visual trends (e.g. % of target achieved, rejection rate visual bar).
  - Machine status cards are text-heavy without immediate color-coded machine visual statuses.
- **Recommended Improvements:**
  - Header: Shift context, time, factory badge, greeting, and high-priority operational alert carousel (e.g. stopped machines, low raw materials, missed meter readings).
  - KPI Cards: 4 primary metrics (Production vs Target, Rejection %, Finished Stock in Packs, Active Running Lines) with clear typography and subtle trend tags.
  - Target Progress Bar: Visual progress indicator showing shift goal vs actual completed.
  - Quick Action Tiles: One-tap triggers for high-frequency actions (Log Run, Pack, Dispatch, Receive Material, Energy Reading).

### 2.3 Product Management (`PageTab.PRODUCTS`)
- **Current State:** List of products with basic filters and bottle icon previews.
- **Issues Identified:**
  - Small edit and archive buttons that are hard to tap in industrial settings with gloved or wet hands.
  - Product specs (neck size, gram weight, pack size) are presented as plain text without clear tabular structure.
  - Adding products requires manual text entry without pre-suggested industry bottle capacities.
- **Recommended Improvements:**
  - Product cards with high-contrast capacity badges, color swatches, and prominent pack-size tags.
  - Search bar with quick chips: `200ml`, `300ml`, `500ml`, `1L`, `Active Only`.
  - Large touch targets for edit and configuration actions.

### 2.4 Production Operations (`PageTab.PRODUCTION`)
- **Current State:** Shows recent production runs and an action button to record a new run.
- **Issues Identified:**
  - Production form relies on standard text fields for produced, good, and rejected quantities without visual sanity checks before submission.
  - Machine selection in the form is a standard dropdown that does not indicate machine availability or whether a machine is currently stopped.
- **Recommended Improvements:**
  - Modern production run cards showing machine name, line code, shift, produced vs good vs rejected breakdown, and reject % badge.
  - Form: Searchable bottom sheet for product and machine selection, with non-running machines disabled and labeled.
  - Live math card: Displays $\text{Produced} = \text{Good} + \text{Rejected}$ in real-time, green when balanced, red when mismatched.

### 2.5 Packing Workflow (`PageTab.PACKING`)
- **Current State:** Form that inputs bottles consumed and computes complete packs and loose bottles.
- **Issues Identified:**
  - The operator has to remember how many good unpacked bottles are actually available before typing.
  - Pack size configuration is not clearly highlighted during entry.
- **Recommended Improvements:**
  - Live Unpacked Bottle Balance Banner: Shows exact available good bottles for the selected product.
  - Stepper controls ($+24, +48, +100, +500$) for rapid box packing calculations.
  - Large animated preview of Resulting Complete Packs + Loose Bottles remaining.

### 2.6 Raw Material & Finished Goods Inventory (`PageTab.STOCK`)
- **Current State:** TabRow toggling between Finished Goods and Raw Materials.
- **Issues Identified:**
  - Raw material low-stock alerts are plain text.
  - Stock adjustment dialog lacks a before-and-after preview of new inventory balance.
  - No quick filter to show only low-stock items requiring urgent reorder.
- **Recommended Improvements:**
  - Prominent Reorder Warning Badges with urgency indicators (Red for critical, Amber for warning).
  - `RawMaterialIssueForm` and `StockAdjustmentForm` equipped with live remaining balance calculation:
    $$\text{Current Stock} - \text{Issue Qty} = \text{New Balance}$$
  - Quick filter chip: "Low Stock Alerts Only".

### 2.7 Sales Orders & Dispatch Management (`PageTab.ORDERS`, `PageTab.DISPATCH`)
- **Current State:** Dispatch batches with items, reversal button, and Delivery Gate Pass viewer.
- **Issues Identified:**
  - Order lines in Sales Orders are plain text lists.
  - Gate pass dialog can be improved with a high-fidelity receipt card layout and print preview aesthetic.
  - Multi-line dispatch form needs smoother line additions and auto-calculation of total packs and bottles.
- **Recommended Improvements:**
  - Sales order cards with visual fulfillment progress bars ($\text{Dispatched} / \text{Ordered}$).
  - Gate Pass preview styled like a crisp industrial delivery document with security checkpoint authorization blocks.
  - Searchable Customer and Sales Order bottom sheets.

### 2.8 Quality Control (`PageTab.QUALITY`)
- **Current State:** Inspection list with defect counts and test results.
- **Issues Identified:**
  - Defect severity (Minor, Major, Critical) lacks color differentiation.
  - Pass/Fail status is communicated primarily through small text badges.
- **Recommended Improvements:**
  - Prominent PASS / FAIL inspection badges with clear iconography (never color alone).
  - Visual defect breakdown (Visual inspection, Leakage test, Neck dimension).

### 2.9 Energy & Electricity Monitoring (`PageTab.ELECTRICITY`)
- **Current State:** Morning, Evening, and Full reading entries with catch-up alerts.
- **Issues Identified:**
  - Missed reading banner is functional but visually jarring with sharp borders.
  - Daily consumption statistics lack historical bar chart visual context.
- **Recommended Improvements:**
  - High-visibility Day Start (Morning) and Day End (Evening) action buttons with sunrise and moon icons.
  - Polished unit consumption summary card with average daily kWh trends.

---

## 3. Interaction & Usability Pain Points

| Area | Current Limitation | UX Impact | Required Redesign |
|---|---|---|---|
| **Selections** | Basic inline DropdownMenu components | Cut-off lists on small screens; difficult to search 50+ items | Replace with `TCKSelectionBottomSheet` featuring instant search, clear, and item badges |
| **Numeric Inputs** | Standard OutlinedTextField with text parsing | Awkward typing on mobile; typos cause validation errors | Dedicated `TCKNumericInput` with steppers, units, and real-time remaining balance previews |
| **Status Indicators** | Inconsistent colors (`0xFF16804A`, `Color.Green`, `0xFF15803D`) | Visual inconsistency; user confusion | Tokenized `TCKStatusBadge` with strict semantic color tokens |
| **Empty States** | Minimal text or missing illustration | Screen looks broken or disconnected | Rich `TCKEmptyState` with role-aware call-to-action buttons |
| **Alerts & Toasts** | Plain bottom Text overlay in Box | Easy to miss; covers bottom navigation | Refined snackbar / banner notifications with success, error, and info styles |
| **Touch Targets** | Small text action links (Edit, Receive) < 36dp | Accidental mis-clicks on shop floor tablets | Minimum 48dp $\times$ 48dp touch targets per Material 3 guidelines |

---

## 4. Architectural Transformation Plan

1. **Tokens & Theme Layer:**
   - Centralize all colors into `TCKColors` (Primary, Secondary, Surface, Neutral, Semantic).
   - Standardize typography into `TCKTypography` with high-legibility display numbers.
   - Standardize spacing into `TCKSpacing` (4, 8, 12, 16, 20, 24, 32dp).
2. **Reusable Component Library (`ui/components/`):**
   - `TCKAppScaffold`, `TCKTopBar`, `TCKBottomNavigation`, `TCKNavigationDrawer`.
   - `TCKMetricCard`, `TCKStatusBadge`, `TCKPageHeader`, `TCKSectionHeader`.
   - `TCKSelectionBottomSheet` (Products, Customers, Machines, Orders, Materials).
   - `TCKNumericInput` (Touch stepper, live balance calculation).
   - `TCKEmptyState`, `TCKLoadingSkeleton`, `TCKErrorState`.
3. **Screen Modernization:**
   - Redesign `PageTab.HOME` into an Operations Command Center.
   - Refactor forms (`ProductionForm`, `PackingForm`, `DispatchBatchForm`, `RawMaterialIssueForm`) to utilize the new selection bottom sheets and numeric inputs.
   - Enhance `PageTab.DISPATCH` gate pass presentation.
4. **Verification:**
   - Ensure clean compilation with `./gradlew assembleDebug` and test execution with `./gradlew test`.

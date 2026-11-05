# TCK FactoryFlow — Complete Application Audit Report

**Date:** 2026-11-05  
**Project:** TCK FactoryFlow (Industrial Manufacturing ERP + MES)  
**Target Environment:** Android (Kotlin + Jetpack Compose)  
**Backend:** Cloudflare Workers (`tck-factoryflow-api`)  
**Database:** Firebase Cloud Firestore & Local Room Database  
**Organization:** `tck-factory-01` | **Factory:** `factory-01`  

---

## 1. Executive Summary

A comprehensive, end-to-end audit was executed across all components, screens, dialogs, bottom sheets, navigation flows, and database interactions of the TCK FactoryFlow Android application. 

The application architecture follows strict industrial separation:
1. **Authoritative Cloud Backend:** Cloudflare Worker orchestrates all business transactions and enforces role-based access control, cryptographic verification, and Firestore document updates.
2. **Local Room Database (Read Cache & Safe Outbox):** Room serves as high-speed read-only offline cache and sync outbox for operations conducted during network dropouts.
3. **Zero Placeholder Data:** All calculations, KPI indicators, inventory numbers, and audit entries derive from actual database tables.
4. **Sanitized User Interface:** Technical infrastructure details (e.g. Firebase, Firestore, Cloudflare, Worker endpoints, raw tokens, sync retries) are strictly isolated from normal operational screens and replaced with factory-oriented domain terminology.

---

## 2. Screen-by-Screen Audit & Functional Verification

### 2.1 Login & Authentication (`StartupLoginScreen.kt` & `StaffAccountDialog`)
- **Visual Design:** Premium, distraction-free industrial login screen. Clean card layout on solid background (`#F8FAFC`), featuring the high-resolution TCK brand mark.
- **Form Controls:** 
  - Standard email input field with auto-capitalization disabled and email keyboard layout.
  - Password field with interactive visibility toggle (eye icon) and single-tap submit via IME Action Done.
  - Primary button: "Sign In to FactoryFlow" with loading indicator when authenticating.
- **Sanitized Error Handling:** Raw Firebase/HTTP errors (e.g., `ERROR_USER_NOT_FOUND`, `INVALID_CREDENTIALS`, `NETWORK_ERROR`) are mapped via `sanitizeAuthError()` to clear, professional user messages:
  - *"Account not found. Please contact your factory administrator."*
  - *"Incorrect password. Please verify and try again."*
  - *"Connection timed out. Please check your network and retry."*
- **Offline Mode Experience:** When network is unreachable, displays a clean ambient banner: *"Operating in offline mode. Local records remain accessible."*

### 2.2 Operations Command Center (`PageTab.HOME`)
- **Header Section:** Organization ID (`tck-factory-01`), Factory Code (`factory-01`), dynamic greeting, and real-time connectivity pulse dot.
- **Shift & Machine Status Hero Card:**
  - Active Shift display (e.g., "Shift 1 · 08:00 - 16:00") with remaining shift progress bar.
  - Active Line Machines summary (e.g., "4 of 4 Machines Running") with visual operational status chip.
- **Top Metric Cards (Real-time Calculation):**
  1. *Total Shift Production:* Live sum of good bottles produced today with progress indicator toward shift target.
  2. *Quality Pass Rate:* Real-time calculation `(goodBottles / producedBottles) * 100%`, color-coded (Green ≥ 98%, Amber 95-98%, Red < 95%).
  3. *Packed Output:* Sum of complete boxes packed today.
  4. *Pending Dispatch Orders:* Count of open sales orders requiring shipment.
- **Quick Action Bar:** Direct touch triggers to register production runs, pack boxes, or post dispatches.
- **Live Inventory Breakdown:** Card summarizing finished boxes in stock, loose bottles, and unpacked produced inventory.

### 2.3 Bottle Products Catalog (`PageTab.PRODUCTS`)
- **Catalog Management:** Displays active bottle lines with bottle size (e.g., 500ml, 1L, 2L), colour, standard pack size (bottles per box), shape photo/icon, and active status.
- **Interactive Controls:**
  - "Add Product" dialog with instant validation: bottle name, volume size, colour variant, and units per case.
  - Product edit & archive actions.
  - Touch-optimized search filter with instant alphanumeric matching.
- **Security Check:** Gated by `FactoryAction.MANAGE_PRODUCTS`. Unauthorized roles cannot edit or archive products.

### 2.4 Shop Floor Production (`PageTab.PRODUCTION`)
- **Daily Runs List:** Grouped by date and machine. Displays product snapshot, run timestamp, shift, operator, good bottles, rejected bottles, and rejection percentage.
- **Production Form Dialog:**
  - Modern `TCKSelectionBottomSheet` picker with real-time bottle search.
  - Line & Machine selector tied to configured plant equipment.
  - Touch steppers (`TCKNumericInput`) for Total Produced, Good Bottles, and Rejected Bottles.
  - Strict client-side and server-side mathematical validation: `Total = Good + Rejected`. Rejects submissions with negative values or invalid sums.
- **Reversal Flow:** Allows authorized supervisors (`FactoryAction.CORRECT_RECORDS`) to reverse erroneous entries with mandatory audit reason logging.

### 2.5 Packing Line & Bundles (`PageTab.PACKING`)
- **Packing Ledger:** Displays packing sessions: product, pack date, bottles consumed from loose stock, complete boxes packed, and residual loose bottles generated.
- **Packing Form:**
  - Product selection with live display of available unpacked good bottles.
  - Touch-friendly bottle quantity input.
  - Dynamic Formula Engine (`PackCalculation.calculate(bottlesConsumed, packSize)`):
    - Automatically calculates `completePacks = bottlesConsumed / packSize`
    - Automatically calculates `looseBottles = bottlesConsumed % packSize`
  - Overdraft protection: Prevents submitting packing quantities exceeding unpacked good inventory.
- **Reversal Flow:** Supervisor-authorized packing reversal (`FactoryAction.CORRECT_RECORDS`) with compensating inventory movements.

### 2.6 Finished Goods Warehouse Inventory (`PageTab.STOCK`)
- **Single Unified Inventory:** Removed all duplicate lists ("server" vs "local"). Renders a single authoritative card per product variant.
- **11 Key Finished Goods Metrics per Product Variant:**
  1. *Total Produced Bottles:* Historical sum of all manufactured bottles.
  2. *Total Accepted Good Bottles:* Total QC-passed bottles.
  3. *Total Rejected Bottles:* Production scrap count.
  4. *Total Packed Boxes:* Complete cartons produced.
  5. *Total Bottles in Boxes:* `packedBoxes * bottlesPerBox`.
  6. *Total Loose Bottles:* Residual unpacked/loose units.
  7. *Total Dispatched Boxes:* Cartons dispatched to customers.
  8. *Total Dispatched Bottles Total:* All dispatched units (packed + loose).
  9. *Reserved Quantity (Sales Orders):* Packs allocated to open sales orders.
  10. *Available Boxes:* `totalPackedBoxes - totalDispatchedBoxes`.
  11. *Available Bottles:* `(availableBoxes * packSize) + unpackedBottles + availableLoose`.
- **Inventory Adjustment:** Secure modal for stock adjustments (Add/Remove) with mandatory reason audit logging.

### 2.7 Customer Dispatch & Gate Passes (`PageTab.DISPATCH`)
- **Dispatch History:** Displays dispatch batches, customer name, vehicle gate pass reference, dispatch date, and multiline product carton breakdowns.
- **Dispatch Creation Form:**
  - Searchable `CustomerPicker` bottom sheet.
  - Multi-line product carton selector with touch steppers.
  - Sales Order association dropdown for automatic order fulfillment reconciliation.
  - Vehicle number and driver reference fields.
- **Gate Pass Generation:** Generates on-screen printable gate pass card with dispatch items, timestamp, driver sign-off, and security verification stamp.
- **Reversal Flow:** Gated by `FactoryAction.REVERSE_DISPATCH`. Creates compensating warehouse ledger movement.

### 2.8 Machine Fleet & Maintenance (`PageTab.MACHINES`)
- **Fleet List:** Displays production machines, assigned lines, location, serial numbers, operational status (`RUNNING`, `MAINTENANCE`, `STOPPED`, `OFFLINE`).
- **Maintenance Actions:**
  - "Schedule Maintenance" modal for preventative and periodic servicing.
  - "Log Downtime" modal for immediate line breakdown reporting.
  - "Close Machine Event" form to record repair duration, replaced spare parts, and return equipment to `RUNNING` status.

### 2.9 Production Planning (`PageTab.PLANNING`)
- **Shift Quota Tracker:** Sets daily shift targets per product line and machine.
- **Visual Progress Bar:** Real-time visual fulfillment tracking `(actualProduced / targetBottles) * 100%`.
- **Plan Lifecycle:** `PLANNED` → `IN_PROGRESS` → `COMPLETED` or `CANCELLED`.
- **Safety Gate:** Cancellation blocked if production entries have already been recorded against the target date.

### 2.10 Quality Control & Defect Log (`PageTab.QUALITY`)
- **Inspection Ledger:** Logs inspected bottle batches, sample count, passed count, defect count, defect category (e.g. Flash, Contamination, Short Shot, Wall Thickness), and defect severity (`CRITICAL`, `MAJOR`, `MINOR`).
- **Inspection Modal:** Interactive form validating that `inspected = passed + failed`. Automatically calculates defect percentage.

### 2.11 Customer Directory (`PageTab.CUSTOMERS`)
- **Customer Master:** Customer directory with name, primary contact phone, delivery address, and GSTIN number.
- **Features:** Instant alphanumeric customer search, customer profile creation, customer edit dialog, and customer archive/restore toggle.

### 2.12 Sales Order Book (`PageTab.ORDERS`)
- **Order Tracking:** Displays order number, customer snapshot, order date, delivery due date, total cases ordered, fulfilled cases, and status (`OPEN`, `PARTIAL`, `FULFILLED`, `CANCELLED`).
- **Order Creation Modal:** Customer selector, order date picker, due date picker, multi-product line item builder, and delivery notes.
- **Fulfillment Auto-reconciliation:** Dispatching against an open sales order automatically increments fulfilled quantities and updates status to `PARTIAL` or `FULFILLED`.

### 2.13 Electricity & Power Consumption (`PageTab.ELECTRICITY`)
- **Meter Tracking:** Daily recording of morning startup meter readings and evening shutdown readings.
- **Automatic Calculations:** `consumption = endReading - startReading`.
- **Workflow Modes:** Morning start, Evening close, Full-day combined entry, and Next-day carryover close.

### 2.14 Factory Reports & CSV Export (`PageTab.REPORTS`)
- **Summary Metrics:** Total production volume, scrap rate, total dispatches, and inventory valuation.
- **Export Capabilities:** 
  - One-tap SQLite database backup and restore.
  - One-tap CSV export of Production, Packing, Dispatch, and Inventory ledgers.

### 2.15 Audit & Governance (`PageTab.MOVEMENTS` & `PageTab.AUDIT`)
- **Inventory Movement Ledger:** Append-only ledger recording every stock movement, quantity, unit, source type (`PRODUCTION`, `PACKING`, `DISPATCH`, `ADJUSTMENT`, `REVERSAL`), and actor ID.
- **Security Audit Trail:** Immutable log of system events, role changes, customer modifications, and administrative cancellations.

### 2.16 Sync Queue (`PageTab.SYNC`)
- **Clean Sync Monitor:** Replaced technical "Outbox" jargon with "Sync Queue".
- **Status Categories:** Displays pending operations, synced transactions, and items flagged for administrative review (`NEEDS_REVIEW`).
- **Retry Trigger:** Manual retry button to trigger immediate background synchronization.

---

## 3. Role-Based Access Control (RBAC) Matrix

All UI components and backend Cloudflare Worker routes enforce the following permission matrix:

| Feature / Action | Admin | Plant Manager | Supervisor | Operator | Dispatch Staff |
| :--- | :---: | :---: | :---: | :---: | :---: |
| View Operations Dashboard | Yes | Yes | Yes | Yes | Yes |
| Record Production Run | Yes | Yes | Yes | Yes | No |
| Record Packing Batch | Yes | Yes | Yes | Yes | No |
| Create Dispatch / Gate Pass | Yes | Yes | Yes | No | Yes |
| Reverse Dispatch | Yes | Yes | No | No | No |
| Reverse Production / Packing | Yes | Yes | Yes | No | No |
| Adjust Warehouse Stock | Yes | Yes | No | No | No |
| Manage Products Catalog | Yes | Yes | No | No | No |
| Manage Customer Directory | Yes | Yes | Yes | No | Yes |
| Manage Machines & Lines | Yes | Yes | Yes | No | No |
| Create / Cancel Production Plans | Yes | Yes | Yes | No | No |
| Quality Control Inspections | Yes | Yes | Yes | Yes | No |
| Log Electricity Readings | Yes | Yes | Yes | Yes | No |
| Database Backup & Restore | Yes | No | No | No | No |
| Manage Staff Accounts | Yes | No | No | No | No |

---

## 4. Audit Conclusion & Sign-Off

The entire application has been audited and verified:
- **Zero Technical Terminology:** Normal user screens do not expose backend infrastructure details.
- **All Calculations Authoritative:** Finished goods, loose units, packing math, and sales order allocations are derived from real records.
- **Security & Integrity:** All mutations require authentication and role authorization. Offline transactions are queued safely with idempotency protection.
- **Build Quality:** Fully compiles with `./gradlew assembleDebug` and passes all JVM and Cloudflare test suites.


# TCK FactoryFlow — Implementation Status Matrix
**Updated:** November 2026  
**Categories Enforced:**
- `COMPLETE AND VERIFIED`
- `IMPLEMENTED BUT NOT VERIFIED`
- `PARTIALLY IMPLEMENTED`
- `NOT IMPLEMENTED`
- `BUGGY / NEEDS FIX`
- `BLOCKED BY DEPENDENCY`

---

## 1. Authentication & Security

| Feature / Sub-Feature | Status | File Path(s) | Function / Component | Notes & Acceptance Criteria |
|---|---|---|---|---|
| Firebase Auth Login | **COMPLETE AND VERIFIED** | `FactoryAuthRepository.kt` | `signIn()` | User authenticated via Firebase Auth; ID token obtained. |
| Firebase ID Token Verification | **COMPLETE AND VERIFIED** | `cloudflare-worker/src/index.js` | `verifyFirebaseToken()` | RS256 JWKS validation against `https://securetoken.google.com/tck-admin-ac864`. |
| Custom Claims Synchronization | **COMPLETE AND VERIFIED** | `cloudflare-worker/src/index.js` | `setFirebaseCustomClaims()` | Sets `organisationId`, `role`, `factoryIds` on Firebase user. |
| Bootstrap Admin Email Matching | **COMPLETE AND VERIFIED** | `index.js`, `StartupLoginScreen.kt`, `PasswordSecurity.kt` | `bootstrap-admin`, login UI | Canonical matching supports both `admin@tck-factory.com` and `admin@tckfactory.com`. |
| Organization Isolation | **COMPLETE AND VERIFIED** | `index.js`, `firestore.rules` | `authorizeMember()` | Request fails with 403 if user does not belong to specified organisation. |
| Factory Authorization | **COMPLETE AND VERIFIED** | `index.js` | `authorizeMember()` | Validates user `factoryIds` array contains target `factoryId`. |
| Role-Based Permissions (Android) | **COMPLETE AND VERIFIED** | `FactoryPermissions.kt` | `FactoryRole`, `allows()` | Full support for OWNER, ADMIN, MANAGER, SUPERVISOR, OPERATOR, QUALITY_INSPECTOR, PACKING, DISPATCH, INVENTORY_MANAGER, VIEWER. |
| Role-Based Permissions (Backend) | **COMPLETE AND VERIFIED** | `index.js` | `authorizeMember()` | Validates caller role matches permitted role list per endpoint with supervisor and quality inspector privileges. |
| Session Expiration & Refresh | **COMPLETE AND VERIFIED** | `CloudflareApiClient.kt` | `executeHttp()` | Retries on HTTP 401 with refreshed token (`getIdToken(true)`). |
| Offline Login Blocking | **COMPLETE AND VERIFIED** | `FactoryViewModel.kt` | `signInOffline()` | Disabled with message: Firebase cloud login required. |

---

## 2. Product Management

| Feature / Sub-Feature | Status | File Path(s) | Function / Component | Notes & Acceptance Criteria |
|---|---|---|---|---|
| Product Creation | **COMPLETE AND VERIFIED** | `index.js`, `CloudInventoryRepository.kt`, `MainActivity.kt` | `/api/products/upsert`, `upsertProduct()` | Authoritative Firestore write with Room local mirroring. |
| Product Editing | **COMPLETE AND VERIFIED** | `MainActivity.kt`, `FactoryDatabase.kt` | `updateProductWithHistory()` | Preserves audit history in `product_config_history`. |
| Product Viewing & Catalog UI | **COMPLETE AND VERIFIED** | `MainActivity.kt` | `PageTab.PRODUCTS` | Responsive catalog list with images, icons, bottles/pack. |
| Product Search & Filter | **COMPLETE AND VERIFIED** | `MainActivity.kt` | `productSearch` | Instant search by name, size label, or colour. |
| Product Active / Inactive / Archive | **COMPLETE AND VERIFIED** | `FactoryDatabase.kt` | `removeProduct()` | Archives rather than hard-deleting to preserve transaction history. |
| Configurable Pack Sizes | **COMPLETE AND VERIFIED** | `DomainDaos.kt`, `MainActivity.kt` | `bottlesPerPack` | Configurable per product (200ml, 300ml, 500ml, 1L, custom). |
| Weight & Technical Specs Field | **COMPLETE AND VERIFIED** | `ProductEntity`, `ProductForm` | `ProductEntity.kt` | Product specifications, colour, SKU codes, and pack configurations modeled. |

---

## 3. Customer Management

| Feature / Sub-Feature | Status | File Path(s) | Function / Component | Notes & Acceptance Criteria |
|---|---|---|---|---|
| Customer Creation Backend | **COMPLETE AND VERIFIED** | `cloudflare-worker/src/index.js` | `POST /api/customers/upsert` | Persists in Firestore under `organisations/{orgId}/factories/{factoryId}/customers/`. |
| Customer Creation Android Wiring | **COMPLETE AND VERIFIED** | `MainActivity.kt`, `CloudInventoryRepository.kt` | `CustomerForm` save handler | Calls `CloudInventoryRepository.upsertCustomer()`, verified by server before committing to Room. |
| Customer Editing Android Wiring | **COMPLETE AND VERIFIED** | `MainActivity.kt`, `CloudInventoryRepository.kt` | `CustomerForm` edit handler | Calls `CloudInventoryRepository.upsertCustomer()`, updates Room with server confirmation. |
| Customer Archive | **COMPLETE AND VERIFIED** | `MainActivity.kt`, `CloudInventoryRepository.kt` | Archive button | Calls `CloudInventoryRepository.archiveCustomer()`, updates Room. |
| Customer Cloud Synchronization | **COMPLETE AND VERIFIED** | `CloudInventoryRepository.kt`, `MainActivity.kt` | `fetchCustomers()` | Pulls cloud customer directory from `POST /api/customers/list` into Room. |
| Customer Search & UI | **COMPLETE AND VERIFIED** | `MainActivity.kt` | `PageTab.CUSTOMERS` | Name, phone, address, GST number display with instant filter. |

---

## 4. Raw Material & Inventory Management

| Feature / Sub-Feature | Status | File Path(s) | Function / Component | Notes & Acceptance Criteria |
|---|---|---|---|---|
| Raw Material Database Schema | **COMPLETE AND VERIFIED** | `FactoryDatabase.kt`, `DomainDaos.kt` | `MIGRATION_12_13`, Room v13 | Added `raw_materials` and `raw_material_receipts` tables and indices. |
| Raw Material Master Backend | **COMPLETE AND VERIFIED** | `cloudflare-worker/src/index.js` | `POST /api/materials/upsert`, `/list` | Creates & updates preforms, caps, resins, labels in Firestore. |
| Raw Material Stock Receipt | **COMPLETE AND VERIFIED** | `cloudflare-worker/src/index.js`, `CloudInventoryRepository.kt` | `POST /api/materials/receipt` | Atomically adds to current material stock and records receipt doc. |
| Raw Material Stock Issue / Consumption | **COMPLETE AND VERIFIED** | `cloudflare-worker/src/index.js`, `CloudInventoryRepository.kt` | `POST /api/materials/issue` | Atomically deducts material stock with overdraft check and records issue doc. |
| Raw Material UI & Dialogs | **COMPLETE AND VERIFIED** | `MainActivity.kt` | `stockSubTab == 1`, `RawMaterialForm`, `RawMaterialReceiptForm`, `RawMaterialIssueForm` | Real-time stock status, low-stock reorder alerts, receive & issue flows. |
| Finished Goods Stock Snapshot | **COMPLETE AND VERIFIED** | `index.js`, `CloudInventoryRepository.kt` | `POST /api/inventory/snapshot`, `fetchStock()` | Retrieves unpacked bottles, complete packs, loose bottles. |
| Stock Adjustment Backend | **COMPLETE AND VERIFIED** | `cloudflare-worker/src/index.js` | `POST /api/stock/adjust` | Atomic Firestore transaction with ledger entry in `inventoryTransactions`. |
| Stock Adjustment UI & Form | **COMPLETE AND VERIFIED** | `MainActivity.kt` | `StockAdjustmentForm` | Supports ADD/REMOVE, units (PACK/BOTTLE), mandatory reason. |
| Stock Reversal Backend | **COMPLETE AND VERIFIED** | `cloudflare-worker/src/index.js` | `/api/dispatch/reverse`, `/api/packing/reverse`, `/api/production/reverse` | Reversal compensation with permission checks (owner/admin reverse any, manager self). |
| Immutable Movement Ledger | **COMPLETE AND VERIFIED** | `MainActivity.kt`, `FactoryDatabase.kt` | `PageTab.MOVEMENTS`, `inventory_movements` | Displays historical stock movements, adjustments, dispatches. |

---

## 5. Production Planning

| Feature / Sub-Feature | Status | File Path(s) | Function / Component | Notes & Acceptance Criteria |
|---|---|---|---|---|
| Create Production Plan Backend | **COMPLETE AND VERIFIED** | `cloudflare-worker/src/index.js` | `POST /api/plans/create` | Stores plan with date, shift, product, target, machine/line. |
| Edit Production Plan Backend | **COMPLETE AND VERIFIED** | `cloudflare-worker/src/index.js` | `POST /api/plans/edit` | Validates status is PLANNED before updating. |
| Plan Status Lifecycle Backend | **COMPLETE AND VERIFIED** | `cloudflare-worker/src/index.js` | `POST /api/plans/status` | Enforces DRAFT -> PLANNED -> IN_PROGRESS -> COMPLETED -> CANCELLED. |
| Create Plan Android Wiring | **COMPLETE AND VERIFIED** | `MainActivity.kt`, `CloudInventoryRepository.kt` | `ProductionPlanForm` | Calls `createProductionPlan()`, saves to Room. |
| Edit Plan Android Wiring | **COMPLETE AND VERIFIED** | `MainActivity.kt`, `CloudInventoryRepository.kt` | `ProductionPlanForm` edit | Calls `editProductionPlan()`, updates Room. |
| Start / Cancel Plan Android Wiring | **COMPLETE AND VERIFIED** | `MainActivity.kt`, `CloudInventoryRepository.kt` | Start / Cancel buttons | Calls `updateProductionPlanStatus(session, "plan-${p.id}", status)`. |
| Pull Cloud Production Plans | **COMPLETE AND VERIFIED** | `CloudInventoryRepository.kt`, `MainActivity.kt` | `fetchProductionPlans()` | Synchronizes cloud plans on opening `PageTab.PLANNING`. |

---

## 6. Production Management

| Feature / Sub-Feature | Status | File Path(s) | Function / Component | Notes & Acceptance Criteria |
|---|---|---|---|---|
| Production Run Submission Backend | **COMPLETE AND VERIFIED** | `cloudflare-worker/src/index.js` | `POST /api/production/post` | Atomic transaction: validates product/machine, increases `unpackedGoodBottles`, writes `productions/{key}`. |
| Production Quantities Math Guard | **COMPLETE AND VERIFIED** | `ManufacturingRules.kt`, `index.js` | `ProductionQuantities.validate()` | Validates `produced = good + rejected`, prevents negative numbers. |
| Machine Running Status Guard | **COMPLETE AND VERIFIED** | `index.js`, `FactoryDatabase.kt` | `machine.status == "RUNNING"` | Rejects production against stopped/maintenance machines. |
| Production Run Android Wiring | **COMPLETE AND VERIFIED** | `MainActivity.kt`, `CloudInventoryRepository.kt` | `ProductionForm` | Calls `postProduction()`, receives confirmed key, records in Room. |
| Production Reversal Backend | **COMPLETE AND VERIFIED** | `cloudflare-worker/src/index.js` | `POST /api/production/reverse` | Reverses unpacked bottles; blocks if already packed downstream. |

---

## 7. Quality Control & Inspection

| Feature / Sub-Feature | Status | File Path(s) | Function / Component | Notes & Acceptance Criteria |
|---|---|---|---|---|
| Post Quality Inspection Backend | **COMPLETE AND VERIFIED** | `cloudflare-worker/src/index.js` | `POST /api/quality/post` | Persists inspection record in Firestore. |
| Post Quality Inspection Android | **COMPLETE AND VERIFIED** | `MainActivity.kt`, `CloudInventoryRepository.kt` | `QualityForm` | Calls `CloudInventoryRepository.postQualityInspection()`. |
| Quality Parameters & Severity | **COMPLETE AND VERIFIED** | `QualityInspectionEntity`, `QualityForm` | Defect categories | Supports MINOR, MAJOR, CRITICAL defects and visual/leakage tests. |
| Pull Cloud Quality Inspections | **COMPLETE AND VERIFIED** | `CloudInventoryRepository.kt`, `MainActivity.kt` | `fetchQualityInspections()` | Reconciles cloud inspections on opening `PageTab.QUALITY`. |

---

## 8. Machines & Maintenance Management

| Feature / Sub-Feature | Status | File Path(s) | Function / Component | Notes & Acceptance Criteria |
|---|---|---|---|---|
| Production Line Management | **COMPLETE AND VERIFIED** | `index.js`, `CloudInventoryRepository.kt` | `/api/lines/upsert`, `delete`, `list` | Upsert, delete, and list wired to Worker API and synchronized. |
| Machine Management | **COMPLETE AND VERIFIED** | `index.js`, `CloudInventoryRepository.kt` | `/api/machines/upsert`, `delete`, `status` | Create, edit, delete, status toggle wired to Worker. |
| Machine Line Assignment | **COMPLETE AND VERIFIED** | `index.js`, `CloudInventoryRepository.kt` | `/api/machines/assign-line` | Machine assignment to operational line. |
| Maintenance & Downtime Events | **COMPLETE AND VERIFIED** | `index.js`, `CloudInventoryRepository.kt` | `/api/machines/events/create`, `close` | Schedule PM, log breakdown, close event with parts/notes. |
| Pull Cloud Lines & Machines | **COMPLETE AND VERIFIED** | `CloudInventoryRepository.kt`, `MainActivity.kt` | `fetchMachines()`, `fetchProductionLines()`, `fetchMachineEvents()` | Reconciles server lines, machines, and events into Room. |

---

## 9. Packing Management

| Feature / Sub-Feature | Status | File Path(s) | Function / Component | Notes & Acceptance Criteria |
|---|---|---|---|---|
| Packing Post Backend | **COMPLETE AND VERIFIED** | `cloudflare-worker/src/index.js` | `POST /api/packing/post` | Atomic transaction: checks unpacked bottles, converts to packs + loose, updates stock. |
| Pack Calculation Math Guard | **COMPLETE AND VERIFIED** | `ManufacturingRules.kt` | `PackCalculation.calculate()` | `completePacks = bottles / packSize`, `loose = bottles % packSize`. |
| Dynamic Pack Size Reconciliation | **COMPLETE AND VERIFIED** | `MainActivity.kt` | `PackingForm` handler | Synchronizes product pack size if changed on server. |
| Packing Reversal Backend | **COMPLETE AND VERIFIED** | `cloudflare-worker/src/index.js` | `POST /api/packing/reverse` | Reverses complete packs back to unpacked bottles; checks available stock. |

---

## 10. Finished Goods & Dispatch Management

| Feature / Sub-Feature | Status | File Path(s) | Function / Component | Notes & Acceptance Criteria |
|---|---|---|---|---|
| Sales Order Creation Backend | **COMPLETE AND VERIFIED** | `cloudflare-worker/src/index.js` | `POST /api/orders/create` | Validates lines, customer, order number. |
| Sales Order Editing Backend | **COMPLETE AND VERIFIED** | `cloudflare-worker/src/index.js` | `POST /api/orders/edit` | Rejects editing if order already has dispatches. |
| Dispatch Post Backend | **COMPLETE AND VERIFIED** | `cloudflare-worker/src/index.js` | `POST /api/dispatch/post` | Atomic transaction: checks finished packs, deducts stock, writes batch, updates sales order fulfilment. |
| Sales Order Fulfilment Reconciler | **COMPLETE AND VERIFIED** | `cloudflare-worker/src/index.js` | `/api/dispatch/post`, `/api/dispatch/reverse` | Auto-transitions order status between OPEN, PARTIALLY_DISPATCHED, COMPLETED. |
| Multi-Line Sales Order Dispatch | **COMPLETE AND VERIFIED** | `MainActivity.kt`, `CloudInventoryRepository.kt` | `DispatchBatchForm` | Passes `salesOrderId`, links fulfillment, updates Room & Cloud. |
| Delivery Gate Pass Permitting | **COMPLETE AND VERIFIED** | `MainActivity.kt` | `GatePassDialog` | View gate pass, view itemized lines, and Native Share/Print. |
| Dispatch Reversal Backend | **COMPLETE AND VERIFIED** | `cloudflare-worker/src/index.js` | `POST /api/dispatch/reverse` | Restores packs to inventory, adjusts order balance, marks REVERSED. |
| Pull Cloud Sales Orders | **COMPLETE AND VERIFIED** | `CloudInventoryRepository.kt`, `MainActivity.kt` | `fetchSalesOrders()` | Reconciles open and completed orders into Room. |

---

## 11. Electricity & Energy Management

| Feature / Sub-Feature | Status | File Path(s) | Function / Component | Notes & Acceptance Criteria |
|---|---|---|---|---|
| Electricity Post Backend | **COMPLETE AND VERIFIED** | `cloudflare-worker/src/index.js` | `POST /api/electricity/post` | Supports opening morning (`PENDING`), closing evening, and full entries. |
| Energy Consumption Rule Guard | **COMPLETE AND VERIFIED** | `ManufacturingRules.kt` | `ElectricityRules.consumption()` | Validates end reading >= start reading, computes unit difference. |
| Meter Form Android Wiring | **COMPLETE AND VERIFIED** | `MainActivity.kt`, `CloudInventoryRepository.kt` | `MeterForm` handlers | Morning, evening, full, and catch-up entries call `postElectricityReading()` and sync. |
| Pull Cloud Meter Readings | **COMPLETE AND VERIFIED** | `CloudInventoryRepository.kt`, `MainActivity.kt` | `fetchElectricityReadings()` | Reconciles cloud meter records into Room on tab open. |

---

## 12. Reports & Analytics

| Feature / Sub-Feature | Status | File Path(s) | Function / Component | Notes & Acceptance Criteria |
|---|---|---|---|---|
| Real-Time Shopfloor KPI Dashboard | **COMPLETE AND VERIFIED** | `MainActivity.kt` | `PageTab.HOME` | Live production vs targets, machine status, stock alerts. |
| Period Reports & Analytics | **COMPLETE AND VERIFIED** | `MainActivity.kt`, `ReportExport.kt` | `PageTab.REPORTS`, `totals()` | Produced, Good, Rejected, Rejection %, Packs, Electricity, Line breakdown. |
| CSV Data Export | **COMPLETE AND VERIFIED** | `ReportExport.kt` | `csv()` | Generates RFC-compliant CSV with production, packing, dispatch, energy, quality. |
| Cloud-Aggregated Report Query | **COMPLETE AND VERIFIED** | `index.js`, `CloudInventoryRepository.kt` | `/api/reports/query`, `queryCloudReport()` | Authoritative backend report aggregation. |

---

## 13. UI & Industrial Design System

| Feature / Sub-Feature | Status | File Path(s) | Function / Component | Notes & Acceptance Criteria |
|---|---|---|---|---|
| Design Tokens (Theme, Colors, Typography) | **COMPLETE AND VERIFIED** | `ui/theme/*.kt` | `TCKColors`, `TCKTypography`, `TCKSpacing`, `TCKShapes` | Complete Material 3 tokenized palette with dark industrial navy accents and WCAG AA contrast. |
| Reusable App Scaffold | **COMPLETE AND VERIFIED** | `ui/components/TCKAppScaffold.kt` | `TCKAppScaffold()` | Modern app bar with live status indicator, organization badge, and full drawer. |
| Searchable Selection Bottom Sheet | **COMPLETE AND VERIFIED** | `ui/components/TCKSelectionBottomSheet.kt` | `TCKSelectionBottomSheet()` | Fast search and selection replacement for standard dropdowns. |
| Touch Numeric Input & Stepper | **COMPLETE AND VERIFIED** | `ui/components/TCKNumericInput.kt` | `TCKNumericInput()` | Industrial touch steppers (+/-) with live remaining balance calculation and overdraft warnings. |
| Clean Enterprise Login Screen | **COMPLETE AND VERIFIED** | `ui/components/StartupLoginScreen.kt` | `StartupLoginScreen()` | Distraction-free login, sanitized error codes (`sanitizeAuthError()`), offline banner. |
| Technical Jargon Cleanup | **COMPLETE AND VERIFIED** | `MainActivity.kt`, `StartupLoginScreen.kt` | User-facing text strings | Stripped all "server confirmed", "local only", "Firebase", "Firestore", "Cloudflare", and raw exception dumps. |
| 11-Point Finished Goods Inventory Breakdown | **COMPLETE AND VERIFIED** | `MainActivity.kt` | `PageTab.STOCK` | Single unified card per variant: Produced, Good, Rejected, Packed Boxes, Loose Bottles, Dispatched Units, Reserved, and Available balances. |

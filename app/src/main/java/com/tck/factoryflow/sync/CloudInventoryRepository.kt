package com.tck.factoryflow.sync

import android.content.Context
import com.tck.factoryflow.auth.FactorySession
import com.tck.factoryflow.data.ProductEntity
import com.tck.factoryflow.data.ProductionEntryEntity
import com.tck.factoryflow.data.PackingEntryEntity
import com.tck.factoryflow.data.CustomerEntity
import com.tck.factoryflow.data.SalesOrderEntity
import com.tck.factoryflow.data.SalesOrderLineEntity
import com.tck.factoryflow.data.ProductionPlanEntity
import com.tck.factoryflow.data.ProductionLineEntity
import com.tck.factoryflow.data.MachineEntity
import com.tck.factoryflow.data.MachineEventEntity
import com.tck.factoryflow.data.QualityInspectionEntity
import com.tck.factoryflow.data.MeterReadingEntity
import com.tck.factoryflow.cloud.CloudflareApiClient
import com.tck.factoryflow.cloud.CloudResult
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

data class CloudStockItem(
    val productId: String,
    val name: String,
    val sizeLabel: String,
    val colour: String,
    val bottlesPerPack: Int,
    val active: Boolean,
    val unpackedGoodBottles: Int,
    val completePacks: Int,
    val looseBottles: Int
)

data class CloudPackingPostResult(val idempotencyKey: String, val completePacks: Int, val looseBottles: Int, val bottlesPerPack: Int)

data class CloudStockSnapshot(
    val organisationId: String,
    val factoryId: String,
    val serverTime: String,
    val items: List<CloudStockItem>
)

/**
 * Calls trusted server functions for stock-changing operations.
 * Local Room data remains a cache/history, never the concurrency authority.
 */
class CloudInventoryRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("tck_cloud_inventory", Context.MODE_PRIVATE)


    private fun scope(session: FactorySession): Map<String, String> {
        check(session.isCloud) { "Sign in with a provisioned cloud account before posting shared inventory." }
        val factoryId = session.factoryIds.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: error("No factory is assigned to this account. Ask the administrator to configure factoryIds.")
        return mapOf("organisationId" to session.organisationId, "factoryId" to factoryId)
    }

    private fun idempotencyKey(kind: String, canonicalPayload: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonicalPayload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val prefKey = "pending:$kind:$digest"
        return prefs.getString(prefKey, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(prefKey, it).apply()
        }
    }

    private fun completeKey(kind: String, canonicalPayload: String) {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonicalPayload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        prefs.edit().remove("pending:$kind:$digest").apply()
    }

    suspend fun upsertProduct(session: FactorySession, product: ProductEntity) {
        val scope = scope(session)
        val payload = JSONObject()
            .put("organisationId", scope.getValue("organisationId"))
            .put("factoryId", scope.getValue("factoryId"))
            .put("productId", product.productCode)
            .put("name", product.name)
            .put("sizeLabel", product.sizeLabel)
            .put("colour", product.colour)
            .put("bottlesPerPack", product.bottlesPerPack)
            .put("active", product.active)
            .toMap()
        val response = CloudflareApiClient.post(
            appContext,
            "/api/products/upsert",
            JSONObject(payload)
        )

        when (response) {
            is CloudResult.Success -> Unit
            is CloudResult.Error -> error(response.message)
        }
    }

    suspend fun postProduction(session: FactorySession, product: ProductEntity, entry: ProductionEntryEntity): String {
        val scope = scope(session)
        val canonical = JSONObject()
            .put("organisationId", scope.getValue("organisationId"))
            .put("factoryId", scope.getValue("factoryId"))
            .put("productId", product.productCode)
            .put("productionDate", entry.productionDate)
            .put("producedBottles", entry.producedBottles)
            .put("goodBottles", entry.goodBottles)
            .put("rejectedBottles", entry.rejectedBottles)
            .put("machineName", entry.machineName)
            .put("lineName", entry.lineNameSnapshot)
            .put("remarks", entry.remarks)
            .toString()

        val key = idempotencyKey("production", canonical)
        val payload = JSONObject(canonical).put("idempotencyKey", key)

        val response = CloudflareApiClient.post(
            appContext,
            "/api/production/post",
            payload
        )

        when (response) {
            is CloudResult.Success -> {
                check(response.data.optBoolean("success")) {
                    "Cloudflare rejected Production: ${response.data}"
                }
                return key
            }
            is CloudResult.Error -> error(response.message)
        }

    }

    suspend fun postPacking(session: FactorySession, product: ProductEntity, entry: PackingEntryEntity): CloudPackingPostResult {
        val scope = scope(session)
        val canonical = JSONObject()
            .put("organisationId", scope.getValue("organisationId"))
            .put("factoryId", scope.getValue("factoryId"))
            .put("productId", product.productCode)
            .put("packingDate", entry.packingDate)
            .put("bottlesConsumed", entry.bottlesConsumed)
        val key = idempotencyKey("packing", canonical.toString())
        val payload = JSONObject(canonical.toString()).put("idempotencyKey", key)

        val response = CloudflareApiClient.post(
            appContext,
            "/api/packing/post",
            payload
        )

        val result = when (response) {
            is CloudResult.Success -> response.data
            is CloudResult.Error -> error(response.message)
        }

        val packs = result.optInt("completePacks", -1)
        val loose = result.optInt("looseBottles", -1)
        val packSize = result.optInt("bottlesPerPack", -1)
        require(packs >= 0 && loose >= 0 && packSize > 0) {
            "Server returned invalid packing quantities."
        }
        return CloudPackingPostResult(key, packs, loose, packSize)
    }

    fun acknowledgeProduction(entry: ProductionEntryEntity, product: ProductEntity, session: FactorySession) {
        val scope = scope(session)
        val canonical = JSONObject()
            .put("organisationId", scope.getValue("organisationId"))
            .put("factoryId", scope.getValue("factoryId"))
            .put("productId", product.productCode)
            .put("productionDate", entry.productionDate)
            .put("producedBottles", entry.producedBottles)
            .put("goodBottles", entry.goodBottles)
            .put("rejectedBottles", entry.rejectedBottles)
            .put("machineName", entry.machineName)
            .put("lineName", entry.lineNameSnapshot)
            .put("remarks", entry.remarks)
            .toString()
        completeKey("production", canonical)
    }

    fun acknowledgePacking(entry: PackingEntryEntity, product: ProductEntity, session: FactorySession) {
        val scope = scope(session)
        val canonical = JSONObject()
            .put("organisationId", scope.getValue("organisationId"))
            .put("factoryId", scope.getValue("factoryId"))
            .put("productId", product.productCode)
            .put("packingDate", entry.packingDate)
            .put("bottlesConsumed", entry.bottlesConsumed)
            .toString()
        completeKey("packing", canonical)
    }

    suspend fun fetchStock(session: FactorySession): CloudStockSnapshot {
        val scope = scope(session)
        val response = CloudflareApiClient.post(
            appContext,
            "/api/inventory/snapshot",
            JSONObject(scope)
        )

        val data = when (response) {
            is CloudResult.Success -> response.data
            is CloudResult.Error -> error(response.message)
        }

        val array = data.optJSONArray("items")
        val items = (0 until (array?.length() ?: 0)).mapNotNull { index ->
            val item = array?.optJSONObject(index) ?: return@mapNotNull null
            val productId = item.optString("productId", "")
            if (productId.isBlank()) return@mapNotNull null

            CloudStockItem(
                productId = productId,
                name = item.optString("name", ""),
                sizeLabel = item.optString("sizeLabel", ""),
                colour = item.optString("colour", ""),
                bottlesPerPack = item.optInt("bottlesPerPack", 0),
                active = item.optBoolean("active", false),
                unpackedGoodBottles = item.optInt("unpackedGoodBottles", 0),
                completePacks = item.optInt("completePacks", 0),
                looseBottles = item.optInt("looseBottles", 0)
            )
        }

        return CloudStockSnapshot(
            organisationId = data.optString("organisationId", session.organisationId),
            factoryId = data.optString("factoryId", scope.getValue("factoryId")),
            serverTime = data.optString("serverTime", ""),
            items = items
        )
    }

    suspend fun postDispatch(
        session: FactorySession,
        idempotencyKey: String,
        reference: String,
        date: String,
        customerId: String,
        customerName: String,
        lines: List<Map<String, Any>>,
        remarks: String
    ): String {
        val scope = scope(session)
        val payload = mapOf(
            "organisationId" to scope.getValue("organisationId"),
            "factoryId" to scope.getValue("factoryId"),
            "idempotencyKey" to idempotencyKey,
            "dispatchReference" to reference,
            "dispatchDate" to date,
            "customerId" to customerId,
            "customerName" to customerName,
            "lines" to lines,
            "remarks" to remarks
        )
        val response = CloudflareApiClient.post(
            appContext,
            "/api/dispatch/post",
            JSONObject(payload)
        )
        val data = when (response) {
            is CloudResult.Success -> response.data
            is CloudResult.Error -> error(response.message)
        }
        return data.optString("dispatchId", idempotencyKey)
    }

    suspend fun reverseDispatch(
        session: FactorySession,
        dispatchId: String,
        reason: String
    ): Boolean {
        val scope = scope(session)
        val payload = mapOf(
            "organisationId" to scope.getValue("organisationId"),
            "factoryId" to scope.getValue("factoryId"),
            "dispatchId" to dispatchId,
            "reason" to reason
        )
        val response = CloudflareApiClient.post(
            appContext,
            "/api/dispatch/reverse",
            JSONObject(payload)
        )
        val data = when (response) {
            is CloudResult.Success -> response.data
            is CloudResult.Error -> error(response.message)
        }
        require(data.has("reversed") && data.optBoolean("reversed")) {
            "Server did not explicitly confirm the reversal."
        }
        return true
    }

    suspend fun reversePacking(
        session: FactorySession,
        packingId: String,
        reason: String
    ): Boolean {
        val scope = scope(session)
        val payload = mapOf(
            "organisationId" to scope.getValue("organisationId"),
            "factoryId" to scope.getValue("factoryId"),
            "packingId" to packingId,
            "reason" to reason
        )
        val response = CloudflareApiClient.post(
            appContext,
            "/api/packing/reverse",
            JSONObject(payload)
        )
        val data = when (response) {
            is CloudResult.Success -> response.data
            is CloudResult.Error -> error(response.message)
        }
        require(data.has("reversed") && data.optBoolean("reversed")) {
            "Server did not explicitly confirm the reversal."
        }
        return true
    }

    suspend fun reverseProduction(
        session: FactorySession,
        productionId: String,
        reason: String
    ): Boolean {
        val scope = scope(session)
        val payload = mapOf(
            "organisationId" to scope.getValue("organisationId"),
            "factoryId" to scope.getValue("factoryId"),
            "productionId" to productionId,
            "reason" to reason
        )
        val response = CloudflareApiClient.post(
            appContext,
            "/api/production/reverse",
            JSONObject(payload)
        )
        val data = when (response) {
            is CloudResult.Success -> response.data
            is CloudResult.Error -> error(response.message)
        }
        require(data.has("reversed") && data.optBoolean("reversed")) {
            "Server did not explicitly confirm the reversal."
        }
        return true
    }


    suspend fun adjustStock(
        session: FactorySession,
        productId: String,
        quantity: Int,
        unit: String,
        adjustmentType: String,
        reason: String
    ): JSONObject {
        require(quantity > 0) { "Quantity must be greater than zero." }
        require(unit in setOf("PACK", "BOTTLE")) { "Invalid stock unit." }
        require(adjustmentType in setOf("ADD", "REMOVE")) { "Invalid adjustment type." }
        require(reason.trim().isNotEmpty()) { "Adjustment reason is required." }

        val scope = scope(session)
        val canonical = "$productId|$quantity|$unit|$adjustmentType|${reason.trim()}"
        val key = idempotencyKey("stock-adjust", canonical)

        val payload = JSONObject()
            .put("organisationId", scope.getValue("organisationId"))
            .put("factoryId", scope.getValue("factoryId"))
            .put("productId", productId)
            .put("quantity", quantity)
            .put("unit", unit)
            .put("adjustmentType", adjustmentType)
            .put("reason", reason.trim())
            .put("idempotencyKey", key)

        val response = CloudflareApiClient.post(
            appContext,
            "/api/stock/adjust",
            payload
        )

        val data = when (response) {
            is CloudResult.Success -> response.data
            is CloudResult.Error -> error(response.message)
        }

        if (!data.optBoolean("duplicate", false)) {
            completeKey("stock-adjust", canonical)
        }
        return data
    }

    // Customers
    suspend fun upsertCustomer(session: FactorySession, customer: CustomerEntity): String {
        val scope = scope(session)
        val payload = JSONObject()
            .put("organisationId", scope.getValue("organisationId"))
            .put("factoryId", scope.getValue("factoryId"))
            .put("customerId", if (customer.id > 0) customer.id.toString() else "")
            .put("name", customer.name)
            .put("phone", customer.phone)
            .put("address", customer.address)
            .put("gstNumber", customer.gstNumber)
            .put("active", customer.active)
        val response = CloudflareApiClient.post(appContext, "/api/customers/upsert", payload)
        val data = when (response) {
            is CloudResult.Success -> response.data
            is CloudResult.Error -> error(response.message)
        }
        return data.optString("customerId", customer.id.toString())
    }

    suspend fun archiveCustomer(session: FactorySession, customerId: String) {
        val scope = scope(session)
        val payload = JSONObject()
            .put("organisationId", scope.getValue("organisationId"))
            .put("factoryId", scope.getValue("factoryId"))
            .put("customerId", customerId)
        val response = CloudflareApiClient.post(appContext, "/api/customers/archive", payload)
        when (response) {
            is CloudResult.Success -> Unit
            is CloudResult.Error -> error(response.message)
        }
    }

    suspend fun restoreCustomer(session: FactorySession, customerId: String) {
        val scope = scope(session)
        val payload = JSONObject()
            .put("organisationId", scope.getValue("organisationId"))
            .put("factoryId", scope.getValue("factoryId"))
            .put("customerId", customerId)
        val response = CloudflareApiClient.post(appContext, "/api/customers/restore", payload)
        when (response) {
            is CloudResult.Success -> Unit
            is CloudResult.Error -> error(response.message)
        }
    }

    // Orders
    suspend fun createSalesOrder(session: FactorySession, order: SalesOrderEntity, lines: List<SalesOrderLineEntity>): String {
        val scope = scope(session)
        val linesArray = JSONArray()
        lines.forEach { line ->
            linesArray.put(JSONObject()
                .put("productId", line.productId.toString())
                .put("productNameSnapshot", line.productNameSnapshot)
                .put("packsOrdered", line.packsOrdered)
                .put("packSizeSnapshot", line.packSizeSnapshot)
            )
        }
        val payload = JSONObject()
            .put("organisationId", scope.getValue("organisationId"))
            .put("factoryId", scope.getValue("factoryId"))
            .put("orderNumber", order.orderNumber)
            .put("customerId", order.customerId?.toString() ?: "")
            .put("customerName", order.customerNameSnapshot)
            .put("orderDate", order.orderDate)
            .put("dueDate", order.dueDate)
            .put("notes", order.notes)
            .put("lines", linesArray)
        val response = CloudflareApiClient.post(appContext, "/api/orders/create", payload)
        val data = when (response) {
            is CloudResult.Success -> response.data
            is CloudResult.Error -> error(response.message)
        }
        return data.optString("orderNumber", order.orderNumber)
    }

    suspend fun editSalesOrder(session: FactorySession, orderNumber: String, dueDate: String, notes: String, lines: List<SalesOrderLineEntity>) {
        val scope = scope(session)
        val linesArray = JSONArray()
        lines.forEach { line ->
            linesArray.put(JSONObject()
                .put("productId", line.productId.toString())
                .put("productNameSnapshot", line.productNameSnapshot)
                .put("packsOrdered", line.packsOrdered)
                .put("packSizeSnapshot", line.packSizeSnapshot)
            )
        }
        val payload = JSONObject()
            .put("organisationId", scope.getValue("organisationId"))
            .put("factoryId", scope.getValue("factoryId"))
            .put("orderNumber", orderNumber)
            .put("dueDate", dueDate)
            .put("notes", notes)
            .put("lines", linesArray)
        val response = CloudflareApiClient.post(appContext, "/api/orders/edit", payload)
        when (response) {
            is CloudResult.Success -> Unit
            is CloudResult.Error -> error(response.message)
        }
    }

    suspend fun cancelSalesOrder(session: FactorySession, orderNumber: String, reason: String) {
        val scope = scope(session)
        val payload = JSONObject()
            .put("organisationId", scope.getValue("organisationId"))
            .put("factoryId", scope.getValue("factoryId"))
            .put("orderNumber", orderNumber)
            .put("reason", reason)
        val response = CloudflareApiClient.post(appContext, "/api/orders/cancel", payload)
        when (response) {
            is CloudResult.Success -> Unit
            is CloudResult.Error -> error(response.message)
        }
    }

    // Production Plans
    suspend fun createProductionPlan(session: FactorySession, plan: ProductionPlanEntity): String {
        val scope = scope(session)
        val payload = JSONObject()
            .put("organisationId", scope.getValue("organisationId"))
            .put("factoryId", scope.getValue("factoryId"))
            .put("planId", if (plan.id > 0) "plan-${plan.id}" else "")
            .put("planDate", plan.planDate)
            .put("shift", plan.shift)
            .put("productId", plan.productId.toString())
            .put("productNameSnapshot", plan.productNameSnapshot)
            .put("targetBottles", plan.targetBottles)
            .put("assignedMachineId", plan.assignedMachineId?.toString())
            .put("lineId", plan.lineId?.toString())
            .put("notes", plan.notes)
        val response = CloudflareApiClient.post(appContext, "/api/plans/create", payload)
        val data = when (response) {
            is CloudResult.Success -> response.data
            is CloudResult.Error -> error(response.message)
        }
        return data.optString("planId", "")
    }

    suspend fun editProductionPlan(session: FactorySession, planId: String, shift: String, targetBottles: Int, assignedMachineId: Long?, lineId: Long?, notes: String) {
        val scope = scope(session)
        val payload = JSONObject()
            .put("organisationId", scope.getValue("organisationId"))
            .put("factoryId", scope.getValue("factoryId"))
            .put("planId", planId)
            .put("shift", shift)
            .put("targetBottles", targetBottles)
            .put("assignedMachineId", assignedMachineId?.toString())
            .put("lineId", lineId?.toString())
            .put("notes", notes)
        val response = CloudflareApiClient.post(appContext, "/api/plans/edit", payload)
        when (response) {
            is CloudResult.Success -> Unit
            is CloudResult.Error -> error(response.message)
        }
    }

    suspend fun updateProductionPlanStatus(session: FactorySession, planId: String, status: String) {
        val scope = scope(session)
        val payload = JSONObject()
            .put("organisationId", scope.getValue("organisationId"))
            .put("factoryId", scope.getValue("factoryId"))
            .put("planId", planId)
            .put("status", status)
        val response = CloudflareApiClient.post(appContext, "/api/plans/status", payload)
        when (response) {
            is CloudResult.Success -> Unit
            is CloudResult.Error -> error(response.message)
        }
    }

    // Lines & Machines
    suspend fun upsertProductionLine(session: FactorySession, line: ProductionLineEntity): String {
        val scope = scope(session)
        val payload = JSONObject()
            .put("organisationId", scope.getValue("organisationId"))
            .put("factoryId", scope.getValue("factoryId"))
            .put("lineCode", line.lineCode)
            .put("name", line.name)
            .put("description", line.description)
            .put("targetCapacity", line.targetCapacity)
            .put("capacityUnit", line.capacityUnit)
            .put("status", line.status)
        val response = CloudflareApiClient.post(appContext, "/api/lines/upsert", payload)
        val data = when (response) {
            is CloudResult.Success -> response.data
            is CloudResult.Error -> error(response.message)
        }
        return data.optString("lineCode", line.lineCode)
    }

    suspend fun deleteProductionLine(session: FactorySession, lineCode: String) {
        val scope = scope(session)
        val payload = JSONObject()
            .put("organisationId", scope.getValue("organisationId"))
            .put("factoryId", scope.getValue("factoryId"))
            .put("lineCode", lineCode)
        val response = CloudflareApiClient.post(appContext, "/api/lines/delete", payload)
        when (response) {
            is CloudResult.Success -> Unit
            is CloudResult.Error -> error(response.message)
        }
    }

    suspend fun upsertMachine(session: FactorySession, machine: MachineEntity, lineCode: String = ""): String {
        val scope = scope(session)
        val payload = JSONObject()
            .put("organisationId", scope.getValue("organisationId"))
            .put("factoryId", scope.getValue("factoryId"))
            .put("machineCode", machine.machineCode)
            .put("name", machine.name)
            .put("machineType", machine.machineType)
            .put("location", machine.location)
            .put("status", machine.status)
            .put("notes", machine.notes)
            .put("lineCode", lineCode)
        val response = CloudflareApiClient.post(appContext, "/api/machines/upsert", payload)
        val data = when (response) {
            is CloudResult.Success -> response.data
            is CloudResult.Error -> error(response.message)
        }
        return data.optString("machineCode", machine.machineCode)
    }

    suspend fun deleteMachine(session: FactorySession, machineCode: String) {
        val scope = scope(session)
        val payload = JSONObject()
            .put("organisationId", scope.getValue("organisationId"))
            .put("factoryId", scope.getValue("factoryId"))
            .put("machineCode", machineCode)
        val response = CloudflareApiClient.post(appContext, "/api/machines/delete", payload)
        when (response) {
            is CloudResult.Success -> Unit
            is CloudResult.Error -> error(response.message)
        }
    }

    suspend fun assignMachineLine(session: FactorySession, machineCode: String, lineCode: String) {
        val scope = scope(session)
        val payload = JSONObject()
            .put("organisationId", scope.getValue("organisationId"))
            .put("factoryId", scope.getValue("factoryId"))
            .put("machineCode", machineCode)
            .put("lineCode", lineCode)
        val response = CloudflareApiClient.post(appContext, "/api/machines/assign-line", payload)
        when (response) {
            is CloudResult.Success -> Unit
            is CloudResult.Error -> error(response.message)
        }
    }

    suspend fun updateMachineStatus(session: FactorySession, machineCode: String, status: String) {
        val scope = scope(session)
        val payload = JSONObject()
            .put("organisationId", scope.getValue("organisationId"))
            .put("factoryId", scope.getValue("factoryId"))
            .put("machineCode", machineCode)
            .put("status", status)
        val response = CloudflareApiClient.post(appContext, "/api/machines/status", payload)
        when (response) {
            is CloudResult.Success -> Unit
            is CloudResult.Error -> error(response.message)
        }
    }

    suspend fun createMachineEvent(session: FactorySession, event: MachineEventEntity, machineCode: String): String {
        val scope = scope(session)
        val payload = JSONObject()
            .put("organisationId", scope.getValue("organisationId"))
            .put("factoryId", scope.getValue("factoryId"))
            .put("eventId", if (event.id > 0) "me-${event.id}" else "")
            .put("machineCode", machineCode)
            .put("machineNameSnapshot", event.machineNameSnapshot)
            .put("eventType", event.eventType)
            .put("startedAt", event.startedAt.toString())
            .put("reason", event.reason)
            .put("maintenanceType", event.maintenanceType)
        val response = CloudflareApiClient.post(appContext, "/api/machines/events/create", payload)
        val data = when (response) {
            is CloudResult.Success -> response.data
            is CloudResult.Error -> error(response.message)
        }
        return data.optString("eventId", "")
    }

    suspend fun closeMachineEvent(session: FactorySession, eventId: String, completionNotes: String, partsUsed: String, finalStatus: String) {
        val scope = scope(session)
        val payload = JSONObject()
            .put("organisationId", scope.getValue("organisationId"))
            .put("factoryId", scope.getValue("factoryId"))
            .put("eventId", eventId)
            .put("completionNotes", completionNotes)
            .put("partsUsed", partsUsed)
            .put("finalMachineStatus", finalStatus)
        val response = CloudflareApiClient.post(appContext, "/api/machines/events/close", payload)
        when (response) {
            is CloudResult.Success -> Unit
            is CloudResult.Error -> error(response.message)
        }
    }

    // Quality & Electricity
    suspend fun postQualityInspection(session: FactorySession, inspection: QualityInspectionEntity): String {
        val scope = scope(session)
        val payload = JSONObject()
            .put("organisationId", scope.getValue("organisationId"))
            .put("factoryId", scope.getValue("factoryId"))
            .put("inspectionId", if (inspection.id > 0) "qi-${inspection.id}" else "")
            .put("productionEntryId", inspection.productionEntryId?.toString())
            .put("productId", inspection.productId.toString())
            .put("productNameSnapshot", inspection.productNameSnapshot)
            .put("inspectionDate", inspection.inspectionDate)
            .put("inspectedBottles", inspection.inspectedBottles)
            .put("passedBottles", inspection.passedBottles)
            .put("failedBottles", inspection.failedBottles)
            .put("defectCategory", inspection.defectCategory)
            .put("severity", inspection.severity)
            .put("notes", inspection.notes)
        val response = CloudflareApiClient.post(appContext, "/api/quality/post", payload)
        val data = when (response) {
            is CloudResult.Success -> response.data
            is CloudResult.Error -> error(response.message)
        }
        return data.optString("inspectionId", "")
    }

    suspend fun postElectricityReading(session: FactorySession, reading: MeterReadingEntity): String {
        val scope = scope(session)
        val payload = JSONObject()
            .put("organisationId", scope.getValue("organisationId"))
            .put("factoryId", scope.getValue("factoryId"))
            .put("readingId", if (reading.id > 0) "el-${reading.id}" else "")
            .put("meterId", reading.meterId)
            .put("readingDate", reading.readingDate)
            .put("startReading", reading.startReading)
            .put("endReading", reading.endReading)
            .put("remarks", reading.remarks)
        val response = CloudflareApiClient.post(appContext, "/api/electricity/post", payload)
        val data = when (response) {
            is CloudResult.Success -> response.data
            is CloudResult.Error -> error(response.message)
        }
        return data.optString("readingId", "")
    }

    suspend fun queryCloudReport(session: FactorySession, fromDate: String, toDate: String): JSONObject {
        val scope = scope(session)
        val payload = JSONObject()
            .put("organisationId", scope.getValue("organisationId"))
            .put("factoryId", scope.getValue("factoryId"))
            .put("fromDate", fromDate)
            .put("toDate", toDate)
        val response = CloudflareApiClient.post(appContext, "/api/reports/query", payload)
        return when (response) {
            is CloudResult.Success -> response.data
            is CloudResult.Error -> error(response.message)
        }
    }

    private fun JSONObject.toMap(): Map<String, Any> {
        val out = linkedMapOf<String, Any>()
        val keys = keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = get(key)
            if (value != JSONObject.NULL) out[key] = value
        }
        return out
    }
}

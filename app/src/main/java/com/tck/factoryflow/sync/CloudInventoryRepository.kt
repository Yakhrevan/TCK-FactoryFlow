package com.tck.factoryflow.sync

import android.content.Context
import com.tck.factoryflow.auth.FactorySession
import com.tck.factoryflow.data.ProductEntity
import com.tck.factoryflow.data.ProductionEntryEntity
import com.tck.factoryflow.data.PackingEntryEntity
import com.tck.factoryflow.cloud.CloudflareApiClient
import com.tck.factoryflow.cloud.CloudResult
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
        return data.optBoolean("reversed", true)
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
        return data.optBoolean("reversed", true)
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

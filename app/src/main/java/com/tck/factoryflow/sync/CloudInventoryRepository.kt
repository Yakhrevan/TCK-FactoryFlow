package com.tck.factoryflow.sync

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.functions.FirebaseFunctions
import com.tck.factoryflow.auth.FactorySession
import com.tck.factoryflow.data.ProductEntity
import com.tck.factoryflow.data.ProductionEntryEntity
import com.tck.factoryflow.data.PackingEntryEntity
import kotlinx.coroutines.tasks.await
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

    private fun functions(): FirebaseFunctions {
        check(FirebaseApp.getApps(appContext).isNotEmpty()) { "Firebase is not configured on this installation." }
        return FirebaseFunctions.getInstance()
    }

    private fun scope(session: FactorySession): Map<String, Any> {
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
        functions().getHttpsCallable("upsertProduct").call(payload).await()
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
        val payload = JSONObject(canonical).put("idempotencyKey", key).toMap()
        functions().getHttpsCallable("postProduction").call(payload).await()
        return key
    }

    suspend fun postPacking(session: FactorySession, product: ProductEntity, entry: PackingEntryEntity): CloudPackingPostResult {
        val scope = scope(session)
        val canonical = JSONObject()
            .put("organisationId", scope.getValue("organisationId"))
            .put("factoryId", scope.getValue("factoryId"))
            .put("productId", product.productCode)
            .put("packingDate", entry.packingDate)
            .put("bottlesConsumed", entry.bottlesConsumed)
            .toString()
        val key = idempotencyKey("packing", canonical)
        val payload = JSONObject(canonical).put("idempotencyKey", key).toMap()
        val response = functions().getHttpsCallable("postPacking").call(payload).await()
        val result = response.data as? Map<*, *> ?: error("Server returned an invalid packing confirmation.")
        val packs = (result["completePacks"] as? Number)?.toInt() ?: error("Server did not return complete-pack quantity.")
        val loose = (result["looseBottles"] as? Number)?.toInt() ?: error("Server did not return loose-bottle quantity.")
        val packSize = (result["bottlesPerPack"] as? Number)?.toInt() ?: error("Server did not return the authoritative pack size.")
        require(packs >= 0 && loose >= 0 && packSize > 0) { "Server returned invalid packing quantities." }
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
        val result = functions().getHttpsCallable("getInventorySnapshot").call(scope).await()
        val data = result.data as? Map<*, *> ?: error("Server returned an invalid stock snapshot.")
        val items = (data["items"] as? List<*>)?.mapNotNull { raw ->
            val item = raw as? Map<*, *> ?: return@mapNotNull null
            CloudStockItem(
                productId = item["productId"] as? String ?: return@mapNotNull null,
                name = item["name"] as? String ?: "",
                sizeLabel = item["sizeLabel"] as? String ?: "",
                colour = item["colour"] as? String ?: "",
                bottlesPerPack = (item["bottlesPerPack"] as? Number)?.toInt() ?: 0,
                active = item["active"] as? Boolean ?: false,
                unpackedGoodBottles = (item["unpackedGoodBottles"] as? Number)?.toInt() ?: 0,
                completePacks = (item["completePacks"] as? Number)?.toInt() ?: 0,
                looseBottles = (item["looseBottles"] as? Number)?.toInt() ?: 0
            )
        }.orEmpty()
        return CloudStockSnapshot(
            organisationId = data["organisationId"] as? String ?: session.organisationId,
            factoryId = data["factoryId"] as? String ?: scope.getValue("factoryId"),
            serverTime = data["serverTime"] as? String ?: "",
            items = items
        )
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

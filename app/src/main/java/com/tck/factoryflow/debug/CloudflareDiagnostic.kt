package com.tck.factoryflow.debug

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await
import java.net.HttpURLConnection
import java.net.URL

object CloudflareDiagnostic {

    private const val WORKER_URL =
        "https://tck-factoryflow-api.tckfactoryflow.workers.dev"

    suspend fun testAuthentication(): String {
        val user = FirebaseAuth.getInstance().currentUser
            ?: return "FAILED: No Firebase user is currently signed in."

        val tokenResult = user.getIdToken(false).await()
        val token = tokenResult.token
            ?: return "FAILED: Firebase did not return an ID token."

        return try {
            val connection = URL("$WORKER_URL/api/auth/verify")
                .openConnection() as HttpURLConnection

            connection.requestMethod = "GET"
            connection.setRequestProperty(
                "Authorization",
                "Bearer $token"
            )
            connection.connectTimeout = 15000
            connection.readTimeout = 15000

            val status = connection.responseCode
            val response = (
                if (status in 200..299) connection.inputStream
                else connection.errorStream
            ).bufferedReader().use { it.readText() }

            connection.disconnect()

            "HTTP $status\n$response"
        } catch (e: Exception) {
            "FAILED: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    suspend fun testFirestore(): String {
        val user = FirebaseAuth.getInstance().currentUser
            ?: return "FAILED: No Firebase user is currently signed in."

        val tokenResult = user.getIdToken(false).await()
        val token = tokenResult.token
            ?: return "FAILED: Firebase did not return an ID token."

        return try {
            val connection = URL("$WORKER_URL/api/firestore/test")
                .openConnection() as HttpURLConnection

            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.connectTimeout = 15000
            connection.readTimeout = 15000

            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val response = stream.bufferedReader().use { it.readText() }
            connection.disconnect()

            "HTTP $status\n$response"
        } catch (e: Exception) {
            "FAILED: ${e.javaClass.simpleName}: ${e.message}"
        }
    }


    suspend fun testInventorySnapshot(): String {
        val user = FirebaseAuth.getInstance().currentUser
            ?: return "FAILED: No Firebase user is currently signed in."

        val token = user.getIdToken(false).await().token
            ?: return "FAILED: Firebase ID token unavailable."

        return try {
            val connection = URL("$WORKER_URL/api/inventory/snapshot")
                .openConnection() as HttpURLConnection

            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = 20000
            connection.readTimeout = 20000

            val body = """{"organisationId":"tck-factory-01","factoryId":"factory-01"}"""
            connection.outputStream.bufferedWriter().use { it.write(body) }

            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val response = stream.bufferedReader().use { it.readText() }
            connection.disconnect()

            "HTTP $status\n$response"
        } catch (e: Exception) {
            "FAILED: ${e.javaClass.simpleName}: ${e.message}"
        }
    }


    suspend fun testProductUpsert(): String {
        val user = FirebaseAuth.getInstance().currentUser
            ?: return "FAILED: No Firebase user is currently signed in."

        val token = user.getIdToken(false).await().token
            ?: return "FAILED: Firebase ID token unavailable."

        return try {
            val connection = URL("$WORKER_URL/api/products/upsert")
                .openConnection() as HttpURLConnection

            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = 20000
            connection.readTimeout = 20000

            val body = """
                {
                  "organisationId": "tck-factory-01",
                  "factoryId": "factory-01",
                  "productId": "TEST-001",
                  "name": "Cloudflare Test Product",
                  "sizeLabel": "Test Size",
                  "colour": "Test Colour",
                  "bottlesPerPack": 10,
                  "active": true
                }
            """.trimIndent()

            connection.outputStream.bufferedWriter().use { it.write(body) }

            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val response = stream.bufferedReader().use { it.readText() }
            connection.disconnect()

            "HTTP $status\n$response"
        } catch (e: Exception) {
            "FAILED: ${e.javaClass.simpleName}: ${e.message}"
        }
    }


    suspend fun testProductionPost(): String {
        val user = FirebaseAuth.getInstance().currentUser
            ?: return "FAILED: No Firebase user is currently signed in."

        val token = user.getIdToken(false).await().token
            ?: return "FAILED: Firebase ID token unavailable."

        return try {
            val connection = URL("$WORKER_URL/api/production/post")
                .openConnection() as HttpURLConnection

            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = 20000
            connection.readTimeout = 20000

            val key = "TEST-PRODUCTION-" + System.currentTimeMillis()

            val body = """
                {
                  "organisationId": "tck-factory-01",
                  "factoryId": "factory-01",
                  "idempotencyKey": "$key",
                  "productId": "TEST-001",
                  "productionDate": "2026-10-31",
                  "producedBottles": 10,
                  "goodBottles": 10,
                  "rejectedBottles": 0,
                  "machineId": "",
                  "lineId": "",
                  "machineName": "Test Machine",
                  "lineName": "Test Line",
                  "remarks": "Cloudflare Worker diagnostic test"
                }
            """.trimIndent()

            connection.outputStream.bufferedWriter().use { it.write(body) }

            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val response = stream.bufferedReader().use { it.readText() }
            connection.disconnect()

            "HTTP $status\nIdempotency key: $key\n$response"
        } catch (e: Exception) {
            "FAILED: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

}

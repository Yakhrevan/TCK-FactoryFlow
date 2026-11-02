package com.tck.factoryflow.cloud

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object CloudflareApiClient {

    private const val BASE_URL =
        "https://tck-factoryflow-api.tckfactoryflow.workers.dev"

    suspend fun request(
        context: Context,
        method: String,
        path: String,
        body: JSONObject? = null
    ): CloudResult<JSONObject> = withContext(Dispatchers.IO) {
        try {
            CloudConnection.requireOnlineIfWrite(context, method)

            val user = FirebaseAuth.getInstance().currentUser
                ?: return@withContext CloudResult.Error("Please login first.")

            var token = user.getIdToken(false).await().token
                ?: return@withContext CloudResult.Error("Firebase ID token unavailable.")

            fun executeHttp(authToken: String): Pair<Int, JSONObject> {
                val connection = URL("$BASE_URL$path").openConnection() as HttpURLConnection
                return try {
                    connection.requestMethod = method.uppercase()
                    connection.connectTimeout = 15000
                    connection.readTimeout = 20000
                    connection.setRequestProperty("Authorization", "Bearer $authToken")
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.setRequestProperty("Accept", "application/json")

                    if (body != null && method.uppercase() in setOf("POST", "PUT", "PATCH")) {
                        connection.doOutput = true
                        connection.outputStream.bufferedWriter().use {
                            it.write(body.toString())
                        }
                    }

                    val status = connection.responseCode
                    val stream = if (status in 200..299) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    }

                    val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    val json = if (text.isBlank()) JSONObject() else JSONObject(text)
                    Pair(status, json)
                } finally {
                    connection.disconnect()
                }
            }

            var (status, json) = executeHttp(token)
            if (status == 401) {
                val refreshed = runCatching { user.getIdToken(true).await().token }.getOrNull()
                if (refreshed != null && refreshed.isNotBlank()) {
                    token = refreshed
                    val retried = executeHttp(token)
                    status = retried.first
                    json = retried.second
                }
            }

            if (status in 200..299) {
                CloudResult.Success(json)
            } else {
                CloudResult.Error(
                    json.optString("error", "HTTP $status: Request failed.")
                )
            }
        } catch (e: Exception) {
            CloudResult.Error(
                e.localizedMessage ?: "Cloudflare API request failed.",
                e
            )
        }
    }

    suspend fun get(context: Context, path: String) =
        request(context, "GET", path)

    suspend fun post(context: Context, path: String, body: JSONObject) =
        request(context, "POST", path, body)

    suspend fun put(context: Context, path: String, body: JSONObject) =
        request(context, "PUT", path, body)

    suspend fun patch(context: Context, path: String, body: JSONObject) =
        request(context, "PATCH", path, body)

    suspend fun delete(context: Context, path: String) =
        request(context, "DELETE", path)
}

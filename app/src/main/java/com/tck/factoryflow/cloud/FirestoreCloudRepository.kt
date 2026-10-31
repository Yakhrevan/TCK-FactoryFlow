package com.tck.factoryflow.cloud

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

/**
 * Cloud-first repository.
 *
 * Reads:
 *  - Firestore cache is permitted when offline.
 *
 * Writes:
 *  - Require validated internet.
 *  - Never write to local Room.
 *  - Never queue offline writes.
 */
class FirestoreCloudRepository(
    private val context: Context,
    private val firestore: FirebaseFirestore =
        FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth =
        FirebaseAuth.getInstance()
) {

    private fun currentUid(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("Please login first.")
    }

    private fun requireInternet() {
        CloudConnection.requireOnline(context)
    }

    /**
     * Read data.
     * Firestore may return previously cached data while offline.
     */
    suspend fun readCollection(
        collectionPath: String
    ): CloudResult<List<Map<String, Any?>>> {
        return try {
            val snapshot = firestore
                .collection(collectionPath)
                .get()
                .await()

            CloudResult.Success(
                snapshot.documents.mapNotNull { document ->
                    document.data?.plus("documentId" to document.id)
                }
            )
        } catch (e: Exception) {
            CloudResult.Error(
                e.localizedMessage ?: "Unable to load cloud data.",
                e
            )
        }
    }

    /**
     * Create a document online only.
     */
    suspend fun createDocument(
        collectionPath: String,
        data: Map<String, Any?>
    ): CloudResult<String> {
        return try {
            requireInternet()

            val uid = currentUid()
            val payload = data.toMutableMap()
            payload["createdBy"] = uid
            payload["createdAt"] =
                com.google.firebase.firestore.FieldValue.serverTimestamp()

            val reference = firestore
                .collection(collectionPath)
                .add(payload)
                .await()

            CloudResult.Success(reference.id)
        } catch (e: Exception) {
            CloudResult.Error(
                e.localizedMessage ?: "Cloud create failed.",
                e
            )
        }
    }

    /**
     * Update a document online only.
     */
    suspend fun updateDocument(
        collectionPath: String,
        documentId: String,
        data: Map<String, Any?>
    ): CloudResult<Unit> {
        return try {
            requireInternet()
            currentUid()

            firestore
                .collection(collectionPath)
                .document(documentId)
                .set(data, SetOptions.merge())
                .await()

            CloudResult.Success(Unit)
        } catch (e: Exception) {
            CloudResult.Error(
                e.localizedMessage ?: "Cloud update failed.",
                e
            )
        }
    }

    /**
     * Delete a document online only.
     */
    suspend fun deleteDocument(
        collectionPath: String,
        documentId: String
    ): CloudResult<Unit> {
        return try {
            requireInternet()
            currentUid()

            firestore
                .collection(collectionPath)
                .document(documentId)
                .delete()
                .await()

            CloudResult.Success(Unit)
        } catch (e: Exception) {
            CloudResult.Error(
                e.localizedMessage ?: "Cloud delete failed.",
                e
            )
        }
    }
}

package com.tck.factoryflow.cloud

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

/**
 * Cloud-only Firestore repository.
 *
 * READ:
 * Firestore may return previously cached data while offline.
 *
 * WRITE:
 * Internet connection and authenticated user required.
 * Offline writes are rejected and never queued.
 */
class CloudFirestoreRepository(
    private val context: Context,
    private val firestore: FirebaseFirestore =
        FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth =
        FirebaseAuth.getInstance()
) {

    companion object {
        const val DEFAULT_ORGANIZATION_ID = "tck-factory-01"
    }

    private fun requireAuthenticatedUser(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException(
                "Please login before accessing factory data."
            )
    }

    private fun requireCloudConnection() {
        CloudConnection.requireOnline(context)
        requireAuthenticatedUser()
    }

    private fun collectionPath(
        organizationId: String,
        collection: String
    ): String {
        require(organizationId.isNotBlank()) {
            "Organization ID cannot be empty."
        }
        require(collection.isNotBlank()) {
            "Collection name cannot be empty."
        }

        return "organisations/$organizationId/$collection"
    }

    fun document(
        organizationId: String = DEFAULT_ORGANIZATION_ID,
        collection: String,
        documentId: String
    ): DocumentReference {
        require(documentId.isNotBlank()) {
            "Document ID cannot be empty."
        }

        return firestore
            .collection(collectionPath(organizationId, collection))
            .document(documentId)
    }

    /**
     * Reads may use Firestore's local cache when offline.
     */
    suspend fun getDocument(
        organizationId: String = DEFAULT_ORGANIZATION_ID,
        collection: String,
        documentId: String
    ): DocumentSnapshot {
        requireAuthenticatedUser()

        return document(
            organizationId,
            collection,
            documentId
        ).get().await()
    }

    /**
     * Creates or replaces a cloud document.
     * Offline operation is rejected before calling Firestore.
     */
    suspend fun setDocument(
        organizationId: String = DEFAULT_ORGANIZATION_ID,
        collection: String,
        documentId: String,
        data: Map<String, Any>
    ) {
        requireCloudConnection()

        document(
            organizationId,
            collection,
            documentId
        ).set(data).await()
    }

    /**
     * Updates existing cloud document fields.
     */
    suspend fun updateDocument(
        organizationId: String = DEFAULT_ORGANIZATION_ID,
        collection: String,
        documentId: String,
        fields: Map<String, Any>
    ) {
        requireCloudConnection()

        document(
            organizationId,
            collection,
            documentId
        ).update(fields).await()
    }

    /**
     * Merges data into a cloud document.
     */
    suspend fun mergeDocument(
        organizationId: String = DEFAULT_ORGANIZATION_ID,
        collection: String,
        documentId: String,
        data: Map<String, Any>
    ) {
        requireCloudConnection()

        document(
            organizationId,
            collection,
            documentId
        ).set(data, SetOptions.merge()).await()
    }

    /**
     * Deletes a cloud document.
     */
    suspend fun deleteDocument(
        organizationId: String = DEFAULT_ORGANIZATION_ID,
        collection: String,
        documentId: String
    ) {
        requireCloudConnection()

        document(
            organizationId,
            collection,
            documentId
        ).delete().await()
    }
}

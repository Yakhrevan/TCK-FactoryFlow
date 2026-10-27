package com.tck.factoryflow.auth

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

data class FactorySession(
    val uid: String,
    val email: String?,
    val organisationId: String,
    val role: String,
    val name: String = "",
    val allowedModules: String = "ALL",
    val isCloud: Boolean = false
)

private data class MemberProfile(
    val org: String,
    val role: String,
    val name: String,
    val allowedModules: String,
    val active: Boolean
)

class FactoryAuthRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("tck_auth_prefs", Context.MODE_PRIVATE)

    fun hasFirebase(): Boolean = runCatching {
        FirebaseApp.getApps(context.applicationContext).isNotEmpty()
    }.getOrDefault(false)

    private fun getAuth(): FirebaseAuth? = if (hasFirebase()) {
        runCatching { FirebaseAuth.getInstance() }.getOrNull()
    } else null

    fun inferRoleFromEmail(email: String?): String {
        if (email.isNullOrBlank()) return "admin"
        val lower = email.lowercase()
        return when {
            lower.contains("owner") -> "owner"
            lower.contains("admin") -> "admin"
            lower.contains("manager") -> "manager"
            lower.contains("prod") || lower.contains("operator") -> "production_staff"
            lower.contains("pack") -> "packing_staff"
            lower.contains("store") || lower.contains("inventory") -> "storekeeper"
            lower.contains("dispatch") -> "dispatch_staff"
            lower.contains("view") || lower.contains("audit") -> "viewer"
            else -> "admin"
        }
    }

    private suspend fun resolveRoleAndOrg(userUid: String, tokenClaims: Map<String, Any?>?, email: String?): MemberProfile {
        var org = tokenClaims?.get("organisationId") as? String
        var role = tokenClaims?.get("role") as? String
        var name = (tokenClaims?.get("name") as? String).orEmpty()
        var allowedModules = (tokenClaims?.get("allowedModules") as? String) ?: "ALL"
        var active = true

        if (hasFirebase()) {
            val memberDoc = runCatching {
                FirebaseFirestore.getInstance()
                    .collection("organisations")
                    .document(org ?: "tck-factory-01")
                    .collection("members")
                    .document(userUid)
                    .get()
                    .await()
            }.getOrNull()

            if (memberDoc != null && memberDoc.exists()) {
                val docRole = memberDoc.getString("role")
                val docName = memberDoc.getString("name")
                val docModules = memberDoc.getString("allowedModules")
                val docActive = memberDoc.getBoolean("active")
                if (!docRole.isNullOrBlank()) role = docRole
                if (!docName.isNullOrBlank()) name = docName
                if (!docModules.isNullOrBlank()) allowedModules = docModules
                if (docActive != null) active = docActive
            }
        }

        // Local Room fallback check for staff profile
        if (role.isNullOrBlank() || name.isBlank()) {
            val localStaff = runCatching {
                com.tck.factoryflow.data.FactoryDatabase.get(context).factoryDao().staffByEmail(email.orEmpty())
            }.getOrNull()
            if (localStaff != null) {
                if (role.isNullOrBlank()) role = localStaff.role
                if (name.isBlank()) name = localStaff.name
                if (allowedModules == "ALL") allowedModules = localStaff.allowedModules
                active = localStaff.active
            }
        }

        if (role.isNullOrBlank()) {
            role = inferRoleFromEmail(email)
        }

        return MemberProfile(org ?: "tck-factory-01", role, name, allowedModules, active)
    }

    suspend fun signIn(email: String, password: String): FactorySession {
        require(email.isNotBlank() && password.isNotBlank()) { "Email and password are required." }
        val auth = getAuth() ?: error("Cloud authentication server is not reachable. Please connect to internet or use Local Role mode.")
        val result = auth.signInWithEmailAndPassword(email.trim(), password).await()
        val user = result.user ?: error("Authentication returned no user.")
        val token = user.getIdToken(true).await()
        val profile = resolveRoleAndOrg(user.uid, token.claims, user.email)
        if (!profile.active) {
            auth.signOut()
            error("This staff account has been deactivated. Please contact the administrator.")
        }
        val session = FactorySession(user.uid, user.email, profile.org, profile.role, profile.name, profile.allowedModules, isCloud = true)
        saveLocalSession(session)
        return session
    }

    suspend fun createStaffAccount(
        name: String,
        email: String,
        password: String,
        role: String,
        allowedModules: String,
        creatorUid: String
    ): com.tck.factoryflow.data.StaffMemberEntity {
        require(name.isNotBlank()) { "Staff name cannot be empty." }
        require(email.isNotBlank() && email.contains("@")) { "Valid email address is required." }
        require(password.length >= 6) { "Password must be at least 6 characters." }

        var generatedUid = "staff_${System.currentTimeMillis()}"

        // Register in Firebase Auth using a secondary FirebaseApp instance to prevent signing out active admin
        if (hasFirebase()) {
            runCatching {
                val primaryApp = FirebaseApp.getInstance()
                val secondaryApp = runCatching { FirebaseApp.getInstance("StaffAuthCreator") }.getOrElse {
                    FirebaseApp.initializeApp(context.applicationContext, primaryApp.options, "StaffAuthCreator")
                }
                val secAuth = FirebaseAuth.getInstance(secondaryApp)
                val authResult = secAuth.createUserWithEmailAndPassword(email.trim(), password).await()
                authResult.user?.uid?.let { generatedUid = it }
                secAuth.signOut()
            }
        }

        val staff = com.tck.factoryflow.data.StaffMemberEntity(
            uid = generatedUid,
            name = name.trim(),
            email = email.trim(),
            role = role.lowercase().trim(),
            allowedModules = allowedModules,
            active = true
        )

        val db = com.tck.factoryflow.data.FactoryDatabase.get(context)
        db.factoryDao().insertStaffMember(staff)

        if (hasFirebase()) {
            runCatching {
                val memberMap = mapOf(
                    "uid" to staff.uid,
                    "name" to staff.name,
                    "email" to staff.email,
                    "role" to staff.role,
                    "allowedModules" to staff.allowedModules,
                    "active" to true,
                    "organisationId" to "tck-factory-01",
                    "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                    "createdBy" to creatorUid
                )
                FirebaseFirestore.getInstance()
                    .collection("organisations")
                    .document("tck-factory-01")
                    .collection("members")
                    .document(staff.uid)
                    .set(memberMap)
                    .await()
            }
        }

        db.factoryDao().enqueueSyncEvent(
            com.tck.factoryflow.data.SyncOutboxEntity(
                eventKey = "staff-create:${staff.uid}",
                organisationId = "tck-factory-01",
                eventType = "STAFF_CREATED",
                payloadJson = org.json.JSONObject()
                    .put("uid", staff.uid)
                    .put("name", staff.name)
                    .put("email", staff.email)
                    .put("role", staff.role)
                    .put("allowedModules", staff.allowedModules)
                    .put("active", true)
                    .toString()
            )
        )

        return staff
    }

    suspend fun updateStaffAccount(
        id: Long,
        uid: String,
        name: String,
        role: String,
        allowedModules: String,
        active: Boolean
    ) {
        val db = com.tck.factoryflow.data.FactoryDatabase.get(context)
        db.factoryDao().updateStaffMember(id, name.trim(), role.lowercase().trim(), allowedModules, active)

        if (hasFirebase() && uid.isNotBlank()) {
            runCatching {
                val memberMap = mapOf(
                    "name" to name.trim(),
                    "role" to role.lowercase().trim(),
                    "allowedModules" to allowedModules,
                    "active" to active,
                    "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                )
                FirebaseFirestore.getInstance()
                    .collection("organisations")
                    .document("tck-factory-01")
                    .collection("members")
                    .document(uid)
                    .update(memberMap)
                    .await()
            }
        }

        db.factoryDao().enqueueSyncEvent(
            com.tck.factoryflow.data.SyncOutboxEntity(
                eventKey = "staff-update:$uid:${System.currentTimeMillis()}",
                organisationId = "tck-factory-01",
                eventType = "STAFF_UPDATED",
                payloadJson = org.json.JSONObject()
                    .put("uid", uid)
                    .put("name", name)
                    .put("role", role)
                    .put("allowedModules", allowedModules)
                    .put("active", active)
                    .toString()
            )
        )
    }

    fun setLocalRole(role: String, staffName: String = "Administrator"): FactorySession {
        val cleanRole = role.lowercase().trim()
        val session = FactorySession(
            uid = "local_${cleanRole}_${System.currentTimeMillis() % 10000}",
            email = if (cleanRole == "admin" || cleanRole == "owner") "admin@tck-factoryflow.com" else "$cleanRole@tck-factoryflow.com",
            organisationId = "tck-factory-01",
            role = cleanRole,
            name = staffName,
            allowedModules = "ALL",
            isCloud = false
        )
        saveLocalSession(session)
        return session
    }

    fun saveLocalSession(session: FactorySession) {
        prefs.edit()
            .putString("uid", session.uid)
            .putString("email", session.email)
            .putString("org", session.organisationId)
            .putString("role", session.role)
            .putString("name", session.name)
            .putString("allowedModules", session.allowedModules)
            .putBoolean("isCloud", session.isCloud)
            .apply()
    }

    suspend fun currentSession(): FactorySession? {
        val auth = getAuth()
        if (auth?.currentUser != null) {
            val user = auth.currentUser!!
            val token = runCatching { user.getIdToken(false).await() }.getOrNull()
            val profile = resolveRoleAndOrg(user.uid, token?.claims, user.email)
            if (!profile.active) {
                signOut()
                return null
            }
            return FactorySession(user.uid, user.email, profile.org, profile.role, profile.name, profile.allowedModules, isCloud = true)
        }
        val savedRole = prefs.getString("role", null) ?: return null
        val uid = prefs.getString("uid", "local_$savedRole") ?: "local_$savedRole"
        val email = prefs.getString("email", "$savedRole@tck-factoryflow.com")
        val org = prefs.getString("org", "tck-factory-01") ?: "tck-factory-01"
        val name = prefs.getString("name", "") ?: ""
        val allowedModules = prefs.getString("allowedModules", "ALL") ?: "ALL"
        val isCloud = prefs.getBoolean("isCloud", false)
        return FactorySession(uid, email, org, savedRole, name, allowedModules, isCloud)
    }

    fun signOut() {
        runCatching { getAuth()?.signOut() }
        prefs.edit().clear().apply()
    }
}


package com.tck.factoryflow.auth

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.tck.factoryflow.cloud.CloudResult
import com.tck.factoryflow.cloud.CloudflareApiClient
import org.json.JSONObject
import kotlinx.coroutines.tasks.await

data class FactorySession(
    val uid: String,
    val email: String?,
    val organisationId: String,
    val role: String,
    val name: String = "",
    val allowedModules: String = "ALL",
    val isCloud: Boolean = false,
    val factoryIds: List<String> = listOf("factory-01")
)

private data class MemberProfile(
    val org: String,
    val role: String,
    val name: String,
    val allowedModules: String,
    val active: Boolean,
    val factoryIds: List<String>
)

class FactoryAuthRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("tck_auth_prefs", Context.MODE_PRIVATE)

    fun hasFirebase(): Boolean = runCatching {
        FirebaseApp.getApps(context.applicationContext).isNotEmpty()
    }.getOrDefault(false)

    private fun getAuth(): FirebaseAuth? = if (hasFirebase()) {
        runCatching { FirebaseAuth.getInstance() }.getOrNull()
    } else null

    private suspend fun resolveRoleAndOrg(
        userUid: String,
        tokenClaims: Map<String, Any?>?,
        email: String?
    ): MemberProfile {
        val org = (tokenClaims?.get("organisationId") as? String)
            ?.takeIf { it.isNotBlank() } ?: "tck-factory-01"

        if (!hasFirebase()) {
            error("Cloud authentication is unavailable. Check your internet connection.")
        }

        fun memberRequest() = JSONObject().put("organisationId", org)

        var response = CloudflareApiClient.post(
            context, "/api/auth/member", memberRequest()
        )

        if (response is CloudResult.Error) {
            val cleanEmail = email?.lowercase()?.trim().orEmpty()
            val eligibleAdmin = cleanEmail == "admin@tckfactory.com" ||
                cleanEmail.startsWith("admin@") ||
                tokenClaims?.get("role") == "admin"

            if (eligibleAdmin) {
                val bootstrap = CloudflareApiClient.post(
                    context,
                    "/api/auth/bootstrap-admin",
                    JSONObject()
                        .put("organisationId", org)
                        .put("factoryId", "factory-01")
                        .put("name", "Administrator")
                )

                if (bootstrap is CloudResult.Success) {
                    response = CloudflareApiClient.post(
                        context, "/api/auth/member", memberRequest()
                    )
                }
            }
        }

        val json = when (response) {
            is CloudResult.Success -> response.data
            is CloudResult.Error -> error(
                "This account ($email) has no provisioned factory membership. " +
                    "Contact the administrator. Details: ${response.message}"
            )
        }

        val member = json.optJSONObject("member")
            ?: error("Worker returned an invalid member profile.")

        val docOrg = member.optString("organisationId")
        val role = member.optString("role").lowercase().trim()
        val active = member.optBoolean("active", false)

        val factoryArray = member.optJSONArray("factoryIds")
        val docFactoryIds = buildList {
            if (factoryArray != null) {
                for (i in 0 until factoryArray.length()) {
                    factoryArray.optString(i).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }.distinct()

        val factoryIds = if (
            docFactoryIds.isEmpty() && (role == "admin" || role == "owner")
        ) listOf("factory-01") else docFactoryIds

        if (factoryIds.isEmpty()) {
            error("Factory membership has no assigned factory IDs. Ask the administrator to configure factoryIds.")
        }

        if (docOrg != org || role.isNullOrBlank()) {
            error("Factory membership is incomplete or belongs to another organisation.")
        }

        val allowedRoles = setOf(
            "owner", "admin", "manager", "production_staff", "packing_staff",
            "storekeeper", "dispatch_staff", "viewer", "operator",
            "production", "packing", "dispatch", "production_operator"
        )

        if (role !in allowedRoles) {
            error("Factory membership contains an unsupported role.")
        }

        return MemberProfile(
            org = org,
            role = role,
            name = member.optString("name").takeIf { it.isNotBlank() }
                ?: (tokenClaims?.get("name") as? String).orEmpty(),
            allowedModules = member.optString("allowedModules")
                .takeIf { it.isNotBlank() }
                ?: (tokenClaims?.get("allowedModules") as? String ?: "ALL"),
            active = active,
            factoryIds = factoryIds
        )
    }

    suspend fun signIn(email: String, password: String): FactorySession {
        require(email.isNotBlank() && password.isNotBlank()) { "Email and password are required." }
        val auth = getAuth() ?: error("Cloud authentication server is not reachable. Active internet connection required.")
        val result = auth.signInWithEmailAndPassword(email.trim(), password).await()
        val user = result.user ?: error("Authentication returned no user.")
        val token = user.getIdToken(true).await()
        val profile = resolveRoleAndOrg(user.uid, token.claims, user.email)
        if (!profile.active) {
            auth.signOut()
            error("This staff account has been deactivated. Please contact the administrator.")
        }
        val session = FactorySession(user.uid, user.email, profile.org, profile.role, profile.name, profile.allowedModules, isCloud = true, factoryIds = profile.factoryIds)
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

        // Register using a secondary Firebase app so the active administrator remains signed in.
        if (hasFirebase()) {
            val primaryApp = FirebaseApp.getInstance()
            val secondaryApp = runCatching { FirebaseApp.getInstance("StaffAuthCreator") }.getOrElse {
                FirebaseApp.initializeApp(context.applicationContext, primaryApp.options, "StaffAuthCreator")
            }
            val secAuth = FirebaseAuth.getInstance(secondaryApp)
            try {
                val authResult = secAuth.createUserWithEmailAndPassword(email.trim(), password).await()
                generatedUid = authResult.user?.uid ?: error("Firebase did not return the new staff UID.")
            } finally {
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
        if (hasFirebase()) {
            val provisionResult = CloudflareApiClient.post(
                context,
                "/api/auth/provision-member",
                JSONObject()
                    .put("organisationId", "tck-factory-01")
                    .put("factoryId", "factory-01")
                    .put("uid", staff.uid)
                    .put("name", staff.name)
                    .put("email", staff.email)
                    .put("role", staff.role)
                    .put("allowedModules", staff.allowedModules)
                    .put("active", true)
                    .put("createdBy", creatorUid)
            )
            if (provisionResult is CloudResult.Error) {
                error("Staff membership provisioning failed: ${provisionResult.message}")
            }
        }

        db.factoryDao().insertStaffMember(staff)
        PasswordSecurity.saveCredential(context, staff.email, password)
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
        val staffEmail = db.factoryDao().staffById(id)?.email
            ?: error("Staff record is missing its email address.")

        if (hasFirebase() && uid.isNotBlank()) {
            val provisionResult = CloudflareApiClient.post(
                context,
                "/api/auth/provision-member",
                JSONObject()
                    .put("organisationId", "tck-factory-01")
                    .put("factoryId", "factory-01")
                    .put("uid", uid)
                    .put("name", name.trim())
                    .put("email", staffEmail)
                    .put("role", role.lowercase().trim())
                    .put("allowedModules", allowedModules)
                    .put("active", active)
            )
            if (provisionResult is CloudResult.Error) {
                error("Staff membership update failed: ${provisionResult.message}")
            }
        }

        db.factoryDao().updateStaffMember(id, name.trim(), role.lowercase().trim(), allowedModules, active)
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
            email = if (cleanRole == "admin" || cleanRole == "owner") PasswordSecurity.ADMIN_EMAIL else "$cleanRole@tckfactory.com",
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
            .putString("factoryIds", session.factoryIds.joinToString(","))
            .apply()
    }

    suspend fun currentSession(): FactorySession? {
        val auth = getAuth()
        if (auth?.currentUser != null) {
            val user = auth.currentUser!!
            val onlineProfile = runCatching {
                val token = user.getIdToken(false).await()
                resolveRoleAndOrg(user.uid, token?.claims, user.email)
            }.getOrNull()

            if (onlineProfile != null) {
                if (!onlineProfile.active) {
                    signOut()
                    return null
                }
                val session = FactorySession(
                    uid = user.uid,
                    email = user.email,
                    organisationId = onlineProfile.org,
                    role = onlineProfile.role,
                    name = onlineProfile.name,
                    allowedModules = onlineProfile.allowedModules,
                    isCloud = true,
                    factoryIds = onlineProfile.factoryIds
                )
                saveLocalSession(session)
                return session
            }
            // If online profile could not be refreshed immediately, verify cached session was cloud authenticated
        }
        val isCloud = prefs.getBoolean("isCloud", false)
        if (!isCloud) {
            // In 100% Online mode, mock/local sessions are not permitted.
            return null
        }
        val savedRole = prefs.getString("role", null) ?: return null
        val uid = prefs.getString("uid", null) ?: return null
        val email = prefs.getString("email", null) ?: return null
        val org = prefs.getString("org", "tck-factory-01") ?: "tck-factory-01"
        val name = prefs.getString("name", "") ?: ""
        val allowedModules = prefs.getString("allowedModules", "ALL") ?: "ALL"
        val factoryIds = prefs.getString("factoryIds", "factory-01")?.split(",")?.filter { it.isNotBlank() } ?: listOf("factory-01")
        return FactorySession(uid, email, org, savedRole, name, allowedModules, isCloud = true, factoryIds = factoryIds)
    }

    fun signOut() {
        runCatching { getAuth()?.signOut() }
        prefs.edit().clear().apply()
    }
}


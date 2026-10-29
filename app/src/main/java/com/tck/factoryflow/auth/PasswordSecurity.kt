package com.tck.factoryflow.auth

import android.content.Context
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Enterprise-grade offline password hashing and verification using PBKDF2 with HMAC-SHA256.
 * Secures offline factory login against brute-force attacks and unauthorized elevation.
 */
object PasswordSecurity {
    private const val PREFS_NAME = "tck_offline_credentials"
    const val ADMIN_EMAIL = "admin@tckfactory.com"
    const val DEFAULT_ADMIN_PASS = "admin123"
    private const val ITERATIONS = 10000
    private const val KEY_LENGTH = 256

    fun generateSalt(): String {
        val random = SecureRandom()
        val saltBytes = ByteArray(16)
        random.nextBytes(saltBytes)
        return saltBytes.joinToString("") { "%02x".format(it) }
    }

    fun hashPassword(password: String, salt: String): String {
        return runCatching {
            val spec = PBEKeySpec(password.toCharArray(), salt.toByteArray(Charsets.UTF_8), ITERATIONS, KEY_LENGTH)
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val hash = factory.generateSecret(spec).encoded
            hash.joinToString("") { "%02x".format(it) }
        }.getOrElse {
            // Fallback to SHA-256 with stretching in case PBKDF2 algorithm is unavailable
            var digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest("$salt:$password:tck-factoryflow-secure-token".toByteArray(Charsets.UTF_8))
            for (i in 0 until 1000) {
                digest = java.security.MessageDigest.getInstance("SHA-256").digest(digest)
            }
            digest.joinToString("") { "%02x".format(it) }
        }
    }

    fun saveCredential(context: Context, email: String, password: String) {
        val cleanEmail = email.lowercase().trim()
        val salt = generateSalt()
        val hash = hashPassword(password, salt)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString("hash_$cleanEmail", hash)
            .putString("salt_$cleanEmail", salt)
            .apply()
    }

    fun initializeDefaultAdminIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains("hash_$ADMIN_EMAIL")) {
            saveCredential(context, ADMIN_EMAIL, DEFAULT_ADMIN_PASS)
        }
    }

    fun verifyPassword(context: Context, email: String, password: String): Boolean {
        initializeDefaultAdminIfNeeded(context)
        val cleanEmail = email.lowercase().trim()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedHash = prefs.getString("hash_$cleanEmail", null) ?: return false
        val savedSalt = prefs.getString("salt_$cleanEmail", null) ?: return false
        val computedHash = hashPassword(password, savedSalt)
        return savedHash == computedHash
    }

    fun verifyPasswordDirect(password: String, salt: String, expectedHash: String): Boolean {
        return hashPassword(password, salt) == expectedHash
    }

    fun updatePassword(context: Context, email: String, newPassword: String) {
        saveCredential(context, email, newPassword)
    }
}

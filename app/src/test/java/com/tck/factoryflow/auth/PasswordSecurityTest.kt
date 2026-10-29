package com.tck.factoryflow.auth

import org.junit.Assert.*
import org.junit.Test

class PasswordSecurityTest {
    @Test
    fun saltIsNonEmptyAndUnique() {
        val salt1 = PasswordSecurity.generateSalt()
        val salt2 = PasswordSecurity.generateSalt()
        assertEquals(32, salt1.length)
        assertEquals(32, salt2.length)
        assertNotEquals(salt1, salt2)
    }

    @Test
    fun passwordHashingIsDeterministic() {
        val salt = PasswordSecurity.generateSalt()
        val hash1 = PasswordSecurity.hashPassword("secret123", salt)
        val hash2 = PasswordSecurity.hashPassword("secret123", salt)
        assertEquals(hash1, hash2)
    }

    @Test
    fun differentPasswordsProduceDifferentHashes() {
        val salt = PasswordSecurity.generateSalt()
        val hash1 = PasswordSecurity.hashPassword("passwordA", salt)
        val hash2 = PasswordSecurity.hashPassword("passwordB", salt)
        assertNotEquals(hash1, hash2)
    }

    @Test
    fun differentSaltsProduceDifferentHashesForSamePassword() {
        val salt1 = PasswordSecurity.generateSalt()
        val salt2 = PasswordSecurity.generateSalt()
        val hash1 = PasswordSecurity.hashPassword("samePassword", salt1)
        val hash2 = PasswordSecurity.hashPassword("samePassword", salt2)
        assertNotEquals(hash1, hash2)
    }

    @Test
    fun verifyPasswordDirectValidatesAccurately() {
        val salt = PasswordSecurity.generateSalt()
        val hash = PasswordSecurity.hashPassword("correctPassword", salt)
        assertTrue(PasswordSecurity.verifyPasswordDirect("correctPassword", salt, hash))
        assertFalse(PasswordSecurity.verifyPasswordDirect("wrongPassword", salt, hash))
        assertFalse(PasswordSecurity.verifyPasswordDirect("", salt, hash))
    }
}

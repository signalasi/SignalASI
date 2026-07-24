package com.signalasi.chat

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AgentEncryptedStorageInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val storageNames = mutableListOf<String>()

    @After
    fun cleanUp() {
        storageNames.forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            context.deleteDatabase("$name.db")
        }
    }

    @Test
    fun encryptedPreferencesRejectPlaintextValues() {
        val name = newStorageName()
        context.getSharedPreferences(name, Context.MODE_PRIVATE)
            .edit()
            .putString("value", "obsolete plaintext")
            .commit()

        assertEquals("default", AgentEncryptedPreferences(context, name).readString("value", "default"))
    }

    @Test
    fun encryptedDatabaseDoesNotImportSameNamedPreferences() {
        val name = newStorageName()
        AgentEncryptedPreferences(context, name).writeString("value", "obsolete preference value")
        val database = AgentEncryptedDatabase(context, name)

        assertEquals("default", database.readString("value", "default"))
        assertFalse(database.contains("value"))
        database.close()
    }

    @Test
    fun currentEncryptedStoresRoundTripValues() {
        val preferencesName = newStorageName()
        val preferences = AgentEncryptedPreferences(context, preferencesName)
        preferences.writeString("value", "current preference value")
        assertEquals("current preference value", preferences.readString("value", "default"))

        val databaseName = newStorageName()
        val database = AgentEncryptedDatabase(context, databaseName)
        database.writeString("value", "current database value")
        assertTrue(database.contains("value"))
        assertEquals("current database value", database.readString("value", "default"))
        database.close()
    }

    private fun newStorageName(): String =
        "signalasi_current_storage_test_${UUID.randomUUID()}".also(storageNames::add)
}

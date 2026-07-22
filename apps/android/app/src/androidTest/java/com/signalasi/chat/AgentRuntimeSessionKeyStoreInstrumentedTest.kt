package com.signalasi.chat

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentRuntimeSessionKeyStoreInstrumentedTest {
    @Test
    fun repeatedStoresReusePersistedKeyWithoutSharingMutableArrays() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val firstStore = AgentRuntimeSessionKeyStore(context)
        val first = firstStore.getOrCreate()
        val cached = firstStore.getOrCreate()
        val reopened = AgentRuntimeSessionKeyStore(context).getOrCreate()

        assertArrayEquals(first, cached)
        assertArrayEquals(first, reopened)
        assertNotSame(first, cached)
        assertNotSame(first, reopened)

        first.fill(0)
        assertArrayEquals(reopened, firstStore.getOrCreate())
    }
}

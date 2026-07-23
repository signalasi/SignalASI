package com.signalasi.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlCenterPageRenderCacheTest {
    private val page = ControlCenterPageSpec(
        sections = listOf(
            ControlCenterSectionSpec(
                title = "Core",
                rows = listOf(
                    ControlCenterRowSpec(
                        actionId = "core",
                        title = "Agent core",
                        subtitle = "Ready",
                        iconRes = 1
                    )
                )
            )
        )
    )

    @Test
    fun `unchanged populated page skips redundant render`() {
        val cache = ControlCenterPageRenderCache()

        assertTrue(cache.shouldRender(page, hasContent = false))
        assertFalse(cache.shouldRender(page, hasContent = true))
    }

    @Test
    fun `changed or cleared page renders again`() {
        val cache = ControlCenterPageRenderCache()
        cache.shouldRender(page, hasContent = false)

        assertTrue(
            cache.shouldRender(
                page.copy(footer = "Updated"),
                hasContent = true
            )
        )
        assertTrue(cache.shouldRender(page, hasContent = false))
    }

    @Test
    fun `fresh visible home skips expensive state rebuild`() {
        val policy = ControlCenterHomeRefreshPolicy(maxAgeMillis = 30_000L)
        assertTrue(policy.shouldRefresh(visible = true, nowMillis = 1_000L))
        policy.markRendered(1_000L)

        assertFalse(policy.shouldRefresh(visible = true, nowMillis = 20_000L))
        assertTrue(policy.shouldRefresh(visible = true, nowMillis = 31_000L))
    }

    @Test
    fun `hidden or invalidated home refreshes on next visit`() {
        val policy = ControlCenterHomeRefreshPolicy(maxAgeMillis = 30_000L)
        policy.markRendered(1_000L)

        assertFalse(policy.shouldRefresh(visible = false, nowMillis = 2_000L))
        assertTrue(policy.shouldRefresh(visible = true, nowMillis = 2_001L))
        policy.markRendered(2_001L)
        policy.invalidate()
        assertTrue(policy.shouldRefresh(visible = true, nowMillis = 2_002L))
    }
}

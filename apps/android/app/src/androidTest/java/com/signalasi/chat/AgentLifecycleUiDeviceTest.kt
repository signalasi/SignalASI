package com.signalasi.chat

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentLifecycleUiDeviceTest {
    @Test
    fun rendersEveryDurableTeamLifecycleAcrossCompactAndLargeViewports() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = EncryptedAgentTeamExecutionStore(context)
        val viewport = requestedViewport()
        var activity: MainActivity? = null
        cleanupFixtures(store)
        try {
            // Initialize the production singleton before seeding. This preserves genuine
            // startup recovery behavior without rewriting queued/running acceptance states.
            GlobalSuperAgentRuntime.get(context)
            seedFixtures(store)
            val snapshots = fixtureSnapshots(store)
            assertEquals(EXPECTED_STATES, snapshots.mapTo(linkedSetOf(), AgentTeamExecutionSnapshot::state))

            activity = openRecentTasks(instrumentation)
            val viewportReport = verifyViewport(instrumentation, requireNotNull(activity), viewport)
            writeReport(
                context,
                viewport,
                JSONObject()
                    .put("fixture_count", snapshots.size)
                    .put("states", JSONArray(EXPECTED_STATES.map(AgentTeamExecutionState::name)))
                    .put("viewport", viewportReport)
                    .put("header_fixed", true)
                    .put("details_verified", true)
            )
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            cleanupFixtures(store)
        }
    }

    private suspend fun seedFixtures(store: AgentTeamExecutionStore) {
        val base = System.currentTimeMillis() - 20_000L
        FIXTURES.forEachIndexed { index, fixture ->
            val supervisorId = fixtureSupervisorId(fixture.state)
            val createdAt = base + index * 1_000L
            val definition = AgentTeamDefinition(
                teamId = fixtureTeamId(fixture.state),
                primaryAgentId = PRIMARY_AGENT_ID,
                visibilityMode = AgentTeamVisibilityMode.VISIBLE,
                members = listOf(
                    AgentTeamMember(
                        agentId = OBSERVER_AGENT_ID,
                        deliveryMode = AgentDeliveryMode.OBSERVE,
                        role = "evidence reviewer"
                    ),
                    AgentTeamMember(
                        agentId = PRIMARY_AGENT_ID,
                        deliveryMode = AgentDeliveryMode.RESPOND,
                        role = "primary synthesizer",
                        dependsOnAgentIds = setOf(OBSERVER_AGENT_ID)
                    )
                )
            )
            val request = AgentRunRequest(
                conversationId = "acceptance-lifecycle-conversation",
                messageId = "acceptance-lifecycle-turn-${fixture.state.name.lowercase()}",
                taskId = "acceptance-lifecycle-task-${fixture.state.name.lowercase()}",
                runId = supervisorId,
                goal = fixture.goal,
                idempotencyKey = supervisorId,
                createdAtMillis = createdAt
            )
            store.create(definition, request)
            seedState(store, fixture.state, supervisorId, createdAt)
        }
    }

    private suspend fun seedState(
        store: AgentTeamExecutionStore,
        state: AgentTeamExecutionState,
        supervisorId: String,
        createdAt: Long
    ) {
        if (state == AgentTeamExecutionState.QUEUED) return
        var sequence = 1L
        suspend fun append(
            kind: String,
            childId: String = "",
            childStatus: AgentSubagentStatus? = null,
            runStatus: AgentSubagentRunStatus? = null,
            message: String = "",
            output: String = ""
        ) {
            val timestamp = createdAt + sequence * 10L
            val result = childStatus?.takeIf(AgentSubagentStatus::isTerminal)?.let { terminalStatus ->
                AgentSubagentChildResult(
                    supervisorId = supervisorId,
                    childId = childId,
                    parentId = supervisorId,
                    depth = 1,
                    status = terminalStatus,
                    output = output,
                    errorMessage = message,
                    startedAtMillis = timestamp - 5L,
                    completedAtMillis = timestamp
                )
            }
            store.append(AgentSubagentEvent(
                sequence = sequence++,
                supervisorId = supervisorId,
                childId = childId,
                kind = kind,
                childStatus = childStatus,
                runStatus = runStatus,
                message = message,
                result = result,
                timestampMillis = timestamp
            ))
        }

        append(AgentSubagentEventKinds.SUPERVISOR_STARTED)
        when (state) {
            AgentTeamExecutionState.RUNNING -> {
                append(
                    AgentSubagentEventKinds.CHILD_RUNNING,
                    OBSERVER_AGENT_ID,
                    AgentSubagentStatus.RUNNING
                )
            }
            AgentTeamExecutionState.SUCCEEDED -> {
                appendSuccessfulTeam(::append)
                append(
                    AgentSubagentEventKinds.SUPERVISOR_SUCCEEDED,
                    runStatus = AgentSubagentRunStatus.SUCCEEDED
                )
            }
            AgentTeamExecutionState.COMPLETED_WITH_FAILURES -> {
                append(
                    AgentSubagentEventKinds.CHILD_FAILED,
                    OBSERVER_AGENT_ID,
                    AgentSubagentStatus.FAILED,
                    message = "Research endpoint unavailable"
                )
                append(
                    AgentSubagentEventKinds.CHILD_SUCCEEDED,
                    PRIMARY_AGENT_ID,
                    AgentSubagentStatus.SUCCEEDED,
                    output = "Primary completed with remaining verified evidence."
                )
                append(
                    AgentSubagentEventKinds.SUPERVISOR_COMPLETED_WITH_FAILURES,
                    runStatus = AgentSubagentRunStatus.COMPLETED_WITH_FAILURES
                )
            }
            AgentTeamExecutionState.FAILED -> {
                append(
                    AgentSubagentEventKinds.CHILD_FAILED,
                    OBSERVER_AGENT_ID,
                    AgentSubagentStatus.FAILED,
                    message = "Observer failed"
                )
                append(
                    AgentSubagentEventKinds.CHILD_FAILED,
                    PRIMARY_AGENT_ID,
                    AgentSubagentStatus.FAILED,
                    message = "Primary failed after bounded retries"
                )
                append(
                    AgentSubagentEventKinds.SUPERVISOR_FAILED,
                    runStatus = AgentSubagentRunStatus.FAILED
                )
            }
            AgentTeamExecutionState.CANCELLED -> {
                append(
                    AgentSubagentEventKinds.CHILD_CANCELLED,
                    OBSERVER_AGENT_ID,
                    AgentSubagentStatus.CANCELLED,
                    message = "Cancelled by supervisor"
                )
                append(
                    AgentSubagentEventKinds.CHILD_CANCELLED,
                    PRIMARY_AGENT_ID,
                    AgentSubagentStatus.CANCELLED,
                    message = "Cancelled by supervisor"
                )
                append(
                    AgentSubagentEventKinds.SUPERVISOR_CANCELLED,
                    runStatus = AgentSubagentRunStatus.CANCELLED
                )
            }
            AgentTeamExecutionState.INTERRUPTED -> {
                append(
                    AgentSubagentEventKinds.CHILD_SUCCEEDED,
                    OBSERVER_AGENT_ID,
                    AgentSubagentStatus.SUCCEEDED,
                    output = "Evidence persisted before process loss."
                )
                append(
                    AgentSubagentEventKinds.CHILD_RUNNING,
                    PRIMARY_AGENT_ID,
                    AgentSubagentStatus.RUNNING
                )
                assertEquals(
                    AgentTeamExecutionState.INTERRUPTED,
                    store.markInterrupted(supervisorId, createdAt + 900L)?.state
                )
            }
            AgentTeamExecutionState.QUEUED -> Unit
        }
    }

    private suspend fun appendSuccessfulTeam(
        append: suspend (
            kind: String,
            childId: String,
            childStatus: AgentSubagentStatus?,
            runStatus: AgentSubagentRunStatus?,
            message: String,
            output: String
        ) -> Unit
    ) {
        append(
            AgentSubagentEventKinds.CHILD_SUCCEEDED,
            OBSERVER_AGENT_ID,
            AgentSubagentStatus.SUCCEEDED,
            null,
            "",
            "Evidence verified."
        )
        append(
            AgentSubagentEventKinds.CHILD_SUCCEEDED,
            PRIMARY_AGENT_ID,
            AgentSubagentStatus.SUCCEEDED,
            null,
            "",
            "Final synthesized result."
        )
    }

    private fun verifyViewport(
        instrumentation: Instrumentation,
        activity: MainActivity,
        viewport: Viewport
    ): JSONObject {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(700L)
        lateinit var scroll: ScrollView
        lateinit var featureContent: ViewGroup
        val topHeaderLocation = IntArray(2)
        val bottomHeaderLocation = IntArray(2)
        var scrollYAfter = 0
        val allTexts = mutableListOf<String>()
        instrumentation.runOnMainSync {
            val title = activity.findViewById<TextView>(R.id.featureTitle)
            assertEquals(activity.getString(R.string.cc_tasks_title), title.text.toString())
            title.getLocationOnScreen(topHeaderLocation)
            featureContent = activity.findViewById(R.id.featureContent)
            scroll = featureContent.parent as ScrollView
            collectViewTexts(featureContent, allTexts)
        }
        FIXTURES.forEach { fixture ->
            assertTrue("Missing lifecycle row: ${fixture.goal}", fixture.goal in allTexts)
        }
        EXPECTED_STATES.forEach { state ->
            assertTrue(
                "Missing lifecycle status: $state",
                allTexts.any { it.contains(expectedStateText(activity, state)) }
            )
        }
        val topScreenshot = takeScreenshot(instrumentation, activity, "${viewport.name}-top")
        instrumentation.runOnMainSync { scroll.fullScroll(View.FOCUS_DOWN) }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(350L)
        instrumentation.runOnMainSync {
            activity.findViewById<TextView>(R.id.featureTitle).getLocationOnScreen(bottomHeaderLocation)
            scrollYAfter = scroll.scrollY
        }
        assertEquals(topHeaderLocation[1], bottomHeaderLocation[1])
        if (viewport.name == "compact") {
            assertTrue("Compact viewport did not exercise scrolling", scrollYAfter > 0)
        }
        val bottomScreenshot = takeScreenshot(instrumentation, activity, "${viewport.name}-bottom")

        instrumentation.runOnMainSync {
            scroll.fullScroll(View.FOCUS_UP)
            val target = findTextView(featureContent, INTERRUPTED_GOAL)
            assertNotNull(target)
            var clickable: View? = target
            while (clickable != null && !clickable.isClickable) clickable = clickable.parent as? View
            assertNotNull("Interrupted team row is not clickable", clickable)
            clickable?.performClick()
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(350L)
        val detailTexts = accessibilityTexts(instrumentation)
        assertTrue(detailTexts.any { it.contains(INTERRUPTED_GOAL) })
        assertTrue(detailTexts.any { it.contains(PRIMARY_AGENT_ID) })
        assertTrue(detailTexts.any { it.contains(OBSERVER_AGENT_ID) })
        val detailsScreenshot = takeScreenshot(instrumentation, activity, "${viewport.name}-details")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)

        return JSONObject()
            .put("name", viewport.name)
            .put("requested_size", viewport.size)
            .put("requested_density", viewport.density)
            .put("screenshot_width", topScreenshot.first)
            .put("screenshot_height", topScreenshot.second)
            .put("header_y_top", topHeaderLocation[1])
            .put("header_y_after_scroll", bottomHeaderLocation[1])
            .put("scroll_y_after", scrollYAfter)
            .put("header_fixed", topHeaderLocation[1] == bottomHeaderLocation[1])
            .put("top_screenshot", topScreenshot.third)
            .put("bottom_screenshot", bottomScreenshot.third)
            .put("details_screenshot", detailsScreenshot.third)
    }

    private fun openRecentTasks(instrumentation: Instrumentation): MainActivity {
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("signalasi_debug_open_recent_tasks", true)
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        val activity = try {
            instrumentation.targetContext.startActivity(intent)
            instrumentation.waitForMonitorWithTimeout(monitor, 30_000L) as? MainActivity
        } finally {
            instrumentation.removeMonitor(monitor)
        } ?: throw AssertionError("MainActivity did not launch within 30 seconds")
        val deadline = SystemClock.elapsedRealtime() + 20_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            var visible = false
            instrumentation.runOnMainSync {
                visible = activity.findViewById<TextView>(R.id.featureTitle)?.text?.toString() ==
                    activity.getString(R.string.cc_tasks_title)
            }
            if (visible) return activity
            SystemClock.sleep(100L)
        }
        throw AssertionError("Task Center did not become visible within 20 seconds")
    }

    private fun takeScreenshot(
        instrumentation: Instrumentation,
        activity: MainActivity,
        suffix: String
    ): Triple<Int, Int, String> {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val filename = "agent-lifecycle-ui-$suffix.png"
        val output = File(requireNotNull(activity.getExternalFilesDir(null)), filename)
        FileOutputStream(output).use { stream ->
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream))
        }
        return Triple(bitmap.width, bitmap.height, filename)
    }

    private fun expectedStateText(activity: MainActivity, state: AgentTeamExecutionState): String =
        activity.getString(when (state) {
            AgentTeamExecutionState.QUEUED -> R.string.agent_team_state_queued
            AgentTeamExecutionState.RUNNING -> R.string.agent_team_state_running
            AgentTeamExecutionState.SUCCEEDED -> R.string.agent_team_state_succeeded
            AgentTeamExecutionState.COMPLETED_WITH_FAILURES -> R.string.agent_team_state_completed_with_failures
            AgentTeamExecutionState.FAILED -> R.string.agent_team_state_failed
            AgentTeamExecutionState.CANCELLED -> R.string.agent_team_state_cancelled
            AgentTeamExecutionState.INTERRUPTED -> R.string.agent_team_state_interrupted
        })

    private fun collectViewTexts(view: View, destination: MutableList<String>) {
        if (view is TextView) destination += view.text.toString()
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) collectViewTexts(view.getChildAt(index), destination)
        }
    }

    private fun findTextView(view: View, expected: String): TextView? {
        if (view is TextView && view.text.toString() == expected) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findTextView(view.getChildAt(index), expected)?.let { return it }
            }
        }
        return null
    }

    private fun accessibilityTexts(instrumentation: Instrumentation): List<String> {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return emptyList()
        val texts = mutableListOf<String>()
        fun collect(node: android.view.accessibility.AccessibilityNodeInfo) {
            node.text?.toString()?.takeIf(String::isNotBlank)?.let(texts::add)
            node.contentDescription?.toString()?.takeIf(String::isNotBlank)?.let(texts::add)
            for (index in 0 until node.childCount) node.getChild(index)?.let(::collect)
        }
        collect(root)
        return texts
    }

    private fun fixtureSnapshots(store: AgentTeamExecutionStore): List<AgentTeamExecutionSnapshot> =
        store.snapshots().filter { it.supervisorRunId.startsWith(FIXTURE_PREFIX) }

    private fun cleanupFixtures(store: AgentTeamExecutionStore) {
        EXPECTED_STATES.forEach { store.remove(fixtureSupervisorId(it)) }
    }

    private fun writeReport(context: Context, viewport: Viewport, report: JSONObject) {
        File(requireNotNull(context.getExternalFilesDir(null)), reportFilename(viewport))
            .writeText(report.toString(2), Charsets.UTF_8)
    }

    private fun requestedViewport(): Viewport {
        val requested = InstrumentationRegistry.getArguments().getString(VIEWPORT_ARGUMENT).orEmpty()
        return VIEWPORTS.firstOrNull { it.name == requested }
            ?: throw AssertionError("Unknown lifecycle UI viewport: $requested")
    }

    private fun reportFilename(viewport: Viewport): String = "agent-lifecycle-ui-report-${viewport.name}.json"

    private fun fixtureSupervisorId(state: AgentTeamExecutionState): String =
        "$FIXTURE_PREFIX${state.name.lowercase()}"

    private fun fixtureTeamId(state: AgentTeamExecutionState): String =
        "acceptance-lifecycle-team-${state.name.lowercase()}"

    private data class Fixture(val state: AgentTeamExecutionState, val goal: String)
    private data class Viewport(val name: String, val size: String, val density: Int)

    private companion object {
        const val VIEWPORT_ARGUMENT = "signalasi_viewport"
        const val FIXTURE_PREFIX = "acceptance-lifecycle-supervisor-"
        const val PRIMARY_AGENT_ID = "acceptance-primary"
        const val OBSERVER_AGENT_ID = "acceptance-observer"
        const val INTERRUPTED_GOAL = "Interrupted team after process recreation"
        val FIXTURES = listOf(
            Fixture(AgentTeamExecutionState.QUEUED, "Queued team waiting for capacity"),
            Fixture(AgentTeamExecutionState.RUNNING, "Running team collecting evidence"),
            Fixture(AgentTeamExecutionState.SUCCEEDED, "Succeeded team with final result"),
            Fixture(AgentTeamExecutionState.COMPLETED_WITH_FAILURES, "Partially degraded team with usable result"),
            Fixture(AgentTeamExecutionState.FAILED, "Failed team after bounded retries"),
            Fixture(AgentTeamExecutionState.CANCELLED, "Cancelled team by user request"),
            Fixture(AgentTeamExecutionState.INTERRUPTED, INTERRUPTED_GOAL)
        )
        val EXPECTED_STATES = FIXTURES.mapTo(linkedSetOf(), Fixture::state)
        val VIEWPORTS = listOf(
            Viewport("compact", "720x1280", 320),
            Viewport("large", "1080x2400", 420)
        )
    }
}

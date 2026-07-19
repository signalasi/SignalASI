package com.signalasi.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentRuntimeArchiveToolsDeviceTest {
    @Test
    fun linuxGuestCanCreateListExtractAndRunAProjectArchive() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val lifecycle = AgentOnDeviceRuntimeLifecycle.ensureRunning(context)
        assertTrue("Runtime lifecycle: ${lifecycle.reason}", lifecycle.phase == AgentRuntimeLifecyclePhase.READY)
        val result = AgentOnDeviceRuntimeManager(context).execute(
            AgentRuntimeExecutionRequest(
                language = AgentRuntimeLanguage.SHELL,
                source = """
                    set -eu
                    command -v zip
                    command -v unzip
                    mkdir -p archive-check/project
                    printf 'print(42)\n' > archive-check/project/main.py
                    (cd archive-check && zip -qr project.zip project)
                    mkdir -p archive-check/unpacked
                    unzip -q archive-check/project.zip -d archive-check/unpacked
                    test -f archive-check/unpacked/project/main.py
                    printf 'archive-tools-ready\n'
                """.trimIndent(),
                arguments = emptyList(),
                timeoutMillis = 60_000L,
                networkEnabled = false,
                artifactPaths = listOf("archive-check/project.zip"),
                workspaceId = "device-archive-tool-check",
                requestId = "archive-check-${System.currentTimeMillis()}",
                resourceLimits = AgentRuntimeResourceLimits(
                    wallClockMillis = 60_000L,
                    cpuMillis = 45_000L,
                    memoryBytes = 128L * 1024L * 1024L,
                    diskBytes = 32L * 1024L * 1024L,
                    maxProcesses = 16,
                    maxOutputBytes = 128L * 1024L,
                    maxArtifactBytes = 16L * 1024L * 1024L
                )
            )
        )

        assertEquals("stdout=${result.stdout}\nstderr=${result.stderr}", 0, result.exitCode)
        assertTrue(result.stdout, result.stdout.contains("archive-tools-ready"))
        assertTrue(result.artifacts.single()["host_path"].toString().endsWith("project.zip"))
    }
}

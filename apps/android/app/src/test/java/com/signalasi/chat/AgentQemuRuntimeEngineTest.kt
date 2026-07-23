package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AgentQemuRuntimeEngineTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `launch plan keeps credentials off the command line and disables guest networking`() {
        val root = temporaryFolder.newFolder("runtime plan")
        val spec = AgentRuntimeEngineLaunchSpec(
            engineFile = File(root, "libsignalasi_qemu.so"),
            baseImageFile = File(root, "linux-base.img"),
            socketFile = File(root, "guest.sock"),
            packsDirectory = File(root, "packs"),
            workspacesDirectory = File(root, "workspaces"),
            architecture = "arm64-v8a",
            packAttachments = listOf(
                AgentRuntimePackAttachment(
                    "python-uv",
                    "1.0.0",
                    setOf("python.execute", "uv.sync"),
                    File(root, "python.img")
                ),
                AgentRuntimePackAttachment(
                    "ffmpeg",
                    "1.0.0",
                    setOf("ffmpeg.execute", "ffprobe.inspect"),
                    File(root, "ffmpeg.img")
                )
            ),
            sessionKey = ByteArray(32) { 0x5a }
        )
        val sessionFile = File(root, "guest-session.key")
        val configFile = File(root, "guest-config.json")
        val plan = AgentQemuLaunchPlanBuilder.build(
            spec = spec,
            sessionFile = sessionFile,
            configFile = configFile,
            logFile = File(root, "qemu.log"),
            memoryMegabytes = 512,
            cpuCount = 2
        )

        val command = plan.command.joinToString(" ")
        assertTrue(command.contains("-nic none"))
        assertTrue(command.contains("server=on,wait=on"))
        assertFalse(command.contains("server=on,wait=off"))
        assertTrue(command.contains("readonly=on"))
        assertTrue(command.contains("mount_tag=signalasi_workspaces"))
        assertTrue(command.contains("name=opt/com.signalasi/runtime-session,file=${sessionFile.absolutePath}"))
        assertFalse(command.contains("Wlpa"))
        assertFalse(command.contains("5a5a5a"))
        assertTrue(command.indexOf("ffmpeg.img") < command.indexOf("python.img"))
        assertEquals("C", plan.environment["LC_ALL"])
        assertEquals(spec.engineFile.parentFile?.absolutePath, plan.environment["LD_LIBRARY_PATH"])
        assertFalse(plan.environment.containsKey("PATH"))
    }

    @Test
    fun `pack serials are stable and bounded`() {
        assertEquals("sa-python-uv", AgentQemuLaunchPlanBuilder.packSerial("python-uv"))
        assertTrue(AgentQemuLaunchPlanBuilder.packSerial("a".repeat(80)).length <= 20)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `launch plan rejects qemu key value delimiter in paths`() {
        val root = File("build/runtime,unsafe")
        AgentQemuLaunchPlanBuilder.build(
            spec = AgentRuntimeEngineLaunchSpec(
                engineFile = File(root, "engine"),
                baseImageFile = File(root, "base"),
                socketFile = File(root, "socket"),
                packsDirectory = File(root, "packs"),
                workspacesDirectory = File(root, "workspaces"),
                architecture = "arm64-v8a",
                sessionKey = ByteArray(32)
            ),
            sessionFile = File(root, "session"),
            configFile = File(root, "config"),
            logFile = File(root, "log"),
            memoryMegabytes = 512,
            cpuCount = 2
        )
    }
}

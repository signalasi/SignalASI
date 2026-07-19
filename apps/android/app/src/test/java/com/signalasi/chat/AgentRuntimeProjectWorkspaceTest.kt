package com.signalasi.chat

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgentRuntimeProjectWorkspaceTest {
    private lateinit var root: File
    private lateinit var runtimeRoot: File
    private lateinit var projectRoot: File
    private lateinit var manager: AgentRuntimeWorkspaceManager

    @Before
    fun setUp() {
        root = Files.createTempDirectory("signalasi-runtime-project-").toFile()
        runtimeRoot = File(root, "runtime")
        projectRoot = File(root, "projects")
        manager = AgentRuntimeWorkspaceManager(runtimeRoot, projectRoot, forTesting = true)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun persistsProjectFilesAcrossIsolatedRuntimeRequests() {
        val project = File(projectRoot, "workspace-one").apply { mkdirs() }
        File(project, "README.md").writeText("first")

        val first = manager.prepare(request("run-one", "print('one')"))
        assertEquals("first", File(first.directory, "README.md").readText())
        File(first.directory, "result.txt").writeText("generated")
        File(first.directory, ".signalasi-stdout").writeText("private runtime output")
        val sync = manager.syncProject(first, 8L * 1024L * 1024L)

        assertTrue(sync.fileCount >= 3)
        assertEquals("generated", File(project, "result.txt").readText())
        assertEquals("print('one')", File(project, "main.py").readText())
        assertFalse(File(project, "request.json").exists())
        assertFalse(File(project, ".signalasi-stdout").exists())

        val second = manager.prepare(request("run-two", "print('two')"))
        assertEquals("generated", File(second.directory, "result.txt").readText())
        assertEquals("first", File(second.directory, "README.md").readText())
        assertEquals("print('two')", second.sourceFile.readText())
        assertTrue(second.importedProjectBytes > 0L)
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsProjectSnapshotsThatExceedTheRuntimeQuota() {
        val prepared = manager.prepare(request("run-quota", "print('quota')"))
        File(prepared.directory, "large.bin").writeBytes(ByteArray(32 * 1024))
        manager.syncProject(prepared, 8 * 1024L)
    }

    @Test
    fun conversationScopeIsStableAndCannotBeOverriddenByToolArguments() {
        val first = AgentWorkspaceScope.id("conversation-a", "session-a")
        val repeated = AgentWorkspaceScope.id("conversation-a", "session-b")
        val other = AgentWorkspaceScope.id("conversation-b", "session-a")
        assertEquals(first, repeated)
        assertNotEquals(first, other)

        val bound = AgentWorkspaceScope.bindToolInput(
            AgentPhoneNativeToolCatalog.WORKSPACE_READ_TEXT,
            mapOf("workspace_id" to "attacker", "path" to "notes.txt"),
            first
        )
        assertEquals(first, bound["workspace_id"])
        assertEquals("notes.txt", bound["path"])
    }

    @Test
    fun packagesMultipleFilesAndDirectoriesAsOneProjectArchive() {
        val request = request(
            requestId = "run-project",
            source = "print('project')",
            artifactPaths = listOf("src", "README.md")
        )
        val prepared = manager.prepare(request)
        File(prepared.directory, "src/lib").mkdirs()
        File(prepared.directory, "src/main.py").writeText("print('hello')")
        File(prepared.directory, "src/lib/value.py").writeText("VALUE = 7")
        File(prepared.directory, "README.md").writeText("# Sample")
        manager.syncProject(prepared, 8L * 1024L * 1024L)

        val artifact = manager.collectArtifacts(prepared, request).single()
        assertEquals("project_archive", artifact["artifact_kind"])
        assertEquals(3, artifact["file_count"])
        val archive = File(artifact.getValue("host_path").toString())
        assertTrue(archive.isFile)
        ZipFile(archive).use { zip ->
            assertEquals(
                setOf("src/main.py", "src/lib/value.py", "README.md"),
                zip.entries().asSequence().map { it.name }.toSet()
            )
        }
    }

    private fun request(
        requestId: String,
        source: String,
        artifactPaths: List<String> = listOf("result.txt")
    ) = AgentRuntimeExecutionRequest(
        language = AgentRuntimeLanguage.PYTHON,
        source = source,
        arguments = emptyList(),
        timeoutMillis = 1_000L,
        networkEnabled = false,
        artifactPaths = artifactPaths,
        workspaceId = "workspace-one",
        requestId = requestId,
        resourceLimits = AgentRuntimeResourceLimits(
            wallClockMillis = 1_000L,
            cpuMillis = 750L,
            memoryBytes = 64L * 1024L * 1024L,
            diskBytes = 8L * 1024L * 1024L,
            maxProcesses = 8,
            maxOutputBytes = 64L * 1024L,
            maxArtifactBytes = 4L * 1024L * 1024L
        )
    )
}

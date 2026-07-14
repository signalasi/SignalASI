package com.signalasi.chat

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Comparator
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test

class AgentWorkspaceFileToolsTest {
    private lateinit var storageRoot: Path
    private lateinit var tools: AgentWorkspaceFileTools

    @Before
    fun setUp() {
        storageRoot = Files.createTempDirectory("agent-workspace-tools-")
        tools = AgentWorkspaceFileTools(storageRoot)
    }

    @After
    fun tearDown() {
        if (!::storageRoot.isInitialized || !Files.exists(storageRoot)) return
        Files.walk(storageRoot).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun performsScopedFileOperationsAndReturnsMetadata() {
        tools.initializeWorkspace("alpha").success()
        val mkdir = tools.mkdir("alpha", "docs/nested").success()
        assertEquals(2, mkdir.affectedEntries)

        tools.createText("alpha", "docs/nested/note.txt", "hello").success()
        assertEquals(
            AgentWorkspaceFileErrorCode.ALREADY_EXISTS,
            tools.createText("alpha", "docs/nested/note.txt", "again").failureCode()
        )
        tools.appendText("alpha", "docs/nested/note.txt", " world").success()
        assertEquals("hello world", tools.readText("alpha", "docs/nested/note.txt").success().text)

        val bytes = tools.readBytes("alpha", "docs/nested/note.txt").success()
        assertArrayEquals("hello world".toByteArray(), bytes.bytes)
        assertEquals(AgentWorkspaceEntryType.FILE, bytes.metadata.type)
        assertEquals(11, bytes.metadata.sizeBytes)
        assertEquals(sha256("hello world".toByteArray()), bytes.sha256)

        tools.writeText("alpha", "docs/nested/note.txt", "rewritten").success()
        val stat = tools.stat("alpha", "docs/nested/note.txt").success()
        assertEquals("docs/nested/note.txt", stat.path)
        assertEquals(9, stat.sizeBytes)

        val listing = tools.list("alpha", "docs", recursive = true).success()
        assertEquals(listOf("docs/nested", "docs/nested/note.txt"), listing.entries.map { it.path })
        assertEquals(
            AgentWorkspaceFileErrorCode.NOT_FOUND,
            tools.stat("beta", "docs/nested/note.txt").failureCode()
        )
    }

    @Test
    fun copiesMovesSearchesAndDeletesTrees() {
        tools.createText("work", "docs/one.txt", "Needle one\nsecond needle", createParents = true).success()
        tools.createText("work", "docs/two.txt", "nothing here").success()
        tools.create("work", "docs/binary.bin", byteArrayOf(0xc3.toByte(), 0x28)).success()

        val search = tools.searchText("work", "docs", "needle", maxResults = 1).success()
        assertEquals(1, search.matches.size)
        assertEquals("docs/one.txt", search.matches.single().path)
        assertEquals(1, search.matches.single().line)
        assertTrue(search.truncated)

        val copied = tools.copy("work", "docs", "backup").success()
        assertEquals(4, copied.affectedEntries)
        assertEquals("Needle one\nsecond needle", tools.readText("work", "backup/one.txt").success().text)

        val moved = tools.move("work", "backup/one.txt", "backup/moved.txt").success()
        assertEquals("Needle one\nsecond needle".toByteArray().size.toLong(), moved.affectedBytes)
        assertEquals(AgentWorkspaceFileErrorCode.NOT_FOUND, tools.stat("work", "backup/one.txt").failureCode())
        assertEquals(
            AgentWorkspaceFileErrorCode.DIRECTORY_NOT_EMPTY,
            tools.delete("work", "backup").failureCode()
        )
        val deleted = tools.delete("work", "backup", recursive = true).success()
        assertEquals(4, deleted.affectedEntries)
        assertEquals(AgentWorkspaceFileErrorCode.NOT_FOUND, tools.stat("work", "backup").failureCode())
    }

    @Test
    fun rejectsAbsoluteTraversalAndInvalidWorkspacePaths() {
        val attempts = listOf(
            tools.writeText("alpha", "../escape.txt", "bad"),
            tools.writeText("alpha", "docs/../escape.txt", "bad", createParents = true),
            tools.writeText("alpha", "..\\escape.txt", "bad"),
            tools.writeText("alpha", "/absolute.txt", "bad"),
            tools.writeText("alpha", "C:\\absolute.txt", "bad")
        )
        attempts.forEach { assertEquals(AgentWorkspaceFileErrorCode.PATH_ESCAPE, it.failureCode()) }
        assertEquals(
            AgentWorkspaceFileErrorCode.INVALID_WORKSPACE,
            tools.initializeWorkspace("../alpha").failureCode()
        )
        assertFalse(Files.exists(storageRoot.resolve("escape.txt")))
    }

    @Test
    fun rejectsSymbolicLinksEvenWhenTheirTargetIsInsidePrivateStorage() {
        tools.initializeWorkspace("alpha").success()
        val target = storageRoot.resolve("other-workspace").also { Files.createDirectories(it) }
        Files.write(target.resolve("secret.txt"), "secret".toByteArray())
        val link = storageRoot.resolve("alpha/link")
        try {
            Files.createSymbolicLink(link, target)
        } catch (error: UnsupportedOperationException) {
            Assume.assumeNoException(error)
        } catch (error: SecurityException) {
            Assume.assumeNoException(error)
        } catch (error: IOException) {
            Assume.assumeNoException(error)
        }

        assertEquals(AgentWorkspaceFileErrorCode.SYMLINK_REJECTED, tools.stat("alpha", "link").failureCode())
        assertEquals(
            AgentWorkspaceFileErrorCode.SYMLINK_REJECTED,
            tools.readText("alpha", "link/secret.txt").failureCode()
        )
    }

    @Test
    fun enforcesReadWriteListAndUtf8Bounds() {
        val limited = AgentWorkspaceFileTools(
            storageRoot,
            AgentWorkspaceFilePolicy(
                maxTextReadBytes = 4,
                maxBytesReadBytes = 4,
                maxWriteBytes = 5,
                maxListEntries = 2
            )
        )
        limited.initializeWorkspace("limits").success()
        val root = storageRoot.resolve("limits")
        Files.write(root.resolve("large.txt"), "12345".toByteArray())
        Files.write(root.resolve("invalid.txt"), byteArrayOf(0xc3.toByte(), 0x28))
        Files.write(root.resolve("third.txt"), byteArrayOf(1))

        assertEquals(AgentWorkspaceFileErrorCode.LIMIT_EXCEEDED, limited.readText("limits", "large.txt").failureCode())
        assertEquals(AgentWorkspaceFileErrorCode.LIMIT_EXCEEDED, limited.readBytes("limits", "large.txt").failureCode())
        assertEquals(
            AgentWorkspaceFileErrorCode.LIMIT_EXCEEDED,
            limited.write("limits", "too-large.bin", ByteArray(6)).failureCode()
        )
        assertEquals(AgentWorkspaceFileErrorCode.INVALID_TEXT, limited.readText("limits", "invalid.txt").failureCode())
        assertEquals(AgentWorkspaceFileErrorCode.LIMIT_EXCEEDED, limited.list("limits").failureCode())
    }

    @Test
    fun appliesOnlyExactPatchesAndSummarizesDiffs() {
        tools.createText("patch", "note.txt", "one\ntwo\nthree\n", createParents = true).success()
        assertEquals(
            AgentWorkspaceFileErrorCode.PATCH_MISMATCH,
            tools.applyExactPatch("patch", "note.txt", "two", "TWO", expectedOccurrences = 2).failureCode()
        )
        assertEquals("one\ntwo\nthree\n", tools.readText("patch", "note.txt").success().text)

        val patch = tools.applyExactPatch("patch", "note.txt", "two", "TWO").success()
        assertEquals(1, patch.replacements)
        assertEquals(2, patch.diff.firstChangedLine)
        assertEquals(1, patch.diff.changedLinePairs)
        assertNotEquals(patch.diff.beforeSha256, patch.diff.afterSha256)

        val unchanged = tools.diffSummary("patch", "note.txt", "one\nTWO\nthree\n").success()
        assertNull(unchanged.firstChangedLine)
        assertEquals(0, unchanged.addedLines)
        assertEquals(0, unchanged.deletedLines)
        val digest = tools.sha256("patch", "note.txt").success()
        assertEquals(sha256("one\nTWO\nthree\n".toByteArray()), digest.hex)
        assertEquals("SHA-256", digest.algorithm)
    }

    @Test
    fun createsListsAndExtractsZipArchives() {
        tools.createText("zip", "docs/a.txt", "alpha", createParents = true).success()
        tools.createText("zip", "docs/nested/b.txt", "beta", createParents = true).success()

        val created = tools.createZip("zip", "bundle.zip", listOf("docs")).success()
        assertTrue(created.archiveBytes > 0)
        assertEquals(
            listOf("docs", "docs/a.txt", "docs/nested", "docs/nested/b.txt"),
            created.entries.map { it.path }
        )
        val listed = tools.listZip("zip", "bundle.zip").success()
        assertEquals(9, listed.totalUncompressedBytes)

        val extracted = tools.extractZip("zip", "bundle.zip", "unpacked").success()
        assertEquals(4, extracted.extractedEntries)
        assertEquals(9, extracted.extractedBytes)
        assertEquals("alpha", tools.readText("zip", "unpacked/docs/a.txt").success().text)
        assertEquals("beta", tools.readText("zip", "unpacked/docs/nested/b.txt").success().text)
    }

    @Test
    fun rejectsZipSlipAndDoesNotWriteOutsideDestination() {
        tools.initializeWorkspace("zip-slip").success()
        val workspace = storageRoot.resolve("zip-slip")
        writeZip(workspace.resolve("bad.zip"), listOf("../escaped.txt" to "bad".toByteArray()))

        assertEquals(AgentWorkspaceFileErrorCode.INVALID_ARCHIVE, tools.listZip("zip-slip", "bad.zip").failureCode())
        assertEquals(
            AgentWorkspaceFileErrorCode.INVALID_ARCHIVE,
            tools.extractZip("zip-slip", "bad.zip", "out").failureCode()
        )
        assertFalse(Files.exists(workspace.resolve("escaped.txt")))
        assertFalse(Files.exists(storageRoot.resolve("escaped.txt")))
    }

    @Test
    fun rejectsZipEntryCountSizeAndCompressionRatioBombs() {
        tools.initializeWorkspace("zip-limits").success()
        val workspace = storageRoot.resolve("zip-limits")
        writeZip(
            workspace.resolve("entries.zip"),
            listOf("one" to byteArrayOf(), "two" to byteArrayOf(), "three" to byteArrayOf())
        )
        val entryLimited = AgentWorkspaceFileTools(storageRoot, AgentWorkspaceFilePolicy(maxZipEntries = 2))
        assertEquals(
            AgentWorkspaceFileErrorCode.LIMIT_EXCEEDED,
            entryLimited.listZip("zip-limits", "entries.zip").failureCode()
        )

        writeZip(
            workspace.resolve("total.zip"),
            listOf("one" to ByteArray(5) { it.toByte() }, "two" to ByteArray(5) { (it + 5).toByte() })
        )
        val sizeLimited = AgentWorkspaceFileTools(
            storageRoot,
            AgentWorkspaceFilePolicy(
                maxZipEntryBytes = 8,
                maxZipUncompressedBytes = 8,
                maxZipCompressionRatio = 1_000.0
            )
        )
        assertEquals(
            AgentWorkspaceFileErrorCode.LIMIT_EXCEEDED,
            sizeLimited.listZip("zip-limits", "total.zip").failureCode()
        )

        writeZip(workspace.resolve("ratio.zip"), listOf("zeros.bin" to ByteArray(4_096)))
        val ratioLimited = AgentWorkspaceFileTools(
            storageRoot,
            AgentWorkspaceFilePolicy(
                maxZipEntryBytes = 8_192,
                maxZipUncompressedBytes = 8_192,
                maxZipCompressionRatio = 2.0
            )
        )
        assertEquals(
            AgentWorkspaceFileErrorCode.LIMIT_EXCEEDED,
            ratioLimited.listZip("zip-limits", "ratio.zip").failureCode()
        )
        assertEquals(
            AgentWorkspaceFileErrorCode.LIMIT_EXCEEDED,
            ratioLimited.extractZip("zip-limits", "ratio.zip", "out").failureCode()
        )
    }

    private fun writeZip(path: Path, entries: List<Pair<String, ByteArray>>) {
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }

    private fun <T> AgentWorkspaceFileResult<T>.success(): T = when (this) {
        is AgentWorkspaceFileResult.Success -> value
        is AgentWorkspaceFileResult.Failure -> throw AssertionError("Expected success, got $error")
    }

    private fun AgentWorkspaceFileResult<*>.failureCode(): AgentWorkspaceFileErrorCode = when (this) {
        is AgentWorkspaceFileResult.Success -> throw AssertionError("Expected failure, got $value")
        is AgentWorkspaceFileResult.Failure -> error.code
    }
}

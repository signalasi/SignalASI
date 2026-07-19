package com.signalasi.chat

import android.content.Context
import java.io.File
import java.security.MessageDigest

data class AgentStagedAttachment(
    val name: String,
    val relativePath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String
)

/** Copies user-authorized content into the active conversation's app-private project. */
object AgentAttachmentWorkspaceStager {
    fun stage(
        context: Context,
        conversationId: String,
        turnId: String,
        attachments: List<AgentInputAttachment>
    ): List<AgentStagedAttachment> {
        require(turnId.matches(SAFE_ID)) { "Attachment turn ID is invalid" }
        val workspaceId = AgentWorkspaceScope.id(conversationId)
        return AgentWorkspaceScope.withLock(workspaceId) {
            val projectRoot = File(context.applicationContext.filesDir, "agent-native-workspaces")
            val workspace = File(projectRoot, workspaceId).canonicalFile
            val inputDirectory = File(workspace, "inputs/$turnId").canonicalFile
            require(inputDirectory.path.startsWith(workspace.path + File.separator)) { "Attachment path is unsafe" }
            check(inputDirectory.mkdirs() || inputDirectory.isDirectory) { "Attachment workspace is unavailable" }
            var totalBytes = 0L
            attachments.mapIndexed { index, attachment ->
                val safeName = uniqueName(inputDirectory, sanitizeName(attachment.displayName), index)
                val target = File(inputDirectory, safeName)
                val temporary = File(inputDirectory, ".$safeName.part")
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                try {
                    val input = context.contentResolver.openInputStream(attachment.uri)
                        ?: error("Attachment content is unavailable: ${attachment.displayName}")
                    input.buffered().use { source ->
                        temporary.outputStream().buffered().use { destination ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = source.read(buffer)
                                if (read < 0) break
                                size += read
                                totalBytes += read
                                check(size <= MAX_ATTACHMENT_BYTES && totalBytes <= MAX_TURN_BYTES) {
                                    "Attachment input exceeds the workspace limit"
                                }
                                digest.update(buffer, 0, read)
                                destination.write(buffer, 0, read)
                            }
                        }
                    }
                    check(temporary.renameTo(target)) { "Attachment could not be committed" }
                } finally {
                    temporary.delete()
                }
                AgentStagedAttachment(
                    name = attachment.displayName,
                    relativePath = "inputs/$turnId/$safeName",
                    mimeType = attachment.mimeType,
                    sizeBytes = size,
                    sha256 = digest.digest().joinToString("") { "%02x".format(it) }
                )
            }
        }
    }

    private fun sanitizeName(value: String): String {
        val cleaned = value.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim('.', ' ')
            .take(120)
        return cleaned.ifBlank { "attachment" }
    }

    private fun uniqueName(directory: File, baseName: String, index: Int): String {
        if (!File(directory, baseName).exists()) return baseName
        val extension = baseName.substringAfterLast('.', "").takeIf { it.isNotBlank() }
        val stem = if (extension == null) baseName else baseName.removeSuffix(".$extension")
        var suffix = index + 1
        while (true) {
            val candidate = "$stem-$suffix${extension?.let { ".$it" }.orEmpty()}"
            if (!File(directory, candidate).exists()) return candidate
            suffix += 1
        }
    }

    private const val MAX_ATTACHMENT_BYTES = 256L * 1024L * 1024L
    private const val MAX_TURN_BYTES = 512L * 1024L * 1024L
    private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
}

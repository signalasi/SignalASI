package com.signalasi.chat

import android.app.ActivityManager
import android.content.Context
import android.system.Os
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

internal data class AgentQemuLaunchPlan(
    val command: List<String>,
    val environment: Map<String, String>,
    val logFile: File
)

internal object AgentQemuLaunchPlanBuilder {
    fun build(
        spec: AgentRuntimeEngineLaunchSpec,
        sessionFile: File,
        configFile: File,
        logFile: File,
        memoryMegabytes: Int,
        cpuCount: Int
    ): AgentQemuLaunchPlan {
        val files = buildList {
            add(spec.engineFile)
            add(spec.baseImageFile)
            add(spec.socketFile)
            add(spec.workspacesDirectory)
            add(sessionFile)
            add(configFile)
            add(logFile)
            addAll(spec.packAttachments.map(AgentRuntimePackAttachment::imageFile))
        }
        files.forEach(::requireSafeQemuPath)
        require(spec.socketFile.absolutePath.toByteArray(Charsets.UTF_8).size <= MAX_UNIX_SOCKET_PATH_BYTES) {
            "Runtime socket path is too long"
        }
        require(memoryMegabytes in MIN_MEMORY_MEGABYTES..MAX_MEMORY_MEGABYTES)
        require(cpuCount in 1..MAX_CPU_COUNT)

        val command = buildList {
            add(spec.engineFile.absolutePath)
            addAll(listOf(
                "-name", "SignalASI Runtime",
                "-machine", "virt,gic-version=3,highmem=off",
                "-accel", "tcg,thread=multi",
                "-cpu", "max",
                "-smp", cpuCount.toString(),
                "-m", "${memoryMegabytes}M",
                "-display", "none",
                "-nodefaults",
                "-no-user-config",
                "-no-reboot",
                "-monitor", "none",
                "-serial", "stdio",
                "-nic", "none",
                "-kernel", spec.baseImageFile.absolutePath,
                "-append", "console=ttyAMA0,115200 panic=1 quiet loglevel=3 signalasi.runtime=1",
                "-chardev",
                "socket,id=signalasi_api,path=${spec.socketFile.absolutePath},server=on,wait=on",
                "-device", "virtio-serial-device",
                "-device", "virtserialport,chardev=signalasi_api,name=org.signalasi.runtime",
                "-fsdev",
                "local,id=signalasi_workspaces,path=${spec.workspacesDirectory.absolutePath},security_model=none,multidevs=remap",
                "-device", "virtio-9p-device,fsdev=signalasi_workspaces,mount_tag=signalasi_workspaces",
                "-fw_cfg", "name=opt/com.signalasi/runtime-session,file=${sessionFile.absolutePath}",
                "-fw_cfg", "name=opt/com.signalasi/runtime-config,file=${configFile.absolutePath}",
                "-object", "rng-random,id=signalasi_rng,filename=/dev/urandom",
                "-device", "virtio-rng-device,rng=signalasi_rng"
            ))
            spec.packAttachments.sortedBy(AgentRuntimePackAttachment::packId).forEachIndexed { index, pack ->
                val driveId = "signalasi_pack_$index"
                addAll(listOf(
                    "-drive",
                    "if=none,id=$driveId,file=${pack.imageFile.absolutePath},format=raw,readonly=on,cache=none,aio=threads",
                    "-device",
                    "virtio-blk-device,drive=$driveId,serial=${packSerial(pack.packId)}"
                ))
            }
        }
        require(command.size <= MAX_COMMAND_ARGUMENTS) { "Runtime launch command is too large" }
        val runtimeDirectory = requireNotNull(spec.socketFile.parentFile) { "Runtime socket directory is unavailable" }
        val nativeLibraryDirectory = requireNotNull(spec.engineFile.parentFile) {
            "Native runtime directory is unavailable"
        }
        return AgentQemuLaunchPlan(
            command = command,
            environment = mapOf(
                "HOME" to runtimeDirectory.absolutePath,
                "TMPDIR" to runtimeDirectory.absolutePath,
                "LD_LIBRARY_PATH" to nativeLibraryDirectory.absolutePath,
                "LC_ALL" to "C",
                "LANG" to "C"
            ),
            logFile = logFile
        )
    }

    private fun requireSafeQemuPath(file: File) {
        val path = file.absolutePath
        require(path.isNotBlank() && '\n' !in path && '\r' !in path && ',' !in path) {
            "Runtime path cannot be represented safely in the QEMU command"
        }
    }

    internal fun packSerial(packId: String): String = "sa-${packId.lowercase(Locale.ROOT)}"
        .replace(Regex("[^a-z0-9._-]"), "-")
        .take(MAX_PACK_SERIAL_CHARS)

    private const val MIN_MEMORY_MEGABYTES = 256
    private const val MAX_MEMORY_MEGABYTES = 2_048
    private const val MAX_CPU_COUNT = 4
    private const val MAX_COMMAND_ARGUMENTS = 256
    private const val MAX_UNIX_SOCKET_PATH_BYTES = 100
    private const val MAX_PACK_SERIAL_CHARS = 20
}

class AgentQemuRuntimeEngineController(
    context: Context
) : AgentRuntimeEngineController {
    private val appContext = context.applicationContext
    private val runtimeDirectory = File(appContext.filesDir, "agent-runtime")
    private val sessionFile = File(runtimeDirectory, "guest-session.key")
    private val configFile = File(runtimeDirectory, "guest-config.json")
    private val logFile = File(runtimeDirectory, "qemu.log")

    @Volatile private var process: Process? = null
    @Volatile private var activeSocketFile: File? = null
    @Volatile private var startedAtMillis: Long = 0L
    @Volatile private var lastExitCode: Int? = null

    override val controllerId: String = "signalasi.qemu.tcg.v1"

    @Synchronized
    override fun isRunning(): Boolean {
        val current = process ?: return false
        if (current.isAlive) return true
        lastExitCode = runCatching(current::exitValue).getOrNull()
        process = null
        clearEphemeralFiles()
        return false
    }

    @Synchronized
    override fun start(spec: AgentRuntimeEngineLaunchSpec) {
        check(!isRunning()) { "The QEMU runtime is already running" }
        validate(spec)
        check(runtimeDirectory.mkdirs() || runtimeDirectory.isDirectory) { "Runtime storage is unavailable" }
        check(!spec.socketFile.exists() || spec.socketFile.delete()) { "Cannot remove a stale runtime socket" }
        rotateLog()
        secureWrite(sessionFile, spec.sessionKey)
        secureWrite(configFile, runtimeConfig(spec).toString().toByteArray(Charsets.UTF_8))
        val plan = AgentQemuLaunchPlanBuilder.build(
            spec = spec,
            sessionFile = sessionFile,
            configFile = configFile,
            logFile = logFile,
            memoryMegabytes = runtimeMemoryMegabytes(),
            cpuCount = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        )
        val child = try {
            ProcessBuilder(plan.command).apply {
                environment().clear()
                environment().putAll(plan.environment)
                redirectInput(ProcessBuilder.Redirect.PIPE)
                redirectOutput(ProcessBuilder.Redirect.appendTo(plan.logFile))
                redirectErrorStream(true)
            }.start()
        } catch (error: Throwable) {
            clearEphemeralFiles()
            throw error
        }
        runCatching { child.outputStream.close() }
        process = child
        activeSocketFile = spec.socketFile
        startedAtMillis = System.currentTimeMillis()
        lastExitCode = null
        monitor(child)
    }

    @Synchronized
    override fun stop() {
        val current = process
        process = null
        if (current != null && current.isAlive) {
            current.destroy()
            if (!runCatching { current.waitFor(GRACEFUL_STOP_MILLIS, TimeUnit.MILLISECONDS) }.getOrDefault(false)) {
                current.destroyForcibly()
                runCatching { current.waitFor(FORCED_STOP_MILLIS, TimeUnit.MILLISECONDS) }
            }
        }
        lastExitCode = current?.let { runCatching(it::exitValue).getOrNull() } ?: lastExitCode
        activeSocketFile?.delete()
        activeSocketFile = null
        clearEphemeralFiles()
    }

    private fun validate(spec: AgentRuntimeEngineLaunchSpec) {
        check(spec.engineFile.isFile && spec.engineFile.canExecute()) { "Install the SignalASI QEMU engine" }
        check(spec.baseImageFile.isFile && spec.baseImageFile.canRead()) { "The linux-base image is unavailable" }
        check(spec.workspacesDirectory.isDirectory && spec.workspacesDirectory.canWrite()) {
            "Runtime workspace storage is unavailable"
        }
        check(spec.sessionKey.size >= MIN_SESSION_KEY_BYTES) { "Runtime session key is too short" }
        spec.packAttachments.forEach { pack ->
            check(pack.packId.matches(PACK_ID_PATTERN) && pack.version.isNotBlank()) { "Runtime pack metadata is invalid" }
            check(pack.imageFile.isFile && pack.imageFile.canRead()) { "Runtime pack image is unavailable: ${pack.packId}" }
        }
    }

    private fun runtimeConfig(spec: AgentRuntimeEngineLaunchSpec): JSONObject = JSONObject()
        .put("format_version", 1)
        .put("guest_api_version", AgentRuntimeGuestProtocol.VERSION)
        .put("host_epoch_millis", System.currentTimeMillis())
        .put("architecture", spec.architecture)
        .put("api_channel", "org.signalasi.runtime")
        .put("workspace_mount_tag", "signalasi_workspaces")
        .put("workspace_uid", android.os.Process.myUid())
        .put("workspace_gid", android.os.Process.myUid())
        .put("network_mode", "host_mediated")
        .put("packs", JSONArray().apply {
            spec.packAttachments.sortedBy(AgentRuntimePackAttachment::packId).forEachIndexed { index, pack ->
                put(JSONObject()
                    .put("id", pack.packId)
                    .put("version", pack.version)
                    .put("capabilities", JSONArray(pack.capabilities.sorted()))
                    .put("serial", AgentQemuLaunchPlanBuilder.packSerial(pack.packId))
                    .put("read_only", true)
                    .put("device_index", index))
            }
        })

    private fun runtimeMemoryMegabytes(): Int {
        val memoryClass = appContext.getSystemService(ActivityManager::class.java)?.memoryClass ?: 2_048
        return (memoryClass / 4).coerceIn(384, 1_536)
    }

    private fun monitor(child: Process) {
        Thread({
            val exitCode = runCatching(child::waitFor).getOrNull()
            synchronized(this) {
                if (process === child) {
                    process = null
                    lastExitCode = exitCode
                    activeSocketFile?.delete()
                    activeSocketFile = null
                    clearEphemeralFiles()
                }
            }
        }, "signalasi-qemu-monitor").apply {
            isDaemon = true
            start()
        }
    }

    private fun rotateLog() {
        if (!logFile.isFile || logFile.length() <= MAX_LOG_BYTES) return
        val previous = File(runtimeDirectory, "qemu.log.1")
        previous.delete()
        logFile.renameTo(previous)
    }

    private fun secureWrite(target: File, bytes: ByteArray) {
        val temporary = File(target.parentFile, ".${target.name}.tmp")
        temporary.delete()
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        Os.chmod(temporary.absolutePath, PRIVATE_FILE_MODE)
        check(!target.exists() || target.delete()) { "Cannot replace runtime bootstrap data" }
        check(temporary.renameTo(target)) { "Cannot publish runtime bootstrap data" }
        Os.chmod(target.absolutePath, PRIVATE_FILE_MODE)
    }

    private fun clearEphemeralFiles() {
        sessionFile.delete()
        configFile.delete()
    }

    companion object {
        private const val MIN_SESSION_KEY_BYTES = 32
        private const val GRACEFUL_STOP_MILLIS = 3_000L
        private const val FORCED_STOP_MILLIS = 1_000L
        private const val MAX_LOG_BYTES = 2L * 1024L * 1024L
        private const val PRIVATE_FILE_MODE = 384
        private val PACK_ID_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,79}")
    }
}

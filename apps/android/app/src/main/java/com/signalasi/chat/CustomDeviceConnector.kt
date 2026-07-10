package com.signalasi.chat

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.net.URL
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

enum class CustomDeviceTransport {
    HTTP_REST,
    MQTT,
    WEBSOCKET,
    TCP,
    UDP,
    MCP,
    SIGNALASI_AGENT,
    BLE,
    MATTER_THREAD
}

data class CustomDeviceConnector(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val transport: CustomDeviceTransport,
    val endpoint: String,
    val commandTarget: String = "",
    val username: String = "",
    val authToken: String = "",
    val risk: AgentRisk = AgentRisk.MEDIUM,
    val enabled: Boolean = true
) {
    val configured: Boolean
        get() = enabled && name.isNotBlank() && endpoint.isNotBlank()
}

data class CustomDeviceCommandResult(
    val handled: Boolean,
    val success: Boolean,
    val message: String,
    val metadata: Map<String, String> = emptyMap()
)

class CustomDeviceConnectorStore(context: Context) {
    private val preferences = AgentEncryptedPreferences(context.applicationContext, PREFS)

    fun list(): List<CustomDeviceConnector> = decode(preferences.readString(KEY_ITEMS, "[]"))

    fun find(id: String): CustomDeviceConnector? = list().firstOrNull { it.id == id }

    fun upsert(connector: CustomDeviceConnector) {
        val clean = connector.sanitized()
        val updated = (list().filterNot { it.id == clean.id } + clean).takeLast(MAX_CONNECTORS)
        preferences.writeString(KEY_ITEMS, encode(updated).toString())
    }

    fun delete(id: String): Boolean {
        val existing = list()
        val updated = existing.filterNot { it.id == id }
        if (updated.size == existing.size) return false
        preferences.writeString(KEY_ITEMS, encode(updated).toString())
        return true
    }

    fun clear() = preferences.clear()

    fun exportJson(): JSONArray = encode(list())

    fun restoreJson(array: JSONArray) {
        val restored = buildList {
            for (index in 0 until array.length()) {
                decodeItem(array.optJSONObject(index))?.let { add(it) }
            }
        }.distinctBy { it.id }.takeLast(MAX_CONNECTORS)
        preferences.writeString(KEY_ITEMS, encode(restored).toString())
    }

    private fun CustomDeviceConnector.sanitized(): CustomDeviceConnector = copy(
        id = id.trim().take(80).ifBlank { UUID.randomUUID().toString() },
        name = name.trim().take(100),
        endpoint = endpoint.trim().take(1_000),
        commandTarget = commandTarget.trim().take(300),
        username = username.trim().take(200),
        authToken = authToken.trim().take(2_000)
    )

    private fun encode(items: List<CustomDeviceConnector>): JSONArray = JSONArray().apply {
        items.forEach { item ->
            put(
                JSONObject()
                    .put("id", item.id)
                    .put("name", item.name)
                    .put("transport", item.transport.name)
                    .put("endpoint", item.endpoint)
                    .put("command_target", item.commandTarget)
                    .put("username", item.username)
                    .put("auth_token", item.authToken)
                    .put("risk", item.risk.name)
                    .put("enabled", item.enabled)
            )
        }
    }

    private fun decode(raw: String): List<CustomDeviceConnector> {
        val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return buildList {
            for (index in 0 until array.length()) {
                decodeItem(array.optJSONObject(index))?.let { add(it) }
            }
        }.distinctBy { it.id }.takeLast(MAX_CONNECTORS)
    }

    private fun decodeItem(json: JSONObject?): CustomDeviceConnector? {
        json ?: return null
        val name = json.optString("name").trim().take(100)
        if (name.isBlank()) return null
        return CustomDeviceConnector(
            id = json.optString("id").trim().take(80).ifBlank { UUID.randomUUID().toString() },
            name = name,
            transport = enumOrDefault(json.optString("transport"), CustomDeviceTransport.HTTP_REST),
            endpoint = json.optString("endpoint").trim().take(1_000),
            commandTarget = json.optString("command_target").trim().take(300),
            username = json.optString("username").trim().take(200),
            authToken = json.optString("auth_token").trim().take(2_000),
            risk = enumOrDefault(json.optString("risk"), AgentRisk.MEDIUM),
            enabled = json.optBoolean("enabled", true)
        )
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(value: String, default: T): T =
        runCatching { enumValueOf<T>(value) }.getOrElse { default }

    private companion object {
        const val PREFS = "signalasi_custom_device_connectors"
        const val KEY_ITEMS = "items"
        const val MAX_CONNECTORS = 50
    }
}

object CustomDeviceConnectorClient {
    fun execute(context: Context, connector: CustomDeviceConnector, prompt: String): CustomDeviceCommandResult {
        if (!connector.configured) return result(connector, false, "Custom device connector is not configured")
        val command = prompt.trim().take(MAX_COMMAND_CHARACTERS)
        if (command.isBlank()) return result(connector, false, "Device command is empty")
        return when (connector.transport) {
            CustomDeviceTransport.HTTP_REST -> executeHttp(connector, command, mcp = false)
            CustomDeviceTransport.MCP -> executeHttp(connector, command, mcp = true)
            CustomDeviceTransport.MQTT -> executeMqtt(connector, command)
            CustomDeviceTransport.WEBSOCKET -> executeWebSocket(connector, command)
            CustomDeviceTransport.TCP -> executeTcp(connector, command)
            CustomDeviceTransport.UDP -> executeUdp(connector, command)
            CustomDeviceTransport.SIGNALASI_AGENT -> CustomDeviceCommandResult(
                handled = false,
                success = false,
                message = "SignalASI Agent transport must be routed through a verified contact"
            )
            CustomDeviceTransport.BLE -> result(connector, false, "BLE connector requires device pairing")
            CustomDeviceTransport.MATTER_THREAD -> result(connector, false, "Matter/Thread connector requires commissioning")
        }
    }

    private fun executeHttp(
        connector: CustomDeviceConnector,
        command: String,
        mcp: Boolean
    ): CustomDeviceCommandResult = runCatching {
        val body = if (mcp) {
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", UUID.randomUUID().toString())
                .put("method", "tools/call")
                .put(
                    "params",
                    JSONObject()
                        .put("name", connector.commandTarget.ifBlank { "device_control" })
                        .put("arguments", JSONObject().put("command", command))
                )
        } else {
            JSONObject()
                .put("connector_id", connector.id)
                .put("target", connector.commandTarget)
                .put("command", command)
        }
        val connection = (URL(connector.endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            if (connector.authToken.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer ${connector.authToken}")
            }
        }
        try {
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.let { BufferedReader(it.reader(Charsets.UTF_8)).use { reader -> reader.readText() } }
                .orEmpty()
            if (connection.responseCode !in 200..299) {
                error("HTTP ${connection.responseCode}: ${response.take(240)}")
            }
            result(connector, true, response.take(500).ifBlank { "Command accepted" })
        } finally {
            connection.disconnect()
        }
    }.getOrElse { result(connector, false, it.message?.take(240) ?: "HTTP device request failed") }

    private fun executeMqtt(connector: CustomDeviceConnector, command: String): CustomDeviceCommandResult = runCatching {
        val topic = connector.commandTarget.ifBlank { error("MQTT topic is missing") }
        val client = MqttAsyncClient(
            connector.endpoint,
            "signalasi-device-${UUID.randomUUID()}",
            MemoryPersistence()
        )
        try {
            val options = MqttConnectOptions().apply {
                isAutomaticReconnect = false
                isCleanSession = true
                connectionTimeout = 8
                if (connector.username.isNotBlank()) userName = connector.username
                if (connector.authToken.isNotBlank()) password = connector.authToken.toCharArray()
            }
            client.connect(options).waitForCompletion(10_000)
            client.publish(topic, MqttMessage(command.toByteArray(Charsets.UTF_8)).apply { qos = 1 })
                .waitForCompletion(10_000)
            result(connector, true, "MQTT command published to $topic")
        } finally {
            if (client.isConnected) runCatching { client.disconnect().waitForCompletion(3_000) }
            runCatching { client.close() }
        }
    }.getOrElse { result(connector, false, it.message?.take(240) ?: "MQTT device request failed") }

    private fun executeWebSocket(connector: CustomDeviceConnector, command: String): CustomDeviceCommandResult {
        val outcome = AtomicReference<CustomDeviceCommandResult>()
        val latch = CountDownLatch(1)
        val request = Request.Builder().url(connector.endpoint).apply {
            if (connector.authToken.isNotBlank()) header("Authorization", "Bearer ${connector.authToken}")
        }.build()
        val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        val payload = JSONObject()
            .put("connector_id", connector.id)
            .put("target", connector.commandTarget)
            .put("command", command)
            .toString()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!webSocket.send(payload)) {
                    outcome.compareAndSet(null, result(connector, false, "WebSocket send failed"))
                    latch.countDown()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                outcome.compareAndSet(null, result(connector, true, text.take(500).ifBlank { "Command accepted" }))
                latch.countDown()
                webSocket.close(1000, "complete")
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = onMessage(webSocket, bytes.utf8())

            override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
                outcome.compareAndSet(null, result(connector, false, error.message?.take(240) ?: "WebSocket failed"))
                latch.countDown()
            }
        })
        val completed = latch.await(12, TimeUnit.SECONDS)
        if (!completed) socket.close(1000, "timeout")
        client.dispatcher.executorService.shutdown()
        return outcome.get() ?: result(connector, completed, if (completed) "Command accepted" else "WebSocket timed out")
    }

    private fun executeTcp(connector: CustomDeviceConnector, command: String): CustomDeviceCommandResult = runCatching {
        val (host, port) = endpointHostPort(connector.endpoint, "tcp")
        Socket(host, port).use { socket ->
            socket.soTimeout = READ_TIMEOUT_MILLIS
            socket.getOutputStream().bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(command)
                writer.newLine()
                writer.flush()
            }
        }
        result(connector, true, "TCP command sent to $host:$port")
    }.getOrElse { result(connector, false, it.message?.take(240) ?: "TCP device request failed") }

    private fun executeUdp(connector: CustomDeviceConnector, command: String): CustomDeviceCommandResult = runCatching {
        val (host, port) = endpointHostPort(connector.endpoint, "udp")
        val payload = command.toByteArray(Charsets.UTF_8)
        DatagramSocket().use { socket ->
            socket.send(DatagramPacket(payload, payload.size, InetAddress.getByName(host), port))
        }
        result(connector, true, "UDP command sent to $host:$port")
    }.getOrElse { result(connector, false, it.message?.take(240) ?: "UDP device request failed") }

    private fun endpointHostPort(endpoint: String, scheme: String): Pair<String, Int> {
        val normalized = if ("://" in endpoint) endpoint else "$scheme://$endpoint"
        val uri = URI(normalized)
        val host = uri.host?.takeIf { it.isNotBlank() } ?: error("Device host is missing")
        val port = uri.port.takeIf { it in 1..65535 } ?: error("Device port is missing")
        return host to port
    }

    private fun result(
        connector: CustomDeviceConnector,
        success: Boolean,
        message: String
    ): CustomDeviceCommandResult = CustomDeviceCommandResult(
        handled = true,
        success = success,
        message = message,
        metadata = mapOf(
            "connector_id" to connector.id,
            "connector_name" to connector.name,
            "transport" to connector.transport.name
        )
    )

    private const val MAX_COMMAND_CHARACTERS = 8_000
    private const val CONNECT_TIMEOUT_MILLIS = 8_000
    private const val READ_TIMEOUT_MILLIS = 12_000
}

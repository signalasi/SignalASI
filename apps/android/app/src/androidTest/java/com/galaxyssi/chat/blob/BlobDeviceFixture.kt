package com.galaxyssi.chat.blob

import android.os.Debug
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

internal class BlobDeviceFixture(root: File) : AutoCloseable {
    val value = JSONObject(File(root, "fixture.json").readText())
    val origin = BlobHttp.normalizeOrigin(value.getString("origin"))
    val token = value.getString("token")
    val client: OkHttpClient
    init {
        val ca = File(root, "test-cert.pem").inputStream().use {
            CertificateFactory.getInstance("X.509").generateCertificate(it)
        }
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null); setCertificateEntry("isolated-relay", ca)
        }
        val manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            .trustManagers.filterIsInstance<X509TrustManager>().single()
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
        // This trust manager belongs only to this test client; hostname validation remains enabled.
        client = OkHttpClient.Builder().sslSocketFactory(tls.socketFactory, manager)
            .followRedirects(false).callTimeout(15, TimeUnit.SECONDS).build()
    }

    fun control(path: String, body: JSONObject? = null): JSONObject {
        require(path in setOf("offer", "status", "events"))
        val bytes = body?.toString()?.toByteArray(Charsets.UTF_8)
        try {
            val request = Request.Builder().url("$origin/__test/$path").header("Authorization", "Bearer $token")
                .apply { if (bytes != null) post(bytes.toRequestBody("application/json".toMediaType())) }.build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Test control failed: ${response.code}" }
                val data = response.body!!.byteStream().use { BlobStaging.readBounded(it, 128 * 1024) }
                return try { JSONObject(data.toString(Charsets.UTF_8)) } finally { data.fill(0) }
            }
        } finally { bytes?.fill(0) }
    }

    fun event(phase: String, metrics: JSONObject = JSONObject()) {
        control("events", JSONObject().put("phase", phase).put("metrics", metrics))
    }

    override fun close() {
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}

internal class BlobDeviceMemory : AutoCloseable {
    val baselineKb = Debug.getPss()
    private val peak = AtomicLong(baselineKb)
    private val done = AtomicBoolean()
    private val sampler = Thread {
        while (!done.get()) {
            peak.accumulateAndGet(Debug.getPss(), ::maxOf)
            Thread.sleep(100)
        }
    }.apply { isDaemon = true; start() }
    fun snapshot() = JSONObject().put("baseline_pss_kib", baselineKb).put("sampled_peak_pss_kib", peak.get())
        .put("sampled_growth_kib", (peak.get() - baselineKb).coerceAtLeast(0))
    override fun close() { done.set(true); sampler.join(2_000); check(!sampler.isAlive) }
}

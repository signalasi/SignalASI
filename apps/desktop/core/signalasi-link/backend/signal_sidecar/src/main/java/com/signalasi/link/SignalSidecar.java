package com.signalasi.link;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONObject;
import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.SessionBuilder;
import org.signal.libsignal.protocol.SessionCipher;
import org.signal.libsignal.protocol.ecc.ECKeyPair;
import org.signal.libsignal.protocol.ecc.ECPublicKey;
import org.signal.libsignal.protocol.kem.KEMKeyPair;
import org.signal.libsignal.protocol.kem.KEMPublicKey;
import org.signal.libsignal.protocol.kem.KEMKeyType;
import org.signal.libsignal.protocol.message.CiphertextMessage;
import org.signal.libsignal.protocol.message.PreKeySignalMessage;
import org.signal.libsignal.protocol.message.SignalMessage;
import org.signal.libsignal.protocol.state.KyberPreKeyRecord;
import org.signal.libsignal.protocol.state.PreKeyBundle;
import org.signal.libsignal.protocol.state.PreKeyRecord;
import org.signal.libsignal.protocol.state.SignedPreKeyRecord;
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore;
import org.signal.libsignal.protocol.util.KeyHelper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.Executors;

public final class SignalSidecar {
    private static final int PORT = Integer.parseInt(
            System.getenv().getOrDefault("SIGNALASI_LINK_PORT", System.getenv().getOrDefault("HERMES_SIGNAL_PORT", "18766"))
    );
    private static final String DEVICE_NAME = "pc";
    private static final int DEVICE_ID = 1;
    private static final int PRE_KEY_ID = 1;
    private static final int SIGNED_PRE_KEY_ID = 1;
    private static final int KYBER_PRE_KEY_ID = 1;

    private final PersistentSignalProtocolStore store;
    private final PreKeyRecord preKey;
    private final SignedPreKeyRecord signedPreKey;
    private final KyberPreKeyRecord kyberPreKey;

    private SignalSidecar() throws Exception {
        Path storePath = storePath();
        this.store = new PersistentSignalProtocolStore(storePath);
        IdentityKeyPair identity = store.getIdentityKeyPair();

        if (!store.containsPreKey(PRE_KEY_ID)) {
            this.store.storePreKey(PRE_KEY_ID, new PreKeyRecord(PRE_KEY_ID, ECKeyPair.generate()));
        }
        this.preKey = store.loadPreKey(PRE_KEY_ID);

        if (!store.containsSignedPreKey(SIGNED_PRE_KEY_ID)) {
            ECKeyPair signedPreKeyPair = ECKeyPair.generate();
            byte[] signedPreKeySignature = identity.getPrivateKey().calculateSignature(signedPreKeyPair.getPublicKey().serialize());
            this.store.storeSignedPreKey(SIGNED_PRE_KEY_ID, new SignedPreKeyRecord(SIGNED_PRE_KEY_ID, Instant.now().toEpochMilli(), signedPreKeyPair, signedPreKeySignature));
        }
        this.signedPreKey = store.loadSignedPreKey(SIGNED_PRE_KEY_ID);

        if (!store.containsKyberPreKey(KYBER_PRE_KEY_ID)) {
            KEMKeyPair kyberPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024);
            byte[] kyberSignature = identity.getPrivateKey().calculateSignature(kyberPair.getPublicKey().serialize());
            this.store.storeKyberPreKey(KYBER_PRE_KEY_ID, new KyberPreKeyRecord(KYBER_PRE_KEY_ID, Instant.now().toEpochMilli(), kyberPair, kyberSignature));
        }
        this.kyberPreKey = store.loadKyberPreKey(KYBER_PRE_KEY_ID);
    }

    public static void main(String[] args) throws Exception {
        SignalSidecar app = new SignalSidecar();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        server.createContext("/health", app::health);
        server.createContext("/bundle", app::bundle);
        server.createContext("/decrypt", app::decrypt);
        server.createContext("/encrypt", app::encrypt);
        server.createContext("/replace-peer", app::replacePeer);
        server.createContext("/remove-peer", app::removePeer);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("SignalASI Link sidecar listening on 127.0.0.1:" + PORT);
    }

    private static Path storePath() throws IOException {
        Path storePath = Path.of(System.getProperty("user.home"), ".signalasi", "signal_pc_store.json");
        Files.createDirectories(storePath.getParent());
        return storePath;
    }

    private void health(HttpExchange exchange) throws IOException {
        writeJson(exchange, new JSONObject()
                .put("ok", true)
                .put("protocol", "signalasi-link")
                .put("apiVersion", 1)
                .put("device", DEVICE_NAME)
                .put("port", PORT)
                .put("removePeer", true));
    }

    private void bundle(HttpExchange exchange) throws IOException {
        try {
            writeJson(exchange, currentBundleJson());
        } catch (Exception exc) {
            writeError(exchange, exc);
        }
    }

    private void decrypt(HttpExchange exchange) throws IOException {
        try {
            JSONObject req = readJson(exchange);
            SignalProtocolAddress address = address(req);
            SessionCipher cipher = new SessionCipher(store, address);
            byte[] body = b64d(req.getString("body"));
            String type = req.optString("type", "prekey");
            byte[] plaintext;
            if ("prekey".equals(type) || req.optInt("messageType", -1) == CiphertextMessage.PREKEY_TYPE) {
                plaintext = cipher.decrypt(new PreKeySignalMessage(body));
            } else {
                plaintext = cipher.decrypt(new SignalMessage(body));
            }
            writeJson(exchange, new JSONObject()
                    .put("ok", true)
                    .put("plaintext", new String(plaintext, StandardCharsets.UTF_8)));
        } catch (Exception exc) {
            writeError(exchange, exc);
        }
    }

    private void encrypt(HttpExchange exchange) throws IOException {
        try {
            JSONObject req = readJson(exchange);
            SignalProtocolAddress address = address(req);
            SessionCipher cipher = new SessionCipher(store, address);
            CiphertextMessage message = cipher.encrypt(req.getString("plaintext").getBytes(StandardCharsets.UTF_8));
            writeJson(exchange, new JSONObject()
                    .put("ok", true)
                    .put("type", message.getType() == CiphertextMessage.PREKEY_TYPE ? "prekey" : "signal")
                    .put("messageType", message.getType())
                    .put("body", b64e(message.serialize())));
        } catch (Exception exc) {
            writeError(exchange, exc);
        }
    }

    private void replacePeer(HttpExchange exchange) throws IOException {
        try {
            JSONObject req = readJson(exchange);
            String remoteName = req.optString("remoteName", "android");
            int deviceId = req.optInt("remoteDeviceId", 1);
            JSONObject bundleJson = req.getJSONObject("bundle");

            store.deleteAllSessions(remoteName);
            store.deleteSenderKeys(remoteName);
            store.deleteIdentity(remoteName, deviceId);

            PreKeyBundle bundle = new PreKeyBundle(
                    bundleJson.getInt("registrationId"),
                    bundleJson.optInt("deviceId", deviceId),
                    bundleJson.getInt("preKeyId"),
                    new ECPublicKey(b64d(bundleJson.getString("preKey"))),
                    bundleJson.getInt("signedPreKeyId"),
                    new ECPublicKey(b64d(bundleJson.getString("signedPreKey"))),
                    b64d(bundleJson.getString("signedPreKeySignature")),
                    new IdentityKey(b64d(bundleJson.getString("identityKey"))),
                    bundleJson.getInt("kyberPreKeyId"),
                    new KEMPublicKey(b64d(bundleJson.getString("kyberPreKey"))),
                    b64d(bundleJson.getString("kyberPreKeySignature"))
            );
            new SessionBuilder(store, new SignalProtocolAddress(remoteName, deviceId)).process(bundle);
            writeJson(exchange, new JSONObject().put("ok", true).put("remoteName", remoteName).put("remoteDeviceId", deviceId));
        } catch (Exception exc) {
            writeError(exchange, exc);
        }
    }

    private void removePeer(HttpExchange exchange) throws IOException {
        try {
            JSONObject req = readJson(exchange);
            String remoteName = req.getString("remoteName");
            int deviceId = req.optInt("remoteDeviceId", 1);
            store.deleteAllSessions(remoteName);
            store.deleteSenderKeys(remoteName);
            store.deleteIdentity(remoteName, deviceId);
            writeJson(exchange, new JSONObject().put("ok", true).put("remoteName", remoteName));
        } catch (Exception exc) {
            writeError(exchange, exc);
        }
    }

    private JSONObject currentBundleJson() throws Exception {
        IdentityKeyPair identity = store.getIdentityKeyPair();
        PreKeyBundle bundle = new PreKeyBundle(
                store.getLocalRegistrationId(),
                DEVICE_ID,
                preKey.getId(),
                preKey.getKeyPair().getPublicKey(),
                signedPreKey.getId(),
                signedPreKey.getKeyPair().getPublicKey(),
                signedPreKey.getSignature(),
                identity.getPublicKey(),
                kyberPreKey.getId(),
                kyberPreKey.getKeyPair().getPublicKey(),
                kyberPreKey.getSignature()
        );
        return new JSONObject()
                .put("version", 1)
                .put("scheme", "signal")
                .put("name", DEVICE_NAME)
                .put("deviceId", DEVICE_ID)
                .put("registrationId", bundle.getRegistrationId())
                .put("identityKey", b64e(bundle.getIdentityKey().serialize()))
                .put("preKeyId", bundle.getPreKeyId())
                .put("preKey", b64e(bundle.getPreKey().serialize()))
                .put("signedPreKeyId", bundle.getSignedPreKeyId())
                .put("signedPreKey", b64e(bundle.getSignedPreKey().serialize()))
                .put("signedPreKeySignature", b64e(bundle.getSignedPreKeySignature()))
                .put("kyberPreKeyId", bundle.getKyberPreKeyId())
                .put("kyberPreKey", b64e(bundle.getKyberPreKey().serialize()))
                .put("kyberPreKeySignature", b64e(bundle.getKyberPreKeySignature()))
                .put("identityKeySha256", sha256Hex(identity.getPublicKey().serialize()))
                .put("identityFingerprint", identity.getPublicKey().getFingerprint());
    }

    private static SignalProtocolAddress address(JSONObject req) {
        String name = req.optString("remoteName", req.optString("name", "android"));
        int deviceId = req.optInt("remoteDeviceId", req.optInt("deviceId", 1));
        return new SignalProtocolAddress(name, deviceId);
    }

    private static JSONObject readJson(HttpExchange exchange) throws IOException {
        byte[] raw = exchange.getRequestBody().readAllBytes();
        return new JSONObject(new String(raw, StandardCharsets.UTF_8));
    }

    private static void writeJson(HttpExchange exchange, JSONObject json) throws IOException {
        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void writeError(HttpExchange exchange, Exception exc) throws IOException {
        byte[] bytes = new JSONObject()
                .put("ok", false)
                .put("error", exc.getClass().getSimpleName())
                .put("message", exc.getMessage() == null ? "" : exc.getMessage())
                .toString()
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(500, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String b64e(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }

    private static byte[] b64d(String value) {
        return Base64.getDecoder().decode(value);
    }

    private static String sha256Hex(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", b & 0xff));
        }
        return hex.toString();
    }
}

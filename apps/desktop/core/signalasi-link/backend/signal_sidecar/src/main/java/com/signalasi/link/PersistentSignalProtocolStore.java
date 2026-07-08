package com.signalasi.link;

import org.json.JSONObject;
import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.InvalidKeyIdException;
import org.signal.libsignal.protocol.InvalidMessageException;
import org.signal.libsignal.protocol.NoSessionException;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.ecc.ECPublicKey;
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord;
import org.signal.libsignal.protocol.state.IdentityKeyStore;
import org.signal.libsignal.protocol.state.KyberPreKeyRecord;
import org.signal.libsignal.protocol.state.PreKeyRecord;
import org.signal.libsignal.protocol.state.SessionRecord;
import org.signal.libsignal.protocol.state.SignalProtocolStore;
import org.signal.libsignal.protocol.state.SignedPreKeyRecord;
import org.signal.libsignal.protocol.util.KeyHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class PersistentSignalProtocolStore implements SignalProtocolStore {
    private final Path path;
    private final IdentityKeyPair identityKeyPair;
    private final int registrationId;
    private final Map<String, String> identities = new HashMap<>();
    private final Map<Integer, String> preKeys = new HashMap<>();
    private final Map<Integer, String> signedPreKeys = new HashMap<>();
    private final Map<Integer, String> kyberPreKeys = new HashMap<>();
    private final Map<String, String> sessions = new HashMap<>();
    private final Map<String, String> senderKeys = new HashMap<>();

    PersistentSignalProtocolStore(Path path) throws Exception {
        this.path = path;
        Files.createDirectories(path.getParent());
        JSONObject root = Files.exists(path)
                ? new JSONObject(Files.readString(path, StandardCharsets.UTF_8))
                : new JSONObject();
        if (root.has("identityKeyPair")) {
            identityKeyPair = new IdentityKeyPair(b64d(root.getString("identityKeyPair")));
            registrationId = root.getInt("registrationId");
        } else {
            identityKeyPair = IdentityKeyPair.generate();
            registrationId = KeyHelper.generateRegistrationId(false);
        }
        loadStringMap(root.optJSONObject("identities"), identities);
        loadIntMap(root.optJSONObject("preKeys"), preKeys);
        loadIntMap(root.optJSONObject("signedPreKeys"), signedPreKeys);
        loadIntMap(root.optJSONObject("kyberPreKeys"), kyberPreKeys);
        loadStringMap(root.optJSONObject("sessions"), sessions);
        loadStringMap(root.optJSONObject("senderKeys"), senderKeys);
        save();
    }

    @Override
    public synchronized IdentityKeyPair getIdentityKeyPair() {
        return identityKeyPair;
    }

    @Override
    public synchronized int getLocalRegistrationId() {
        return registrationId;
    }

    @Override
    public synchronized IdentityChange saveIdentity(SignalProtocolAddress address, IdentityKey identityKey) {
        String key = addressKey(address);
        String encoded = b64e(identityKey.serialize());
        String existing = identities.put(key, encoded);
        saveQuietly();
        return existing != null && !existing.equals(encoded)
                ? IdentityChange.REPLACED_EXISTING
                : IdentityChange.NEW_OR_UNCHANGED;
    }

    @Override
    public synchronized boolean isTrustedIdentity(
            SignalProtocolAddress address,
            IdentityKey identityKey,
            IdentityKeyStore.Direction direction
    ) {
        String existing = identities.get(addressKey(address));
        return existing == null || existing.equals(b64e(identityKey.serialize()));
    }

    @Override
    public synchronized IdentityKey getIdentity(SignalProtocolAddress address) {
        String encoded = identities.get(addressKey(address));
        if (encoded == null) return null;
        try {
            return new IdentityKey(b64d(encoded));
        } catch (Exception exc) {
            return null;
        }
    }

    @Override
    public synchronized PreKeyRecord loadPreKey(int id) throws InvalidKeyIdException {
        String encoded = preKeys.get(id);
        if (encoded == null) throw new InvalidKeyIdException("No pre-key: " + id);
        try {
            return new PreKeyRecord(b64d(encoded));
        } catch (InvalidMessageException exc) {
            throw new InvalidKeyIdException(exc);
        }
    }

    @Override
    public synchronized void storePreKey(int id, PreKeyRecord record) {
        preKeys.put(id, b64e(record.serialize()));
        saveQuietly();
    }

    @Override
    public synchronized boolean containsPreKey(int id) {
        return preKeys.containsKey(id);
    }

    @Override
    public synchronized void removePreKey(int id) {
        // The MQTT retained PC bundle is this app's lightweight pre-key server.
        // Keep the advertised pre-key stable until we implement explicit bundle rotation.
        saveQuietly();
    }

    @Override
    public synchronized SessionRecord loadSession(SignalProtocolAddress address) {
        String encoded = sessions.get(addressKey(address));
        if (encoded == null) return new SessionRecord();
        try {
            return new SessionRecord(b64d(encoded));
        } catch (InvalidMessageException exc) {
            return new SessionRecord();
        }
    }

    @Override
    public synchronized List<SessionRecord> loadExistingSessions(List<SignalProtocolAddress> addresses) throws NoSessionException {
        List<SessionRecord> result = new ArrayList<>();
        for (SignalProtocolAddress address : addresses) {
            if (!containsSession(address)) {
                throw new NoSessionException(address, "No session");
            }
            result.add(loadSession(address));
        }
        return result;
    }

    @Override
    public synchronized List<Integer> getSubDeviceSessions(String name) {
        List<Integer> result = new ArrayList<>();
        String prefix = name + "|";
        for (String key : sessions.keySet()) {
            if (key.startsWith(prefix)) {
                result.add(Integer.parseInt(key.substring(prefix.length())));
            }
        }
        return result;
    }

    @Override
    public synchronized void storeSession(SignalProtocolAddress address, SessionRecord record) {
        sessions.put(addressKey(address), b64e(record.serialize()));
        saveQuietly();
    }

    @Override
    public synchronized boolean containsSession(SignalProtocolAddress address) {
        return sessions.containsKey(addressKey(address));
    }

    @Override
    public synchronized void deleteSession(SignalProtocolAddress address) {
        sessions.remove(addressKey(address));
        saveQuietly();
    }

    @Override
    public synchronized void deleteAllSessions(String name) {
        sessions.keySet().removeIf(key -> key.startsWith(name + "|"));
        saveQuietly();
    }

    public synchronized void deleteIdentity(String name, int deviceId) {
        identities.remove(name + "|" + deviceId);
        saveQuietly();
    }

    public synchronized void deleteSenderKeys(String name) {
        senderKeys.keySet().removeIf(key -> key.startsWith(name + "|"));
        saveQuietly();
    }

    @Override
    public synchronized SignedPreKeyRecord loadSignedPreKey(int id) throws InvalidKeyIdException {
        String encoded = signedPreKeys.get(id);
        if (encoded == null) throw new InvalidKeyIdException("No signed pre-key: " + id);
        try {
            return new SignedPreKeyRecord(b64d(encoded));
        } catch (InvalidMessageException exc) {
            throw new InvalidKeyIdException(exc);
        }
    }

    @Override
    public synchronized List<SignedPreKeyRecord> loadSignedPreKeys() {
        List<SignedPreKeyRecord> result = new ArrayList<>();
        for (String encoded : signedPreKeys.values()) {
            try {
                result.add(new SignedPreKeyRecord(b64d(encoded)));
            } catch (InvalidMessageException ignored) {
            }
        }
        return result;
    }

    @Override
    public synchronized void storeSignedPreKey(int id, SignedPreKeyRecord record) {
        signedPreKeys.put(id, b64e(record.serialize()));
        saveQuietly();
    }

    @Override
    public synchronized boolean containsSignedPreKey(int id) {
        return signedPreKeys.containsKey(id);
    }

    @Override
    public synchronized void removeSignedPreKey(int id) {
        signedPreKeys.remove(id);
        saveQuietly();
    }

    @Override
    public synchronized void storeSenderKey(SignalProtocolAddress address, UUID distributionId, SenderKeyRecord record) {
        senderKeys.put(addressKey(address) + "|" + distributionId, b64e(record.serialize()));
        saveQuietly();
    }

    @Override
    public synchronized SenderKeyRecord loadSenderKey(SignalProtocolAddress address, UUID distributionId) {
        String encoded = senderKeys.get(addressKey(address) + "|" + distributionId);
        try {
            return encoded == null ? null : new SenderKeyRecord(b64d(encoded));
        } catch (InvalidMessageException exc) {
            return null;
        }
    }

    @Override
    public synchronized KyberPreKeyRecord loadKyberPreKey(int id) throws InvalidKeyIdException {
        String encoded = kyberPreKeys.get(id);
        if (encoded == null) throw new InvalidKeyIdException("No kyber pre-key: " + id);
        try {
            return new KyberPreKeyRecord(b64d(encoded));
        } catch (InvalidMessageException exc) {
            throw new InvalidKeyIdException(exc);
        }
    }

    @Override
    public synchronized List<KyberPreKeyRecord> loadKyberPreKeys() {
        List<KyberPreKeyRecord> result = new ArrayList<>();
        for (String encoded : kyberPreKeys.values()) {
            try {
                result.add(new KyberPreKeyRecord(b64d(encoded)));
            } catch (InvalidMessageException ignored) {
            }
        }
        return result;
    }

    @Override
    public synchronized void storeKyberPreKey(int id, KyberPreKeyRecord record) {
        kyberPreKeys.put(id, b64e(record.serialize()));
        saveQuietly();
    }

    @Override
    public synchronized boolean containsKyberPreKey(int id) {
        return kyberPreKeys.containsKey(id);
    }

    @Override
    public synchronized void markKyberPreKeyUsed(int id, int signedPreKeyId, ECPublicKey baseKey) {
        saveQuietly();
    }

    private synchronized void saveQuietly() {
        try {
            save();
        } catch (Exception exc) {
            throw new IllegalStateException("Failed to save Signal store", exc);
        }
    }

    private synchronized void save() throws IOException {
        JSONObject root = new JSONObject()
                .put("version", 1)
                .put("identityKeyPair", b64e(identityKeyPair.serialize()))
                .put("registrationId", registrationId)
                .put("identities", stringMapJson(identities))
                .put("preKeys", intMapJson(preKeys))
                .put("signedPreKeys", intMapJson(signedPreKeys))
                .put("kyberPreKeys", intMapJson(kyberPreKeys))
                .put("sessions", stringMapJson(sessions))
                .put("senderKeys", stringMapJson(senderKeys));
        Files.writeString(path, root.toString(2), StandardCharsets.UTF_8);
    }

    private static String addressKey(SignalProtocolAddress address) {
        return address.getName() + "|" + address.getDeviceId();
    }

    private static JSONObject stringMapJson(Map<String, String> map) {
        JSONObject json = new JSONObject();
        map.forEach(json::put);
        return json;
    }

    private static JSONObject intMapJson(Map<Integer, String> map) {
        JSONObject json = new JSONObject();
        map.forEach((key, value) -> json.put(Integer.toString(key), value));
        return json;
    }

    private static void loadStringMap(JSONObject json, Map<String, String> target) {
        if (json == null) return;
        for (String key : json.keySet()) {
            target.put(key, json.getString(key));
        }
    }

    private static void loadIntMap(JSONObject json, Map<Integer, String> target) {
        if (json == null) return;
        for (String key : json.keySet()) {
            target.put(Integer.parseInt(key), json.getString(key));
        }
    }

    private static String b64e(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }

    private static byte[] b64d(String value) {
        return Base64.getDecoder().decode(value);
    }
}

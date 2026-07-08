package com.signalasi.link;

import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.SessionBuilder;
import org.signal.libsignal.protocol.SessionCipher;
import org.signal.libsignal.protocol.ecc.ECKeyPair;
import org.signal.libsignal.protocol.kem.KEMKeyPair;
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

import java.nio.charset.StandardCharsets;
import java.time.Instant;

public final class SignalRoundTripProbe {
    public static void main(String[] args) throws Exception {
        SignalProtocolAddress androidAddress = new SignalProtocolAddress("android", 1);
        SignalProtocolAddress pcAddress = new SignalProtocolAddress("pc", 1);

        InMemorySignalProtocolStore pcStore = newStore();
        PreKeyBundle pcBundle = publishBundle(pcStore);

        InMemorySignalProtocolStore androidStore = newStore();
        new SessionBuilder(androidStore, pcAddress).process(pcBundle);

        byte[] plaintext = "{\"type\":\"text\",\"content\":\"signal_probe_plaintext\"}".getBytes(StandardCharsets.UTF_8);
        CiphertextMessage androidToPc = new SessionCipher(androidStore, pcAddress).encrypt(plaintext);
        byte[] decryptedAtPc = new SessionCipher(pcStore, androidAddress).decrypt(new PreKeySignalMessage(androidToPc.serialize()));
        if (!new String(decryptedAtPc, StandardCharsets.UTF_8).contains("signal_probe_plaintext")) {
            throw new IllegalStateException("PC decrypt failed");
        }

        byte[] reply = "{\"type\":\"text\",\"content\":\"signal_probe_reply\"}".getBytes(StandardCharsets.UTF_8);
        CiphertextMessage pcToAndroid = new SessionCipher(pcStore, androidAddress).encrypt(reply);
        byte[] decryptedAtAndroid = new SessionCipher(androidStore, pcAddress).decrypt(new SignalMessage(pcToAndroid.serialize()));
        String decodedReply = new String(decryptedAtAndroid, StandardCharsets.UTF_8);
        if (!decodedReply.contains("signal_probe_reply")) {
            throw new IllegalStateException("Android decrypt failed");
        }

        System.out.println("ROUND_TRIP_OK");
        System.out.println("first_message_type=" + androidToPc.getType());
        System.out.println("reply_message_type=" + pcToAndroid.getType());
        System.out.println("plaintext_not_in_ciphertext=" + !new String(androidToPc.serialize(), StandardCharsets.ISO_8859_1).contains("signal_probe_plaintext"));
    }

    private static InMemorySignalProtocolStore newStore() {
        return new InMemorySignalProtocolStore(IdentityKeyPair.generate(), KeyHelper.generateRegistrationId(false));
    }

    private static PreKeyBundle publishBundle(InMemorySignalProtocolStore store) throws Exception {
        int preKeyId = 1;
        int signedPreKeyId = 1;
        int kyberPreKeyId = 1;

        ECKeyPair preKeyPair = ECKeyPair.generate();
        store.storePreKey(preKeyId, new PreKeyRecord(preKeyId, preKeyPair));

        ECKeyPair signedPreKeyPair = ECKeyPair.generate();
        byte[] signedPreKeySignature = store.getIdentityKeyPair().getPrivateKey().calculateSignature(signedPreKeyPair.getPublicKey().serialize());
        SignedPreKeyRecord signedPreKey = new SignedPreKeyRecord(signedPreKeyId, Instant.now().toEpochMilli(), signedPreKeyPair, signedPreKeySignature);
        store.storeSignedPreKey(signedPreKeyId, signedPreKey);

        KEMKeyPair kyberPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024);
        byte[] kyberSignature = store.getIdentityKeyPair().getPrivateKey().calculateSignature(kyberPair.getPublicKey().serialize());
        KyberPreKeyRecord kyberPreKey = new KyberPreKeyRecord(kyberPreKeyId, Instant.now().toEpochMilli(), kyberPair, kyberSignature);
        store.storeKyberPreKey(kyberPreKeyId, kyberPreKey);

        return new PreKeyBundle(
                store.getLocalRegistrationId(),
                1,
                preKeyId,
                preKeyPair.getPublicKey(),
                signedPreKeyId,
                signedPreKeyPair.getPublicKey(),
                signedPreKeySignature,
                store.getIdentityKeyPair().getPublicKey(),
                kyberPreKeyId,
                kyberPair.getPublicKey(),
                kyberSignature
        );
    }
}

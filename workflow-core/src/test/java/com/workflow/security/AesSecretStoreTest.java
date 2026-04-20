package com.workflow.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class AesSecretStoreTest {

    private AesSecretStore store;

    @BeforeEach
    void setUp() {
        store = new AesSecretStore();
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) i;
        ReflectionTestUtils.setField(store, "configuredKeyBase64", Base64.getEncoder().encodeToString(key));
        store.init();
    }

    @Test
    void encryptDecryptRoundTrip() {
        String plaintext = "glpat-xxxxxxxxxxxxxxxxxxxx";
        String cipher = store.encrypt(plaintext);
        assertNotNull(cipher);
        assertTrue(cipher.startsWith("enc:v1:"));
        assertNotEquals(plaintext, cipher);
        assertEquals(plaintext, store.decrypt(cipher));
    }

    @Test
    void eachEncryptionUsesFreshIv() {
        String plaintext = "same-token";
        String a = store.encrypt(plaintext);
        String b = store.encrypt(plaintext);
        assertNotEquals(a, b, "Identical plaintexts must produce different ciphertexts (random IV)");
        assertEquals(plaintext, store.decrypt(a));
        assertEquals(plaintext, store.decrypt(b));
    }

    @Test
    void legacyPlaintextPassesThroughDecrypt() {
        // Rows written before encryption was enabled have no envelope — must be returned verbatim.
        String legacyPlaintext = "legacy-unencrypted-token";
        assertEquals(legacyPlaintext, store.decrypt(legacyPlaintext));
    }

    @Test
    void tamperedCiphertextIsRejected() {
        String cipher = store.encrypt("secret");
        // Flip the last base64 character — GCM auth tag check must fail.
        String tampered = cipher.substring(0, cipher.length() - 2) + (cipher.endsWith("A=") ? "B=" : "A=");
        assertThrows(IllegalStateException.class, () -> store.decrypt(tampered));
    }

    @Test
    void nullIsReturnedAsNull() {
        assertNull(store.encrypt(null));
        assertNull(store.decrypt(null));
    }

    @Test
    void unicodePlaintextSurvivesRoundTrip() {
        String plaintext = "токен-с-юникодом-🔐";
        assertEquals(plaintext, store.decrypt(store.encrypt(plaintext)));
    }

    @Test
    void wrongKeyLengthFailsInit() {
        AesSecretStore bad = new AesSecretStore();
        ReflectionTestUtils.setField(bad, "configuredKeyBase64",
            Base64.getEncoder().encodeToString(new byte[16]));  // 128 bits, wrong
        assertThrows(IllegalStateException.class, bad::init);
    }
}

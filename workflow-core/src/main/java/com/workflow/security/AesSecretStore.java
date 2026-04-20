package com.workflow.security;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM implementation of {@link SecretStore}.
 *
 * <p>Envelope format for encrypted values: {@code enc:v1:<base64(iv || ciphertext || tag)>}.
 * The {@code enc:v1:} prefix identifies the scheme so legacy plaintext rows (written before
 * encryption was enabled) pass through {@link #decrypt} unchanged and get lazily re-encrypted
 * on the next write.
 *
 * <p>The 256-bit key is loaded from {@code workflow.encryption.key} (Base64-encoded). In the
 * absence of a configured key the store falls back to a static development key and logs a
 * loud warning — this keeps local installs working without config while making production
 * misconfiguration impossible to miss.
 */
@Component
public class AesSecretStore implements SecretStore {

    private static final Logger log = LoggerFactory.getLogger(AesSecretStore.class);
    private static final String ENVELOPE_PREFIX = "enc:v1:";
    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int KEY_LENGTH_BYTES = 32;
    private static final String DEV_KEY_BASE64 = "ZGV2LWtleS0zMi1ieXRlcy1ub3QtZm9yLXByb2R1Y3Rpb24h";

    @Value("${workflow.encryption.key:}")
    private String configuredKeyBase64;

    private SecretKey secretKey;
    private final SecureRandom random = new SecureRandom();

    @PostConstruct
    void init() {
        byte[] keyBytes;
        if (configuredKeyBase64 == null || configuredKeyBase64.isBlank()) {
            log.warn("!!! workflow.encryption.key is not set. Falling back to a DEVELOPMENT key. "
                + "This is UNSAFE for production — set WORKFLOW_ENCRYPTION_KEY to a 32-byte base64-encoded key.");
            keyBytes = Base64.getDecoder().decode(DEV_KEY_BASE64);
        } else {
            try {
                keyBytes = Base64.getDecoder().decode(configuredKeyBase64);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("workflow.encryption.key is not valid Base64", e);
            }
        }
        if (keyBytes.length != KEY_LENGTH_BYTES) {
            throw new IllegalStateException(
                "workflow.encryption.key must decode to " + KEY_LENGTH_BYTES + " bytes (AES-256); got " + keyBytes.length);
        }
        this.secretKey = new SecretKeySpec(keyBytes, ALGORITHM);
    }

    @Override
    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertext.length);
            buffer.put(iv).put(ciphertext);
            return ENVELOPE_PREFIX + Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    @Override
    public String decrypt(String ciphertext) {
        if (ciphertext == null) return null;
        if (!ciphertext.startsWith(ENVELOPE_PREFIX)) {
            // Legacy plaintext — return verbatim for backward compatibility.
            return ciphertext;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(ciphertext.substring(ENVELOPE_PREFIX.length()));
            if (combined.length <= IV_LENGTH_BYTES) {
                throw new IllegalArgumentException("Ciphertext envelope is too short");
            }
            ByteBuffer buffer = ByteBuffer.wrap(combined);
            byte[] iv = new byte[IV_LENGTH_BYTES];
            buffer.get(iv);
            byte[] actualCiphertext = new byte[buffer.remaining()];
            buffer.get(actualCiphertext);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] plaintext = cipher.doFinal(actualCiphertext);
            return new String(plaintext, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Decryption failed — key rotated or ciphertext tampered?", e);
        }
    }
}

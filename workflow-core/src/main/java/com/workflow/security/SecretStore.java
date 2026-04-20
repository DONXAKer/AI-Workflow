package com.workflow.security;

/**
 * Two-way encryption of short strings (integration tokens, credentials).
 * Implementations must be authenticated so tampered ciphertext is rejected.
 */
public interface SecretStore {

    /**
     * Encrypts a plaintext value. Returns a self-describing cipher envelope that
     * downstream callers store verbatim. {@code null} returns {@code null}.
     */
    String encrypt(String plaintext);

    /**
     * Decrypts a ciphertext produced by {@link #encrypt}. For backward compatibility
     * with plaintext rows written before encryption was enabled, implementations may
     * return the input verbatim when it doesn't carry the cipher envelope marker.
     * {@code null} returns {@code null}.
     */
    String decrypt(String ciphertext);
}

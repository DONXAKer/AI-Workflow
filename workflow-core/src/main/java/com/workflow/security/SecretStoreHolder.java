package com.workflow.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Bridge between Spring DI and JPA attribute converters (which are instantiated by the
 * persistence provider, not Spring). Spring injects the {@link SecretStore} here on
 * startup; {@link EncryptedStringConverter} reads it statically.
 */
@Component
public class SecretStoreHolder {

    private static SecretStore instance;

    @Autowired
    public void setSecretStore(SecretStore store) {
        SecretStoreHolder.instance = store;
    }

    public static SecretStore get() {
        if (instance == null) {
            throw new IllegalStateException("SecretStore not initialized — Spring context must be up before JPA converters run");
        }
        return instance;
    }
}

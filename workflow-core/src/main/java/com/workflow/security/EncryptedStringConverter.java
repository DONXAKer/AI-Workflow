package com.workflow.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA converter that transparently encrypts/decrypts string column values.
 * Apply with {@code @Convert(converter = EncryptedStringConverter.class)} on fields
 * that hold secrets (tokens, credentials).
 */
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return SecretStoreHolder.get().encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return SecretStoreHolder.get().decrypt(dbData);
    }
}

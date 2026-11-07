package com.skybook.common.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA converter that encrypts a String column on write and decrypts it on read. Use it with
 * {@code @Convert(converter = EncryptedStringConverter.class)} on personal-data fields.
 *
 * <p>Spring Boot lets Hibernate obtain converters from the Spring context, so the
 * {@link FieldEncryptor} is constructor-injected; no static key holder is needed.
 */
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private final FieldEncryptor encryptor;

    public EncryptedStringConverter(FieldEncryptor encryptor) {
        this.encryptor = encryptor;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return encryptor.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return encryptor.decrypt(dbData);
    }
}

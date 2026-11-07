package com.skybook.common.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Authenticated field-level encryption (AES-256-GCM) for personal data at rest.
 *
 * <p>Ciphertext format: {@code <keyId>:<base64url(iv || ciphertext || tag)>}. A fresh random 96-bit IV is
 * used for every value, so equal plaintexts produce different ciphertexts. The key id prefix enables
 * key rotation: new values are always written with the active key, while values written with older keys
 * remain readable as long as those keys stay in the key ring.
 *
 * <p>GCM authenticates the data, so tampered or truncated ciphertext fails to decrypt instead of
 * producing garbage. Keys come from Vault in production (see ADR-0009), never from source control.
 */
public final class FieldEncryptor {

    static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final Map<String, SecretKeySpec> keys;
    private final String activeKeyId;
    private final SecureRandom random;

    public FieldEncryptor(Map<String, byte[]> keyRing, String activeKeyId) {
        this(keyRing, activeKeyId, new SecureRandom());
    }

    FieldEncryptor(Map<String, byte[]> keyRing, String activeKeyId, SecureRandom random) {
        Objects.requireNonNull(keyRing, "keyRing");
        if (!keyRing.containsKey(activeKeyId)) {
            throw new IllegalArgumentException("Active key '" + activeKeyId + "' is not in the key ring");
        }
        keyRing.forEach((id, key) -> {
            if (id.isBlank() || id.contains(":")) {
                throw new IllegalArgumentException("Key ids must be non-blank and must not contain ':'");
            }
            if (key.length != KEY_BYTES) {
                throw new IllegalArgumentException("Key '" + id + "' must be 256 bits (32 bytes)");
            }
        });
        this.keys = keyRing.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> new SecretKeySpec(entry.getValue(), "AES")));
        this.activeKeyId = activeKeyId;
        this.random = random;
    }

    /** Builds an encryptor from base64-encoded keys, as supplied by configuration or Vault. */
    public static FieldEncryptor fromBase64(Map<String, String> base64Keys, String activeKeyId) {
        Map<String, byte[]> decoded = new HashMap<>();
        base64Keys.forEach((id, key) -> decoded.put(id, Base64.getDecoder().decode(key.trim())));
        return new FieldEncryptor(decoded, activeKeyId);
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(activeKeyId), new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array();
            return activeKeyId + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Encryption failed", ex);
        }
    }

    public String decrypt(String ciphertext) {
        if (ciphertext == null) {
            return null;
        }
        int separator = ciphertext.indexOf(':');
        if (separator <= 0) {
            throw new IllegalArgumentException("Value is not in <keyId>:<payload> format");
        }
        SecretKeySpec key = keys.get(ciphertext.substring(0, separator));
        if (key == null) {
            throw new IllegalStateException("Unknown encryption key id '%s'"
                    .formatted(ciphertext.substring(0, separator)));
        }
        try {
            byte[] payload = Base64.getUrlDecoder().decode(ciphertext.substring(separator + 1));
            if (payload.length <= IV_BYTES) {
                throw new IllegalArgumentException("Ciphertext is truncated");
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, payload, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(payload, IV_BYTES, payload.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Decryption failed: tampered ciphertext or wrong key", ex);
        }
    }

    public String activeKeyId() {
        return activeKeyId;
    }
}

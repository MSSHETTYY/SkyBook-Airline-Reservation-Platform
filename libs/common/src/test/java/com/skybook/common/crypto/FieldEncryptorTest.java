package com.skybook.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;

class FieldEncryptorTest {

    private static final String KEY_V1 = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String KEY_V2 = Base64.getEncoder().encodeToString(filled((byte) 7));

    private static byte[] filled(byte value) {
        byte[] key = new byte[32];
        Arrays.fill(key, value);
        return key;
    }

    private final FieldEncryptor v1 = FieldEncryptor.fromBase64(Map.of("v1", KEY_V1), "v1");

    @Test
    void roundTripsUnicodeText() {
        String secret = "Zoë Ångström <zoe@example.test>";

        String ciphertext = v1.encrypt(secret);

        assertThat(ciphertext).startsWith("v1:").doesNotContain("Zo", "example");
        assertThat(v1.decrypt(ciphertext)).isEqualTo(secret);
    }

    @Test
    void sameInputEncryptsDifferentlyEveryTime() {
        assertThat(v1.encrypt("Ada")).isNotEqualTo(v1.encrypt("Ada"));
    }

    @Test
    void nullsPassThrough() {
        assertThat(v1.encrypt(null)).isNull();
        assertThat(v1.decrypt(null)).isNull();
    }

    @Test
    void rotatedKeyRingReadsOldValuesAndWritesWithTheNewKey() {
        String writtenWithV1 = v1.encrypt("Lovelace");
        FieldEncryptor rotated = FieldEncryptor.fromBase64(Map.of("v1", KEY_V1, "v2", KEY_V2), "v2");

        assertThat(rotated.decrypt(writtenWithV1)).isEqualTo("Lovelace");
        assertThat(rotated.encrypt("Turing")).startsWith("v2:");
        assertThat(rotated.activeKeyId()).isEqualTo("v2");
    }

    @Test
    void tamperedCiphertextIsDetected() {
        String ciphertext = v1.encrypt("1990-12-10");
        char last = ciphertext.charAt(ciphertext.length() - 1);
        String tampered = ciphertext.substring(0, ciphertext.length() - 1) + (last == 'A' ? 'B' : 'A');

        assertThatIllegalStateException().isThrownBy(() -> v1.decrypt(tampered)).withMessageContaining("tampered");
    }

    @Test
    void wrongKeyIsDetected() {
        FieldEncryptor other = FieldEncryptor.fromBase64(Map.of("v1", KEY_V2), "v1");

        assertThatIllegalStateException().isThrownBy(() -> other.decrypt(v1.encrypt("x")));
    }

    @Test
    void rejectsMalformedInputAndUnknownKeys() {
        assertThatIllegalArgumentException().isThrownBy(() -> v1.decrypt("no-separator"));
        assertThatIllegalArgumentException().isThrownBy(() -> v1.decrypt("v1:AAAA"));
        assertThatIllegalStateException().isThrownBy(() -> v1.decrypt("v9:AAAAAAAAAAAAAAAAAAAAAAAA"))
                .withMessageContaining("v9");
    }

    @Test
    void validatesTheKeyRing() {
        assertThatIllegalArgumentException().isThrownBy(() -> FieldEncryptor.fromBase64(Map.of("v1", KEY_V1), "v2"));
        assertThatIllegalArgumentException().isThrownBy(() -> FieldEncryptor.fromBase64(
                Map.of("v1", Base64.getEncoder().encodeToString(new byte[16])), "v1"));
        assertThatIllegalArgumentException().isThrownBy(() -> FieldEncryptor.fromBase64(Map.of("a:b", KEY_V1), "a:b"));
    }

    @Test
    void converterDelegatesToTheEncryptor() {
        EncryptedStringConverter converter = new EncryptedStringConverter(v1);

        String column = converter.convertToDatabaseColumn("ada@example.test");

        assertThat(column).startsWith("v1:");
        assertThat(converter.convertToEntityAttribute(column)).isEqualTo("ada@example.test");
    }
}

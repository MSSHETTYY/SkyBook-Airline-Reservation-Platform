package com.skybook.common.crypto;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Key ring for field encryption ({@code skybook.crypto.*}), normally injected from Vault.
 *
 * @param activeKey id of the key used for new writes
 * @param keys      key id to base64-encoded 256-bit key; keep retired keys until data is re-encrypted
 */
@ConfigurationProperties("skybook.crypto")
public record EncryptionProperties(String activeKey, Map<String, String> keys) {

    public EncryptionProperties {
        keys = keys == null ? Map.of() : Map.copyOf(keys);
    }
}

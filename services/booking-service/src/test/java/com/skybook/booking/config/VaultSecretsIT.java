package com.skybook.booking.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.vault.VaultContainer;

import com.skybook.common.crypto.FieldEncryptor;

/**
 * Secrets are read from Vault at start-up (profile "vault"): the encryption key ring and the OAuth2 client
 * secret configured in Vault replace the development defaults. Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles({"test", "vault"})
class VaultSecretsIT {

    private static final String TOKEN = "it-root-token";

    @Container
    static final VaultContainer<?> VAULT = new VaultContainer<>("hashicorp/vault:2.2.1")
            .withVaultToken(TOKEN)
            .withInitCommand("kv put secret/booking-service "
                    + "skybook.crypto.active-key=vault1 "
                    + "skybook.crypto.keys.vault1=AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA= "
                    + "spring.security.oauth2.client.registration.skybook-services.client-secret=from-vault");

    @Autowired
    private FieldEncryptor encryptor;

    @Value("${spring.security.oauth2.client.registration.skybook-services.client-secret}")
    private String clientSecret;

    /** Vault is resolved through spring.config.import, before dynamic properties exist; use system properties. */
    @BeforeAll
    static void pointSpringAtVault() {
        System.setProperty("VAULT_ADDR", VAULT.getHttpHostAddress());
        System.setProperty("VAULT_TOKEN", TOKEN);
    }

    @AfterAll
    static void cleanUp() {
        System.clearProperty("VAULT_ADDR");
        System.clearProperty("VAULT_TOKEN");
    }

    @Test
    void encryptionKeysAndClientSecretComeFromVault() {
        assertThat(encryptor.activeKeyId()).isEqualTo("vault1");
        assertThat(encryptor.encrypt("x")).startsWith("vault1:");
        assertThat(clientSecret).isEqualTo("from-vault");
    }
}

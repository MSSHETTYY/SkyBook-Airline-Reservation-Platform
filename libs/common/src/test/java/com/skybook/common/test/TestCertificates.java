package com.skybook.common.test;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.util.Collections;

import javax.net.ssl.SSLContext;

import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.pem.PemSslStoreBundle;
import org.springframework.boot.ssl.pem.PemSslStoreDetails;

/**
 * Access to the test-only TLS material in {@code classpath:test-certs/} (see the README there), as
 * Spring Boot SSL bundles, plain SSL contexts, or PKCS12 files for tools that need a file (WireMock).
 */
public final class TestCertificates {

    public static final String CA = "classpath:test-certs/ca.crt";
    public static final String SERVICE_CERT = "classpath:test-certs/service.crt";
    public static final String SERVICE_KEY = "classpath:test-certs/service.key";
    public static final String ROGUE_CERT = "classpath:test-certs/rogue-client.crt";
    public static final String ROGUE_KEY = "classpath:test-certs/rogue-client.key";
    public static final String STORE_PASSWORD = "changeit";

    private TestCertificates() {
    }

    /** Spring properties that configure the "skybook-tls" bundle from the test certificates. */
    public static String[] bundleProperties() {
        return new String[] {
                "SKYBOOK_TLS_CERT=" + SERVICE_CERT,
                "SKYBOOK_TLS_KEY=" + SERVICE_KEY,
                "SKYBOOK_TLS_CA=" + CA
        };
    }

    /** Trusted service identity: presents a client certificate signed by the test CA. */
    public static SslBundle trustedClient() {
        return bundle(SERVICE_CERT, SERVICE_KEY);
    }

    /** A client certificate from a CA the services do not trust. */
    public static SslBundle rogueClient() {
        return bundle(ROGUE_CERT, ROGUE_KEY);
    }

    /** Trusts the test CA but presents no client certificate. */
    public static SslBundle anonymousClient() {
        return SslBundle.of(new PemSslStoreBundle(null, PemSslStoreDetails.forCertificate(CA)));
    }

    public static SSLContext sslContext(SslBundle bundle) {
        return bundle.createSslContext();
    }

    /** Writes the service identity as a PKCS12 key store file (password {@value #STORE_PASSWORD}). */
    public static Path serviceKeyStoreFile() {
        try {
            // The PEM-derived store protects its key entry with an empty password; tools such as WireMock expect
            // the key password to equal the store password, so copy the entry under STORE_PASSWORD.
            KeyStore source = trustedClient().getStores().getKeyStore();
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(null, null);
            char[] password = STORE_PASSWORD.toCharArray();
            for (String alias : Collections.list(source.aliases())) {
                Key key = source.getKey(alias, new char[0]);
                keyStore.setKeyEntry(alias, key, password, source.getCertificateChain(alias));
            }
            Path file = Files.createTempFile("skybook-test-keystore", ".p12");
            try (OutputStream out = Files.newOutputStream(file)) {
                keyStore.store(out, password);
            }
            file.toFile().deleteOnExit();
            return file;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Copies the PKCS12 trust store (test CA) to a file. */
    public static Path trustStoreFile() {
        try (var in = TestCertificates.class.getResourceAsStream("/test-certs/truststore.p12")) {
            Path file = Files.createTempFile("skybook-test-truststore", ".p12");
            Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
            file.toFile().deleteOnExit();
            return file;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static SslBundle bundle(String certificate, String privateKey) {
        PemSslStoreDetails keyStore = PemSslStoreDetails.forCertificate(certificate).withPrivateKey(privateKey);
        PemSslStoreDetails trustStore = PemSslStoreDetails.forCertificate(CA);
        return SslBundle.of(new PemSslStoreBundle(keyStore, trustStore));
    }
}

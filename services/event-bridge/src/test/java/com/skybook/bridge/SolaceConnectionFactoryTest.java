package com.skybook.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.jms.connection.CachingConnectionFactory;

import com.solacesystems.jms.SolConnectionFactory;

/** The Solace connection factory is configured from properties; creating it opens no connection. */
class SolaceConnectionFactoryTest {

    @Test
    void configuresHostVpnAndCredentials() throws Exception {
        var factory = (CachingConnectionFactory) new BridgeConfig.Solace()
                .connectionFactory("tcp://broker:55555", "skybook-vpn", "bridge", "secret");

        SolConnectionFactory solace = (SolConnectionFactory) factory.getTargetConnectionFactory();
        assertThat(solace.getHost()).isEqualTo("tcp://broker:55555");
        assertThat(solace.getVPN()).isEqualTo("skybook-vpn");
        assertThat(solace.getUsername()).isEqualTo("bridge");
        assertThat(solace.getDirectTransport()).as("guaranteed messaging").isFalse();
    }
}

package com.skybook.notification.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.jms.connection.CachingConnectionFactory;

import com.solacesystems.jms.SolConnectionFactory;

class SolaceConnectionFactoryTest {

    @Test
    void configuresHostVpnAndCredentials() throws Exception {
        var factory = (CachingConnectionFactory) new NotificationConfig.Solace()
                .connectionFactory("tcp://broker:55555", "skybook-vpn", "notifier", "secret");

        SolConnectionFactory solace = (SolConnectionFactory) factory.getTargetConnectionFactory();
        assertThat(solace.getHost()).isEqualTo("tcp://broker:55555");
        assertThat(solace.getVPN()).isEqualTo("skybook-vpn");
        assertThat(solace.getUsername()).isEqualTo("notifier");
    }
}

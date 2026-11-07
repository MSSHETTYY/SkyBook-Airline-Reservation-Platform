package com.skybook.booking.adapter.out.persistence;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;

/**
 * Repository contract on real MariaDB, with Hibernate schema validation enabled so the Flyway
 * migrations and the JPA mappings are proven to agree. Skipped automatically without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
class BookingRepositoryMariaDbIT extends AbstractBookingRepositoryTest {

    @Container
    @ServiceConnection
    static final MariaDBContainer MARIADB = new MariaDBContainer("mariadb:11.8.9");
}

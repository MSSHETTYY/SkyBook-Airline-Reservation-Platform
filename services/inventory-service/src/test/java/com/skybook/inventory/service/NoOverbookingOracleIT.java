package com.skybook.inventory.service;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;

/**
 * Concurrency proof against real Oracle Database Free: Flyway migrations, Hibernate schema validation,
 * Oracle row locking and READ COMMITTED semantics. Skipped automatically when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
class NoOverbookingOracleIT extends AbstractNoOverbookingTest {

    @Container
    @ServiceConnection
    static final OracleContainer ORACLE = new OracleContainer("gvenzl/oracle-free:23-slim-faststart")
            .withUsername("inventory")
            .withPassword("inventory_test");
}

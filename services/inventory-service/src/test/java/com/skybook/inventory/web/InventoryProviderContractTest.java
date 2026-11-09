package com.skybook.inventory.web;

import java.util.Map;

import org.apache.hc.core5.http.HttpRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.skybook.common.security.Roles;
import com.skybook.inventory.support.InventoryTestSupport;

import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;

/** Verifies booking-service's contract (holding seats) against the real inventory-service. */
@Provider("inventory-service")
@PactFolder("../../contracts/pacts")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InventoryProviderContractTest extends InventoryTestSupport {

    @LocalServerPort
    private int port;

    @BeforeEach
    void target(PactVerificationContext context) {
        context.setTarget(new HttpTestTarget("localhost", port));
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider.class)
    void verify(PactVerificationContext context, HttpRequest request) {
        request.setHeader("Authorization", bearer("booking-service", Roles.SERVICE));
        context.verifyInteraction();
    }

    /** Inventory assigns flight ids itself; the created id is injected into the contract's request path. */
    @State("a scheduled flight with seats 2A and 2B available")
    Map<String, Object> scheduledFlight() {
        return Map.of("flightId", newFlight(3, "AB"));
    }
}

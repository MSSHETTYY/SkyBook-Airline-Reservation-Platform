package com.skybook.payment.web;

import org.apache.hc.core5.http.HttpRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.skybook.common.money.Money;
import com.skybook.payment.domain.Payment;
import com.skybook.payment.domain.PaymentPurpose;
import com.skybook.payment.service.PaymentService;
import com.skybook.payment.service.PaymentSimulationService;
import com.skybook.payment.support.PaymentTestSupport;

import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;

/**
 * Verifies the contract booking-service published ({@code contracts/pacts}) against the real payment-service.
 * A change that breaks what booking relies on fails here, in the provider's own build.
 */
@Provider("payment-service")
@PactFolder("../../contracts/pacts")
class PaymentProviderContractTest extends PaymentTestSupport {

    @LocalServerPort
    private int port;

    @Autowired
    private PaymentService payments;

    @Autowired
    private PaymentSimulationService simulation;

    @BeforeEach
    void target(PactVerificationContext context) {
        context.setTarget(new HttpTestTarget("localhost", port));
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider.class)
    void verify(PactVerificationContext context, HttpRequest request) {
        // Contracts describe the business exchange; the provider adds the caller's (service) credentials.
        request.setHeader("Authorization", SERVICE);
        context.verifyInteraction();
    }

    @State("booking PCTBBB has a successful payment of 200.00 USD")
    void bookingWithSuccessfulPayment() {
        Payment payment = payments.create("PCTBBB", PaymentPurpose.BOOKING, Money.of("200.00", "USD"),
                "contract-state-PCTBBB");
        simulation.simulate(payment.getId(), true);
    }
}

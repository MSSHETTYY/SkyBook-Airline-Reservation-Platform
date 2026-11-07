package com.skybook.payment.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

import com.skybook.payment.api.v1.SimulationApi;
import com.skybook.payment.api.v1.model.SimulationRequest;
import com.skybook.payment.service.PaymentSimulationService;

/** Only registered with the fake provider: lets the SPA and E2E tests "pay" without a Stripe account. */
@RestController
@ConditionalOnProperty(name = "skybook.payment.provider", havingValue = "fake", matchIfMissing = true)
class SimulationController implements SimulationApi {

    private final PaymentSimulationService simulation;

    SimulationController(PaymentSimulationService simulation) {
        this.simulation = simulation;
    }

    @Override
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> simulatePaymentOutcome(String paymentId, SimulationRequest request) {
        simulation.simulate(paymentId, request.getOutcome() == SimulationRequest.OutcomeEnum.SUCCEEDED);
        return ResponseEntity.accepted().build();
    }
}

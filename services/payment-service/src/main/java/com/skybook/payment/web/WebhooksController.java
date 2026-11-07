package com.skybook.payment.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.skybook.payment.api.v1.WebhooksApi;
import com.skybook.payment.service.StripeWebhookService;

/** Stripe calls this; the body is taken raw because the signature is computed over the exact bytes. */
@RestController
class WebhooksController implements WebhooksApi {

    private final StripeWebhookService webhooks;

    WebhooksController(StripeWebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @Override
    public ResponseEntity<Void> handleStripeWebhook(String stripeSignature, String body) {
        webhooks.handle(body, stripeSignature);
        return ResponseEntity.ok().build();
    }
}

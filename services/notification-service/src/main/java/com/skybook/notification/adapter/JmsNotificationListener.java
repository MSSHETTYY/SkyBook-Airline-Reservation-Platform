package com.skybook.notification.adapter;

import jakarta.jms.JMSException;
import jakarta.jms.TextMessage;

import org.slf4j.MDC;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

import com.skybook.common.web.CorrelationId;
import com.skybook.notification.service.NotificationService;

/**
 * Consumes the durable Solace queue that subscribes to {@code skybook/>}. The listener container is
 * transacted: the message is acknowledged only when processing succeeded; an exception rolls back and the
 * broker redelivers (and eventually moves the message to the dead-message queue).
 */
@Component
class JmsNotificationListener {

    private final NotificationService notifications;

    JmsNotificationListener(NotificationService notifications) {
        this.notifications = notifications;
    }

    @JmsListener(destination = "${skybook.notification.queue}", containerFactory = "notificationListenerFactory")
    void onMessage(TextMessage message) throws JMSException {
        String correlationId = message.getStringProperty("correlationId");
        try (MDC.MDCCloseable ignored = MDC.putCloseable(CorrelationId.MDC_KEY,
                correlationId == null ? CorrelationId.generate() : correlationId)) {
            notifications.process(message.getText());
        }
    }
}

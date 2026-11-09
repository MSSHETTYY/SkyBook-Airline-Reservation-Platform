package com.skybook.notification.port;

import java.util.Optional;

/**
 * Local projection of booking reference to contact e-mail, built from booking events. Payment events
 * carry no personal data, so this is how a refund e-mail finds its recipient without calling booking-service.
 */
public interface ContactDirectory {

    void remember(String bookingReference, String contactEmail);

    Optional<String> contactFor(String bookingReference);
}

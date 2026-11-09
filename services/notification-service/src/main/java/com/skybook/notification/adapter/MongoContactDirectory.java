package com.skybook.notification.adapter;

import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.stereotype.Component;

import com.skybook.notification.port.ContactDirectory;

/** Booking reference to contact e-mail, learned from booking events. */
@Component
@ConditionalOnProperty(name = "skybook.notification.store", havingValue = "mongo", matchIfMissing = true)
class MongoContactDirectory implements ContactDirectory {

    @Document("booking_contacts")
    record Contact(@Id String bookingReference, String contactEmail) {
    }

    private final MongoTemplate mongo;

    MongoContactDirectory(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public void remember(String bookingReference, String contactEmail) {
        mongo.save(new Contact(bookingReference, contactEmail));
    }

    @Override
    public Optional<String> contactFor(String bookingReference) {
        return Optional.ofNullable(mongo.findById(bookingReference, Contact.class)).map(Contact::contactEmail);
    }
}

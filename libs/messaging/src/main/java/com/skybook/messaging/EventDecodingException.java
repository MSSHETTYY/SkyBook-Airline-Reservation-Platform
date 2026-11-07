package com.skybook.messaging;

import java.io.Serial;

/**
 * A record that can never be processed (malformed JSON, missing fields). Not retried: it goes straight
 * to the dead-letter topic, because retrying a poison message only delays the records behind it.
 */
public class EventDecodingException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public EventDecodingException(String message, Throwable cause) {
        super(message, cause);
    }
}

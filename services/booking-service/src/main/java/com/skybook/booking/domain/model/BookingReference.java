package com.skybook.booking.domain.model;

import java.util.Objects;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

/**
 * A six-character booking reference (PNR), e.g. {@code K7QX2M}.
 *
 * <p>The alphabet omits 0/O and 1/I/L so references can be read out over the phone. 31^6 is about
 * 887 million combinations; uniqueness is enforced by a database constraint and collisions are retried.
 */
public record BookingReference(String value) {

    static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final Pattern FORMAT = Pattern.compile("[" + ALPHABET + "]{6}");

    public BookingReference {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid booking reference: " + value);
        }
    }

    public static BookingReference random(RandomGenerator random) {
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return new BookingReference(sb.toString());
    }

    @Override
    public String toString() {
        return value;
    }
}

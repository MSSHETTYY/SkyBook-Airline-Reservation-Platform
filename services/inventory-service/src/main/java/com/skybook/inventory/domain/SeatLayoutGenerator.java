package com.skybook.inventory.domain;

import java.util.ArrayList;
import java.util.List;

import com.skybook.common.error.SkyBookException;

/** Generates the seats of a flight from a simple cabin layout: business rows first, then economy. */
public final class SeatLayoutGenerator {

    private SeatLayoutGenerator() {
    }

    public static List<Seat> generate(Long flightId, int rows, String seatLetters, int businessRows) {
        if (rows < 1 || seatLetters == null || seatLetters.isBlank() || businessRows < 0 || businessRows > rows) {
            throw new SkyBookException(InventoryErrorCode.INVALID_FLIGHT,
                    "Invalid seat layout: %d rows, letters '%s', %d business rows"
                            .formatted(rows, seatLetters, businessRows));
        }
        if (seatLetters.chars().distinct().count() != seatLetters.length()) {
            throw new SkyBookException(InventoryErrorCode.INVALID_FLIGHT, "Seat letters must be unique");
        }
        List<Seat> seats = new ArrayList<>(rows * seatLetters.length());
        for (int row = 1; row <= rows; row++) {
            CabinClass cabin = row <= businessRows ? CabinClass.BUSINESS : CabinClass.ECONOMY;
            for (char letter : seatLetters.toCharArray()) {
                seats.add(new Seat(flightId, row + String.valueOf(letter), cabin));
            }
        }
        return seats;
    }
}

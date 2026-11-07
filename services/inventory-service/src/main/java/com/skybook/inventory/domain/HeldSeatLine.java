package com.skybook.inventory.domain;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A seat captured by a hold, with the price quoted at hold time (prices are locked for the hold's lifetime). */
@Embeddable
public class HeldSeatLine {

    @Column(name = "seat_number", nullable = false)
    private String seatNumber;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "cabin_class", nullable = false)
    private CabinClass cabinClass;

    @Column(name = "price_amount", nullable = false)
    private BigDecimal priceAmount;

    protected HeldSeatLine() {
        // for JPA
    }

    HeldSeatLine(String seatNumber, CabinClass cabinClass, BigDecimal priceAmount) {
        this.seatNumber = seatNumber;
        this.cabinClass = cabinClass;
        this.priceAmount = priceAmount;
    }

    public String getSeatNumber() {
        return seatNumber;
    }

    public CabinClass getCabinClass() {
        return cabinClass;
    }

    public BigDecimal getPriceAmount() {
        return priceAmount;
    }
}

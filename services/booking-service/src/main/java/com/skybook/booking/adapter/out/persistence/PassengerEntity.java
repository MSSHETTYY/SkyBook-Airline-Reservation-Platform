package com.skybook.booking.adapter.out.persistence;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "booking_passenger")
class PassengerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id")
    private BookingEntity booking;

    @Column(nullable = false)
    private int position;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    /** ISO-8601 date; stored as text so it can be encrypted like the other personal data. */
    @Column(name = "date_of_birth", nullable = false)
    private String dateOfBirth;

    @Column(name = "seat_number", nullable = false)
    private String seatNumber;

    @Column(name = "seat_price", nullable = false)
    private BigDecimal seatPrice;

    @Column(name = "ticket_number")
    private String ticketNumber;

    protected PassengerEntity() {
        // for JPA
    }

    PassengerEntity(BookingEntity booking, int position) {
        this.booking = booking;
        this.position = position;
    }

    int getPosition() {
        return position;
    }

    String getFirstName() {
        return firstName;
    }

    void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    String getLastName() {
        return lastName;
    }

    void setLastName(String lastName) {
        this.lastName = lastName;
    }

    String getDateOfBirth() {
        return dateOfBirth;
    }

    void setDateOfBirth(String dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
    }

    String getSeatNumber() {
        return seatNumber;
    }

    void setSeatNumber(String seatNumber) {
        this.seatNumber = seatNumber;
    }

    BigDecimal getSeatPrice() {
        return seatPrice;
    }

    void setSeatPrice(BigDecimal seatPrice) {
        this.seatPrice = seatPrice;
    }

    String getTicketNumber() {
        return ticketNumber;
    }

    void setTicketNumber(String ticketNumber) {
        this.ticketNumber = ticketNumber;
    }
}

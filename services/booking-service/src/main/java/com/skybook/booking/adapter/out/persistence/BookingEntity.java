package com.skybook.booking.adapter.out.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.BatchSize;

/** Persistence model of a booking. Mapped to and from the domain by {@link BookingPersistenceMapper}. */
@Entity
@Table(name = "booking")
class BookingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private String reference;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private String customerId;

    @Column(name = "contact_email", nullable = false)
    private String contactEmail;

    @Column(nullable = false)
    private String status;

    @Column(name = "flight_id", nullable = false)
    private Long flightId;

    @Column(name = "flight_number", nullable = false)
    private String flightNumber;

    @Column(nullable = false)
    private String origin;

    @Column(nullable = false)
    private String destination;

    @Column(name = "departure_time", nullable = false)
    private Instant departureTime;

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;

    @Column(nullable = false)
    private String currency;

    @Column(name = "hold_id")
    private String holdId;

    @Column(name = "hold_expires_at")
    private Instant holdExpiresAt;

    @Column(name = "payment_id")
    private String paymentId;

    @Column(name = "payment_client_secret")
    private String paymentClientSecret;

    @Column(name = "amount_paid", nullable = false)
    private BigDecimal amountPaid;

    @Column(name = "amount_refunded", nullable = false)
    private BigDecimal amountRefunded;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, updatable = false)
    private String requestFingerprint;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    @BatchSize(size = 50)
    private List<PassengerEntity> passengers = new ArrayList<>();

    @OneToOne(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true)
    private PendingChangeEntity pendingChange;

    protected BookingEntity() {
        // for JPA
    }

    BookingEntity(String reference, String customerId, String idempotencyKey, String requestFingerprint,
                  Instant createdAt) {
        this.reference = reference;
        this.customerId = customerId;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.createdAt = createdAt;
    }

    Long getId() {
        return id;
    }

    String getReference() {
        return reference;
    }

    String getCustomerId() {
        return customerId;
    }

    String getContactEmail() {
        return contactEmail;
    }

    void setContactEmail(String contactEmail) {
        this.contactEmail = contactEmail;
    }

    String getStatus() {
        return status;
    }

    void setStatus(String status) {
        this.status = status;
    }

    Long getFlightId() {
        return flightId;
    }

    String getFlightNumber() {
        return flightNumber;
    }

    String getOrigin() {
        return origin;
    }

    String getDestination() {
        return destination;
    }

    Instant getDepartureTime() {
        return departureTime;
    }

    void setFlight(Long newFlightId, String newFlightNumber, String newOrigin, String newDestination,
                   Instant newDepartureTime) {
        this.flightId = newFlightId;
        this.flightNumber = newFlightNumber;
        this.origin = newOrigin;
        this.destination = newDestination;
        this.departureTime = newDepartureTime;
    }

    BigDecimal getTotalAmount() {
        return totalAmount;
    }

    String getCurrency() {
        return currency;
    }

    void setTotal(BigDecimal amount, String currencyCode) {
        this.totalAmount = amount;
        this.currency = currencyCode;
    }

    String getHoldId() {
        return holdId;
    }

    Instant getHoldExpiresAt() {
        return holdExpiresAt;
    }

    void setHold(String newHoldId, Instant expiresAt) {
        this.holdId = newHoldId;
        this.holdExpiresAt = expiresAt;
    }

    String getPaymentId() {
        return paymentId;
    }

    String getPaymentClientSecret() {
        return paymentClientSecret;
    }

    void setPayment(String newPaymentId, String clientSecret) {
        this.paymentId = newPaymentId;
        this.paymentClientSecret = clientSecret;
    }

    BigDecimal getAmountPaid() {
        return amountPaid;
    }

    BigDecimal getAmountRefunded() {
        return amountRefunded;
    }

    void setAmounts(BigDecimal paid, BigDecimal refunded) {
        this.amountPaid = paid;
        this.amountRefunded = refunded;
    }

    String getIdempotencyKey() {
        return idempotencyKey;
    }

    String getRequestFingerprint() {
        return requestFingerprint;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    long getVersion() {
        return version;
    }

    List<PassengerEntity> getPassengers() {
        return passengers;
    }

    PendingChangeEntity getPendingChange() {
        return pendingChange;
    }

    void setPendingChange(PendingChangeEntity pendingChange) {
        this.pendingChange = pendingChange;
    }
}

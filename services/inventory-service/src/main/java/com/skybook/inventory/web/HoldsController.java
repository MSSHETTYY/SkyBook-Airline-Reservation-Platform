package com.skybook.inventory.web;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

import com.skybook.inventory.api.v1.HoldsApi;
import com.skybook.inventory.api.v1.model.ConfirmHoldRequest;
import com.skybook.inventory.api.v1.model.HoldSeatsRequest;
import com.skybook.inventory.api.v1.model.SeatHold;
import com.skybook.inventory.service.SeatHoldService;
import com.skybook.inventory.service.SeatHoldView;
import com.skybook.common.security.Roles;

/** Seat holds are an internal API: only booking-service (role SERVICE) may call it. */
@RestController
@PreAuthorize(Roles.IS_SERVICE)
class HoldsController implements HoldsApi {

    private final SeatHoldService holds;

    HoldsController(SeatHoldService holds) {
        this.holds = holds;
    }

    @Override
    public ResponseEntity<SeatHold> holdSeats(Long flightId, String idempotencyKey, HoldSeatsRequest request) {
        SeatHoldView hold = holds.holdSeats(flightId, request.getSeatNumbers(), idempotencyKey);
        return ResponseEntity.created(URI.create("/api/v1/holds/" + hold.holdId())).body(ApiMapper.toApi(hold));
    }

    @Override
    public ResponseEntity<SeatHold> getHold(UUID holdId) {
        return ResponseEntity.ok(ApiMapper.toApi(holds.hold(holdId.toString())));
    }

    @Override
    public ResponseEntity<SeatHold> confirmHold(UUID holdId, ConfirmHoldRequest request) {
        return ResponseEntity.ok(ApiMapper.toApi(holds.confirm(holdId.toString(), request.getBookingReference())));
    }

    @Override
    public ResponseEntity<Void> releaseHold(UUID holdId) {
        holds.release(holdId.toString());
        return ResponseEntity.noContent().build();
    }
}

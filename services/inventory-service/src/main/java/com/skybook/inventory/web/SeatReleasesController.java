package com.skybook.inventory.web;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

import com.skybook.inventory.api.v1.SeatReleasesApi;
import com.skybook.inventory.api.v1.model.SeatReleaseRequest;
import com.skybook.inventory.api.v1.model.SeatReleaseResult;
import com.skybook.inventory.service.SeatHoldService;
import com.skybook.common.security.Roles;

@RestController
@PreAuthorize(Roles.IS_SERVICE)
class SeatReleasesController implements SeatReleasesApi {

    private final SeatHoldService holds;

    SeatReleasesController(SeatHoldService holds) {
        this.holds = holds;
    }

    @Override
    public ResponseEntity<SeatReleaseResult> releaseBookedSeats(Long flightId, SeatReleaseRequest request) {
        return ResponseEntity.ok(new SeatReleaseResult()
                .flightId(flightId)
                .releasedSeats(holds.releaseBookedSeats(flightId, request.getBookingReference())));
    }
}

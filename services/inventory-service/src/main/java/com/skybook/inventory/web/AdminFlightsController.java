package com.skybook.inventory.web;

import java.net.URI;

import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

import com.skybook.inventory.api.v1.AdminFlightsApi;
import com.skybook.inventory.api.v1.model.CreateFlightRequest;
import com.skybook.inventory.api.v1.model.FlightPage;
import com.skybook.inventory.api.v1.model.FlightSummary;
import com.skybook.inventory.service.FlightAdminService;
import com.skybook.inventory.service.FlightAdminService.CreateFlightCommand;
import com.skybook.inventory.service.FlightView;
import com.skybook.common.security.Roles;

@RestController
class AdminFlightsController implements AdminFlightsApi {

    private final FlightAdminService admin;

    AdminFlightsController(FlightAdminService admin) {
        this.admin = admin;
    }

    @Override
    @PreAuthorize(Roles.IS_ADMIN)
    public ResponseEntity<FlightSummary> createFlight(String idempotencyKey, CreateFlightRequest request) {
        FlightView created = admin.createFlight(new CreateFlightCommand(
                request.getFlightNumber(),
                request.getOrigin(),
                request.getDestination(),
                request.getDepartureTime().toInstant(),
                request.getArrivalTime().toInstant(),
                request.getAircraftType(),
                ApiMapper.toDomain(request.getBaseFare()),
                request.getLayout().getRows(),
                request.getLayout().getSeatLetters(),
                request.getLayout().getBusinessRows()), idempotencyKey);
        return ResponseEntity.created(URI.create("/api/v1/flights/" + created.flight().getId()))
                .body(ApiMapper.toApi(created));
    }

    @Override
    @PreAuthorize(Roles.IS_ADMIN)
    public ResponseEntity<FlightSummary> cancelFlight(Long flightId) {
        return ResponseEntity.ok(ApiMapper.toApi(admin.cancelFlight(flightId)));
    }

    @Override
    @PreAuthorize(Roles.IS_STAFF)
    public ResponseEntity<FlightPage> listFlights(Integer page, Integer size) {
        Page<FlightView> result = admin.listFlights(page == null ? 0 : page, size == null ? 20 : size);
        return ResponseEntity.ok(new FlightPage()
                .items(result.getContent().stream().map(ApiMapper::toApi).toList())
                .page(result.getNumber())
                .size(result.getSize())
                .totalElements(result.getTotalElements()));
    }
}

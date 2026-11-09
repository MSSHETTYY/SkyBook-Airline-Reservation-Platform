package com.skybook.inventory.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.skybook.common.error.CommonErrorCode;
import com.skybook.common.error.SkyBookException;
import com.skybook.inventory.api.v2.FlightOffersApi;
import com.skybook.inventory.api.v2.model.CabinClass;
import com.skybook.inventory.api.v2.model.CabinOffer;
import com.skybook.inventory.api.v2.model.FlightOffer;
import com.skybook.inventory.api.v2.model.FlightOfferPage;
import com.skybook.inventory.api.v2.model.Money;
import com.skybook.inventory.domain.Flight;
import com.skybook.inventory.service.FlightOfferService;
import com.skybook.inventory.service.FlightOfferService.Query;
import com.skybook.inventory.service.FlightOfferService.Sort;

/** Flight search, API version 2 (ADR-0020). */
@RestController
class FlightOffersController implements FlightOffersApi {

    private final FlightOfferService offers;

    FlightOffersController(FlightOfferService offers) {
        this.offers = offers;
    }

    @Override
    public ResponseEntity<FlightOfferPage> searchFlightOffers(String origin, String destination,
                                                              LocalDate departureFrom, LocalDate departureTo,
                                                              Integer passengers, CabinClass cabin,
                                                              BigDecimal maxPrice, String sort,
                                                              Integer page, Integer size) {
        Query query = new Query(origin, destination, departureFrom, departureTo,
                passengers == null ? 1 : passengers,
                cabin == null ? null : com.skybook.inventory.domain.CabinClass.valueOf(cabin.name()),
                maxPrice, sort(sort), page == null ? 0 : page, size == null ? 20 : size);
        FlightOfferService.OfferPage result = offers.search(query);
        return ResponseEntity.ok(new FlightOfferPage()
                .items(result.items().stream().map(FlightOffersController::toApi).toList())
                .page(result.page())
                .size(result.size())
                .totalElements(result.totalElements()));
    }

    private static Sort sort(String value) {
        if (value == null || value.isBlank()) {
            return Sort.DEPARTURE;
        }
        try {
            return Sort.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new SkyBookException(CommonErrorCode.VALIDATION_FAILED, "sort must be DEPARTURE or PRICE");
        }
    }

    private static FlightOffer toApi(FlightOfferService.FlightOffer offer) {
        Flight flight = offer.flight();
        return new FlightOffer()
                .flightId(flight.getId())
                .flightNumber(flight.getFlightNumber())
                .origin(flight.getOrigin())
                .destination(flight.getDestination())
                .departureTime(ApiMapper.toOffset(flight.getDepartureTime()))
                .arrivalTime(ApiMapper.toOffset(flight.getArrivalTime()))
                .durationMinutes((int) java.time.Duration.between(flight.getDepartureTime(), flight.getArrivalTime())
                        .toMinutes())
                .aircraftType(flight.getAircraftType())
                .cabins(offer.cabins().stream().map(FlightOffersController::toApi).toList());
    }

    private static CabinOffer toApi(FlightOfferService.CabinOffer cabin) {
        CabinOffer api = new CabinOffer()
                .cabinClass(CabinClass.valueOf(cabin.cabinClass().name()))
                .seatsAvailable(cabin.seatsAvailable());
        if (cabin.fromPrice() != null) {
            api.fromPrice(new Money().amount(cabin.fromPrice().amount()).currency(cabin.fromPrice().currencyCode()));
        }
        return api;
    }
}

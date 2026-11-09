package com.skybook.inventory.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.skybook.common.error.CommonErrorCode;
import com.skybook.common.error.SkyBookException;
import com.skybook.common.money.Money;
import com.skybook.inventory.domain.CabinClass;
import com.skybook.inventory.domain.Flight;
import com.skybook.inventory.domain.FlightStatus;
import com.skybook.inventory.repository.FlightRepository;
import com.skybook.inventory.service.SeatMapSnapshot.SeatView;

/**
 * Flight search, API version 2: a departure date range, availability and from-price per cabin, filtering,
 * sorting and paging (ADR-0020). Availability comes from the same cached seat maps as version 1.
 */
@Service
public class FlightOfferService {

    /** A wider range would make one request scan too many flights (and seat maps). */
    static final int MAX_RANGE_DAYS = 7;

    private final FlightRepository flights;
    private final SeatMapService seatMaps;

    public FlightOfferService(FlightRepository flights, SeatMapService seatMaps) {
        this.flights = flights;
        this.seatMaps = seatMaps;
    }

    public enum Sort { DEPARTURE, PRICE }

    /**
     * Search criteria; {@code to}, {@code cabin} and {@code maxPrice} are optional (null).
     */
    public record Query(String origin, String destination, LocalDate from, LocalDate to, int passengers,
                        CabinClass cabin, BigDecimal maxPrice, Sort sort, int page, int size) {

        public Query {
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(destination, "destination");
            Objects.requireNonNull(from, "from");
            sort = sort == null ? Sort.DEPARTURE : sort;
        }

        LocalDate lastDay() {
            return to == null ? from : to;
        }
    }

    /** Seats left and the cheapest free seat in one cabin; {@code fromPrice} is null when sold out. */
    public record CabinOffer(CabinClass cabinClass, int seatsAvailable, Money fromPrice) {
    }

    public record FlightOffer(Flight flight, List<CabinOffer> cabins) {

        Optional<CabinOffer> cabin(CabinClass cabinClass) {
            return cabins.stream().filter(c -> c.cabinClass() == cabinClass).findFirst();
        }

        /** Cheapest seat in the requested cabin, or across cabins when none was requested. */
        Optional<Money> cheapest(CabinClass cabinClass) {
            return cabins.stream()
                    .filter(c -> cabinClass == null || c.cabinClass() == cabinClass)
                    .map(CabinOffer::fromPrice)
                    .filter(Objects::nonNull)
                    .min(Comparator.naturalOrder());
        }

        boolean hasRoomFor(int passengers, CabinClass cabinClass) {
            int seats = cabinClass == null
                    ? cabins.stream().mapToInt(CabinOffer::seatsAvailable).sum()
                    : cabin(cabinClass).map(CabinOffer::seatsAvailable).orElse(0);
            return seats >= passengers;
        }
    }

    public record OfferPage(List<FlightOffer> items, int page, int size, long totalElements) {
    }

    @Transactional(readOnly = true)
    public OfferPage search(Query query) {
        LocalDate lastDay = query.lastDay();
        if (lastDay.isBefore(query.from())) {
            throw new SkyBookException(CommonErrorCode.VALIDATION_FAILED,
                    "departureTo must not be before departureFrom");
        }
        if (ChronoUnit.DAYS.between(query.from(), lastDay) >= MAX_RANGE_DAYS) {
            throw new SkyBookException(CommonErrorCode.VALIDATION_FAILED,
                    "The departure date range may cover at most " + MAX_RANGE_DAYS + " days");
        }
        List<FlightOffer> matching = flights.searchRoute(query.origin(), query.destination(), FlightStatus.SCHEDULED,
                        query.from().atStartOfDay().toInstant(ZoneOffset.UTC),
                        lastDay.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC))
                .stream()
                .map(flight -> offer(flight, seatMaps.seatMap(flight.getId())))
                .filter(offer -> offer.hasRoomFor(query.passengers(), query.cabin()))
                .filter(offer -> withinBudget(offer, query))
                .sorted(order(query))
                .toList();
        int fromIndex = Math.min(query.page() * query.size(), matching.size());
        int toIndex = Math.min(fromIndex + query.size(), matching.size());
        return new OfferPage(matching.subList(fromIndex, toIndex), query.page(), query.size(), matching.size());
    }

    static FlightOffer offer(Flight flight, SeatMapSnapshot seatMap) {
        List<CabinOffer> cabins = Arrays.stream(CabinClass.values())
                .map(cabin -> {
                    List<SeatView> free = seatMap.seats().stream()
                            .filter(seat -> seat.available() && seat.cabinClass() == cabin)
                            .toList();
                    Money fromPrice = free.stream().map(SeatView::price).min(Comparator.naturalOrder())
                            .map(price -> Money.of(price, seatMap.currency()))
                            .orElse(null);
                    return new CabinOffer(cabin, free.size(), fromPrice);
                })
                .toList();
        return new FlightOffer(flight, cabins);
    }

    private static boolean withinBudget(FlightOffer offer, Query query) {
        return query.maxPrice() == null || offer.cheapest(query.cabin())
                .map(price -> price.amount().compareTo(query.maxPrice()) <= 0)
                .orElse(false);
    }

    private static Comparator<FlightOffer> order(Query query) {
        Comparator<FlightOffer> byDeparture = Comparator.comparing(offer -> offer.flight().getDepartureTime());
        if (query.sort() == Sort.DEPARTURE) {
            return byDeparture;
        }
        Comparator<FlightOffer> byPrice = Comparator.comparing(
                offer -> offer.cheapest(query.cabin()).map(Money::amount).orElse(null),
                Comparator.nullsLast(Comparator.naturalOrder()));
        return byPrice.thenComparing(byDeparture);
    }
}

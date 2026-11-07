package com.skybook.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.skybook.common.error.SkyBookException;
import com.skybook.inventory.domain.InventoryErrorCode;
import com.skybook.inventory.domain.Seat;
import com.skybook.inventory.domain.SeatStatus;
import com.skybook.inventory.repository.SeatRepository;
import com.skybook.inventory.support.InventoryTestSupport;

/**
 * Proof that concurrent holds never allocate a seat twice.
 *
 * <p>Runs against H2 in the fast suite ({@link NoOverbookingTest}) and against a real Oracle database
 * via Testcontainers ({@code NoOverbookingOracleIT}). Both variants exercise the production code path:
 * real transactions, real row locks, real optimistic-locking version checks.
 */
abstract class AbstractNoOverbookingTest extends InventoryTestSupport {

    private static final int THREADS = 24;

    @Autowired
    private SeatHoldService holds;

    @Autowired
    private SeatRepository seats;

    private record Outcome(SeatHoldView hold, SkyBookException failure) {
    }

    @Test
    void exactlyOneOfManyConcurrentRequestsForTheSameSeatWins() throws Exception {
        Long flightId = newFlight(5, "ABCDEF");

        List<Outcome> outcomes = runConcurrently(THREADS * 2,
                i -> holds.holdSeats(flightId, List.of("3C"), "same-seat-" + flightId + "-" + i));

        List<SeatHoldView> winners = outcomes.stream().map(Outcome::hold).filter(Objects::nonNull).toList();
        assertThat(winners).hasSize(1);
        assertThat(outcomes).filteredOn(o -> o.failure() != null)
                .allSatisfy(o -> assertThat(o.failure().getErrorCode()).isEqualTo(InventoryErrorCode.SEAT_UNAVAILABLE));
        Seat seat = seats.findByFlightIdAndSeatNumberIn(flightId, List.of("3C")).getFirst();
        assertThat(seat.getStatus()).isEqualTo(SeatStatus.HELD);
        assertThat(seat.getHoldId()).isEqualTo(winners.getFirst().holdId());
    }

    @Test
    void overlappingMultiSeatHoldsNeverShareASeat() throws Exception {
        Long flightId = newFlight(5, "ABCDEF");
        // High contention: every request picks 3 seats out of the same 10.
        List<String> hotSeats = List.of("1A", "1B", "1C", "1D", "1E", "1F", "2A", "2B", "2C", "2D");
        Random random = new Random(42);
        List<List<String>> requests = new ArrayList<>();
        for (int i = 0; i < THREADS * 3; i++) {
            List<String> shuffled = new ArrayList<>(hotSeats);
            Collections.shuffle(shuffled, random);
            requests.add(List.copyOf(shuffled.subList(0, 3)));
        }

        List<Outcome> outcomes = runConcurrently(requests.size(),
                i -> holds.holdSeats(flightId, requests.get(i), "multi-" + flightId + "-" + i));

        List<SeatHoldView> winners = outcomes.stream().map(Outcome::hold).filter(Objects::nonNull).toList();
        assertThat(winners).isNotEmpty();
        assertThat(outcomes).filteredOn(o -> o.failure() != null)
                .allSatisfy(o -> assertThat(o.failure().getErrorCode()).isEqualTo(InventoryErrorCode.SEAT_UNAVAILABLE));

        // 1. No seat appears in two successful holds.
        List<String> allocated = winners.stream()
                .flatMap(h -> h.seats().stream().map(SeatHoldView.Line::seatNumber))
                .toList();
        assertThat(allocated).doesNotHaveDuplicates();

        // 2. The database agrees with the API responses, seat by seat.
        Map<String, String> holderBySeat = winners.stream()
                .flatMap(h -> h.seats().stream().map(line -> Map.entry(line.seatNumber(), h.holdId())))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        List<Seat> heldInDb = seats.findByFlightIdOrderById(flightId).stream()
                .filter(seat -> seat.getStatus() == SeatStatus.HELD)
                .toList();
        assertThat(heldInDb).hasSize(allocated.size());
        assertThat(heldInDb).allSatisfy(seat ->
                assertThat(seat.getHoldId()).isEqualTo(holderBySeat.get(seat.getSeatNumber())));

        // 3. Nothing outside the requested seats was touched.
        Set<String> touched = new HashSet<>(holderBySeat.keySet());
        assertThat(hotSeats).containsAll(touched);
    }

    private List<Outcome> runConcurrently(int tasks, IntFunction<SeatHoldView> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (int i = 0; i < tasks; i++) {
                int index = i;
                Callable<Outcome> task = () -> {
                    start.await();
                    try {
                        return new Outcome(action.apply(index), null);
                    } catch (SkyBookException ex) {
                        return new Outcome(null, ex);
                    }
                };
                futures.add(pool.submit(task));
            }
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}

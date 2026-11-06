package com.skybook.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ErrorCodeTest {

    @Test
    void typeIsDerivedFromCode() {
        assertThat(CommonErrorCode.RESOURCE_NOT_FOUND.type())
                .isEqualTo(URI.create("urn:skybook:problem:resource-not-found"));
    }

    @Test
    void serviceSpecificCodesPlugIn() {
        ErrorCode seatUnavailable = new ErrorCode() {
            @Override
            public String code() {
                return "SEAT_UNAVAILABLE";
            }

            @Override
            public HttpStatus httpStatus() {
                return HttpStatus.CONFLICT;
            }

            @Override
            public String title() {
                return "Seat no longer available";
            }
        };

        assertThat(seatUnavailable.type()).hasToString("urn:skybook:problem:seat-unavailable");
    }

    @Test
    void exceptionCopiesPropertiesDefensively() {
        Map<String, Object> props = new HashMap<>(Map.of("flightId", "SB101"));
        SkyBookException ex = new SkyBookException(CommonErrorCode.CONFLICT, "detail", props, null);
        props.put("injected", "later");

        assertThat(ex.getProperties()).containsOnlyKeys("flightId");
        assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.CONFLICT);
        assertThat(ex).hasMessage("detail");
    }

    @Test
    void exceptionRequiresCode() {
        assertThatNullPointerException().isThrownBy(() -> new SkyBookException(null, "detail"));
    }

    @Test
    void notFoundDescribesResource() {
        ResourceNotFoundException ex = new ResourceNotFoundException("Booking", "BK-1");

        assertThat(ex).hasMessage("Booking 'BK-1' was not found");
        assertThat(ex.getProperties()).containsEntry("resourceType", "Booking").containsEntry("resourceId", "BK-1");
        assertThat(ex.getErrorCode().httpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}

package com.skybook.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.skybook.common.web.CorrelationId;
import com.skybook.common.web.CorrelationIdFilter;


class GlobalProblemHandlerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-06T10:15:30Z"), ZoneOffset.UTC);
    private static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    private MockMvc mockMvc;

    record PassengerRequest(@NotBlank String name, @Email String email, @Positive int seats) {
    }

    @RestController
    static class TestController {

        @GetMapping("/bookings/{id}")
        String booking(@PathVariable String id) {
            throw new ResourceNotFoundException("Booking", id);
        }

        @GetMapping("/business-rule")
        String businessRule() {
            throw new SkyBookException(CommonErrorCode.BUSINESS_RULE_VIOLATION, "Hold expired",
                    Map.of("holdId", "H-1"), null);
        }

        @GetMapping("/server-side-code")
        String serverSideCode() {
            throw new SkyBookException(CommonErrorCode.INTERNAL_ERROR, "Downstream unavailable");
        }

        @GetMapping("/stale")
        String stale() {
            throw new OptimisticLockingFailureException("Booking BK-1 was updated concurrently");
        }

        @GetMapping("/unexpected")
        String unexpected() {
            throw new IllegalStateException("password=hunter2 leaked internals");
        }

        @PostMapping("/passengers")
        String create(@Valid @RequestBody PassengerRequest request) {
            return "ok";
        }
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalProblemHandler(CLOCK))
                .addFilters(new CorrelationIdFilter())
                .build();
    }

    @Test
    void skyBookExceptionBecomesProblemWithExtensions() throws Exception {
        mockMvc.perform(get("/bookings/BK-9").header(CorrelationId.HEADER, "corr-42"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:skybook:problem:resource-not-found"))
                .andExpect(jsonPath("$.title").value("Resource not found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Booking 'BK-9' was not found"))
                .andExpect(jsonPath("$.instance").value("/bookings/BK-9"))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.correlationId").value("corr-42"))
                .andExpect(jsonPath("$.timestamp").value("2026-11-06T10:15:30Z"))
                .andExpect(jsonPath("$.resourceId").value("BK-9"))
                .andExpect(header().string(CorrelationId.HEADER, "corr-42"));
    }

    @Test
    void customPropertiesAreIncluded() throws Exception {
        mockMvc.perform(get("/business-rule"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.holdId").value("H-1"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void serverSideSkyBookCodeKeepsItsDetail() throws Exception {
        mockMvc.perform(get("/server-side-code"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("Downstream unavailable"));
    }

    @Test
    void optimisticLockFailureBecomesConflict() throws Exception {
        mockMvc.perform(get("/stale"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    }

    @Test
    void unexpectedExceptionDoesNotLeakItsMessage() throws Exception {
        mockMvc.perform(get("/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value(not(containsString("hunter2"))))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void beanValidationFailuresListFieldsWithoutRejectedValues() throws Exception {
        mockMvc.perform(post("/passengers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"email\":\"not-an-email\",\"seats\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.type").value("urn:skybook:problem:validation-failed"))
                .andExpect(jsonPath("$.errors", hasSize(3)))
                .andExpect(jsonPath("$.errors[0].field").value("email"))
                .andExpect(jsonPath("$.errors[1].field").value("name"))
                .andExpect(jsonPath("$.errors[2].field").value("seats"))
                .andExpect(jsonPath("$.instance").value("/passengers"))
                .andExpect(content().string(not(containsString("not-an-email"))));
    }

    @Test
    void malformedJsonIsReportedAsMalformedRequest() throws Exception {
        mockMvc.perform(post("/passengers").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").value("2026-11-06T10:15:30Z"));
    }

    @Test
    void frameworkErrorsWithoutCommonCodeGetHttpStatusCode() throws Exception {
        mockMvc.perform(post("/passengers").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("HTTP_415"));
    }

    @Test
    void constraintViolationsOnMethodParametersAreReported() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            Set<ConstraintViolation<PassengerRequest>> violations =
                    validator.validate(new PassengerRequest(" ", "a@b.example", 1));
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/flights");
            request.setAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE, "corr-7");

            ResponseEntity<ProblemDetail> response = new GlobalProblemHandler(CLOCK)
                    .handleConstraintViolation(new ConstraintViolationException(violations), request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            ProblemDetail body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.getProperties())
                    .containsEntry("code", "VALIDATION_FAILED")
                    .containsEntry("correlationId", "corr-7");
            assertThat(body.getInstance()).hasToString("/flights");
        }
    }
}

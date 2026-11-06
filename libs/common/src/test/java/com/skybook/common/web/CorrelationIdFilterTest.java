package com.skybook.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final AtomicReference<String> mdcDuringChain = new AtomicReference<>();
    private final MockFilterChain chain = new MockFilterChain(new HttpServlet() {
        @Override
        protected void service(HttpServletRequest req, HttpServletResponse res) {
            mdcDuringChain.set(MDC.get(CorrelationId.MDC_KEY));
        }
    });

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void propagatesValidInboundId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/flights");
        request.addHeader(CorrelationId.HEADER, "gw-123");

        filter.doFilter(request, response, chain);

        assertThat(mdcDuringChain).hasValue("gw-123");
        assertThat(response.getHeader(CorrelationId.HEADER)).isEqualTo("gw-123");
        assertThat(request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE)).isEqualTo("gw-123");
    }

    @Test
    void replacesMissingId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/flights");

        filter.doFilter(request, response, chain);

        assertThat(mdcDuringChain.get()).isNotBlank();
        assertThat(response.getHeader(CorrelationId.HEADER)).isEqualTo(mdcDuringChain.get());
    }

    @Test
    void replacesMaliciousIdSoItNeverReachesLogs() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/flights");
        request.addHeader(CorrelationId.HEADER, "x\r\nERROR forged");

        filter.doFilter(request, response, chain);

        assertThat(mdcDuringChain.get()).doesNotContain("forged");
        assertThat(CorrelationId.isValid(mdcDuringChain.get())).isTrue();
    }

    @Test
    void clearsMdcAfterRequestEvenWhenChainFails() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/boom");
        MockFilterChain failingChain = new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res)
                    throws ServletException {
                throw new ServletException("boom");
            }
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, failingChain)).hasMessage("boom");

        assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
    }

    @Test
    void errorDispatchReusesIdChosenOnOriginalDispatch() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/flights");
        request.addHeader(CorrelationId.HEADER, "not valid!");
        filter.doFilter(request, response, new MockFilterChain());
        String original = (String) request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);

        request.setDispatcherType(DispatcherType.ERROR);
        // OncePerRequestFilter skips re-entry unless its "already filtered" marker is cleared, as the container does.
        request.removeAttribute(filter.getClass().getName() + ".FILTERED");
        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(mdcDuringChain).hasValue(original);
    }
}

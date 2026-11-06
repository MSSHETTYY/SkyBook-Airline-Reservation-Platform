package com.skybook.common.autoconfigure;

import java.time.Clock;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

import com.skybook.common.error.GlobalProblemHandler;
import com.skybook.common.web.CorrelationIdFilter;

/**
 * Registers the shared web infrastructure in every servlet-based SkyBook service.
 *
 * <p>The reactive API gateway is excluded by the condition and provides its own WebFlux
 * equivalents. Each bean backs off when a service declares its own.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = Type.SERVLET)
public class SkyBookCommonAutoConfiguration {

    /** Runs the correlation filter before Spring Security and everything else that may log. */
    static final int CORRELATION_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    @Bean
    @ConditionalOnMissingBean
    Clock skyBookClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean(name = "correlationIdFilterRegistration")
    FilterRegistrationBean<CorrelationIdFilter> correlationIdFilterRegistration() {
        FilterRegistrationBean<CorrelationIdFilter> registration =
                new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.setOrder(CORRELATION_FILTER_ORDER);
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean
    GlobalProblemHandler globalProblemHandler(Clock clock) {
        return new GlobalProblemHandler(clock);
    }
}

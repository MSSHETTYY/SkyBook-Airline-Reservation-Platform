package com.skybook.common.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.skybook.common.error.GlobalProblemHandler;

class SkyBookCommonAutoConfigurationTest {

    private final WebApplicationContextRunner webRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SkyBookCommonAutoConfiguration.class));

    @Test
    void registersWebInfrastructureInServletApps() {
        webRunner.run(context -> {
            assertThat(context).hasSingleBean(GlobalProblemHandler.class).hasSingleBean(Clock.class);
            FilterRegistrationBean<?> registration =
                    context.getBean("correlationIdFilterRegistration", FilterRegistrationBean.class);
            assertThat(registration.getOrder()).isEqualTo(SkyBookCommonAutoConfiguration.CORRELATION_FILTER_ORDER);
            assertThat(registration.getUrlPatterns()).containsExactly("/*");
        });
    }

    @Test
    void backsOffWhenServiceDefinesItsOwnBeans() {
        webRunner.withUserConfiguration(CustomBeans.class).run(context -> {
            assertThat(context).hasSingleBean(Clock.class).hasSingleBean(GlobalProblemHandler.class);
            assertThat(context.getBean(Clock.class)).isSameAs(CustomBeans.FIXED);
            assertThat(context.getBean(GlobalProblemHandler.class)).isSameAs(context.getBean("customHandler"));
        });
    }

    @Test
    void doesNothingOutsideServletApps() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SkyBookCommonAutoConfiguration.class))
                .run(context -> assertThat(context)
                        .doesNotHaveBean(GlobalProblemHandler.class)
                        .doesNotHaveBean("correlationIdFilterRegistration"));
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomBeans {

        static final Clock FIXED = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

        @Bean
        Clock fixedClock() {
            return FIXED;
        }

        @Bean
        GlobalProblemHandler customHandler(Clock clock) {
            return new GlobalProblemHandler(clock);
        }
    }
}

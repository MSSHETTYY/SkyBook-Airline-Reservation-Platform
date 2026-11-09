package com.skybook.booking;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.onionArchitecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Hexagonal (ports and adapters) rules for booking-service, see ADR-0005:
 * <ul>
 *   <li>the domain depends on nothing but the JDK and the shared value types;</li>
 *   <li>the application core depends on the domain and on its own ports, never on adapters;</li>
 *   <li>adapters depend inwards only and never on each other.</li>
 * </ul>
 */
@AnalyzeClasses(packages = "com.skybook.booking", importOptions = ImportOption.DoNotIncludeTests.class)
class BookingArchitectureTest {

    @ArchTest
    static final ArchRule HEXAGON = onionArchitecture()
            .domainModels("..booking.domain.model..")
            .domainServices("..booking.domain.policy..")
            .applicationServices("..booking.application..")
            .adapter("web", "..booking.adapter.in.web..")
            .adapter("scheduling", "..booking.adapter.in.scheduling..")
            .adapter("messaging-in", "..booking.adapter.in.messaging..")
            .adapter("payment", "..booking.adapter.out.payment..")
            .adapter("partners", "..booking.adapter.out.partner..")
            .adapter("messaging-out", "..booking.adapter.out.messaging..")
            .adapter("persistence", "..booking.adapter.out.persistence..")
            .adapter("inventory", "..booking.adapter.out.inventory..")
            .withOptionalLayers(true)
            // The composition root wires adapters to ports; that is its job.
            .ignoreDependency(resideInAPackage("..booking.config.."), DescribedPredicate.alwaysTrue())
            // Shared resilience helper used by several outbound adapters (it maps remote errors to domain codes).
            .ignoreDependency(resideInAPackage("..booking.adapter.out.support.."), DescribedPredicate.alwaysTrue());

    /** The domain is plain Java: no Spring, no JPA, no Jackson, no web. */
    @ArchTest
    static final ArchRule DOMAIN_IS_FRAMEWORK_FREE = classes()
            .that().resideInAPackage("..booking.domain..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "java..", "..booking.domain..", "com.skybook.common.error..", "com.skybook.common.money..");

    /**
     * The application core may use Spring's stereotype/transaction annotations and the technology-neutral
     * DataAccessException hierarchy, but no persistence, web or HTTP-client technology.
     */
    @ArchTest
    static final ArchRule APPLICATION_IS_TECHNOLOGY_NEUTRAL = noClasses()
            .that().resideInAPackage("..booking.application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "jakarta.persistence..", "org.springframework.web..", "org.springframework.data..",
                    "org.springframework.http..", "org.hibernate..", "tools.jackson..", "io.github.resilience4j..");

    /** JPA entities never escape the persistence adapter. */
    @ArchTest
    static final ArchRule ENTITIES_STAY_IN_PERSISTENCE = noClasses()
            .that().resideOutsideOfPackage("..booking.adapter.out.persistence..")
            .should().dependOnClassesThat().areAnnotatedWith(jakarta.persistence.Entity.class);
}

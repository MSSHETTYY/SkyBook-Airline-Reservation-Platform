package com.skybook.payment;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** payment-service is layered (ADR-0005); the provider package is the only one that knows Stripe. */
@AnalyzeClasses(packages = "com.skybook.payment", importOptions = ImportOption.DoNotIncludeTests.class)
class PaymentArchitectureTest {

    @ArchTest
    static final ArchRule LAYERS = layeredArchitecture().consideringOnlyDependenciesInLayers()
            .layer("Web").definedBy("..payment.web..")
            .layer("Api").definedBy("..payment.api..")
            .layer("Service").definedBy("..payment.service..")
            .layer("Provider").definedBy("..payment.provider..")
            .layer("Repository").definedBy("..payment.repository..")
            .layer("Domain").definedBy("..payment.domain..")
            .layer("Config").definedBy("..payment.config..")
            .whereLayer("Web").mayNotBeAccessedByAnyLayer()
            .whereLayer("Api").mayOnlyBeAccessedByLayers("Web")
            .whereLayer("Service").mayOnlyBeAccessedByLayers("Web", "Config")
            .whereLayer("Provider").mayOnlyBeAccessedByLayers("Service", "Config")
            .whereLayer("Repository").mayOnlyBeAccessedByLayers("Service");

    /** Stripe types stay in the provider adapter (and the webhook verifier), never in the domain or web layer. */
    @ArchTest
    static final ArchRule STRIPE_IS_CONTAINED = noClasses()
            .that().resideInAnyPackage("..payment.domain..", "..payment.web..", "..payment.repository..")
            .should().dependOnClassesThat().resideInAPackage("com.stripe..");
}

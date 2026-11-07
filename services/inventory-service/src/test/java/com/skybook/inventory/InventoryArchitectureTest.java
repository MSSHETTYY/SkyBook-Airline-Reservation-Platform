package com.skybook.inventory;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** inventory-service is deliberately a classic layered service (see ADR-0001); these rules keep it so. */
@AnalyzeClasses(packages = "com.skybook.inventory", importOptions = ImportOption.DoNotIncludeTests.class)
class InventoryArchitectureTest {

    @ArchTest
    static final ArchRule LAYERS = layeredArchitecture().consideringOnlyDependenciesInLayers()
            .layer("Web").definedBy("..inventory.web..")
            .layer("Api").definedBy("..inventory.api..")
            .layer("Service").definedBy("..inventory.service..")
            .layer("Repository").definedBy("..inventory.repository..")
            .layer("Domain").definedBy("..inventory.domain..")
            .layer("Config").definedBy("..inventory.config..")
            .whereLayer("Web").mayNotBeAccessedByAnyLayer()
            .whereLayer("Api").mayOnlyBeAccessedByLayers("Web")
            .whereLayer("Service").mayOnlyBeAccessedByLayers("Web", "Config")
            .whereLayer("Repository").mayOnlyBeAccessedByLayers("Service")
            .whereLayer("Domain").mayOnlyBeAccessedByLayers("Service", "Repository", "Web", "Config");

    /** Domain logic stays testable without Spring: entities may use JPA, but not Spring beans or web types. */
    @ArchTest
    static final ArchRule DOMAIN_IS_FREE_OF_SPRING_BEANS_AND_WEB = noClasses()
            .that().resideInAPackage("..inventory.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.stereotype..", "org.springframework.web..",
                    "org.springframework.beans..", "org.springframework.data..");

    /** Generated API models must not leak into services: only the web layer maps them. */
    @ArchTest
    static final ArchRule GENERATED_MODELS_STAY_IN_WEB = noClasses()
            .that().resideOutsideOfPackages("..inventory.web..", "..inventory.api..")
            .should().dependOnClassesThat().resideInAPackage("..inventory.api..");
}

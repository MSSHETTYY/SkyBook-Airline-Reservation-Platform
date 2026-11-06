package com.skybook.common;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.skybook.common", importOptions = ImportOption.DoNotIncludeTests.class)
class CommonArchitectureTest {

    /** Events are shared with every consumer (incl. non-Spring ones), so they must stay framework-free. */
    @ArchTest
    static final ArchRule EVENTS_DEPEND_ONLY_ON_THE_JDK = classes()
            .that().resideInAPackage("..common.event..")
            .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "..common.event..");

    /** The error model must not know about transport details such as servlet filters. */
    @ArchTest
    static final ArchRule ERROR_MODEL_IS_INDEPENDENT_OF_AUTOCONFIGURATION = noClasses()
            .that().resideInAPackage("..common.error..")
            .should().dependOnClassesThat().resideInAPackage("..common.autoconfigure..");

    @ArchTest
    static final ArchRule NO_PACKAGE_CYCLES = slices()
            .matching("com.skybook.common.(*)..")
            .should().beFreeOfCycles();

    /** A shared library must never pull in a concrete business domain. */
    @ArchTest
    static final ArchRule NO_DEPENDENCY_ON_SERVICES = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.skybook.inventory..", "com.skybook.booking..", "com.skybook.payment..",
                    "com.skybook.notification..", "com.skybook.gateway..");
}

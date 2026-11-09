package com.skybook.notification;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** The service talks to ports only; JMS, MongoDB and SMTP stay in the adapters. */
@AnalyzeClasses(packages = "com.skybook.notification", importOptions = ImportOption.DoNotIncludeTests.class)
class NotificationArchitectureTest {

    @ArchTest
    static final ArchRule SERVICE_USES_PORTS_ONLY = noClasses()
            .that().resideInAnyPackage("..notification.service..", "..notification.domain..", "..notification.port..")
            .should().dependOnClassesThat().resideInAnyPackage("..notification.adapter..", "jakarta.jms..",
                    "org.springframework.data.mongodb..", "org.springframework.mail..");
}

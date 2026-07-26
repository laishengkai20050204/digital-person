package com.laishengkai.digitalperson.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@AnalyzeClasses(
        packages = "com.laishengkai.digitalperson",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class ArchitectureTest {
    private static final String[] CORE_PACKAGES = {
            "com.laishengkai.digitalperson.activity..",
            "com.laishengkai.digitalperson.agent..",
            "com.laishengkai.digitalperson.application..",
            "com.laishengkai.digitalperson.conversation..",
            "com.laishengkai.digitalperson.dialogue..",
            "com.laishengkai.digitalperson.experience..",
            "com.laishengkai.digitalperson.memory..",
            "com.laishengkai.digitalperson.person..",
            "com.laishengkai.digitalperson.personality..",
            "com.laishengkai.digitalperson.state.."
    };

    @ArchTest
    static final ArchRule CORE_DOES_NOT_DEPEND_ON_ADAPTERS = noClasses()
            .that().resideInAnyPackage(CORE_PACKAGES)
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.laishengkai.digitalperson.infrastructure..",
                    "com.laishengkai.digitalperson.web.."
            );

    @ArchTest
    static final ArchRule APPLICATION_DOES_NOT_DEPEND_ON_WEB = noClasses()
            .that().resideInAPackage("com.laishengkai.digitalperson.application..")
            .should().dependOnClassesThat().resideInAPackage(
                    "com.laishengkai.digitalperson.web.."
            );

    @ArchTest
    static final ArchRule STATE_IS_INDEPENDENT_OF_HIGHER_LEVEL_CONTEXT = noClasses()
            .that().resideInAPackage("com.laishengkai.digitalperson.state..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.laishengkai.digitalperson.application..",
                    "com.laishengkai.digitalperson.conversation..",
                    "com.laishengkai.digitalperson.memory..",
                    "com.laishengkai.digitalperson.modelcontext..",
                    "com.laishengkai.digitalperson.person..",
                    "com.laishengkai.digitalperson.personality.."
            );

    @ArchTest
    static final ArchRule TOP_LEVEL_PACKAGES_ARE_FREE_OF_CYCLES = slices()
            .matching("com.laishengkai.digitalperson.(*)..")
            .should().beFreeOfCycles();

    @ArchTest
    static final ArchRule CORE_DOES_NOT_DEPEND_ON_JSON_FRAMEWORKS = noClasses()
            .that().resideInAnyPackage(CORE_PACKAGES)
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.fasterxml.jackson..",
                    "tools.jackson.."
            );
}

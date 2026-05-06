package com.company.shop.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.springframework.web.bind.annotation.RestController;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.company.shop", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {

    @ArchTest
    static final ArchRule controllersMustNotAccessRepositoriesDirectly =
            noClasses()
                    .that().resideInAPackage("..controller..")
                    .should().dependOnClassesThat().resideInAPackage("..repository..");

    @ArchTest
    static final ArchRule repositoriesMustNotDependOnControllers =
            noClasses()
                    .that().resideInAPackage("..repository..")
                    .should().dependOnClassesThat().resideInAPackage("..controller..");

    @ArchTest
    static final ArchRule repositoriesMustNotDependOnServices =
            noClasses()
                    .that().resideInAPackage("..repository..")
                    .should().dependOnClassesThat().resideInAPackage("..service..");

    @ArchTest
    static final ArchRule servicesMustNotDependOnControllers =
            noClasses()
                    .that().resideInAPackage("..service..")
                    .should().dependOnClassesThat().resideInAPackage("..controller..");

    @ArchTest
    static final ArchRule dtosMustNotDependOnEntities =
            noClasses()
                    .that().resideInAPackage("..dto..")
                    .should().dependOnClassesThat().resideInAPackage("..entity..");

    @ArchTest
    static final ArchRule entitiesMustNotDependOnDtosControllersServicesOrRepositories =
            noClasses()
                    .that().resideInAPackage("..entity..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("..dto..", "..controller..", "..service..", "..repository..");

    @ArchTest
    static final ArchRule controllersShouldBeAnnotatedWithRestController =
            classes()
                    .that().resideInAPackage("..controller..")
                    .and().haveSimpleNameEndingWith("Controller")
                    .should().beAnnotatedWith(RestController.class);

    @ArchTest
    static final ArchRule repositoriesShouldBeInterfaces =
            classes()
                    .that().resideInAPackage("..repository..")
                    .should().beInterfaces();

}

package com.company.shop.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import java.util.Set;

import org.springframework.web.bind.annotation.RestController;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

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
    static final ArchRule dtosMustNotDependOnEntityClassesExceptEnums =
            classes()
                    .that().resideInAPackage("..dto..")
                    .should(notDependOnNonEnumClassesInEntityPackages());

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
                    .and().haveSimpleNameEndingWith("Repository")
                    .should().beInterfaces();


    // TODO: Expand this rule to additional repositories after CartFacade,
    // UserLookupFacade, CategoryFacade, and other module APIs are introduced.
    @ArchTest
    static final ArchRule orderModuleMustNotDependOnProductRepository =
            noClasses()
                    .that().resideInAPackage("com.company.shop.module.order..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.company.shop.module.product.repository..");


    @ArchTest
    static final ArchRule orderModuleMustNotDependOnProductEntities =
            noClasses()
                    .that().resideInAPackage("com.company.shop.module.order..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.company.shop.module.product.entity..");



    // TODO: Expand cart/order entity boundary rules after order item product snapshot decoupling.
    @ArchTest
    static final ArchRule orderModuleMustNotDependOnCartEntities =
            noClasses()
                    .that().resideInAPackage("com.company.shop.module.order..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("com.company.shop.module.cart.entity..");

    private static ArchCondition<JavaClass> notDependOnNonEnumClassesInEntityPackages() {
        return new ArchCondition<>("not depend on non-enum classes in ..entity.. packages") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                Set<Dependency> dependencies = javaClass.getDirectDependenciesFromSelf();
                for (Dependency dependency : dependencies) {
                    JavaClass targetClass = dependency.getTargetClass();
                    boolean isEntityPackage = targetClass.getPackageName().contains(".entity");
                    boolean isAllowedEnum = targetClass.isEnum();

                    if (isEntityPackage && !isAllowedEnum) {
                        String message = String.format(
                                "%s depends on non-enum entity class %s",
                                javaClass.getName(),
                                targetClass.getName());
                        events.add(SimpleConditionEvent.violated(dependency, message));
                    }
                }
            }
        };
    }
}

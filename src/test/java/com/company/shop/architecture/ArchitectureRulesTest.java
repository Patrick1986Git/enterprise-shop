package com.company.shop.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Pattern BUSINESS_MODULE_PACKAGE = Pattern.compile(
            "^com\\.company\\.shop\\.module\\.([^.]+)(?:\\..*)?$");
    private static final Set<String> BUSINESS_MODULES = Set.of(
            "cart", "category", "notification", "order", "product", "system", "user");
    private static final Map<String, Set<String>> INTERNAL_API_CONSUMERS = Map.of(
            "cart", Set.of("order"),
            "category", Set.of("product"),
            "product", Set.of("cart", "order"),
            "user", Set.of("cart", "order", "product"));
    private static final Map<String, Set<String>> NARROW_INTERNAL_API_CONSUMERS = Map.of(
            "com.company.shop.module.user.api.internal.CurrentUserAssociationFacade",
            Set.of("cart", "product"));

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


    @ArchTest
    static final ArchRule businessModulesMustNotAccessForeignRepositoriesOrServices =
            classes()
                    .that().resideInAPackage("com.company.shop.module..")
                    .should(notDependOnRepositoryOrServiceOwnedByAnotherBusinessModule());

    @ArchTest
    static final ArchRule everyProductionBusinessModuleMustBeRegistered =
            classes()
                    .that().resideInAPackage("com.company.shop.module..")
                    .should(belongToARegisteredBusinessModule());

    @ArchTest
    static final ArchRule internalApisMayOnlyBeUsedByDocumentedConsumerModules =
            classes()
                    .that().resideInAPackage("com.company.shop.module..")
                    .should(onlyAccessInternalApisAllowedForTheirModule());

    @ArchTest
    static final ArchRule notificationMayOnlyAccessTheOrderOutboxBoundary =
            classes()
                    .that().resideInAPackage("com.company.shop.module.notification..")
                    .should(onlyAccessOrderOutboxTypes());

    @ArchTest
    static final ArchRule orderModuleMustNotDependOnProductEntities =
            noClasses()
                    .that().resideInAPackage("com.company.shop.module.order..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.company.shop.module.product.entity..");

    @ArchTest
    static final ArchRule orderModuleMustNotDependOnUserEntities =
            noClasses()
                    .that().resideInAPackage("com.company.shop.module.order..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.company.shop.module.user.entity..");

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

    private static ArchCondition<JavaClass> notDependOnRepositoryOrServiceOwnedByAnotherBusinessModule() {
        return new ArchCondition<>("not depend on another business module's repository or service package") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                String sourceModule = businessModuleOf(javaClass);
                if (sourceModule == null) {
                    return;
                }

                for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
                    JavaClass targetClass = dependency.getTargetClass();
                    String targetModule = businessModuleOf(targetClass);
                    if (targetModule != null
                            && !sourceModule.equals(targetModule)
                            && isRepositoryOrServicePackage(targetClass.getPackageName())) {
                        String message = String.format(
                                "%s in module '%s' directly depends on forbidden %s owned by module '%s'; "
                                        + "use the target module's documented internal API instead",
                                javaClass.getName(),
                                sourceModule,
                                targetClass.getName(),
                                targetModule);
                        events.add(SimpleConditionEvent.violated(dependency, message));
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaClass> belongToARegisteredBusinessModule() {
        return new ArchCondition<>("belong to a registered business module") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                String packageModule = packageModuleOf(javaClass);
                if (packageModule != null && !BUSINESS_MODULES.contains(packageModule)) {
                    String message = String.format(
                            "%s belongs to unregistered module '%s'; add that module to BUSINESS_MODULES and "
                                    + "intentionally review its ownership and internal API relationships",
                            javaClass.getName(),
                            packageModule);
                    events.add(SimpleConditionEvent.violated(javaClass, message));
                }
            }
        };
    }

    private static ArchCondition<JavaClass> onlyAccessInternalApisAllowedForTheirModule() {
        return new ArchCondition<>("only access internal APIs through documented module relationships") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                String sourceModule = businessModuleOf(javaClass);
                if (sourceModule == null) {
                    return;
                }

                for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
                    JavaClass targetClass = dependency.getTargetClass();
                    String targetModule = businessModuleOf(targetClass);
                    if (targetModule != null
                            && !sourceModule.equals(targetModule)
                            && isInternalApiPackage(targetClass.getPackageName())
                            && !allowedInternalApiConsumers(targetClass, targetModule).contains(sourceModule)) {
                        String message = String.format(
                                "%s in module '%s' depends on internal API %s owned by module '%s', but that "
                                        + "owner/consumer relationship is not documented in INTERNAL_API_CONSUMERS",
                                javaClass.getName(),
                                sourceModule,
                                targetClass.getName(),
                                targetModule);
                        events.add(SimpleConditionEvent.violated(dependency, message));
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaClass> onlyAccessOrderOutboxTypes() {
        return new ArchCondition<>("only access order through its outbox boundary") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
                    JavaClass targetClass = dependency.getTargetClass();
                    if ("order".equals(businessModuleOf(targetClass))
                            && !targetClass.getPackageName().startsWith("com.company.shop.module.order.outbox")) {
                        String message = String.format(
                                "%s in module 'notification' depends on forbidden order type %s; notification "
                                        + "may consume only com.company.shop.module.order.outbox",
                                javaClass.getName(),
                                targetClass.getName());
                        events.add(SimpleConditionEvent.violated(dependency, message));
                    }
                }
            }
        };
    }

    private static String businessModuleOf(JavaClass javaClass) {
        String module = packageModuleOf(javaClass);
        return module != null && BUSINESS_MODULES.contains(module) ? module : null;
    }

    private static String packageModuleOf(JavaClass javaClass) {
        Matcher matcher = BUSINESS_MODULE_PACKAGE.matcher(javaClass.getPackageName());
        return matcher.matches() ? matcher.group(1) : null;
    }

    private static boolean isInternalApiPackage(String packageName) {
        return packageName.endsWith(".api.internal") || packageName.contains(".api.internal.");
    }

    private static Set<String> allowedInternalApiConsumers(JavaClass targetClass, String targetModule) {
        return NARROW_INTERNAL_API_CONSUMERS.getOrDefault(
                targetClass.getName(),
                INTERNAL_API_CONSUMERS.getOrDefault(targetModule, Set.of()));
    }

    private static boolean isRepositoryOrServicePackage(String packageName) {
        return packageName.endsWith(".repository")
                || packageName.contains(".repository.")
                || packageName.endsWith(".service")
                || packageName.contains(".service.");
    }
}

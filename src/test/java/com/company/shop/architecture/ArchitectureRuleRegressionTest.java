package com.company.shop.architecture;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.company.shop.module.category.archfixture.ForbiddenInternalApiConsumer;
import com.company.shop.architecture.archfixture.ForbiddenDurableCoordinationClock;
import com.company.shop.architecture.archfixture.InjectedClockBusinessObservation;
import com.company.shop.module.future.archfixture.UnregisteredModuleType;
import com.company.shop.module.user.api.internal.CurrentUserFacade;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

class ArchitectureRuleRegressionTest {

    private final ClassFileImporter importer = new ClassFileImporter();

    @Test
    void internalApiRule_shouldRejectUndocumentedOwnerConsumerRelationship() {
        var classes = importer.importClasses(ForbiddenInternalApiConsumer.class, CurrentUserFacade.class);

        assertThatThrownBy(() -> ArchitectureRulesTest.internalApisMayOnlyBeUsedByDocumentedConsumerModules
                .check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("module 'category'")
                .hasMessageContaining("module 'user'")
                .hasMessageContaining("INTERNAL_API_CONSUMERS");
    }

    @Test
    void moduleRegistryRule_shouldRejectUnregisteredModule() {
        var classes = importer.importClasses(UnregisteredModuleType.class);

        assertThatThrownBy(() -> ArchitectureRulesTest.everyProductionBusinessModuleMustBeRegistered.check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("unregistered module 'future'")
                .hasMessageContaining("BUSINESS_MODULES");
    }

    @Test
    void durableCoordinationTimeRule_shouldRejectDirectWallClockObservation() {
        var classes = importer.importClasses(ForbiddenDurableCoordinationClock.class);
        var rule = ArchRuleDefinition.classes()
                .should(ArchitectureRulesTest.notReadReplicaLocalWallTimeInDurableCoordinationForRegression());

        assertThatThrownBy(() -> rule.check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(ForbiddenDurableCoordinationClock.class.getName())
                .hasMessageContaining("java.time.Instant.now()")
                .hasMessageContaining("repository/database time boundary");
    }

    @Test
    void durableCoordinationTimeRule_shouldAllowInjectedClockObservation() {
        var classes = importer.importClasses(InjectedClockBusinessObservation.class);
        var rule = ArchRuleDefinition.classes()
                .should(ArchitectureRulesTest.notReadReplicaLocalWallTimeInDurableCoordinationForRegression());

        rule.check(classes);
    }
}

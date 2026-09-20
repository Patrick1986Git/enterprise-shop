package com.company.shop.architecture;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.company.shop.module.category.archfixture.ForbiddenInternalApiConsumer;
import com.company.shop.module.future.archfixture.UnregisteredModuleType;
import com.company.shop.module.user.api.internal.CurrentUserFacade;
import com.tngtech.archunit.core.importer.ClassFileImporter;

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
}

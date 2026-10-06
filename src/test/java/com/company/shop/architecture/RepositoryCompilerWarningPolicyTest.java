package com.company.shop.architecture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.company.shop.support.RepositoryCompilerWarningPolicy;

class RepositoryCompilerWarningPolicyTest {

    @TempDir
    Path output;

    @Test
    void compile_shouldRejectRepositoryDeprecationAndUncheckedWarnings() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toRealPath();
        if (!Files.isRegularFile(root.resolve("pom.xml"))
                || !Files.isDirectory(root.resolve("target/classes"))
                || !Files.isDirectory(root.resolve("target/test-classes"))) {
            throw new IllegalStateException("Run this policy through the repository Maven test lifecycle");
        }
        List<Path> sources = RepositoryCompilerWarningPolicy.sources(root);
        RepositoryCompilerWarningPolicy.verifyTrackedSources(root, sources);
        var before = RepositoryCompilerWarningPolicy.snapshot(sources);
        // Use Surefire's actual resolved application/test dependencies, not a maintained jar inventory.
        String classpath = System.getProperty("surefire.test.class.path");
        var evidence = RepositoryCompilerWarningPolicy.compile(sources, output, classpath);
        if (!sources.equals(RepositoryCompilerWarningPolicy.sources(root))
                || !before.equals(RepositoryCompilerWarningPolicy.snapshot(sources))) {
            throw new IllegalStateException("Repository source inventory changed during compilation");
        }
        RepositoryCompilerWarningPolicy.verifyTrackedSources(root, sources);
        RepositoryCompilerWarningPolicy.validate(root, new HashSet<>(sources), evidence);
        System.out.println("Compiler-warning policy: checked " + sources.size() + " repository sources; "
                + "deprecation/unchecked warnings: 0");
    }
}

package com.company.shop.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.company.shop.support.RepositoryCompilerWarningPolicy;
import com.company.shop.support.RepositoryCompilerWarningPolicy.Evidence;
import com.company.shop.support.RepositoryCompilerWarningPolicy.SourceDiagnostic;

class RepositoryCompilerWarningPolicyRegressionTest {

    @TempDir
    Path root;

    @ParameterizedTest
    @ValueSource(strings = {"src/main/java", "src/test/java"})
    void validate_shouldAcceptCleanSource(String sourceRoot) throws Exception {
        Path source = source(sourceRoot + "/Clean.java", "class Clean { int value() { return 1; } }");
        validate(Set.of(source), compile(List.of(source)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"src/main/java", "src/test/java"})
    void validate_shouldRejectRealDeprecatedApiDiagnostic(String sourceRoot) throws Exception {
        Path source = source(sourceRoot + "/Use.java", "class Use { void use() { new Old().old(); } }");
        Path api = source("target/generated-sources/Old.java",
                "class Old { @Deprecated void old() {} }");
        var evidence = compile(List.of(source, api));
        assertThat(evidence.diagnostics()).anySatisfy(d -> {
            assertThat(d.source()).isEqualTo(source.toUri());
            assertThat(d.code()).isEqualTo("compiler.warn.has.been.deprecated");
        });
        assertThatThrownBy(() -> validate(Set.of(source, api), evidence))
                .hasMessageContaining(sourceRoot + "/Use.java")
                .hasMessageContaining("compiler.warn.has.been.deprecated");
    }

    @ParameterizedTest
    @ValueSource(strings = {"src/main/java", "src/test/java"})
    void validate_shouldRejectRealUncheckedDiagnostic(String sourceRoot) throws Exception {
        Path source = source(sourceRoot + "/Unsafe.java", uncheckedSource());
        var evidence = compile(List.of(source));
        assertThat(evidence.diagnostics()).anySatisfy(d ->
                assertThat(d.code()).isEqualTo("compiler.warn.prob.found.req"));
        assertThatThrownBy(() -> validate(Set.of(source), evidence))
                .hasMessageContaining(sourceRoot + "/Unsafe.java")
                .hasMessageContaining("compiler.warn.prob.found.req");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "java.util.List<String> use(java.util.List value) { return value; }",
            "void use(java.util.List value) { value.add(1); }",
            "static <T> void take(java.util.List<T>... values) {} void use(java.util.List<String> value) { take(value); }"})
    void validate_shouldRejectOtherUncheckedDiagnosticForms(String body) throws Exception {
        Path source = source("src/main/java/Unsafe.java", "class Unsafe { " + body + " }");
        var evidence = compile(List.of(source));
        assertThat(evidence.diagnostics()).isNotEmpty();
        assertThatThrownBy(() -> validate(Set.of(source), evidence))
                .hasMessageContaining("Repository compiler warnings");
    }

    @ParameterizedTest
    @ValueSource(strings = {"target/generated-sources", "target/generated-test-sources"})
    void validate_shouldExcludeRealGeneratedUncheckedDiagnostic(String generatedRoot) throws Exception {
        Path source = source(generatedRoot + "/Unsafe.java", uncheckedSource());
        var evidence = compile(List.of(source));
        assertThat(evidence.diagnostics()).isNotEmpty();
        assertThatCode(() -> validate(Set.of(source), evidence)).doesNotThrowAnyException();
    }

    @Test
    void validate_shouldExcludeProcessorWarningEvenWhenItNamesRepositorySource() throws Exception {
        Path source = source("src/main/java/Clean.java", "class Clean {}");
        validate(Set.of(source), evidence(source, new SourceDiagnostic(Diagnostic.Kind.WARNING,
                "compiler.warn.proc.messager", source.toUri(), 1, 1)));
    }

    @Test
    void validate_shouldExcludeClasspathAnnotationDiagnostic() throws Exception {
        Path source = source("src/main/java/Clean.java", "class Clean {}");
        validate(Set.of(source), evidence(source, new SourceDiagnostic(Diagnostic.Kind.WARNING,
                "compiler.warn.annotation.method.not.found", null, -1, -1)));
    }

    @Test
    void compile_shouldProveWerrorRejectsGeneratedWarnings() throws Exception {
        Path source = source("target/generated-sources/Unsafe.java", uncheckedSource());
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = compiler.getTask(null, manager, diagnostics,
                    List.of("--release", "21", "-proc:none", "-Xlint:deprecation,unchecked", "-Werror"),
                    null, manager.getJavaFileObjects(source));
            assertThat(task.call()).isFalse();
            assertThat(diagnostics.getDiagnostics()).anySatisfy(d -> {
                assertThat(d.getKind()).isEqualTo(Diagnostic.Kind.MANDATORY_WARNING);
                assertThat(d.getSource().toUri()).isEqualTo(source.toUri());
            });
        }
    }

    @Test
    void validate_shouldRejectWarningSummaryWithoutDetailedEvidence() throws Exception {
        Path source = source("src/main/java/Clean.java", "class Clean {}");
        var diagnostic = new SourceDiagnostic(Diagnostic.Kind.NOTE,
                "compiler.note.unchecked.filename", source.toUri(), -1, -1);
        assertThatThrownBy(() -> validate(Set.of(source), evidence(source, diagnostic)))
                .hasMessageContaining("Incomplete source-warning diagnostics");
    }

    @Test
    void snapshot_shouldDetectSourceChanges() throws Exception {
        Path source = source("src/main/java/Clean.java", "class Clean {}");
        var before = RepositoryCompilerWarningPolicy.snapshot(List.of(source));
        Files.writeString(source, "class Clean { int added; }");
        assertThat(RepositoryCompilerWarningPolicy.snapshot(List.of(source))).isNotEqualTo(before);
    }

    @Test
    void verifyTrackedSources_shouldRejectJavaTrackedUnderBuildOutput() throws Exception {
        Path source = source("src/main/java/Clean.java", "class Clean {}");
        assertThat(new ProcessBuilder("git", "init", "-q", root.toString()).start().waitFor()).isZero();
        assertThat(new ProcessBuilder("git", "-C", root.toString(), "add", "src/main/java/Clean.java")
                .start().waitFor()).isZero();
        RepositoryCompilerWarningPolicy.verifyTrackedSources(root, List.of(source));
        source("target/generated-sources/Owned.java", "class Owned {}");
        assertThat(new ProcessBuilder("git", "-C", root.toString(), "add", "-f", "target/generated-sources/Owned.java")
                .start().waitFor()).isZero();
        assertThatThrownBy(() -> RepositoryCompilerWarningPolicy.verifyTrackedSources(root, List.of(source)))
                .hasMessageContaining("Tracked Java source escaped ownership inventory");
    }

    @Test
    void verifyTrackedSources_shouldRejectMissingGitEvidence() throws Exception {
        Path source = source("src/main/java/Clean.java", "class Clean {}");
        assertThatThrownBy(() -> RepositoryCompilerWarningPolicy.verifyTrackedSources(root, List.of(source)))
                .hasMessageContaining("Missing or malformed tracked Java source inventory");
    }

    @Test
    void validate_shouldNotReadMavenMetadataLogs() throws Exception {
        Path source = source("src/main/java/Clean.java", "class Clean {}");
        source("maven.log", "[WARNING] Could not transfer metadata from Redgate: HTTP 403\n");
        validate(Set.of(source), compile(List.of(source)));
    }

    @Test
    void validate_shouldRejectMissingOrUnsuccessfulEvidence() throws Exception {
        Path source = source("src/main/java/Clean.java", "class Clean {}");
        for (Evidence evidence : new Evidence[] {null, new Evidence(null, Set.of(source), List.of(), ""),
                new Evidence(false, Set.of(source), List.of(), ""),
                new Evidence(true, Set.of(), List.of(), ""),
                new Evidence(true, Set.of(source), null, ""),
                new Evidence(true, Set.of(source), List.of(), "unexpected compiler output")}) {
            assertThatThrownBy(() -> validate(Set.of(source), evidence))
                    .hasMessageContaining("compiler evidence");
        }
    }

    @Test
    void validate_shouldRejectMalformedOrUnattributedDiagnostic() throws Exception {
        Path source = source("src/main/java/Clean.java", "class Clean {}");
        for (SourceDiagnostic diagnostic : List.of(
                new SourceDiagnostic(null, "compiler.warn.unchecked.assign", source.toUri(), 1, 1),
                new SourceDiagnostic(Diagnostic.Kind.WARNING, "", source.toUri(), 1, 1),
                new SourceDiagnostic(Diagnostic.Kind.WARNING, "compiler.warn.unchecked.assign", null, 1, 1),
                new SourceDiagnostic(Diagnostic.Kind.WARNING, "compiler.warn.unchecked.assign", source.toUri(), -1, -1))) {
            assertThatThrownBy(() -> validate(Set.of(source), evidence(source, diagnostic)))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void validate_shouldRejectUnknownSourceOwnership() throws Exception {
        Path source = source("src/main/java/Clean.java", "class Clean {}");
        Path unknown = source("additional-sources/New.java", "class New {}");
        var diagnostic = new SourceDiagnostic(Diagnostic.Kind.WARNING, "compiler.warn.unchecked.assign",
                unknown.toUri(), 1, 1);
        assertThatThrownBy(() -> validate(Set.of(source), evidence(source, diagnostic)))
                .hasMessageContaining("Unreviewed source-warning ownership");
    }

    @Test
    void sources_shouldDiscoverNewPackagesAndRejectNewRoots() throws Exception {
        source("src/main/java/Clean.java", "class Clean {}");
        source("src/test/java/Test.java", "class Test {}");
        Path added = source("src/main/java/new/package/New.java", "package newpackage; class New {}");
        assertThat(RepositoryCompilerWarningPolicy.sources(root)).contains(added);
        source("extra/java/Bypass.java", "class Bypass {}");
        assertThatThrownBy(() -> RepositoryCompilerWarningPolicy.sources(root))
                .hasMessageContaining("Unreviewed repository source path");
    }

    @Test
    void sources_shouldRejectMissingTestInventoryAndSymlinks() throws Exception {
        source("src/main/java/Clean.java", "class Clean {}");
        assertThatThrownBy(() -> RepositoryCompilerWarningPolicy.sources(root))
                .hasMessageContaining("Missing production or test source inventory");
        source("src/test/java/Test.java", "class Test {}");
        Files.createSymbolicLink(root.resolve("src/main/java/linked"), root.resolve("src/test/java"));
        assertThatThrownBy(() -> RepositoryCompilerWarningPolicy.sources(root))
                .hasMessageContaining("symlink");
    }

    private Path source(String relative, String text) throws Exception {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        return Files.writeString(path, text).toRealPath();
    }

    private Evidence compile(List<Path> sources) throws Exception {
        return RepositoryCompilerWarningPolicy.compile(sources, root.resolve("target/audit-classes"),
                System.getProperty("surefire.test.class.path"));
    }

    private Evidence evidence(Path source, SourceDiagnostic diagnostic) {
        return new Evidence(true, Set.of(source), List.of(diagnostic), "");
    }

    private void validate(Set<Path> sources, Evidence evidence) throws Exception {
        RepositoryCompilerWarningPolicy.validate(root, new HashSet<>(sources), evidence);
    }

    private String uncheckedSource() {
        return "class Unsafe { java.util.List<String> use(Object value) { return (java.util.List<String>) value; } }";
    }
}

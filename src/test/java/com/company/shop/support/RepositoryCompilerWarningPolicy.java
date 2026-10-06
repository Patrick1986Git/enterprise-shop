package com.company.shop.support;

import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;

/** Source-only audit after the normal Maven compilation has run both processors. */
public final class RepositoryCompilerWarningPolicy {

    private RepositoryCompilerWarningPolicy() {
    }

    public static List<Path> sources(Path root) throws IOException {
        Path repository = root.toRealPath();
        List<Path> sources = new ArrayList<>();
        Files.walkFileTree(repository, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                if (directory.equals(repository.resolve(".git"))
                        || directory.equals(repository.resolve("target"))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (attributes.isSymbolicLink()) {
                    throw new IllegalStateException("Unreviewed source-tree symlink: " + file);
                }
                if (file.toString().endsWith(".java")) {
                    Path source = file.toRealPath();
                    if (!source.startsWith(repository.resolve("src/main/java"))
                            && !source.startsWith(repository.resolve("src/test/java"))) {
                        throw new IllegalStateException("Unreviewed repository source path: " + source);
                    }
                    sources.add(source);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        if (sources.stream().noneMatch(p -> p.startsWith(repository.resolve("src/main/java")))
                || sources.stream().noneMatch(p -> p.startsWith(repository.resolve("src/test/java")))) {
            throw new IllegalStateException("Missing production or test source inventory");
        }
        return sources.stream().sorted().toList();
    }

    public static Evidence compile(List<Path> sources, Path output, String classpath) throws IOException {
        if (Runtime.version().feature() != 21 || ToolProvider.getSystemJavaCompiler() == null) {
            throw new IllegalStateException("The compiler-warning policy requires a Java 21 JDK");
        }
        if (sources.isEmpty() || classpath == null || classpath.isBlank()) {
            throw new IllegalStateException("Missing source or classpath evidence");
        }
        Files.createDirectories(output);
        Path emptySourcePath = Files.createDirectory(output.resolve("empty-sourcepath"));
        var compiler = ToolProvider.getSystemJavaCompiler();
        var collector = new DiagnosticCollector<JavaFileObject>();
        Set<Path> parsed = new HashSet<>();
        var compilerOutput = new StringWriter();
        try (var manager = compiler.getStandardFileManager(collector, Locale.ROOT, StandardCharsets.UTF_8)) {
            var units = manager.getJavaFileObjectsFromPaths(sources);
            // Generated implementations/metamodels already exist on the Maven test classpath.
            // This audit does not rerun processors or replace the real compiler lifecycle.
            var task = (JavacTask) compiler.getTask(compilerOutput, manager, collector,
                    List.of("--release", "21", "-encoding", "UTF-8", "-proc:none",
                            "-Xlint:deprecation,unchecked", "-Xmaxwarns", "2147483647",
                            "-implicit:none", "-sourcepath",
                            emptySourcePath.toString(), "-classpath", classpath, "-d", output.toString()),
                    null, units);
            task.addTaskListener(new TaskListener() {
                @Override
                public void finished(TaskEvent event) {
                    if (event.getKind() == TaskEvent.Kind.PARSE) {
                        parsed.add(Path.of(event.getSourceFile().toUri()).toAbsolutePath().normalize());
                    }
                }
            });
            boolean completed = Boolean.TRUE.equals(task.call());
            List<SourceDiagnostic> diagnostics = collector.getDiagnostics().stream()
                    .map(d -> new SourceDiagnostic(d.getKind(), d.getCode(),
                            d.getSource() == null ? null : d.getSource().toUri(),
                            d.getLineNumber(), d.getColumnNumber()))
                    .toList();
            return new Evidence(completed, Set.copyOf(parsed), diagnostics, compilerOutput.toString());
        }
    }

    public static Map<Path, String> snapshot(List<Path> sources) throws IOException {
        Map<Path, String> snapshot = new TreeMap<>();
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (Path source : sources) {
                snapshot.put(source, HexFormat.of().formatHex(digest.digest(Files.readAllBytes(source))));
            }
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Missing SHA-256 support", exception);
        }
        return snapshot;
    }

    public static void validate(Path root, Set<Path> expectedSources, Evidence evidence) throws IOException {
        Path repository = root.toRealPath();
        if (evidence == null || !Boolean.TRUE.equals(evidence.completed())
                || expectedSources == null || expectedSources.isEmpty()
                || evidence.parsedSources() == null || !evidence.parsedSources().equals(expectedSources)
                || evidence.diagnostics() == null || evidence.unstructuredOutput() == null
                || !evidence.unstructuredOutput().isBlank()) {
            throw new IllegalStateException("Missing, incomplete or unstructured compiler evidence: " + evidence);
        }
        List<String> violations = new ArrayList<>();
        for (SourceDiagnostic diagnostic : evidence.diagnostics()) {
            if (diagnostic == null || diagnostic.kind() == null
                    || diagnostic.code() == null || diagnostic.code().isBlank()) {
                throw new IllegalStateException("Malformed compiler diagnostic");
            }
            if (diagnostic.kind() == Diagnostic.Kind.ERROR) {
                throw new IllegalStateException("Compilation failed: " + diagnostic);
            }
            boolean warning = diagnostic.kind() == Diagnostic.Kind.WARNING
                    || diagnostic.kind() == Diagnostic.Kind.MANDATORY_WARNING;
            boolean reviewedFamily = diagnostic.code().equals("compiler.warn.has.been.deprecated")
                    || diagnostic.code().equals("compiler.warn.has.been.deprecated.for.removal")
                    // javac wraps unchecked casts/conversions in this found/required diagnostic.
                    || diagnostic.code().equals("compiler.warn.prob.found.req")
                    || diagnostic.code().startsWith("compiler.warn.unchecked.");
            // Messager warnings can point at a repository Element; their code owns them.
            if (diagnostic.code().equals("compiler.warn.proc.messager")
                    || diagnostic.code().equals("compiler.note.proc.messager")) {
                continue;
            }
            if (diagnostic.code().startsWith("compiler.note.deprecated.")
                    || diagnostic.code().startsWith("compiler.note.unchecked.")) {
                throw new IllegalStateException("Incomplete source-warning diagnostics: " + diagnostic);
            }
            if (!warning || !reviewedFamily) {
                continue;
            }
            if (diagnostic.source() == null || !"file".equals(diagnostic.source().getScheme())
                    || diagnostic.line() < 1 || diagnostic.column() < 1) {
                throw new IllegalStateException("Unattributed source-warning evidence: " + diagnostic);
            }
            Path source = Path.of(diagnostic.source()).toRealPath();
            if (source.startsWith(repository.resolve("target/generated-sources"))
                    || source.startsWith(repository.resolve("target/generated-test-sources"))) {
                continue;
            }
            if (!expectedSources.contains(source)) {
                throw new IllegalStateException("Unreviewed source-warning ownership: " + diagnostic);
            }
            violations.add(repository.relativize(source) + ":" + diagnostic.line() + ":"
                    + diagnostic.column() + " " + diagnostic.code());
        }
        if (!violations.isEmpty()) {
            throw new IllegalStateException("Repository compiler warnings:\n"
                    + String.join("\n", violations.stream().sorted().toList()));
        }
    }

    public record SourceDiagnostic(Diagnostic.Kind kind, String code, URI source, long line, long column) {
    }

    public record Evidence(Boolean completed, Set<Path> parsedSources, List<SourceDiagnostic> diagnostics,
            String unstructuredOutput) {
    }
}

package com.company.shop.persistence.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VerifiedPythonInterpreterTest {

    private static final String IMAGE = "sha256:" + "a".repeat(64);
    @TempDir
    Path temporary;
    private Path executable;
    private Path verifier;
    private Path receipt;

    @BeforeEach
    void setUp() throws Exception {
        executable = Files.writeString(temporary.resolve("prepared-python"), "trusted executable fixture").toRealPath();
        assertThat(executable.toFile().setExecutable(true)).isTrue();
        verifier = Files.writeString(temporary.resolve("verifier.py"), "reviewed verifier fixture");
        receipt = temporary.resolve("identity.json");
        write(executable.toString(), IMAGE);
    }

    private void write(String command, String image) throws Exception {
        Files.writeString(receipt, new JsonMapper().writeValueAsString(Map.of(
                "executable", command, "sha256", digest(executable), "image_config", image,
                "verifier_source_sha256", digest(verifier))));
    }

    private static String digest(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    @Test
    void resolve_shouldReturnOnlyThePreparedAbsoluteIdentity() throws Exception {
        assertThat(VerifiedPythonInterpreter.resolve(receipt, verifier, IMAGE)).isEqualTo(executable);
    }

    @Test
    void resolve_shouldRejectRelativeCommandsEvenWithAMatchingDigest() throws Exception {
        write("python3", IMAGE);
        assertThatThrownBy(() -> VerifiedPythonInterpreter.resolve(receipt, verifier, IMAGE))
                .hasMessageContaining("absolute real path");
    }

    @Test
    void resolve_shouldRejectSubstitutedExecutablePaths() throws Exception {
        Path substituted = Files.writeString(temporary.resolve("other-python"), "substituted executable");
        assertThat(substituted.toFile().setExecutable(true)).isTrue();
        write(substituted.toString(), IMAGE);
        assertThatThrownBy(() -> VerifiedPythonInterpreter.resolve(receipt, verifier, IMAGE))
                .hasMessageContaining("identity changed");
    }

    @Test
    void resolve_shouldRejectChangedExecutableBytes() throws Exception {
        Files.writeString(executable, "changed executable");
        assertThatThrownBy(() -> VerifiedPythonInterpreter.resolve(receipt, verifier, IMAGE))
                .hasMessageContaining("identity changed");
    }

    @Test
    void resolve_shouldRejectSymlinkCommands() throws Exception {
        Path link = Files.createSymbolicLink(temporary.resolve("python-link"), executable);
        write(link.toString(), IMAGE);
        assertThatThrownBy(() -> VerifiedPythonInterpreter.resolve(receipt, verifier, IMAGE))
                .hasMessageContaining("absolute real path");
    }

    @Test
    void resolve_shouldRejectAnotherImageOrChangedVerifier() throws Exception {
        write(executable.toString(), "sha256:" + "b".repeat(64));
        assertThatThrownBy(() -> VerifiedPythonInterpreter.resolve(receipt, verifier, IMAGE))
                .hasMessageContaining("Ryuk configuration");
        write(executable.toString(), IMAGE);
        Files.writeString(verifier, "modified verifier");
        assertThatThrownBy(() -> VerifiedPythonInterpreter.resolve(receipt, verifier, IMAGE))
                .hasMessageContaining("verifier source changed");
    }

    @Test
    void resolve_shouldRejectMissingOversizedAndDuplicateIdentityEvidence() throws Exception {
        String original = Files.readString(receipt);
        Files.delete(receipt);
        assertThatThrownBy(() -> VerifiedPythonInterpreter.resolve(receipt, verifier, IMAGE))
                .hasMessageContaining("preparation identity");
        Files.writeString(receipt, " ".repeat(8193));
        assertThatThrownBy(() -> VerifiedPythonInterpreter.resolve(receipt, verifier, IMAGE))
                .hasMessageContaining("preparation identity");
        Files.writeString(receipt, "{\"executable\":\"python3\"," + original.substring(1));
        assertThatThrownBy(() -> VerifiedPythonInterpreter.resolve(receipt, verifier, IMAGE)).isInstanceOf(Exception.class);
    }
}

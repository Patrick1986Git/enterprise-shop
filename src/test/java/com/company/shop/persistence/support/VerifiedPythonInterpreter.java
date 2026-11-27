package com.company.shop.persistence.support;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

final class VerifiedPythonInterpreter {

    private VerifiedPythonInterpreter() {
    }

    static Path resolve(Path receipt, Path verifier, String expectedImage) throws Exception {
        require(Files.isRegularFile(receipt, LinkOption.NOFOLLOW_LINKS) && Files.size(receipt) <= 8192,
                "Missing or oversized Python preparation identity; prepare reviewed Ryuk again");
        var mapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
        var identity = mapper.readTree(Files.readString(receipt));
        require(identity.isObject(), "Malformed Python preparation identity");
        for (String field : new String[]{"executable", "sha256", "image_config", "verifier_source_sha256"}) {
            require(identity.path(field).isString(), "Missing Python preparation identity field");
        }
        require(identity.path("image_config").asString().equals(expectedImage), "Wrong prepared Ryuk configuration");
        Path executable = Path.of(identity.path("executable").asString());
        require(executable.isAbsolute() && Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)
                && Files.isExecutable(executable) && executable.equals(executable.toRealPath()),
                "Python interpreter must be an executable absolute real path");
        require(identity.path("sha256").asString().matches("[0-9a-f]{64}")
                && digest(executable).equals(identity.path("sha256").asString()), "Python interpreter identity changed");
        require(digest(verifier).equals(identity.path("verifier_source_sha256").asString()),
                "Ryuk verifier source changed; prepare reviewed Ryuk again");
        return executable;
    }

    private static String digest(Path path) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536];
            int length;
            while ((length = input.read(buffer)) != -1) digest.update(buffer, 0, length);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }
}

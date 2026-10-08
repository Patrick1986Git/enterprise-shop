package com.company.shop.persistence.support;

import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class RyukExecutionIT extends PostgresContainerSupport {

    @Test
    void ryuk_shouldExecutePinnedOfficialImageAndRegisterCleanupResources() throws Exception {
        Properties configuration = new Properties();
        try (var input = getClass().getResourceAsStream("/testcontainers.properties")) {
            assertThat(input).isNotNull();
            configuration.load(input);
        }
        String expected = configuration.getProperty("ryuk.container.image");
        var docker = DockerClientFactory.instance().client();
        var ryuk = docker.listContainersCmd()
                .withLabelFilter(Map.of("org.testcontainers.ryuk", "true"))
                .exec();
        assertThat(ryuk).hasSize(1);
        var actual = docker.inspectContainerCmd(ryuk.getFirst().getId()).exec();
        assertThat(actual.getConfig().getImage()).isEqualTo(expected);
        assertThat(actual.getState().getRunning()).isTrue();
        assertThat(actual.getImageId()).isEqualTo(docker.inspectImageCmd(expected).exec().getId());
        var resources = docker.listContainersCmd()
                .withLabelFilter(Map.of("org.testcontainers", "true"))
                .exec();
        assertThat(resources).anyMatch(container -> container.getImage().startsWith("postgres:"));

        Properties evidence = new Properties();
        evidence.setProperty("reference", expected);
        evidence.setProperty("image_id", actual.getImageId());
        evidence.setProperty("ryuk_container_id", actual.getId());
        evidence.setProperty("cleanup_container_ids", resources.stream()
                .map(container -> container.getId()).collect(Collectors.joining(",")));
        Path output = Path.of("target", "ryuk-compatibility", "execution.properties");
        Files.createDirectories(output.getParent());
        try (var writer = Files.newBufferedWriter(output)) {
            evidence.store(writer, "Actual Testcontainers startup; CI checks resource removal after JVM exit");
        }
        System.out.println("Ryuk execution verified: " + expected + " " + actual.getImageId());
    }
}

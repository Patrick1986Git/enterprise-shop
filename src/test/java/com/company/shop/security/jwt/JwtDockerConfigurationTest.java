package com.company.shop.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import org.junit.jupiter.api.Test;

class JwtDockerConfigurationTest {

    private static final String DEVELOPMENT_SECRET =
            "ZGV2LW9ubHktand0LXNpZ25pbmcta2V5LTMyLWJ5dGVzISE=";

    @Test
    void composeDefault_shouldMatchValidDevelopmentBase64SecretContract() throws Exception {
        String compose = Files.readString(Path.of("docker-compose.yml"));
        String developmentConfiguration = Files.readString(
                Path.of("src/main/resources/application-dev.yml"));

        assertThat(compose).contains("JWT_SECRET: ${JWT_SECRET:-" + DEVELOPMENT_SECRET + "}");
        assertThat(developmentConfiguration)
                .contains("secret: ${JWT_SECRET:" + DEVELOPMENT_SECRET + "}");
        assertThat(Base64.getDecoder().decode(DEVELOPMENT_SECRET)).hasSizeGreaterThanOrEqualTo(32);
    }
}

package com.company.shop.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class CorsPropertiesTest {

    @Test
    void constructor_shouldNormalizeExplicitOrigins() {
        CorsProperties properties = new CorsProperties(List.of(
                " https://shop.example ", "", "https://shop.example", "http://localhost:3000"));

        assertThat(properties.allowedOrigins())
                .containsExactly("https://shop.example", "http://localhost:3000");
    }

    @Test
    void binding_shouldAcceptCommaSeparatedEnvironmentStyleValue() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource();
        source.put("app.security.cors.allowed-origins", "https://shop.example, https://admin.example");

        CorsProperties properties = new Binder(source)
                .bind("app.security.cors", Bindable.of(CorsProperties.class))
                .get();

        assertThat(properties.allowedOrigins())
                .containsExactly("https://shop.example", "https://admin.example");
    }

    @Test
    void constructor_shouldAllowEmptyOriginsForSameOriginDeployment() {
        assertThat(new CorsProperties(null).allowedOrigins()).isEmpty();
    }

    @Test
    void constructor_shouldRejectWildcardOrigin() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CorsProperties(List.of("*")));
    }

    @Test
    void constructor_shouldRejectValuesThatAreNotOrigins() {
        for (String invalidOrigin : List.of(
                "not a URI", "ftp://shop.example", "https://shop.example/path",
                "https://shop.example?query=value", "https://shop.example#fragment")) {
            assertThatIllegalArgumentException()
                    .as(invalidOrigin)
                    .isThrownBy(() -> new CorsProperties(List.of(invalidOrigin)));
        }
    }
}

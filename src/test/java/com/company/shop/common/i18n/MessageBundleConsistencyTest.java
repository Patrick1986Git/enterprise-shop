package com.company.shop.common.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;

import org.junit.jupiter.api.Test;

class MessageBundleConsistencyTest {

    private static final String MESSAGES_DEFAULT = "i18n/messages.properties";
    private static final String MESSAGES_EN = "i18n/messages_en.properties";
    private static final String MESSAGES_PL = "i18n/messages_pl.properties";

    @Test
    void shouldHaveSameKeysAcrossAllBundles() throws IOException {
        Properties defaultMessages = load(MESSAGES_DEFAULT);
        Properties englishMessages = load(MESSAGES_EN);
        Properties polishMessages = load(MESSAGES_PL);

        assertSameKeys(defaultMessages, MESSAGES_DEFAULT, englishMessages, MESSAGES_EN);
        assertSameKeys(polishMessages, MESSAGES_PL, englishMessages, MESSAGES_EN);
    }

    @Test
    void shouldNotContainBlankKeysOrValues() throws IOException {
        assertNoBlankKeysOrValues(load(MESSAGES_DEFAULT), MESSAGES_DEFAULT);
        assertNoBlankKeysOrValues(load(MESSAGES_EN), MESSAGES_EN);
        assertNoBlankKeysOrValues(load(MESSAGES_PL), MESSAGES_PL);
    }

    @Test
    void shouldContainTrimmedKeys() throws IOException {
        assertTrimmedKeys(load(MESSAGES_DEFAULT), MESSAGES_DEFAULT);
        assertTrimmedKeys(load(MESSAGES_EN), MESSAGES_EN);
        assertTrimmedKeys(load(MESSAGES_PL), MESSAGES_PL);
    }

    private Properties load(String resourcePath) throws IOException {
        try (InputStream inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath)) {
            assertThat(inputStream)
                    .as("Resource '%s' should exist on classpath", resourcePath)
                    .isNotNull();
            Properties properties = new Properties();
            properties.load(inputStream);
            return properties;
        }
    }

    private void assertSameKeys(Properties actualBundle, String actualBundleName,
                                Properties expectedBundle, String expectedBundleName) {
        Set<String> actualKeys = actualBundle.stringPropertyNames();
        Set<String> expectedKeys = expectedBundle.stringPropertyNames();

        Set<String> missingKeys = new LinkedHashSet<>(expectedKeys);
        missingKeys.removeAll(actualKeys);

        Set<String> extraKeys = new LinkedHashSet<>(actualKeys);
        extraKeys.removeAll(expectedKeys);

        assertThat(missingKeys)
                .as("Missing keys in %s compared to %s:%n%s", actualBundleName, expectedBundleName, formatKeys(missingKeys))
                .isEmpty();

        assertThat(extraKeys)
                .as("Extra keys in %s compared to %s:%n%s", actualBundleName, expectedBundleName, formatKeys(extraKeys))
                .isEmpty();
    }

    private void assertNoBlankKeysOrValues(Properties properties, String bundleName) {
        Set<String> blankKeys = new LinkedHashSet<>();
        Set<String> blankValues = new LinkedHashSet<>();

        for (String key : properties.stringPropertyNames()) {
            if (key == null || key.trim().isEmpty()) {
                blankKeys.add(String.valueOf(key));
            }

            String value = properties.getProperty(key);
            if (value == null || value.trim().isEmpty()) {
                blankValues.add(key);
            }
        }

        assertThat(blankKeys)
                .as("Blank keys in %s:%n%s", bundleName, formatKeys(blankKeys))
                .isEmpty();

        assertThat(blankValues)
                .as("Blank values in %s:%n%s", bundleName, formatKeys(blankValues))
                .isEmpty();
    }

    private void assertTrimmedKeys(Properties properties, String bundleName) {
        Set<String> untrimmedKeys = new LinkedHashSet<>();

        for (String key : properties.stringPropertyNames()) {
            if (!key.equals(key.trim())) {
                untrimmedKeys.add(key);
            }
        }

        assertThat(untrimmedKeys)
                .as("Keys with leading/trailing whitespace in %s:%n%s", bundleName, formatKeys(untrimmedKeys))
                .isEmpty();
    }

    private String formatKeys(Set<String> keys) {
        if (keys.isEmpty()) {
            return "(none)";
        }
        return keys.stream()
                .sorted()
                .map(key -> "- " + key)
                .reduce((left, right) -> left + System.lineSeparator() + right)
                .orElse("(none)");
    }
}

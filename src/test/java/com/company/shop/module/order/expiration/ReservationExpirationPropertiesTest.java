package com.company.shop.module.order.expiration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ReservationExpirationPropertiesTest {

    @Test
    void defaults_shouldMatchApprovedReservationAndWorkerPolicy() {
        var properties = new ReservationExpirationProperties();

        assertThat(properties.duration()).isEqualTo(Duration.ofMinutes(30));
        assertThat(properties.fixedDelay()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.retryDelay()).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.claimLease()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.batchSize()).isEqualTo(25);
        assertThat(properties.maxAttempts()).isEqualTo(10);
    }

    @ParameterizedTest(name = "{0} accepts a positive duration")
    @MethodSource("durationSetters")
    void durationSetter_shouldAcceptPositiveDuration(String name,
            Consumer<ReservationExpirationProperties> setter) {
        var properties = new ReservationExpirationProperties();

        setter.accept(properties);

        assertThat(durationValue(properties, name)).isEqualTo(Duration.ofSeconds(1));
    }

    @ParameterizedTest(name = "{0} rejects {1}")
    @MethodSource("invalidDurations")
    void durationSetter_shouldRejectNonPositiveOrMissingDuration(String name, Duration value,
            Consumer<ReservationExpirationProperties> setter) {
        var properties = new ReservationExpirationProperties();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> setter.accept(properties))
                .withMessage(name + " must be positive");
    }

    @Test
    void batchSize_shouldAcceptPositiveValueAndRejectNonPositiveValues() {
        var properties = new ReservationExpirationProperties();

        properties.setBatchSize(1);
        assertThat(properties.batchSize()).isEqualTo(1);
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setBatchSize(0));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setBatchSize(-1));
    }

    @Test
    void maxAttempts_shouldAcceptPositiveValueAndRejectNonPositiveValues() {
        var properties = new ReservationExpirationProperties();

        properties.setMaxAttempts(1);
        assertThat(properties.maxAttempts()).isEqualTo(1);
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setMaxAttempts(0));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setMaxAttempts(-1));
    }

    private static Stream<Arguments> durationSetters() {
        return Stream.of(
                Arguments.of("duration", setter("duration", Duration.ofSeconds(1))),
                Arguments.of("fixedDelay", setter("fixedDelay", Duration.ofSeconds(1))),
                Arguments.of("retryDelay", setter("retryDelay", Duration.ofSeconds(1))),
                Arguments.of("claimLease", setter("claimLease", Duration.ofSeconds(1))));
    }

    private static Stream<Arguments> invalidDurations() {
        return Stream.of("duration", "fixedDelay", "retryDelay", "claimLease")
                .flatMap(name -> Stream.of(null, Duration.ZERO, Duration.ofSeconds(-1))
                        .map(value -> Arguments.of(name, value, setter(name, value))));
    }

    private static Consumer<ReservationExpirationProperties> setter(String name, Duration value) {
        return properties -> {
            switch (name) {
                case "duration" -> properties.setDuration(value);
                case "fixedDelay" -> properties.setFixedDelay(value);
                case "retryDelay" -> properties.setRetryDelay(value);
                case "claimLease" -> properties.setClaimLease(value);
                default -> throw new IllegalArgumentException("Unknown duration property: " + name);
            }
        };
    }

    private static Duration durationValue(ReservationExpirationProperties properties, String name) {
        return switch (name) {
            case "duration" -> properties.duration();
            case "fixedDelay" -> properties.fixedDelay();
            case "retryDelay" -> properties.retryDelay();
            case "claimLease" -> properties.claimLease();
            default -> throw new IllegalArgumentException("Unknown duration property: " + name);
        };
    }
}

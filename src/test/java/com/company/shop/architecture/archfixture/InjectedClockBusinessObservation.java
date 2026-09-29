package com.company.shop.architecture.archfixture;

import java.time.Clock;
import java.time.Instant;

public class InjectedClockBusinessObservation {

    public Instant observe(Clock clock) {
        return Instant.now(clock);
    }
}

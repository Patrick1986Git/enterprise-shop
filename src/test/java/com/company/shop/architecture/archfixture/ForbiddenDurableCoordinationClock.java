package com.company.shop.architecture.archfixture;

import java.time.Instant;

public class ForbiddenDurableCoordinationClock {

    public Instant coordinate() {
        return Instant.now();
    }
}

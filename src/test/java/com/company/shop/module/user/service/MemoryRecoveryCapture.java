package com.company.shop.module.user.service;

/** Synthetic test courier; deliberately has no component annotation or output facility. */
final class MemoryRecoveryCapture {
    private String token;
    private int deliveries;

    void issue(PasswordRecoveryService service, java.util.UUID userId) {
        token = null;
        String committed = service.issue(userId);
        token = committed;
        deliveries++;
    }

    String token() { return token; }
    int deliveries() { return deliveries; }
}

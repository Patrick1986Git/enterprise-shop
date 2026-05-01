package com.company.shop.module.order.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.company.shop.module.order.entity.StripeWebhookEvent;

public interface StripeWebhookEventRepository extends JpaRepository<StripeWebhookEvent, UUID> {
}

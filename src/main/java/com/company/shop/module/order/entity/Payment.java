package com.company.shop.module.order.entity;

import java.math.BigDecimal;

import org.hibernate.annotations.SQLRestriction;

import com.company.shop.common.model.SoftDeleteEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "payments")
@SQLRestriction("deleted = false")
public class Payment extends SoftDeleteEntity {

	@OneToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "order_id")
	private Order order;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private PaymentStatus status;

	@Column(name = "payment_method", nullable = false)
	private String provider;

	@Column(nullable = false)
	private BigDecimal amount;

	@Column(name = "provider_payment_id", length = 255)
	private String providerPaymentId;

	@Column(name = "client_secret", length = 500)
	private String clientSecret;

	protected Payment() {
	}

	public Payment(Order order, String provider, BigDecimal amount) {
		this.order = order;
		this.provider = provider;
		this.amount = amount;
		this.status = PaymentStatus.PENDING;
	}

	public Order getOrder() {
		return order;
	}

	public PaymentStatus getStatus() {
		return status;
	}

	public String getProvider() {
		return provider;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public String getProviderPaymentId() {
		return providerPaymentId;
	}

	public String getClientSecret() {
		return clientSecret;
	}

	public void attachProviderPayment(String providerPaymentId, String clientSecret) {
		this.providerPaymentId = providerPaymentId;
		this.clientSecret = clientSecret;
		if (this.status != PaymentStatus.COMPLETED) {
			this.status = PaymentStatus.PENDING;
		}
	}

	public void markAsCompleted() {
		this.status = PaymentStatus.COMPLETED;
	}

	public void markAsFailed() {
		if (this.status != PaymentStatus.COMPLETED) {
			this.status = PaymentStatus.FAILED;
		}
	}
}

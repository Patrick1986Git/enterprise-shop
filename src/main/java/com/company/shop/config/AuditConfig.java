package com.company.shop.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import com.company.shop.common.model.AuditAwareImpl;

@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorProvider")
public class AuditConfig {

	@Bean
	public Clock applicationClock() {
		return Clock.systemUTC();
	}

	@Bean
	public AuditAwareImpl auditorProvider() {
		return new AuditAwareImpl();
	}
}

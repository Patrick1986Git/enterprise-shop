package com.company.shop.support;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.company.shop.common.exception.GlobalExceptionHandler;
import com.company.shop.common.i18n.MessageService;
import com.company.shop.config.SecurityConfig;
import com.company.shop.security.JwtAuthenticationFilter;

@Configuration
@Import({ SecurityConfig.class, JwtAuthenticationFilter.class, GlobalExceptionHandler.class, MessageService.class,
        TestMeterRegistryConfig.class })
public class WebMvcSliceTestConfig {
}

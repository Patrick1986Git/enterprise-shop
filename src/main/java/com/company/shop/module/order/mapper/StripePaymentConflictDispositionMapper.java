package com.company.shop.module.order.mapper;

import org.mapstruct.Mapper;

import com.company.shop.module.order.dto.StripePaymentConflictDispositionResponseDTO;
import com.company.shop.module.order.entity.StripePaymentConflictDisposition;

@Mapper(componentModel = "spring")
public interface StripePaymentConflictDispositionMapper {
    StripePaymentConflictDispositionResponseDTO toDto(StripePaymentConflictDisposition disposition);
}

package com.company.shop.module.order.expiration;

import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface ReservationExpirationAdminActionLogMapper {
    ReservationExpirationAdminActionLogResponseDTO toDto(ReservationExpirationAdminActionLog actionLog);
}

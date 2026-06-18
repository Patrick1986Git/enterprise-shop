package com.company.shop.module.order.outbox;

import org.mapstruct.Mapper;

import com.company.shop.module.order.outbox.dto.OutboxEventAdminActionLogResponseDTO;

@Mapper(componentModel = "spring")
public interface OutboxEventAdminActionLogMapper {

    OutboxEventAdminActionLogResponseDTO toDto(OutboxEventAdminActionLog outboxEventAdminActionLog);
}

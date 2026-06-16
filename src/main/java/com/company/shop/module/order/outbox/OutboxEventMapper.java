package com.company.shop.module.order.outbox;

import org.mapstruct.Mapper;

import com.company.shop.module.order.outbox.dto.OutboxEventDetailResponseDTO;
import com.company.shop.module.order.outbox.dto.OutboxEventResponseDTO;

@Mapper(componentModel = "spring")
public interface OutboxEventMapper {

    OutboxEventResponseDTO toDto(OutboxEvent outboxEvent);

    OutboxEventDetailResponseDTO toDetailDto(OutboxEvent outboxEvent);
}

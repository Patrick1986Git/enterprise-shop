package com.company.shop.module.notification.mapper;

import org.mapstruct.Mapper;

import com.company.shop.module.notification.dto.NotificationResponseDTO;
import com.company.shop.module.notification.entity.Notification;

@Mapper(componentModel = "spring")
public interface NotificationMapper {

    NotificationResponseDTO toDto(Notification notification);
}

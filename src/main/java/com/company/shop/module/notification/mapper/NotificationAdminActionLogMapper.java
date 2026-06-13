package com.company.shop.module.notification.mapper;

import org.mapstruct.Mapper;

import com.company.shop.module.notification.dto.NotificationAdminActionLogResponseDTO;
import com.company.shop.module.notification.entity.NotificationAdminActionLog;

@Mapper(componentModel = "spring")
public interface NotificationAdminActionLogMapper {

    NotificationAdminActionLogResponseDTO toDto(NotificationAdminActionLog notificationAdminActionLog);
}

package com.company.shop.module.user.service;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.company.shop.module.user.dto.UserResponseDTO;
import com.company.shop.module.user.dto.PasswordChangeRequestDTO;
import com.company.shop.module.user.dto.UserUpdateDTO;
import com.company.shop.module.user.entity.User;

public interface UserService {

	Page<UserResponseDTO> findAll(Pageable pageable);

	UserResponseDTO findById(UUID id);

	UserResponseDTO getCurrentUserProfile();

	void changeCurrentUserPassword(PasswordChangeRequestDTO request);

	UserResponseDTO update(UUID id, UserUpdateDTO dto);

	void delete(UUID id);

	User getCurrentUserEntity();

	boolean isAdmin(User user);
}

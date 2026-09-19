package com.company.shop.module.user.service;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.company.shop.module.user.dto.UserResponseDTO;
import com.company.shop.module.user.dto.PasswordChangeRequestDTO;
import com.company.shop.module.user.exception.CurrentPasswordIncorrectException;
import com.company.shop.module.user.dto.UserUpdateDTO;
import com.company.shop.module.user.entity.User;
import com.company.shop.module.user.exception.UserNotFoundException;
import com.company.shop.module.user.mapper.UserMapper;
import com.company.shop.module.user.repository.UserRepository;
import com.company.shop.security.CurrentUserProvider;
import com.company.shop.security.SecurityConstants;

@Service
@Transactional
public class UserServiceImpl implements UserService {

	private final UserRepository repository;
	private final UserMapper mapper;
	private final CurrentUserProvider currentUserProvider;
	private final PasswordEncoder passwordEncoder;

	public UserServiceImpl(UserRepository repository, UserMapper mapper, CurrentUserProvider currentUserProvider,
			PasswordEncoder passwordEncoder) {
		this.repository = repository;
		this.mapper = mapper;
		this.currentUserProvider = currentUserProvider;
		this.passwordEncoder = passwordEncoder;
	}

	@Override
	@Transactional(readOnly = true)
	public Page<UserResponseDTO> findAll(Pageable pageable) {
		return repository.findAllActive(pageable).map(mapper::toDto);
	}

	@Override
	@Transactional(readOnly = true)
	public UserResponseDTO findById(UUID id) {
		return repository.findActiveWithRolesById(id)
				.map(mapper::toDto)
				.orElseThrow(UserNotFoundException::new);
	}

	@Override
	@Transactional(readOnly = true)
	public UserResponseDTO getCurrentUserProfile() {
		return mapper.toDto(getCurrentUserEntity());
	}

	@Override
	public void changeCurrentUserPassword(PasswordChangeRequestDTO request) {
		User user = getCurrentUserEntity();
		if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
			throw new CurrentPasswordIncorrectException();
		}
		user.changePassword(passwordEncoder.encode(request.getNewPassword()));
	}

	@Override
	public UserResponseDTO update(UUID id, UserUpdateDTO dto) {
		repository.acquireCommerceLifecycleLock(id);
		User user = repository.findActiveById(id)
				.orElseThrow(UserNotFoundException::new);

		user.setFirstName(dto.getFirstName().trim());
		user.setLastName(dto.getLastName().trim());

		return mapper.toDto(user);
	}

	@Override
	public void delete(UUID id) {
		repository.acquireCommerceLifecycleLock(id);
		User user = repository.findActiveById(id)
				.orElseThrow(UserNotFoundException::new);

		user.markDeleted();
	}

	@Override
	public User getCurrentUserEntity() {
		String email = currentUserProvider.getCurrentUserEmail();
		User candidate = repository.findActiveByEmailWithRoles(email)
				.orElseThrow(UserNotFoundException::new);
		repository.acquireCommerceLifecycleLock(candidate.getId());
		return repository.findActiveByEmailWithRoles(email)
				.orElseThrow(UserNotFoundException::new);
	}

	@Override
	public boolean isAdmin(User user) {
		return user.getRoles().stream()
				.anyMatch(role -> SecurityConstants.ROLE_ADMIN.equals(role.getName()));
	}
}

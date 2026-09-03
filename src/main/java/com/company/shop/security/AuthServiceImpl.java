package com.company.shop.security;

import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.company.shop.module.user.dto.AuthResponseDTO;
import com.company.shop.module.user.dto.LoginRequestDTO;
import com.company.shop.module.user.dto.RegisterRequestDTO;
import com.company.shop.module.user.entity.Role;
import com.company.shop.module.user.entity.User;
import com.company.shop.module.user.exception.UserAlreadyExistsException;
import com.company.shop.module.user.exception.UserRoleNotConfiguredException;
import com.company.shop.module.user.repository.RoleRepository;
import com.company.shop.module.user.repository.UserRepository;
import com.company.shop.security.jwt.JwtTokenProvider;

@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);
    private static final String USER_EMAIL_UNIQUE_CONSTRAINT = "ux_users_email_lower";

    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider tokenProvider;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailNormalizer emailNormalizer;

    public AuthServiceImpl(AuthenticationManager authenticationManager,
                           JwtTokenProvider tokenProvider,
                           UserRepository userRepository,
                           RoleRepository roleRepository,
                           PasswordEncoder passwordEncoder,
                           EmailNormalizer emailNormalizer) {
        this.authenticationManager = authenticationManager;
        this.tokenProvider = tokenProvider;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailNormalizer = emailNormalizer;
    }

	@Override
	public AuthResponseDTO login(LoginRequestDTO request) {
		String normalizedEmail = emailNormalizer.normalize(request.getEmail());
		Authentication authentication = authenticationManager
				.authenticate(new UsernamePasswordAuthenticationToken(normalizedEmail, request.getPassword()));

		String token = tokenProvider.generateToken(authentication);
		return new AuthResponseDTO(token);
	}

	@Override
	@Transactional
	public void register(RegisterRequestDTO request) {
		String normalizedEmail = emailNormalizer.normalize(request.getEmail());

		User user = new User(normalizedEmail, passwordEncoder.encode(request.getPassword()), request.getFirstName(),
				request.getLastName());

		Role userRole = roleRepository.findByName(SecurityConstants.ROLE_USER)
				.orElseThrow(() -> new UserRoleNotConfiguredException(SecurityConstants.ROLE_USER));

		user.addRole(userRole);
		try {
			userRepository.saveAndFlush(user);
		} catch (DataIntegrityViolationException ex) {
			if (isEmailUniqueConstraintViolation(ex)) {
				throw new UserAlreadyExistsException();
			}
			log.warn("Unexpected data integrity violation during registration", ex);
			throw ex;
		}
	}

	private boolean isEmailUniqueConstraintViolation(DataIntegrityViolationException ex) {
		Throwable current = ex;
		while (current != null) {
			if (current instanceof ConstraintViolationException constraintViolationException
					&& USER_EMAIL_UNIQUE_CONSTRAINT.equalsIgnoreCase(constraintViolationException.getConstraintName())) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}
}

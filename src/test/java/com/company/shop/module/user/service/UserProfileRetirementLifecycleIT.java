package com.company.shop.module.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.company.shop.module.user.dto.UserUpdateDTO;
import com.company.shop.module.user.dto.PasswordChangeRequestDTO;
import com.company.shop.module.user.entity.User;
import com.company.shop.module.user.exception.UserNotFoundException;
import com.company.shop.module.user.repository.RoleRepository;
import com.company.shop.module.user.repository.UserRepository;
import com.company.shop.persistence.support.PostgresContainerSupport;
import com.company.shop.security.SecurityConstants;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class UserProfileRetirementLifecycleIT extends PostgresContainerSupport {

    @Autowired private UserService userService;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserDetailsService userDetailsService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    void retirementWinning_shouldRejectWaitingProfileUpdateAndKeepPhysicalRowRetired() throws Exception {
        User user = fixture();
        CountDownLatch retired = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        AtomicInteger waiterPid = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(2);

        Future<?> retirement = executor.submit(() -> transaction().executeWithoutResult(status -> {
            userRepository.acquireCommerceLifecycleLock(user.getId());
            User active = userRepository.findActiveById(user.getId()).orElseThrow();
            active.markDeleted();
            userRepository.flush();
            retired.countDown();
            await(commit);
        }));
        assertThat(retired.await(10, TimeUnit.SECONDS)).isTrue();

        Future<?> update = executor.submit(() -> transaction().executeWithoutResult(status -> {
            waiterPid.set(jdbcTemplate.queryForObject("select pg_backend_pid()", Integer.class));
            userService.update(user.getId(), new UserUpdateDTO("Updated", "Name"));
        }));
        assertThat(awaitAdvisoryWait(waiterPid)).isTrue();

        commit.countDown();
        retirement.get(10, TimeUnit.SECONDS);
        assertThatThrownBy(() -> update.get(10, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(UserNotFoundException.class);
        shutdown(executor);

        assertRetiredPhysicalRow(user, "Original", "Person");
    }

    @Test
    void profileUpdateWinning_shouldCommitNamesBeforeWaitingRetirement() throws Exception {
        User user = fixture();
        CountDownLatch updated = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        AtomicInteger waiterPid = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(2);

        Future<?> update = executor.submit(() -> transaction().executeWithoutResult(status -> {
            userService.update(user.getId(), new UserUpdateDTO("Updated", "Name"));
            userRepository.flush();
            updated.countDown();
            await(commit);
        }));
        assertThat(updated.await(10, TimeUnit.SECONDS)).isTrue();

        Future<?> retirement = executor.submit(() -> transaction().executeWithoutResult(status -> {
            waiterPid.set(jdbcTemplate.queryForObject("select pg_backend_pid()", Integer.class));
            userService.delete(user.getId());
        }));
        assertThat(awaitAdvisoryWait(waiterPid)).isTrue();

        commit.countDown();
        update.get(10, TimeUnit.SECONDS);
        retirement.get(10, TimeUnit.SECONDS);
        shutdown(executor);

        assertRetiredPhysicalRow(user, "Updated", "Name");
    }

    @Test
    void retirementWinning_shouldRejectWaitingPasswordChangeWithoutMutatingCredential() throws Exception {
        User user = fixture(passwordEncoder.encode("StrongPassword123!"));
        String originalPassword = user.getPassword();
        CountDownLatch retired = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        AtomicInteger waiterPid = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(2);

        Future<?> retirement = executor.submit(() -> transaction().executeWithoutResult(status -> {
            userRepository.acquireCommerceLifecycleLock(user.getId());
            userRepository.findActiveById(user.getId()).orElseThrow().markDeleted();
            userRepository.flush();
            retired.countDown();
            await(commit);
        }));
        assertThat(retired.await(10, TimeUnit.SECONDS)).isTrue();

        Future<?> passwordChange = executor.submit(() -> {
            SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated(user.getEmail(), null, java.util.List.of()));
            try {
                transaction().executeWithoutResult(status -> {
                    waiterPid.set(jdbcTemplate.queryForObject("select pg_backend_pid()", Integer.class));
                    userService.changeCurrentUserPassword(new PasswordChangeRequestDTO(
                            "StrongPassword123!", "NewStrongPassword456!", "NewStrongPassword456!"));
                });
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
        assertThat(awaitAdvisoryWait(waiterPid)).isTrue();

        commit.countDown();
        retirement.get(10, TimeUnit.SECONDS);
        assertThatThrownBy(() -> passwordChange.get(10, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(UserNotFoundException.class);
        shutdown(executor);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select password, credential_version, deleted from users where id = ?", user.getId());
        assertThat(row).containsEntry("password", originalPassword)
                .containsEntry("credential_version", 0L)
                .containsEntry("deleted", true);
    }

    @Test
    void retirementWinning_shouldRejectWaitingEnableWithoutResurrectingAccount() throws Exception {
        User user = fixture();
        userService.disable(user.getId());
        CountDownLatch retired = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        AtomicInteger waiterPid = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(2);

        Future<?> retirement = executor.submit(() -> transaction().executeWithoutResult(status -> {
            userRepository.acquireCommerceLifecycleLock(user.getId());
            userRepository.findActiveById(user.getId()).orElseThrow().markDeleted();
            userRepository.flush();
            retired.countDown();
            await(commit);
        }));
        assertThat(retired.await(10, TimeUnit.SECONDS)).isTrue();

        Future<?> enable = executor.submit(() -> transaction().executeWithoutResult(status -> {
            waiterPid.set(jdbcTemplate.queryForObject("select pg_backend_pid()", Integer.class));
            userService.enable(user.getId());
        }));
        assertThat(awaitAdvisoryWait(waiterPid)).isTrue();

        commit.countDown();
        retirement.get(10, TimeUnit.SECONDS);
        assertThatThrownBy(() -> enable.get(10, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(UserNotFoundException.class);
        shutdown(executor);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select enabled, credential_version, deleted from users where id = ?", user.getId());
        assertThat(row).containsEntry("enabled", false)
                .containsEntry("credential_version", 1L)
                .containsEntry("deleted", true);
    }

    @Test
    void disableWinning_shouldSerializeWaitingEnableAndKeepOldCredentialVersionRevoked() throws Exception {
        User user = fixture();
        CountDownLatch disabled = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        AtomicInteger waiterPid = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(2);

        Future<?> disable = executor.submit(() -> transaction().executeWithoutResult(status -> {
            userService.disable(user.getId());
            userRepository.flush();
            disabled.countDown();
            await(commit);
        }));
        assertThat(disabled.await(10, TimeUnit.SECONDS)).isTrue();

        Future<?> enable = executor.submit(() -> transaction().executeWithoutResult(status -> {
            waiterPid.set(jdbcTemplate.queryForObject("select pg_backend_pid()", Integer.class));
            userService.enable(user.getId());
        }));
        assertThat(awaitAdvisoryWait(waiterPid)).isTrue();

        commit.countDown();
        disable.get(10, TimeUnit.SECONDS);
        enable.get(10, TimeUnit.SECONDS);
        shutdown(executor);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select enabled, credential_version, deleted from users where id = ?", user.getId());
        assertThat(row).containsEntry("enabled", true)
                .containsEntry("credential_version", 1L)
                .containsEntry("deleted", false);
    }

    private User fixture() {
        return fixture("encoded");
    }

    private User fixture(String encodedPassword) {
        User user = new User("profile-lifecycle-" + UUID.randomUUID() + "@example.com",
                encodedPassword, "Original", "Person");
        user.addRole(roleRepository.findByName(SecurityConstants.ROLE_USER).orElseThrow());
        return userRepository.saveAndFlush(user);
    }

    private void assertRetiredPhysicalRow(User user, String firstName, String lastName) {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                select id, first_name, last_name, deleted, deleted_at from users where id = ?
                """, user.getId());
        assertThat(row)
                .containsEntry("id", user.getId())
                .containsEntry("first_name", firstName)
                .containsEntry("last_name", lastName)
                .containsEntry("deleted", true);
        assertThat(row.get("deleted_at")).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from users where id = ?", Long.class, user.getId())).isOne();
        assertThat(userRepository.findActiveById(user.getId())).isEmpty();
        assertThatThrownBy(() -> userDetailsService.loadUserByUsername(user.getEmail()))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    private boolean awaitAdvisoryWait(AtomicInteger pid) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (pid.get() != 0 && Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                    select exists (select 1 from pg_locks locks
                    join pg_stat_activity activity on activity.pid = locks.pid
                    where locks.pid = ? and locks.locktype = 'advisory' and not locks.granted
                    and activity.wait_event_type = 'Lock' and activity.wait_event = 'advisory')
                    """, Boolean.class, pid.get()))) {
                return true;
            }
            Thread.onSpinWait();
        }
        return false;
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("coordination timeout");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private static void shutdown(java.util.concurrent.ExecutorService executor) throws InterruptedException {
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
}

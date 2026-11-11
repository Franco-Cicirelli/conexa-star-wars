package com.conexa.starwars.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.conexa.starwars.auth.exception.RegistrationIpAlreadyUsedException;
import com.conexa.starwars.auth.exception.UsernameAlreadyExistsException;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

class RegistrationServiceTest {
    @Test
    void concurrentCaseInsensitiveRegistrationsHaveOnlyOneWinner() throws Exception {
        var users = new InMemoryUserDetailsManager();
        var passwords = mock(PasswordEncoder.class);
        var hashing = new CyclicBarrier(2);
        when(passwords.encode(anyString()))
                .thenAnswer(
                        invocation -> {
                            hashing.await(5, TimeUnit.SECONDS);
                            return "encoded-test-value";
                        });
        var service = new RegistrationService(users, passwords);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results =
                    executor.invokeAll(
                            List.of(
                                    () -> register(service, "Pilot"),
                                    () -> register(service, "pILOT")));
            int successes = 0;
            for (var result : results)
                if (Boolean.TRUE.equals(result.get(5, TimeUnit.SECONDS))) successes++;
            assertThat(successes).isEqualTo(1);
            assertThat(users.loadUserByUsername("pilot").getAuthorities())
                    .extracting(org.springframework.security.core.GrantedAuthority::getAuthority)
                    .containsExactly("catalog:read");
        }
    }

    @Test
    void onlyOneSuccessfulAccountIsAllowedPerIp() {
        var users = new InMemoryUserDetailsManager();
        var passwords = mock(PasswordEncoder.class);
        when(passwords.encode(anyString())).thenReturn("encoded-test-value");
        var service = new RegistrationService(users, passwords);
        service.register("first", "test-password", "198.51.100.1");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.register("second", "test-password", "198.51.100.1"))
                .isInstanceOf(RegistrationIpAlreadyUsedException.class);
        assertThat(users.userExists("second")).isFalse();
        service.register("other", "test-password", "198.51.100.2");
        assertThat(users.userExists("other")).isTrue();
    }

    @Test
    void duplicateUsernameDoesNotConsumeAnotherIpsRegistration() {
        var users = new InMemoryUserDetailsManager();
        var passwords = mock(PasswordEncoder.class);
        when(passwords.encode(anyString())).thenReturn("encoded-test-value");
        var service = new RegistrationService(users, passwords);
        service.register("first", "test-password", "198.51.100.1");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.register("first", "test-password", "198.51.100.2"))
                .isInstanceOf(UsernameAlreadyExistsException.class);
        service.register("second", "test-password", "198.51.100.2");
        assertThat(users.userExists("second")).isTrue();
    }

    @Test
    void concurrentDifferentUsersFromSameIpHaveOnlyOneWinner() throws Exception {
        var users = new InMemoryUserDetailsManager();
        var passwords = mock(PasswordEncoder.class);
        var hashing = new CyclicBarrier(2);
        when(passwords.encode(anyString()))
                .thenAnswer(
                        invocation -> {
                            hashing.await(5, TimeUnit.SECONDS);
                            return "encoded-test-value";
                        });
        var service = new RegistrationService(users, passwords);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results =
                    executor.invokeAll(
                            List.of(
                                    () -> registerFromSameIp(service, "first"),
                                    () -> registerFromSameIp(service, "second")));
            int successes = 0;
            for (var result : results)
                if (Boolean.TRUE.equals(result.get(5, TimeUnit.SECONDS))) successes++;
            assertThat(successes).isEqualTo(1);
            assertThat(users.userExists("first") ^ users.userExists("second")).isTrue();
        }
    }

    private boolean registerFromSameIp(RegistrationService service, String username) {
        try {
            service.register(username, java.util.UUID.randomUUID().toString(), "198.51.100.1");
            return true;
        } catch (RegistrationIpAlreadyUsedException expected) {
            return false;
        }
    }

    private boolean register(RegistrationService service, String username) {
        try {
            service.register(username, java.util.UUID.randomUUID().toString(), username);
            return true;
        } catch (UsernameAlreadyExistsException expected) {
            return false;
        }
    }
}

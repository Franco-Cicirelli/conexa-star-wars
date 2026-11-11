package com.conexa.starwars.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

class UserAuthenticationConfigurationTest {
    @Test
    void applicationStartsWithoutConfiguredAccountsAndLosesRegistrationsOnRestart() {
        var config = new UserAuthenticationConfiguration();
        var first = config.users();
        assertThat(first.userExists("demo")).isFalse();
        var hash =
                Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()
                        .encode(java.util.UUID.randomUUID().toString());
        first.createUser(
                User.withUsername("temporary").password(hash).authorities("catalog:read").build());
        var restarted = config.users();
        assertThat(restarted.userExists("temporary")).isFalse();
    }
}

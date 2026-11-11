package com.conexa.starwars.auth.service;

import com.conexa.starwars.auth.exception.RegistrationIpAlreadyUsedException;
import com.conexa.starwars.auth.exception.UsernameAlreadyExistsException;
import java.util.HashSet;
import java.util.Set;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.stereotype.Service;

@Service
public class RegistrationService {
    private final UserDetailsManager users;
    private final PasswordEncoder passwords;
    private final Set<String> registeredAddresses = new HashSet<>();

    public RegistrationService(UserDetailsManager users, PasswordEncoder passwords) {
        this.users = users;
        this.passwords = passwords;
    }

    public void register(String username, String password, String clientAddress) {
        String hash = passwords.encode(password);
        // Username uniqueness, the IP quota and account creation form a single atomic operation.
        synchronized (users) {
            if (users.userExists(username)) {
                throw new UsernameAlreadyExistsException();
            }
            if (registeredAddresses.contains(clientAddress)) {
                throw new RegistrationIpAlreadyUsedException();
            }
            users.createUser(
                    User.withUsername(username).password(hash).authorities("catalog:read").build());
            registeredAddresses.add(clientAddress);
        }
    }
}

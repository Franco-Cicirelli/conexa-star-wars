package com.conexa.starwars.auth.controller;

import com.conexa.starwars.auth.controller.dto.LoginResponse;
import com.conexa.starwars.auth.security.AuthRateLimiter;
import com.conexa.starwars.auth.service.LoginService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/auth")
public class LoginController {
    private final LoginService service;
    private final AuthRateLimiter limiter;

    public LoginController(LoginService service, AuthRateLimiter limiter) {
        this.service = service;
        this.limiter = limiter;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        if (!limiter.allow("login:" + request.getRemoteAddr()))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts.");
        try {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(LoginResponse.from(service.login(body.username(), body.password())));
        } catch (AuthenticationException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials.");
        }
    }

    public record LoginRequest(
            @NotBlank @Size(max = 80) String username, @NotBlank @Size(max = 256) String password) {
        @Override
        public String toString() {
            return "LoginRequest[redacted]";
        }
    }
}

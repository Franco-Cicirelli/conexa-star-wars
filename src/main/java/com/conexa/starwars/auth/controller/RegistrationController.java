package com.conexa.starwars.auth.controller;

import com.conexa.starwars.auth.security.AuthRateLimiter;
import com.conexa.starwars.auth.service.RegistrationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/auth")
public class RegistrationController {
    private final RegistrationService service;
    private final AuthRateLimiter limiter;

    public RegistrationController(RegistrationService service, AuthRateLimiter limiter) {
        this.service = service;
        this.limiter = limiter;
    }

    @PostMapping("/register")
    public ResponseEntity<RegistrationResponse> register(
            @Valid @RequestBody RegistrationRequest body, HttpServletRequest request) {
        if (!limiter.allow("register:" + request.getRemoteAddr())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts.");
        }
        service.register(body.username(), body.password(), request.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(new RegistrationResponse(body.username()));
    }

    public record RegistrationRequest(
            @NotBlank @Size(max = 80) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,79}")
                    String username,
            @NotBlank @Size(min = 12, max = 256) String password) {
        @Override
        public String toString() {
            return "RegistrationRequest[redacted]";
        }
    }

    public record RegistrationResponse(String username) {}
}

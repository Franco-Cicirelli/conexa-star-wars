package com.conexa.starwars.auth.controller;

import com.conexa.starwars.auth.controller.dto.LoginResponse;
import com.conexa.starwars.auth.service.LoginService;
import com.conexa.starwars.ratelimit.RequestQuotas;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
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
    private final RequestQuotas quotas;

    public LoginController(LoginService service, RequestQuotas quotas) {
        this.service = service;
        this.quotas = quotas;
    }

    @PostMapping("/login")
    @Operation(
            summary = "Log in",
            description =
                    "Authenticate an existing in-memory user. Copy accessToken from the response into Swagger UI Authorize. Five attempts per minute per identity and connection IP.")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        quotas.login(body.username(), request.getRemoteAddr());
        try {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(LoginResponse.from(service.login(body.username(), body.password())));
        } catch (AuthenticationException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials.");
        }
    }

    public record LoginRequest(
            @NotBlank @Size(max = 80) String username,
            @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY)
                    @NotBlank
                    @Size(max = 256)
                    String password) {
        @Override
        public String toString() {
            return "LoginRequest[redacted]";
        }
    }
}

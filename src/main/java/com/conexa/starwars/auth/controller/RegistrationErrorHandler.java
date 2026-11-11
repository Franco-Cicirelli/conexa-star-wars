package com.conexa.starwars.auth.controller;

import com.conexa.starwars.auth.exception.RegistrationIpAlreadyUsedException;
import com.conexa.starwars.auth.exception.UsernameAlreadyExistsException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = RegistrationController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RegistrationErrorHandler {
    @ExceptionHandler(RegistrationIpAlreadyUsedException.class)
    ProblemDetail usedIp() {
        var problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.CONFLICT,
                        "A registration has already been completed from this IP address.");
        problem.setProperty("code", "AUTH_REGISTRATION_IP_USED");
        return problem;
    }

    @ExceptionHandler(UsernameAlreadyExistsException.class)
    ProblemDetail duplicate() {
        var problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.CONFLICT, "The username is already registered.");
        problem.setProperty("code", "AUTH_USERNAME_EXISTS");
        return problem;
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        HttpMessageNotReadableException.class
    })
    ProblemDetail invalidRequest() {
        var problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.BAD_REQUEST,
                        "username must contain 1 to 80 characters (letters, digits, periods, hyphens, or underscores) and start with a letter or digit; password must contain 12 to 256 characters.");
        problem.setProperty("code", "AUTH_INVALID_REGISTRATION");
        return problem;
    }
}

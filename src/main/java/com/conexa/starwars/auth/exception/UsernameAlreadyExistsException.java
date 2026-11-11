package com.conexa.starwars.auth.exception;

public class UsernameAlreadyExistsException extends RuntimeException {
    public UsernameAlreadyExistsException() {
        super("The username is already registered.");
    }
}

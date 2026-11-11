package com.conexa.starwars.auth.exception;

public class RegistrationIpAlreadyUsedException extends RuntimeException {
    public RegistrationIpAlreadyUsedException() {
        super("A registration has already been completed from this IP address.");
    }
}

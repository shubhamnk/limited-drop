package com.limiteddrop.reservation.exception;

public class BadRequestException extends DomainException {

    public BadRequestException(String code, String message) {
        super(code, message);
    }
}

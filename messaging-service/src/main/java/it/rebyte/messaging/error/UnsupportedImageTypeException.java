package it.rebyte.messaging.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

public class UnsupportedImageTypeException extends DomainException {

    public UnsupportedImageTypeException() {
        super(HttpStatus.BAD_REQUEST, "unsupported-image-type", "Unsupported image type");
    }
}

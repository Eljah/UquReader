package com.example.uqureader.webapp;

/**
 * Exception thrown when the morphological analyser fails to produce a result.
 */
public class MorphologyException extends RuntimeException {
    public MorphologyException(String message) {
        super(message);
    }

    public MorphologyException(String message, Throwable cause) {
        super(message, cause);
    }
}

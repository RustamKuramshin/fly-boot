package dev.flyboot.core;

/** The context cannot be built: a missing, ambiguous or circular dependency, or a failing constructor. */
public class FlyContextException extends RuntimeException {

    public FlyContextException(String message) {
        super(message);
    }

    public FlyContextException(String message, Throwable cause) {
        super(message, cause);
    }
}

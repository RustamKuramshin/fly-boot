package dev.flyboot.web;

/** Thrown by a handler to answer with the given status and reason, e.g. 404 when a pet does not exist. */
public class ResponseStatusException extends RuntimeException {

    private final int status;

    public ResponseStatusException(int status, String reason) {
        super(reason);
        this.status = status;
    }

    public int status() {
        return status;
    }
}

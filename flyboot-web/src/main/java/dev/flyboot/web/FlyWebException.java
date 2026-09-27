package dev.flyboot.web;

/** The controllers cannot be turned into handlers: a mapping or parameter that FlyBoot cannot serve. */
public class FlyWebException extends RuntimeException {

    public FlyWebException(String message) {
        super(message);
    }
}

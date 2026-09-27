package dev.flyboot.web.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import dev.flyboot.core.Component;

/**
 * A controller whose handler methods answer HTTP requests; return values become the response body (JSON unless
 * they are strings or bytes). Which method answers a request is decided by the fly.
 */
@Documented
@Component
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RestController {
}

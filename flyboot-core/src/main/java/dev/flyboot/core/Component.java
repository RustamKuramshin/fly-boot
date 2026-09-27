package dev.flyboot.core;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A class the context creates once and injects wherever it is needed. Works as a meta-annotation too:
 * any annotation annotated with {@code @Component} (like {@code @RestController}) marks components.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Component {
}

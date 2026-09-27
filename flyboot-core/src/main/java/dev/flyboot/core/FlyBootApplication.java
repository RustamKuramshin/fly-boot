package dev.flyboot.core;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The application class. Components are scanned in its package and below, unless {@link #scanBasePackages()}
 * names other packages.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface FlyBootApplication {

    String[] scanBasePackages() default {};
}

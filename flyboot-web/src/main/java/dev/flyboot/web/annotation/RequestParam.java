package dev.flyboot.web.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a parameter to a query parameter; the name defaults to the parameter name. A {@code List} parameter
 * receives every value. Simple-typed parameters without any annotation are optional query parameters, as in
 * Spring.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface RequestParam {

    String value() default "";

    String name() default "";

    boolean required() default true;

    /** Used when the parameter is absent; makes it optional. */
    String defaultValue() default ValueConstants.NONE;
}

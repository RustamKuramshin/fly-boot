package dev.flyboot.web.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Maps requests to a controller or a handler method. On a class it is the path prefix of all its methods. On a
 * method without {@link #method()} it maps every standard method (GET, POST, PUT, DELETE, PATCH).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface RequestMapping {

    /** Path patterns such as {@code /pets/{id}}; alias of {@link #path()}. */
    String[] value() default {};

    String[] path() default {};

    RequestMethod[] method() default {};
}

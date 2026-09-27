package dev.flyboot.web.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Shortcut for {@code @RequestMapping(method = RequestMethod.PUT)}. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PutMapping {

    /** Path patterns such as {@code /pets/{id}}; alias of {@link #path()}. */
    String[] value() default {};

    String[] path() default {};
}

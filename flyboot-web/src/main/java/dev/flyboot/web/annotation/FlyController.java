package dev.flyboot.web.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import dev.flyboot.core.Component;

/** Same as {@link RestController}, for those who want the fly on the class. */
@Documented
@Component
@RestController
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface FlyController {
}

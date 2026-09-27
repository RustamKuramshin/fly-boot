package dev.flyboot.web;

import dev.flyboot.brain.learn.RouteTemplate;

import java.lang.reflect.Method;
import java.util.List;
import java.util.regex.Pattern;

/**
 * One endpoint: a controller method bound to an HTTP method and path pattern.
 *
 * @param bean      controller instance
 * @param method    handler method
 * @param template  what the fly learns: HTTP method, pattern and variable kinds
 * @param params    how every parameter is bound
 * @param status    status of a successful response
 * @param segments  how path variables are read from the path segments once the fly chose this endpoint
 */
public record HandlerMethod(Object bean, Method method, RouteTemplate template, List<ParamBinding> params, int status,
                            List<VarSegment> segments) {

    /**
     * A path segment of the pattern that holds variables.
     *
     * @param index   position of the segment in the path
     * @param pattern regex of the whole segment, one group per variable
     * @param names   variable names in group order
     * @param whole   whether the segment is exactly one variable, e.g. {@code {id}}
     */
    public record VarSegment(int index, Pattern pattern, List<String> names, boolean whole) {
    }

    public String httpMethod() {
        return template.method();
    }

    public String pattern() {
        return template.pattern();
    }

    /** {@code PetController#get}. */
    public String describe() {
        return bean.getClass().getSimpleName() + "#" + method.getName();
    }
}

package dev.flyboot.web;

import com.sun.net.httpserver.HttpExchange;
import dev.flyboot.brain.learn.RouteTemplate;
import dev.flyboot.brain.learn.VarKind;
import dev.flyboot.web.annotation.DeleteMapping;
import dev.flyboot.web.annotation.GetMapping;
import dev.flyboot.web.annotation.PatchMapping;
import dev.flyboot.web.annotation.PathVariable;
import dev.flyboot.web.annotation.PostMapping;
import dev.flyboot.web.annotation.PutMapping;
import dev.flyboot.web.annotation.RequestBody;
import dev.flyboot.web.annotation.RequestHeader;
import dev.flyboot.web.annotation.RequestMapping;
import dev.flyboot.web.annotation.RequestMethod;
import dev.flyboot.web.annotation.RequestParam;
import dev.flyboot.web.annotation.ResponseStatus;
import dev.flyboot.web.annotation.ValueConstants;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns controller methods into {@link HandlerMethod}s, rejecting mappings FlyBoot cannot serve. */
public final class HandlerRegistry {

    private static final Pattern VAR = Pattern.compile("\\{([^}/]+)}");
    private static final List<RequestMethod> STANDARD = List.of(RequestMethod.GET, RequestMethod.POST,
            RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.PATCH);
    private static final Set<Class<?>> NUMBERS = Set.of(int.class, Integer.class, long.class, Long.class,
            short.class, Short.class, byte.class, Byte.class, BigInteger.class);

    private HandlerRegistry() {
    }

    /** Handlers of all controllers, sorted by endpoint so the order (and what the fly learns) is stable. */
    public static List<HandlerMethod> collect(Collection<Object> controllers) {
        Map<String, HandlerMethod> byId = new LinkedHashMap<>();
        for (Object bean : controllers) {
            Class<?> type = bean.getClass();
            List<String> prefixes = paths(type.getAnnotation(RequestMapping.class));
            Method[] methods = type.getDeclaredMethods();
            Arrays.sort(methods, Comparator.comparing(Method::getName).thenComparing(Method::toGenericString));
            for (Method method : methods) {
                Mapping mapping = mappingOf(method);
                if (mapping == null) {
                    continue;
                }
                if (Modifier.isStatic(method.getModifiers()) || Modifier.isPrivate(method.getModifiers())) {
                    throw new FlyWebException(where(method) + ": handler methods must be neither static nor private");
                }
                method.setAccessible(true);
                for (String prefix : prefixes) {
                    for (String path : mapping.paths()) {
                        String pattern = normalize(prefix + "/" + path);
                        for (RequestMethod httpMethod : mapping.methods()) {
                            HandlerMethod h = create(bean, method, httpMethod.name(), pattern);
                            HandlerMethod previous = byId.putIfAbsent(h.template().id(), h);
                            if (previous != null) {
                                throw new FlyWebException("Ambiguous mapping " + h.template().id() + ": "
                                        + previous.describe() + " and " + h.describe());
                            }
                        }
                    }
                }
            }
        }
        return byId.values().stream().sorted(Comparator.comparing(h -> h.template().id())).toList();
    }

    private record Mapping(List<String> paths, List<RequestMethod> methods) {
    }

    private static Mapping mappingOf(Method m) {
        List<Mapping> found = new ArrayList<>();
        if (m.getAnnotation(GetMapping.class) instanceof GetMapping a) {
            found.add(new Mapping(merge(a.value(), a.path()), List.of(RequestMethod.GET)));
        }
        if (m.getAnnotation(PostMapping.class) instanceof PostMapping a) {
            found.add(new Mapping(merge(a.value(), a.path()), List.of(RequestMethod.POST)));
        }
        if (m.getAnnotation(PutMapping.class) instanceof PutMapping a) {
            found.add(new Mapping(merge(a.value(), a.path()), List.of(RequestMethod.PUT)));
        }
        if (m.getAnnotation(DeleteMapping.class) instanceof DeleteMapping a) {
            found.add(new Mapping(merge(a.value(), a.path()), List.of(RequestMethod.DELETE)));
        }
        if (m.getAnnotation(PatchMapping.class) instanceof PatchMapping a) {
            found.add(new Mapping(merge(a.value(), a.path()), List.of(RequestMethod.PATCH)));
        }
        if (m.getAnnotation(RequestMapping.class) instanceof RequestMapping a) {
            found.add(new Mapping(merge(a.value(), a.path()), a.method().length == 0 ? STANDARD : List.of(a.method())));
        }
        if (found.size() > 1) {
            throw new FlyWebException(where(m) + ": several mapping annotations");
        }
        return found.isEmpty() ? null : found.getFirst();
    }

    private static List<String> paths(RequestMapping classMapping) {
        return classMapping == null ? List.of("") : merge(classMapping.value(), classMapping.path());
    }

    private static List<String> merge(String[] value, String[] path) {
        List<String> all = new ArrayList<>(List.of(value));
        all.addAll(List.of(path));
        return all.isEmpty() ? List.of("") : all.stream().distinct().toList();
    }

    /** Leading slash, single slashes, no trailing slash except for the root. */
    static String normalize(String path) {
        return "/" + String.join("/", Arrays.stream(path.split("/")).filter(s -> !s.isEmpty()).toList());
    }

    private static HandlerMethod create(Object bean, Method method, String httpMethod, String pattern) {
        List<String> segments = Arrays.stream(pattern.split("/")).filter(s -> !s.isEmpty()).toList();
        List<HandlerMethod.VarSegment> varSegments = new ArrayList<>();
        List<String> patternVars = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            String seg = segments.get(i);
            Matcher m = VAR.matcher(seg);
            StringBuilder regex = new StringBuilder();
            List<String> names = new ArrayList<>();
            int last = 0;
            while (m.find()) {
                regex.append(Pattern.quote(seg.substring(last, m.start()))).append("(.+?)");
                names.add(m.group(1));
                last = m.end();
            }
            if (!names.isEmpty()) {
                regex.append(Pattern.quote(seg.substring(last)));
                boolean whole = seg.equals("{" + names.getFirst() + "}");
                varSegments.add(new HandlerMethod.VarSegment(i, Pattern.compile(whole ? "(.+)" : regex.toString()),
                        List.copyOf(names), whole));
                patternVars.addAll(names);
            }
        }

        List<ParamBinding> params = new ArrayList<>();
        Map<String, VarKind> kinds = new LinkedHashMap<>();
        for (Parameter p : method.getParameters()) {
            params.add(binding(method, p, pattern, patternVars, kinds));
        }
        ResponseStatus rs = method.getAnnotation(ResponseStatus.class);
        return new HandlerMethod(bean, method, new RouteTemplate(httpMethod, pattern, kinds), List.copyOf(params),
                rs == null ? 200 : rs.value(), List.copyOf(varSegments));
    }

    private static ParamBinding binding(Method method, Parameter p, String pattern, List<String> patternVars,
                                        Map<String, VarKind> kinds) {
        Class<?> type = p.getType();
        if (p.getAnnotation(PathVariable.class) instanceof PathVariable a) {
            String name = name(a.value(), a.name(), p, method);
            if (!patternVars.contains(name)) {
                throw new FlyWebException(where(method) + ": @PathVariable " + name + " is not in " + pattern);
            }
            requireConvertible(method, p, type);
            kinds.put(name, kindOf(type));
            return new ParamBinding.PathVar(name, type);
        }
        if (p.getAnnotation(RequestParam.class) instanceof RequestParam a) {
            return queryParam(method, p, name(a.value(), a.name(), p, method), a.required(), a.defaultValue());
        }
        if (p.getAnnotation(RequestHeader.class) instanceof RequestHeader a) {
            requireConvertible(method, p, type);
            return new ParamBinding.Header(name(a.value(), a.name(), p, method), type, a.required(), a.defaultValue());
        }
        if (p.getAnnotation(RequestBody.class) instanceof RequestBody a) {
            return new ParamBinding.Body(p.getParameterizedType(), a.required());
        }
        if (type == HttpExchange.class) {
            return new ParamBinding.Exchange();
        }
        if (TypeConversion.supports(type)) { // Spring: a simple type without annotation is an optional query parameter
            return queryParam(method, p, name("", "", p, method), false, ValueConstants.NONE);
        }
        throw new FlyWebException(where(method) + ": parameter " + p.getName() + " of type " + type.getSimpleName()
                + " needs @PathVariable, @RequestParam, @RequestHeader or @RequestBody");
    }

    private static ParamBinding queryParam(Method method, Parameter p, String name, boolean required, String defaultValue) {
        Class<?> type = p.getType();
        if (List.class.isAssignableFrom(type)) {
            Type t = p.getParameterizedType();
            Class<?> element = t instanceof ParameterizedType pt && pt.getActualTypeArguments()[0] instanceof Class<?> c
                    ? c : String.class;
            requireConvertible(method, p, element);
            return new ParamBinding.QueryParam(name, element, true, required, defaultValue);
        }
        requireConvertible(method, p, type);
        return new ParamBinding.QueryParam(name, type, false, required, defaultValue);
    }

    private static void requireConvertible(Method method, Parameter p, Class<?> type) {
        if (!TypeConversion.supports(type)) {
            throw new FlyWebException(where(method) + ": cannot convert text to " + type.getName()
                    + " for parameter " + p.getName());
        }
    }

    /** What values the fly should expect in a path variable of this type. */
    static VarKind kindOf(Class<?> type) {
        if (NUMBERS.contains(type)) {
            return VarKind.NUMBER;
        }
        if (type == UUID.class) {
            return VarKind.UUID;
        }
        if (type.isEnum()) {
            return VarKind.WORD;
        }
        return VarKind.ANY;
    }

    private static String name(String value, String name, Parameter p, Method method) {
        if (!value.isEmpty()) {
            return value;
        }
        if (!name.isEmpty()) {
            return name;
        }
        if (!p.isNamePresent()) {
            throw new FlyWebException(where(method) + ": parameter names are not available; compile with -parameters "
                    + "or name the parameter in its annotation");
        }
        return p.getName();
    }

    private static String where(Method m) {
        return m.getDeclaringClass().getSimpleName() + "#" + m.getName();
    }
}

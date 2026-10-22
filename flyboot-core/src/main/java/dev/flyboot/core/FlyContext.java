package dev.flyboot.core;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Minimal application context: every component is a singleton created eagerly through its constructor, whose
 * parameters are resolved by type among components and registered objects. One candidate per type is required.
 */
public final class FlyContext {

    private final Set<Class<?>> componentClasses = new LinkedHashSet<>();
    private final Map<Class<?>, Object> singletons = new LinkedHashMap<>();
    private final Deque<Class<?>> creating = new ArrayDeque<>();
    private boolean refreshed;

    /** Finds classes annotated with {@link FlyComponent}, directly or through a meta-annotation, in the packages. */
    public static List<Class<?>> scan(ClassLoader loader, String... packages) {
        try (ScanResult result = new ClassGraph().enableClassInfo().enableAnnotationInfo()
                .overrideClassLoaders(loader).acceptPackages(packages).scan()) {
            List<Class<?>> classes = new ArrayList<>();
            for (ClassInfo info : result.getClassesWithAnnotation(FlyComponent.class)) {
                if (!info.isAnnotation() && !info.isInterface() && !info.isAbstract()) {
                    classes.add(info.loadClass());
                }
            }
            return classes;
        }
    }

    public static FlyContext ofPackages(String... packages) {
        FlyContext context = new FlyContext();
        scan(Thread.currentThread().getContextClassLoader(), packages).forEach(context::registerComponent);
        return context;
    }

    public FlyContext registerComponent(Class<?> type) {
        checkOpen();
        componentClasses.add(type);
        return this;
    }

    /** Makes an existing object injectable, e.g. configuration or infrastructure built outside the context. */
    public FlyContext registerSingleton(Object bean) {
        checkOpen();
        singletons.put(bean.getClass(), bean);
        return this;
    }

    /** Creates every component. */
    public FlyContext refresh() {
        checkOpen();
        for (Class<?> type : componentClasses) {
            getBean(type);
        }
        refreshed = true;
        return this;
    }

    public <T> T getBean(Class<T> type) {
        Class<?> impl = resolve(type, "requested");
        Object bean = singletons.get(impl);
        if (bean == null) {
            bean = create(impl);
        }
        return type.cast(bean);
    }

    /** Components and registered objects whose class carries the annotation, directly or as a meta-annotation. */
    public List<Object> getBeansWithAnnotation(Class<? extends Annotation> annotation) {
        return singletons.values().stream().filter(b -> isAnnotated(b.getClass(), annotation)).toList();
    }

    public Collection<Object> beans() {
        return List.copyOf(singletons.values());
    }

    public static boolean isAnnotated(Class<?> type, Class<? extends Annotation> annotation) {
        return findAnnotation(type, annotation) != null;
    }

    /** The annotation on the type or on one of its annotations, searched depth-first. */
    public static <A extends Annotation> A findAnnotation(Class<?> type, Class<A> annotation) {
        return find(type.getAnnotations(), annotation, new LinkedHashSet<>());
    }

    private static <A extends Annotation> A find(Annotation[] annotations, Class<A> wanted, Set<Class<?>> visited) {
        for (Annotation a : annotations) {
            if (a.annotationType() == wanted) {
                return wanted.cast(a);
            }
        }
        for (Annotation a : annotations) {
            Class<? extends Annotation> t = a.annotationType();
            if (!t.getPackageName().startsWith("java.lang") && visited.add(t)) {
                A found = find(t.getAnnotations(), wanted, visited);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private Class<?> resolve(Class<?> type, String needer) {
        Set<Class<?>> candidates = new LinkedHashSet<>();
        for (Class<?> c : singletons.keySet()) {
            if (type.isAssignableFrom(c)) {
                candidates.add(c);
            }
        }
        for (Class<?> c : componentClasses) {
            if (type.isAssignableFrom(c)) {
                candidates.add(c);
            }
        }
        if (candidates.isEmpty()) {
            throw new FlyContextException("No component of type " + type.getName() + " (" + needer + ")");
        }
        if (candidates.size() > 1) {
            throw new FlyContextException("Several components of type " + type.getName() + " (" + needer + "): "
                    + candidates.stream().map(Class::getName).collect(Collectors.joining(", ")));
        }
        return candidates.iterator().next();
    }

    private Object create(Class<?> type) {
        if (creating.contains(type)) {
            List<String> cycle = new ArrayList<>(creating.stream().map(Class::getSimpleName).toList());
            java.util.Collections.reverse(cycle);
            cycle.add(type.getSimpleName());
            throw new FlyContextException("Circular dependency: " + String.join(" -> ", cycle));
        }
        Constructor<?> constructor = constructorOf(type);
        creating.push(type);
        try {
            Class<?>[] params = constructor.getParameterTypes();
            Object[] args = new Object[params.length];
            for (int i = 0; i < params.length; i++) {
                args[i] = getBean(resolve(params[i], "parameter " + i + " of " + type.getSimpleName()));
            }
            constructor.setAccessible(true);
            Object bean = constructor.newInstance(args);
            singletons.put(type, bean);
            return bean;
        } catch (InvocationTargetException e) {
            throw new FlyContextException("Constructor of " + type.getName() + " failed: " + e.getCause(), e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new FlyContextException("Cannot create " + type.getName(), e);
        } finally {
            creating.pop();
        }
    }

    private static Constructor<?> constructorOf(Class<?> type) {
        if (Modifier.isAbstract(type.getModifiers()) || type.isInterface()) {
            throw new FlyContextException(type.getName() + " is abstract and cannot be a component");
        }
        Constructor<?>[] all = type.getDeclaredConstructors();
        List<Constructor<?>> marked = Arrays.stream(all).filter(c -> c.isAnnotationPresent(FlyAutowired.class)).toList();
        if (marked.size() == 1) {
            return marked.getFirst();
        }
        if (marked.size() > 1) {
            throw new FlyContextException(type.getName() + " has several @FlyAutowired constructors");
        }
        if (all.length == 1) {
            return all[0];
        }
        return Arrays.stream(all).filter(c -> c.getParameterCount() == 0).findFirst().orElseThrow(() ->
                new FlyContextException(type.getName() + " has several constructors; mark one with @FlyAutowired"));
    }

    private void checkOpen() {
        if (refreshed) {
            throw new IllegalStateException("The context is already refreshed");
        }
    }
}

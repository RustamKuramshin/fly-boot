package dev.flyboot.web;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** Converts path variables, query parameters and headers from text to the parameter type. */
final class TypeConversion {

    private static final Map<Class<?>, Function<String, Object>> CONVERTERS = Map.ofEntries(
            Map.entry(String.class, s -> s),
            Map.entry(int.class, Integer::valueOf), Map.entry(Integer.class, Integer::valueOf),
            Map.entry(long.class, Long::valueOf), Map.entry(Long.class, Long::valueOf),
            Map.entry(short.class, Short::valueOf), Map.entry(Short.class, Short::valueOf),
            Map.entry(byte.class, Byte::valueOf), Map.entry(Byte.class, Byte::valueOf),
            Map.entry(double.class, Double::valueOf), Map.entry(Double.class, Double::valueOf),
            Map.entry(float.class, Float::valueOf), Map.entry(Float.class, Float::valueOf),
            Map.entry(boolean.class, TypeConversion::bool), Map.entry(Boolean.class, TypeConversion::bool),
            Map.entry(BigInteger.class, BigInteger::new), Map.entry(BigDecimal.class, BigDecimal::new),
            Map.entry(UUID.class, UUID::fromString));

    private TypeConversion() {
    }

    static boolean supports(Class<?> type) {
        return CONVERTERS.containsKey(type) || type.isEnum();
    }

    /** @throws IllegalArgumentException when the text is not a valid value of the type */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object convert(String text, Class<?> type) {
        if (type.isEnum()) {
            try {
                return Enum.valueOf((Class<Enum>) type, text);
            } catch (IllegalArgumentException e) {
                for (Object c : type.getEnumConstants()) {
                    if (((Enum<?>) c).name().equalsIgnoreCase(text)) {
                        return c;
                    }
                }
                throw new IllegalArgumentException("not one of " + java.util.Arrays.toString(type.getEnumConstants()));
            }
        }
        Function<String, Object> f = CONVERTERS.get(type);
        if (f == null) {
            throw new IllegalArgumentException("unsupported type " + type.getName());
        }
        return f.apply(type == String.class ? text : text.trim());
    }

    private static Boolean bool(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> throw new IllegalArgumentException("not a boolean");
        };
    }

    static Object defaultFor(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        return 0;
    }
}

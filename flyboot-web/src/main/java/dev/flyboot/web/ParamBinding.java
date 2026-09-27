package dev.flyboot.web;

import java.lang.reflect.Type;

/** Where a handler parameter gets its value from. */
sealed interface ParamBinding {

    record PathVar(String name, Class<?> type) implements ParamBinding {
    }

    /** @param list whether the parameter is a {@code List} of {@code type} receiving every value */
    record QueryParam(String name, Class<?> type, boolean list, boolean required, String defaultValue)
            implements ParamBinding {
    }

    record Header(String name, Class<?> type, boolean required, String defaultValue) implements ParamBinding {
    }

    record Body(Type type, boolean required) implements ParamBinding {
    }

    /** The raw {@link com.sun.net.httpserver.HttpExchange}. */
    record Exchange() implements ParamBinding {
    }
}

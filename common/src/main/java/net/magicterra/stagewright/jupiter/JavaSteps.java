package net.magicterra.stagewright.jupiter;

import com.google.gson.Gson;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Step;

/**
 * Java methods that YAML and JavaScript tests reach: the {@link Step @Step} methods of the classes every
 * {@value #INDEX} resource lists, by name, and any public static method by class and name through YAML's {@code java:}.
 * A method takes by type what it needs of {@link Game}, a {@code Map} of its arguments, and the arguments as given
 * ({@code Object}); what it returns is the step's result, read as JSON when it is not plain values.
 */
final class JavaSteps {

    static final String INDEX = "META-INF/stagewright/steps";

    private static final Gson GSON = new Gson();

    private final Map<String, Method> byName;

    private JavaSteps(Map<String, Method> byName) {
        this.byName = byName;
    }

    static JavaSteps none() {
        return new JavaSteps(Map.of());
    }

    /** The {@code @Step} methods of every class the {@value #INDEX} resources on {@code loader} name. */
    static JavaSteps load(ClassLoader loader) {
        Set<String> classes = new LinkedHashSet<>();
        try {
            Enumeration<URL> resources = loader.getResources(INDEX);
            while (resources.hasMoreElements()) {
                try (BufferedReader in = new BufferedReader(
                        new InputStreamReader(resources.nextElement().openStream(), StandardCharsets.UTF_8))) {
                    in.lines()
                            .map(String::trim)
                            .filter(s -> !s.isEmpty() && !s.startsWith("#"))
                            .forEach(classes::add);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + INDEX, e);
        }
        Map<String, Method> byName = new LinkedHashMap<>();
        for (String className : classes) {
            for (Method method : type(className, loader, INDEX).getMethods()) {
                Step step = method.getAnnotation(Step.class);
                if (step == null) {
                    continue;
                }
                String where = "@Step(\"" + step.value() + "\") " + method;
                if (!Modifier.isStatic(method.getModifiers())) {
                    throw new IllegalArgumentException(where + " is not static");
                }
                if (BuiltinSteps.NAMES.contains(step.value())) {
                    throw new IllegalArgumentException(where + ": the name is a built-in step's");
                }
                arguments(method);
                Method other = byName.putIfAbsent(step.value(), method);
                if (other != null) {
                    throw new IllegalArgumentException(where + ": the name is also taken by " + other);
                }
            }
        }
        return new JavaSteps(byName);
    }

    boolean has(String name) {
        return byName.containsKey(name);
    }

    Set<String> names() {
        return byName.keySet();
    }

    Object run(String name, Game game, Object args) {
        return invoke(byName.get(name), game, args);
    }

    /** {@code java: {class, method}}: the one public static method of that name. */
    static Method method(String className, String methodName) {
        List<Method> named = Arrays.stream(
                        type(className, Thread.currentThread().getContextClassLoader(), "the game's class path")
                                .getMethods())
                .filter(m -> m.getName().equals(methodName) && Modifier.isStatic(m.getModifiers()))
                .toList();
        if (named.size() != 1) {
            throw new IllegalArgumentException(className + " has " + named.size() + " public static methods named "
                    + methodName + "; it needs exactly one");
        }
        arguments(named.get(0));
        return named.get(0);
    }

    // The unwrapped cause is what the step failed with; the reflective wrapper adds nothing a reader needs.
    @SuppressWarnings("PMD.PreserveStackTrace")
    static Object invoke(Method method, Game game, Object args) {
        Class<?>[] types = method.getParameterTypes();
        Object[] values = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            if (types[i] == Game.class) {
                values[i] = game;
            } else if (types[i] == Map.class) {
                values[i] = args == null ? Map.of() : asMap(args, method);
            } else {
                values[i] = args;
            }
        }
        Object result;
        try {
            result = method.invoke(null, values);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException r) {
                throw r;
            }
            if (e.getCause() instanceof Error r) {
                throw r;
            }
            throw new IllegalStateException(e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalArgumentException(method + " is not accessible", e);
        }
        return plain(result) ? result : GSON.fromJson(GSON.toJsonTree(result), Object.class);
    }

    /** Refuses a parameter that is none of Game, Map and Object, when the method is found rather than when run. */
    private static void arguments(Method method) {
        for (Class<?> p : method.getParameterTypes()) {
            if (p != Game.class && p != Map.class && p != Object.class) {
                throw new IllegalArgumentException(method + " takes a " + p.getName()
                        + "; a step's method takes only Game, Map (its arguments) and Object (the same, as given)");
            }
        }
    }

    private static Map<?, ?> asMap(Object args, Method method) {
        if (args instanceof Map<?, ?> m) {
            return m;
        }
        throw new IllegalArgumentException(method + " takes its arguments as a map, not " + Expectations.show(args));
    }

    private static Class<?> type(String className, ClassLoader loader, String where) {
        try {
            return Class.forName(className, true, loader);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("no class " + className + " (named in " + where + ")", e);
        }
    }

    private static boolean plain(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return true;
        }
        if (value instanceof List<?> list) {
            return list.stream().allMatch(JavaSteps::plain);
        }
        if (value instanceof Map<?, ?> map) {
            return map.keySet().stream().allMatch(String.class::isInstance)
                    && map.values().stream().allMatch(JavaSteps::plain);
        }
        return false;
    }
}

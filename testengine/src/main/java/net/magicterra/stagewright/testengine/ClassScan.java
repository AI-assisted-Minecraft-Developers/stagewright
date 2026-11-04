package net.magicterra.stagewright.testengine;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Reads a test class from its bytes, never loading it: its signatures name Minecraft and the mods, which this JVM does
 * not have. What it reads is what the outer tree needs — the scenes, the test methods (inherited ones too), and display
 * names — and the game's own discovery is checked against it.
 */
final class ClassScan {

    static final String SCENE = "Lnet/magicterra/stagewright/test/Scene;";

    private static final String DISPLAY_NAME = "Lorg/junit/jupiter/api/DisplayName;";

    private static final String NESTED = "Lorg/junit/jupiter/api/Nested;";

    private static final String TEST = "Lorg/junit/jupiter/api/Test;";

    /** The annotations that make a method a test or a source of tests in Jupiter. */
    private static final List<String> TESTABLE = List.of(
            TEST,
            "Lorg/junit/jupiter/api/RepeatedTest;",
            "Lorg/junit/jupiter/api/TestFactory;",
            "Lorg/junit/jupiter/api/TestTemplate;",
            "Lorg/junit/jupiter/params/ParameterizedTest;");

    /**
     * One test method. {@code parameters} is spelled as JUnit's {@code MethodSource} spells it; a {@code container}
     * (parameterized, repeated, a factory) has its invocations registered while the game runs it. {@code conditions}:
     * the Jupiter conditions on it that {@link Condition} reads.
     */
    record Method(
            String name,
            String parameters,
            Optional<String> displayName,
            boolean container,
            List<Condition> conditions) {
        String key() {
            return name + "(" + parameters + ")";
        }
    }

    /**
     * A class that declares scenes; {@code methods} in the order the class file lists them, inherited ones last.
     * {@code conditions}: those declared on the class itself.
     */
    record Scanned(
            String className,
            List<String> scenes,
            Optional<String> displayName,
            List<Method> methods,
            List<Condition> conditions) {}

    /**
     * A Jupiter condition this JVM can judge for the game before starting it: {@code @Disabled}, and
     * {@code @EnabledIfEnvironmentVariable} / {@code @DisabledIfEnvironmentVariable}, repeated ones too, since the game
     * is started with this JVM's environment and its launch's additions. A system property, a custom condition or a
     * meta-annotation is left to the game, whose JVM is not this one; reading none of them only means a test is not
     * known to be disabled, and its game starts as before.
     */
    record Condition(Kind kind, String named, String matches, String reason) {
        enum Kind {
            DISABLED,
            ENABLED_IF_ENV,
            DISABLED_IF_ENV
        }

        /** Why this disables a test in a game with {@code env}, or empty when it does not. */
        Optional<String> disables(Map<String, String> env) {
            String why = reason.isEmpty() ? null : reason;
            switch (kind) {
                case DISABLED:
                    return Optional.of(why == null ? "@Disabled" : why);
                case ENABLED_IF_ENV: {
                    String v = env.get(named);
                    boolean met = v != null && v.matches(matches);
                    return met
                            ? Optional.empty()
                            : Optional.of(
                                    why == null ? "environment variable " + named + " does not match " + matches : why);
                }
                default: {
                    String v = env.get(named);
                    boolean met = v != null && v.matches(matches);
                    return met
                            ? Optional.of(why == null ? "environment variable " + named + " matches " + matches : why)
                            : Optional.empty();
                }
            }
        }

        /** The first of {@code conditions} that disables a test in a game with {@code env}. */
        static Optional<String> firstDisabling(List<Condition> conditions, Map<String, String> env) {
            for (Condition c : conditions) {
                Optional<String> why = c.disables(env);
                if (why.isPresent()) {
                    return why;
                }
            }
            return Optional.empty();
        }
    }

    private static final String DISABLED = "Lorg/junit/jupiter/api/Disabled;";

    private static final String ENABLED_IF_ENV = "Lorg/junit/jupiter/api/condition/EnabledIfEnvironmentVariable;";

    private static final String DISABLED_IF_ENV = "Lorg/junit/jupiter/api/condition/DisabledIfEnvironmentVariable;";

    private static final String ENABLED_IF_ENVS = "Lorg/junit/jupiter/api/condition/EnabledIfEnvironmentVariables;";

    private static final String DISABLED_IF_ENVS = "Lorg/junit/jupiter/api/condition/DisabledIfEnvironmentVariables;";

    private ClassScan() {}

    /**
     * The class named {@code className} as {@code loader} holds its bytes, or empty when it is no StageWright test:
     * abstract, an interface, nested, or declaring no {@code @Scene}.
     */
    static Optional<Scanned> scan(String className, ClassLoader loader) throws IOException {
        Reading top = read(className, loader);
        if (top == null || top.skip || top.scenes.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Method> methods = new LinkedHashMap<>(top.methods);
        // Inherited tests run in the subclass too; an override with the same signature is the subclass's.
        for (String parent = top.superName; parent != null && !"java.lang.Object".equals(parent); ) {
            Reading up = read(parent, loader);
            if (up == null) {
                break;
            }
            up.methods.forEach(methods::putIfAbsent);
            parent = up.superName;
        }
        return Optional.of(new Scanned(
                className,
                List.copyOf(top.scenes),
                Optional.ofNullable(top.displayName),
                List.copyOf(methods.values()),
                List.copyOf(top.conditions)));
    }

    private static Reading read(String className, ClassLoader loader) throws IOException {
        try (InputStream in = loader.getResourceAsStream(className.replace('.', '/') + ".class")) {
            if (in == null) {
                return null;
            }
            Reading reading = new Reading();
            new ClassReader(in)
                    .accept(reading, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return reading;
        }
    }

    /**
     * {@code (I[Ljava/lang/String;)V} becomes {@code int, [Ljava.lang.String;}, as {@code Class.getName} spells each.
     */
    static String parameters(String descriptor) {
        List<String> names = new ArrayList<>();
        for (Type t : Type.getArgumentTypes(descriptor)) {
            names.add(t.getSort() == Type.ARRAY ? t.getDescriptor().replace('/', '.') : t.getClassName());
        }
        return String.join(", ", names);
    }

    private static final class Reading extends ClassVisitor {
        final List<String> scenes = new ArrayList<>();

        final Map<String, Method> methods = new LinkedHashMap<>();

        final List<Condition> conditions = new ArrayList<>();

        String superName;

        String displayName;

        boolean skip;

        Reading() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(
                int version, int access, String name, String signature, String superName, String[] interfaces) {
            this.superName = superName == null ? null : superName.replace('/', '.');
            skip = (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE | Opcodes.ACC_ANNOTATION)) != 0;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (NESTED.equals(descriptor)) {
                skip = true;
            }
            if (SCENE.equals(descriptor)) {
                return values(scenes);
            }
            if (DISPLAY_NAME.equals(descriptor)) {
                return value(v -> displayName = v);
            }
            return condition(descriptor, conditions);
        }

        @Override
        public MethodVisitor visitMethod(
                int access, String name, String descriptor, String signature, String[] exceptions) {
            if ((access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC)) != 0) {
                return null;
            }
            String parameters = parameters(descriptor);
            return new MethodVisitor(Opcodes.ASM9) {
                final List<Condition> on = new ArrayList<>();
                boolean testable;
                boolean container;
                String display;

                @Override
                public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                    if (TESTABLE.contains(annotation)) {
                        testable = true;
                        container = !TEST.equals(annotation);
                    }
                    if (DISPLAY_NAME.equals(annotation)) {
                        return value(v -> display = v);
                    }
                    return condition(annotation, on);
                }

                @Override
                public void visitEnd() {
                    if (!testable) {
                        return;
                    }
                    Method m = new Method(name, parameters, Optional.ofNullable(display), container, List.copyOf(on));
                    methods.put(m.key(), m);
                }
            };
        }
    }

    /**
     * A visitor adding the condition {@code descriptor} names to {@code into}, or null when it names none read here.
     */
    private static AnnotationVisitor condition(String descriptor, List<Condition> into) {
        switch (descriptor) {
            case DISABLED:
                return conditionValues(Condition.Kind.DISABLED, into);
            case ENABLED_IF_ENV:
                return conditionValues(Condition.Kind.ENABLED_IF_ENV, into);
            case DISABLED_IF_ENV:
                return conditionValues(Condition.Kind.DISABLED_IF_ENV, into);
            case ENABLED_IF_ENVS:
                return repeated(Condition.Kind.ENABLED_IF_ENV, into);
            case DISABLED_IF_ENVS:
                return repeated(Condition.Kind.DISABLED_IF_ENV, into);
            default:
                return null;
        }
    }

    /** One condition's values: {@code named} and {@code matches} for the environment ones, the reason for each. */
    private static AnnotationVisitor conditionValues(Condition.Kind kind, List<Condition> into) {
        return new AnnotationVisitor(Opcodes.ASM9) {
            final Map<String, String> values = new LinkedHashMap<>();

            @Override
            public void visit(String name, Object value) {
                values.put(name, String.valueOf(value));
            }

            @Override
            public void visitEnd() {
                String reason = values.getOrDefault(kind == Condition.Kind.DISABLED ? "value" : "disabledReason", "");
                into.add(new Condition(
                        kind, values.getOrDefault("named", ""), values.getOrDefault("matches", ""), reason));
            }
        };
    }

    /** A repeatable condition's container: each annotation in its {@code value}. */
    private static AnnotationVisitor repeated(Condition.Kind kind, List<Condition> into) {
        return new AnnotationVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitArray(String name) {
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String n, String descriptor) {
                        return conditionValues(kind, into);
                    }
                };
            }
        };
    }

    private static AnnotationVisitor values(List<String> into) {
        return new AnnotationVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitArray(String name) {
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(String n, Object value) {
                        into.add(String.valueOf(value));
                    }
                };
            }

            @Override
            public void visit(String name, Object value) {
                into.add(String.valueOf(value));
            }
        };
    }

    private static AnnotationVisitor value(java.util.function.Consumer<String> into) {
        return new AnnotationVisitor(Opcodes.ASM9) {
            @Override
            public void visit(String name, Object value) {
                if ("value".equals(name)) {
                    into.accept(String.valueOf(value));
                }
            }
        };
    }
}

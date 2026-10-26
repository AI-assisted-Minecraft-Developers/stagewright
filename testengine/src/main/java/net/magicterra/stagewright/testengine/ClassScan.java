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
 * Reads a test class from its bytes, never loading it: its signatures name Minecraft and the mods, which this JVM
 * does not have. What it reads is what the outer tree needs — the scenes, the test methods (inherited ones too), and
 * display names — and the game's own discovery is checked against it.
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
     * (parameterized, repeated, a factory) has its invocations registered while the game runs it.
     */
    record Method(String name, String parameters, Optional<String> displayName, boolean container) {
        String key() {
            return name + "(" + parameters + ")";
        }
    }

    /** A class that declares scenes; {@code methods} in the order the class file lists them, inherited ones last. */
    record Scanned(String className, List<String> scenes, Optional<String> displayName, List<Method> methods) {}

    private ClassScan() {}

    /**
     * The class named {@code className} as {@code loader} holds its bytes, or empty when it is no StageWright test:
     * abstract, an interface, nested, or declaring no {@code @Scene}.
     */
    static Optional<Scanned> scan(String className, ClassLoader loader) throws IOException {
        Reading top = read(className, loader);
        if (top == null || top.skip || top.scenes.isEmpty()) return Optional.empty();
        Map<String, Method> methods = new LinkedHashMap<>(top.methods);
        // Inherited tests run in the subclass too; an override with the same signature is the subclass's.
        for (String parent = top.superName; parent != null && !parent.equals("java.lang.Object"); ) {
            Reading up = read(parent, loader);
            if (up == null) break;
            up.methods.forEach(methods::putIfAbsent);
            parent = up.superName;
        }
        return Optional.of(new Scanned(className, List.copyOf(top.scenes), Optional.ofNullable(top.displayName),
                List.copyOf(methods.values())));
    }

    private static Reading read(String className, ClassLoader loader) throws IOException {
        try (InputStream in = loader.getResourceAsStream(className.replace('.', '/') + ".class")) {
            if (in == null) return null;
            Reading reading = new Reading();
            new ClassReader(in).accept(reading, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return reading;
        }
    }

    /** {@code (I[Ljava/lang/String;)V} becomes {@code int, [Ljava.lang.String;}, as {@code Class.getName} spells each. */
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
        String superName;
        String displayName;
        boolean skip;

        Reading() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            this.superName = superName == null ? null : superName.replace('/', '.');
            skip = (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE | Opcodes.ACC_ANNOTATION)) != 0;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (descriptor.equals(NESTED)) skip = true;
            if (descriptor.equals(SCENE)) return values(scenes);
            if (descriptor.equals(DISPLAY_NAME)) return value(v -> displayName = v);
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            if ((access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC)) != 0) return null;
            String parameters = parameters(descriptor);
            return new MethodVisitor(Opcodes.ASM9) {
                boolean testable;
                boolean container;
                String display;

                @Override
                public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                    if (TESTABLE.contains(annotation)) {
                        testable = true;
                        container = !annotation.equals(TEST);
                    }
                    if (annotation.equals(DISPLAY_NAME)) return value(v -> display = v);
                    return null;
                }

                @Override
                public void visitEnd() {
                    if (!testable) return;
                    Method m = new Method(name, parameters, Optional.ofNullable(display), container);
                    methods.put(m.key(), m);
                }
            };
        }
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
                if ("value".equals(name)) into.accept(String.valueOf(value));
            }
        };
    }
}

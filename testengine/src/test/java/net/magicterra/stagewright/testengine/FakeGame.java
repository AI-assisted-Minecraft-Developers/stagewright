package net.magicterra.stagewright.testengine;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * A stand-in for the game: started by the engine as the primary process, it reports a scripted test run through the
 * supervision agent's {@code Control}, the way the in-game JUnit run does. The one argument names the scenario.
 */
public final class FakeGame {

    private static final String ENGINE = "[engine:junit-jupiter]";

    private FakeGame() {}

    public static void main(String[] args) throws Exception {
        String scenario = args[0];
        // The agent connects on its own thread and drops what the game sends before that; a real game takes seconds
        // to reach its first event, this one has to wait the connection out.
        Thread.sleep(1500);
        List<String> classes = List.of(System.getProperty("stagewright.junit.classes", "").split(","));
        if (scenario.equals("dynamic")) {
            dynamic(classes);
            return;
        }
        if (scenario.equals("script")) {
            script(System.getProperty("stagewright.junit.scripts", ""));
            return;
        }
        List<String> methods = scenario.equals("undiscovered") ? List.of("a") : List.of("a", "b");
        plan(classes, methods);
        if (scenario.equals("crash")) {
            start(classId(classes.get(0)));
            start(methodId(classes.get(0), "a"));
            System.exit(1);
        }
        String worldProblem = scenario.equals("world") ? worldProblem() : null;
        String skipReason = switch (scenario) {
            case "reqSkip", "classSkip" -> "@Requires: this game has no CLIENT";
            case "otherSkip", "classSkipOther" -> "@Disabled";
            default -> null;
        };
        for (String c : classes) {
            if (scenario.startsWith("classSkip")) {
                // Jupiter reports a skipped class as that one node; nothing under it is reported.
                skip(classId(c), skipReason);
                continue;
            }
            start(classId(c));
            for (String m : methods) {
                if (skipReason != null && m.equals("b")) {
                    skip(methodId(c, m), skipReason);
                    continue;
                }
                start(methodId(c, m));
                if (worldProblem != null) {
                    finish(methodId(c, m), "FAILED", "{\"class\":\"org.opentest4j.AssertionFailedError\","
                            + "\"message\":" + q(worldProblem) + ",\"trace\":[]}");
                } else if (scenario.equals("assertFail") && m.equals("b")) {
                    finish(methodId(c, m), "FAILED", "{\"class\":\"org.opentest4j.AssertionFailedError\","
                            + "\"message\":\"expected 1 but was 2\",\"trace\":[\"x.Y.m(Y.java:3)\"]}");
                } else {
                    finish(methodId(c, m), "SUCCESSFUL", null);
                }
            }
            finish(classId(c), "SUCCESSFUL", null);
        }
        test("{\"type\":\"ended\"}");
        done();
        System.exit(0);
    }

    /** What the engine should have handed a game of the "world" scenario; null when it did. */
    private static String worldProblem() throws Exception {
        String json = System.getProperty("stagewright.scene.world");
        if (json == null) return "-Dstagewright.scene.world was not passed";
        if (!json.contains("\"time\":6000")) return "-Dstagewright.scene.world lacks the declared time: " + json;
        java.nio.file.Path file = java.nio.file.Path.of("server.properties").toAbsolutePath();
        if (!java.nio.file.Files.isRegularFile(file)) return "no server.properties at " + file;
        String text = java.nio.file.Files.readString(file);
        if (!text.contains("level-seed=5471")) return "server.properties lacks level-seed=5471: " + text;
        if (!text.contains("server-port=1")) return "server.properties lost server-port=1: " + text;
        return null;
    }

    private static void dynamic(List<String> classes) throws Exception {
        String c = classes.get(0);
        List<String> nodes = new ArrayList<>();
        nodes.add(node(ENGINE, null, "engine", false, null, null, null));
        nodes.add(classNode(c));
        String method = ENGINE + "/[class:" + c + "]/[test-template:rows(java.lang.String)]";
        nodes.add(node(method, classId(c), "rows(String)", false, c, "rows", "java.lang.String"));
        test("{\"type\":\"planned\",\"nodes\":[" + String.join(",", nodes) + "]}");
        start(classId(c));
        start(method);
        for (int i = 1; i <= 2; i++) {
            String id = method + "/[test-template-invocation:#" + i + "]";
            test("{\"type\":\"dynamic\",\"node\":" + node(id, method, "[" + i + "] row", true, null, null, null) + "}");
            start(id);
            finish(id, "SUCCESSFUL", null);
        }
        finish(method, "SUCCESSFUL", null);
        finish(classId(c), "SUCCESSFUL", null);
        test("{\"type\":\"ended\"}");
        done();
        System.exit(0);
    }

    /** The in-game script engine's events for a file registering "adds", which passes, and "fails", which does not. */
    private static void script(String resource) throws Exception {
        String engine = "[engine:stagewright-script]";
        String file = engine + "/[script:" + resource + "]";
        String scene = System.getProperty("stagewright.junit.scene");
        List<String> nodes = new ArrayList<>();
        nodes.add(node(engine, null, "StageWright scripts", false, null, null, null));
        nodes.add(scriptNode(file, engine, resource, false, resource));
        for (String t : List.of("adds", "fails")) nodes.add(scriptNode(file + "/[test:" + t + "]", file, t, true, resource));
        test("{\"type\":\"planned\",\"nodes\":[" + String.join(",", nodes) + "]}");
        start(file);
        start(file + "/[test:adds]");
        test("{\"type\":\"report\",\"id\":" + q(file + "/[test:adds]") + ",\"entries\":{\"sum\":\"2\"}}");
        if ("script".equals(scene)) finish(file + "/[test:adds]", "SUCCESSFUL", null);
        else finish(file + "/[test:adds]", "FAILED", "{\"class\":\"java.lang.AssertionError\",\"message\":"
                + q("-Dstagewright.junit.scene was " + scene) + ",\"trace\":[]}");
        start(file + "/[test:fails]");
        finish(file + "/[test:fails]", "FAILED", "{\"class\":\"java.lang.AssertionError\","
                + "\"message\":\"expected 3 but was 2\",\"trace\":[]}");
        finish(file, "SUCCESSFUL", null);
        test("{\"type\":\"ended\"}");
        done();
        System.exit(0);
    }

    private static String scriptNode(String id, String parent, String name, boolean test, String resource) {
        String n = node(id, parent, name, test, null, null, null);
        return n.substring(0, n.length() - 1) + ",\"script\":" + q(resource) + "}";
    }

    private static void plan(List<String> classes, List<String> methods) throws Exception {
        List<String> nodes = new ArrayList<>();
        nodes.add(node(ENGINE, null, "engine", false, null, null, null));
        for (String c : classes) {
            nodes.add(classNode(c));
            for (String m : methods) nodes.add(node(methodId(c, m), classId(c), m + "()", true, c, m, ""));
        }
        test("{\"type\":\"planned\",\"nodes\":[" + String.join(",", nodes) + "]}");
    }

    private static String classNode(String c) {
        return node(classId(c), ENGINE, c.substring(c.lastIndexOf('.') + 1), false, c, null, null);
    }

    private static String node(String id, String parent, String name, boolean test, String className, String method,
                               String parameters) {
        StringBuilder s = new StringBuilder("{\"id\":").append(q(id));
        if (parent != null) s.append(",\"parent\":").append(q(parent));
        s.append(",\"name\":").append(q(name)).append(",\"test\":").append(test);
        if (className != null) s.append(",\"class\":").append(q(className));
        if (method != null) s.append(",\"method\":").append(q(method));
        if (parameters != null) s.append(",\"parameters\":").append(q(parameters));
        return s.append('}').toString();
    }

    private static String classId(String c) {
        return ENGINE + "/[class:" + c + "]";
    }

    private static String methodId(String c, String m) {
        return classId(c) + "/[method:" + m + "()]";
    }

    private static void start(String id) throws Exception {
        test("{\"type\":\"started\",\"id\":" + q(id) + "}");
    }

    private static void skip(String id, String reason) throws Exception {
        test("{\"type\":\"skipped\",\"id\":" + q(id) + ",\"reason\":" + q(reason) + "}");
    }

    private static void finish(String id, String status, String failure) throws Exception {
        test("{\"type\":\"finished\",\"id\":" + q(id) + ",\"status\":" + q(status)
                + (failure == null ? "" : ",\"failure\":" + failure) + "}");
    }

    private static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void test(String json) throws Exception {
        control("test", String.class, json);
    }

    private static void done() throws Exception {
        control("done", null, null);
    }

    private static void control(String name, Class<?> type, Object arg) throws Exception {
        Class<?> control = ClassLoader.getSystemClassLoader().loadClass("net.magicterra.stagewright.agent.Control");
        if (type == null) {
            control.getMethod(name).invoke(null);
        } else {
            Method m = control.getMethod(name, type);
            m.invoke(null, arg);
        }
    }
}

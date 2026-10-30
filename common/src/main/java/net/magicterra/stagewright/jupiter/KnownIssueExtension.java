package net.magicterra.stagewright.jupiter;

import java.lang.reflect.Method;
import net.magicterra.stagewright.test.KnownIssue;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;
import org.opentest4j.AssertionFailedError;

/** Turns a {@link KnownIssue} test's failure into an abort, and its passing into a failure. */
public final class KnownIssueExtension implements InvocationInterceptor {

    private static final String ABORTED = "org.opentest4j.TestAbortedException";

    @Override
    public void interceptTestMethod(
            Invocation<Void> invocation,
            ReflectiveInvocationContext<Method> invocationContext,
            ExtensionContext context)
            throws Throwable {
        KnownIssue known = invocationContext.getExecutable().getAnnotation(KnownIssue.class);
        if (known == null) {
            invocation.proceed();
            return;
        }
        // The test's failed soft checks count as its failure; taken here, GameExtension does not report them again.
        TestScope scope = TestScope.bound();
        try {
            invocation.proceed();
        } catch (Throwable t) {
            AssertionError checks = scope == null ? null : scope.takeChecks();
            if (checks != null) {
                t.addSuppressed(checks);
            }
            if (isAbort(t)) {
                throw t;
            }
            throw abort("known issue: " + known.value(), t);
        }
        AssertionError checks = scope == null ? null : scope.takeChecks();
        if (checks != null) {
            throw abort("known issue: " + known.value(), checks);
        }
        throw new AssertionFailedError("passed although marked @KnownIssue(\"" + known.value()
                + "\"): if the issue is fixed, remove the annotation");
    }

    // In a Fabric dev run JUnit is loaded by the application class loader while Knot loads its own copy of
    // opentest4j for mod classes, so a TestAbortedException built here is not the class Jupiter checks for and
    // would be reported as a failure. Build and recognise the one JUnit's own loader sees.

    private static Throwable abort(String message, Throwable cause) throws ReflectiveOperationException {
        Class<?> type = Class.forName(ABORTED, false, Assumptions.class.getClassLoader());
        return (Throwable) type.getConstructor(String.class, Throwable.class).newInstance(message, cause);
    }

    private static boolean isAbort(Throwable t) {
        for (Class<?> c = t.getClass(); c != null; c = c.getSuperclass()) {
            if (c.getName().equals(ABORTED)) {
                return true;
            }
        }
        return false;
    }
}

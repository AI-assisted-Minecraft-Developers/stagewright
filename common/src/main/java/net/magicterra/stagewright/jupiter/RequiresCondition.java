package net.magicterra.stagewright.jupiter;

import java.lang.reflect.AnnotatedElement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.magicterra.stagewright.scene.Mods;
import net.magicterra.stagewright.test.Requires;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;

/**
 * Disables what {@link Requires} names when this game lacks it. The reason starts with {@value #REASON_PREFIX}: the
 * test engine outside the game reads that prefix to tell a requirement no scene met from any other skip.
 */
public final class RequiresCondition implements ExecutionCondition {

    static final String REASON_PREFIX = "@Requires: ";

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        AnnotatedElement element = context.getElement().orElse(null);
        Requires requires = element == null ? null
                : AnnotationSupport.findAnnotation(element, Requires.class).orElse(null);
        if (requires == null) return ConditionEvaluationResult.enabled("no @Requires");
        List<String> missing = missing(requires);
        return missing.isEmpty() ? ConditionEvaluationResult.enabled("this game has what @Requires names")
                : ConditionEvaluationResult.disabled(REASON_PREFIX + "this game has no " + String.join(", ", missing));
    }

    private static List<String> missing(Requires requires) {
        List<String> missing = new ArrayList<>();
        if (requires.value().length > 0) {
            boolean dedicated = GameExtension.game().onServer(s -> s.isDedicatedServer());
            for (Requires.Capability c : requires.value()) {
                boolean has = switch (c) {
                    // A dedicated server has a real client only when its topology joins one, and the run waits
                    // for that client before any test starts.
                    case CLIENT -> !dedicated || Boolean.getBoolean(JupiterRun.AWAIT_PLAYER);
                    case DEDICATED_SERVER -> dedicated;
                    case INTEGRATED_SERVER -> !dedicated;
                };
                if (!has) missing.add(c.name());
            }
        }
        if (requires.mods().length > 0) {
            if (!Mods.isInstalled()) {
                throw new IllegalStateException("@Requires(mods) cannot be answered: StageWright's loader entrypoint"
                        + " never installed the mod list");
            }
            Set<String> loaded = Mods.installedIds();
            for (String mod : requires.mods()) if (!loaded.contains(mod)) missing.add("mod " + mod);
        }
        return missing;
    }
}

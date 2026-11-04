package net.magicterra.stagewright.testengine.fixtures;

import net.magicterra.stagewright.test.Scene;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

/** Each test disabled by a condition of its own. */
@Scene("methodsDisabled")
public class MethodsDisabledFixture {
    @Test
    @Disabled("not yet")
    void a() {}

    @Test
    @DisabledIfEnvironmentVariable(named = "PATH", matches = ".*")
    void b() {}
}

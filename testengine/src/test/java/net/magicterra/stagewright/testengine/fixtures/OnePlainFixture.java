package net.magicterra.stagewright.testengine.fixtures;

import net.magicterra.stagewright.test.Scene;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/** One test disabled and one not: the game still starts for the one. */
@Scene("onePlain")
public class OnePlainFixture {
    @Test
    @Disabled
    void a() {}

    @Test
    void b() {}
}

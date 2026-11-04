package net.magicterra.stagewright.testengine.fixtures;

import net.magicterra.stagewright.test.Scene;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Enabled by a variable only its topology's launch sets, which the game has and this JVM does not. */
@Scene("enabledBySpec")
@EnabledIfEnvironmentVariable(named = "STAGEWRIGHT_TEST_SPEC_ENV", matches = "yes")
public class EnabledBySpecFixture extends BaseFixture {}

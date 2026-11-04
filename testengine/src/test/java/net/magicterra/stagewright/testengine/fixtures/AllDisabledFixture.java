package net.magicterra.stagewright.testengine.fixtures;

import net.magicterra.stagewright.test.Scene;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Enabled only by a variable nothing sets, so both inherited tests are disabled and no game is needed. */
@Scene("allDisabled")
@EnabledIfEnvironmentVariable(named = "STAGEWRIGHT_TEST_NEVER_SET", matches = ".*")
public class AllDisabledFixture extends BaseFixture {}

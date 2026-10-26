package net.magicterra.stagewright.testengine.fixtures;

import net.magicterra.stagewright.test.Scene;

/** Skipped for an unmet requirement in one scene, run in the other. */
@Scene({"reqSkip", "pass"})
public class TwoScenesFixture extends BaseFixture {}

package net.magicterra.stagewright.test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The game instance a test class runs in, by scene name. Every test class naming the same scene shares one instance;
 * naming several runs the class once in each. The scene itself — topology, mods, world settings — is declared in a
 * scene file of that name, not here.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Scene {

    /** Scene names; at least one. */
    String[] value();
}

package net.magicterra.stagewright.test;

/**
 * The real client's player, injected as a test method parameter: the integrated server's own, or the client a topology
 * joins to its server. A game without one refuses the parameter, so a test taking it should carry
 * {@code @Requires(CLIENT)}. It is shared by every test of the run and keeps whatever a test leaves on it.
 */
public interface Player extends Body {}

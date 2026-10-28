package net.magicterra.stagewright.test;

import java.util.function.UnaryOperator;

import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts.Press;
import net.magicterra.worlddriver.api.methods.test.TestInputContracts.HeldKeys;

/**
 * A body WorldDriver drives on the server's tick, which a test may take over: {@link #park} takes it off the tick
 * list, and from then on only {@link #tick}, {@link #step} and {@link #think} move it, one tick at a time, so the test
 * can read or change the world between any two of them. A parked body stays parked until {@link #unpark} or until a
 * task is started on it, which puts it back on the list.
 */
public interface Driven {

    /** WorldDriver's reference to this body, the value its methods' {@code bot} parameter takes. */
    String ref();

    /** Off the tick list: it stands where it is until the test ticks it. */
    Driven park();

    /** Back on the tick list: the server ticks it every tick, busy or idle. */
    Driven unpark();

    /** {@code ticks} full ticks of this body alone, parked or not: perceiving, reflexes, task, then the body's step. */
    void tick(int ticks);

    /** Full ticks of this body alone until its task ends or {@code maxTicks} ran out; the ticks it took. */
    int tickUntilIdle(int maxTicks);

    /** Only the body's physics step, {@code ticks} times: no perceiving, reflexes or task. */
    void step(int ticks);

    /**
     * Only the brain, once: perceiving, reflexes and task leave their input on the body, and nothing moves until the
     * next {@link #step}, so a {@link #press} in between changes what that step does.
     */
    void think();

    /**
     * Hold or let go of keys, each staying until a later press changes it: {@code keys} edits a press that changes
     * nothing, for example {@code body.press(k -> k.move(0, 1).jump(true))}. Answers what is held after.
     */
    HeldKeys press(UnaryOperator<Press> keys);
}

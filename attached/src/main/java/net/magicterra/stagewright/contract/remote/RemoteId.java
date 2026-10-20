package net.magicterra.stagewright.contract.remote;

/**
 * Something a registry names — a block, say — as the out-of-process home holds it.
 *
 * <p>By id, not by reference, and that is what makes {@code expect(blockAt(0, 0, 0)).isEqualTo(block('minecraft:stone'))}
 * mean the same in both homes: two references to the same block would be two different handles, and
 * would never compare equal. Two ids that name the same thing always do.
 */
public record RemoteId(String kind, String id) {
    @Override
    public String toString() {
        return id;
    }
}

package net.magicterra.stagewright.scene;

import net.magicterra.stagewright.contract.SceneFailure;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.server.level.ServerPlayer;

/**
 * FTB Quests — the quest book a modpack's progression is actually written in.
 *
 * <p>Reached as {@link SceneContext#quests()}. All the Mods 10 ships 60-odd chapters under
 * {@code config/ftbquests/quests/chapters/}, and the pack's headline goal — the ATM Star — is a
 * quest chain, not a recipe alone. A pack author editing that book has no way today to find out
 * whether they broke it short of playing it.
 *
 * <h2>Test the graph, not the playthrough</h2>
 *
 * The same argument as {@link Recipes#closureOf}: nobody can play a hundred hours in a gate, but the
 * property that breaks when a quest is edited is a property of the graph. Does every quest's
 * dependency still exist? Is the chapter that gates the endgame reachable from the start? Did a
 * renamed quest leave a dangling reference? Those are all answerable in seconds from the loaded
 * quest file.
 *
 * <h2>Reached by name, and absent means skip</h2>
 *
 * FTB Quests is not a dependency of {@code :stagewright-api} — it cannot be, because every
 * conformance fork compiles against this module and almost none of them ship a quest book. So the
 * whole facet is reflection over {@code dev.ftb.mods.ftbquests}, and a runtime without it
 * {@code skip}s the scene with a reason rather than throwing or quietly passing. Same rule as
 * {@link Equip}'s Curios half and {@link SceneContext#player()}'s missing player.
 *
 * <p>Quests are identified by their <b>code string</b> — the hex id FTB Quests itself uses in the
 * book and in its own commands, e.g. {@code "1A2B3C4D5E6F7080"}. Longs would be the internal form,
 * but a scene author reads code strings out of the quest book, so that is what this takes.
 */
public final class Quests {

    private static final String SERVER_QUEST_FILE = "dev.ftb.mods.ftbquests.quest.ServerQuestFile";

    private final SceneContext ctx;

    Quests(SceneContext ctx) {
        this.ctx = ctx;
    }

    /** Whether FTB Quests is in this runtime with a loaded quest file. Lets a scene branch rather
     *  than skip, for a suite where the quest book is a bonus rather than the subject. */
    public boolean loaded() {
        return questFile() != null;
    }

    /** Chapter filenames, in book order — the same names as the {@code .snbt} files under
     *  {@code config/ftbquests/quests/chapters/}. */
    public List<String> chapters() {
        List<String> out = new ArrayList<>();
        for (Object chapter : allChapters()) {
            out.add(String.valueOf(call(chapter, "getFilename")));
        }
        return out;
    }

    /** How many quests the book holds, across every chapter. */
    public int questCount() {
        int total = 0;
        for (Object chapter : allChapters()) total += questsIn(chapter).size();
        return total;
    }

    /** Every quest's code string, in book order. */
    public List<String> allQuests() {
        List<String> out = new ArrayList<>();
        for (Object chapter : allChapters()) {
            for (Object quest : questsIn(chapter)) out.add(codeOf(quest));
        }
        return out;
    }

    /** The code strings of the quests in one chapter, named by its filename. */
    public List<String> questsInChapter(String filename) {
        for (Object chapter : allChapters()) {
            if (!filename.equals(String.valueOf(call(chapter, "getFilename")))) continue;
            List<String> out = new ArrayList<>();
            for (Object quest : questsIn(chapter)) out.add(codeOf(quest));
            return out;
        }
        throw new SceneFailure("no chapter is named '" + filename + "' — this book has "
                + String.join(", ", chapters()));
    }

    /**
     * The code strings this quest depends on.
     *
     * <p>The edge list of the progression graph, and the thing a pack audit walks. A dependency
     * naming a quest that no longer exists is the defect this exists to find — FTB Quests loads
     * such a book without complaint and the chain simply becomes uncompletable.
     */
    public List<String> dependenciesOf(String questCode) {
        Object quest = requireQuest(questCode);
        Object stream = call(quest, "streamDependencies");
        List<String> out = new ArrayList<>();
        if (stream instanceof Stream<?> s) {
            s.forEach(dep -> out.add(codeOf(dep)));
        }
        return out;
    }

    /** Whether this quest is completed for the scene's player (strictly: for their team). */
    public boolean isComplete(String questCode) {
        Object quest = requireQuest(questCode);
        return (boolean) call(teamData(), "isCompleted", new Class<?>[] { questObjectClass() }, quest);
    }

    /** Whether the player may start this quest — i.e. its dependencies are met. The half of the
     *  graph that {@link #dependenciesOf} only describes. */
    public boolean canStart(String questCode) {
        Object quest = requireQuest(questCode);
        return (boolean) call(teamData(), "canStartTasks", new Class<?>[] { questClass() }, quest);
    }

    /**
     * Mark a quest completed for the player's team.
     *
     * <p>Registers a cleanup? <b>No — and that is deliberate.</b> FTB Quests has no public "un-complete"
     * that also unwinds the rewards and the dependent quests it may have unlocked, so an undo here
     * would be a half-undo pretending to be a whole one. Instead, a scene that completes quests
     * should run against a provisioned world — which every gate topology already does, because
     * {@code cleanWorld} deletes the save before the run. The honest position is to say so rather
     * than to register a cleanup that leaves the book in a state nobody can describe.
     */
    public Quests complete(String questCode) {
        Object quest = requireQuest(questCode);
        long id = idOf(quest);
        call(teamData(), "setCompleted", new Class<?>[] { long.class, Date.class }, id, new Date());
        return this;
    }

    // ---- internals ----

    private Class<?> serverQuestFileClass() {
        try {
            return Class.forName(SERVER_QUEST_FILE, false, Quests.class.getClassLoader());
        } catch (Throwable t) {
            return null;
        }
    }

    /** The loaded quest file, or null when FTB Quests is absent or has not loaded one. */
    private Object questFile() {
        Class<?> type = serverQuestFileClass();
        if (type == null) return null;
        try {
            Object result = type.getMethod("getInstance").invoke(null);
            if (result instanceof Optional<?> optional) return optional.orElse(null);
            return result;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private Object requireFile() {
        Object file = questFile();
        if (file == null) {
            ctx.skip("this scene needs FTB Quests with a loaded quest file, which this runtime does"
                    + " not have");
        }
        return file;
    }

    private List<?> allChapters() {
        Object chapters = call(requireFile(), "getAllChapters");
        return chapters instanceof List<?> list ? list : List.of();
    }

    private List<?> questsIn(Object chapter) {
        Object quests = call(chapter, "getQuests");
        return quests instanceof List<?> list ? list : List.of();
    }

    private Object requireQuest(String questCode) {
        for (Object chapter : allChapters()) {
            for (Object quest : questsIn(chapter)) {
                if (codeOf(quest).equalsIgnoreCase(questCode)) return quest;
            }
        }
        throw new SceneFailure("no quest has the code '" + questCode + "' — this book holds "
                + questCount() + " quests across " + chapters().size() + " chapters. Codes are the"
                + " hex ids FTB Quests shows in the book, e.g. 1A2B3C4D5E6F7080.");
    }

    private Object teamData() {
        ServerPlayer player = ctx.player();
        Object result = call(requireFile(), "getTeamData",
                new Class<?>[] { net.minecraft.world.entity.player.Player.class }, player);
        if (!(result instanceof Optional<?> optional) || optional.isEmpty()) {
            ctx.skip("FTB Quests has no team data for this player yet — team data is created on"
                    + " join, so this can also mean the player has not fully joined");
        }
        return ((Optional<?>) result).get();
    }

    private static String codeOf(Object questObject) {
        return String.valueOf(call(questObject, "getCodeString"));
    }

    /** The internal long id, read from the field FTB Quests exposes on every quest object. */
    private static long idOf(Object questObject) {
        try {
            var field = questObject.getClass().getField("id");
            return field.getLong(questObject);
        } catch (ReflectiveOperationException e) {
            // Fall back to parsing the code string, which IS the id in hex — that is what
            // getCodeString formats, so the two cannot disagree.
            try {
                return Long.parseUnsignedLong(codeOf(questObject), 16);
            } catch (NumberFormatException nfe) {
                throw new SceneFailure("cannot read this quest's id: " + e);
            }
        }
    }

    private Class<?> questClass() {
        return named("dev.ftb.mods.ftbquests.quest.Quest");
    }

    private Class<?> questObjectClass() {
        return named("dev.ftb.mods.ftbquests.quest.QuestObject");
    }

    private Class<?> named(String name) {
        try {
            return Class.forName(name, false, Quests.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new SceneFailure("FTB Quests is present but " + name + " is not — the version on"
                    + " this classpath has a different API than this facet knows");
        }
    }

    private static Object call(Object target, String method) {
        return call(target, method, new Class<?>[0]);
    }

    private static Object call(Object target, String method, Class<?>[] signature, Object... args) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Method m = type.getDeclaredMethod(method, signature);
                m.setAccessible(true);
                return m.invoke(target, args);
            } catch (NoSuchMethodException e) {
                type = type.getSuperclass();
            } catch (ReflectiveOperationException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                throw new SceneFailure("FTB Quests call " + method + " failed: " + cause);
            }
        }
        throw new SceneFailure("FTB Quests has no method " + method + " on "
                + target.getClass().getName() + " — the version on this classpath has a different"
                + " API than this facet knows");
    }
}

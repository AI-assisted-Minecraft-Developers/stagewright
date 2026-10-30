package net.magicterra.stagewright.capability;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import net.magicterra.stagewright.test.Body;
import net.magicterra.stagewright.test.Game;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.opentest4j.TestAbortedException;

/**
 * FTB Quests — the quest book a modpack's progression is written in. The capability {@code ftbquests}.
 *
 * <h2>Test the graph, not the playthrough</h2>
 *
 * Nobody can play a hundred hours in a gate, but what breaks when a quest is edited is a property of the graph: does
 * every dependency still exist, is the endgame chapter reachable, did a rename leave a dangling reference. Those are
 * answerable in seconds from the loaded quest file.
 *
 * <h2>Reached by name</h2>
 *
 * FTB Quests is not a dependency of {@code :stagewright-api}, so the whole facet is reflection over
 * {@code dev.ftb.mods.ftbquests}. Quests are identified by their <b>code string</b>, the hex id the book shows, e.g.
 * {@code "1A2B3C4D5E6F7080"}. Every call runs on the server thread.
 */
public final class Quests {

    private static final String SERVER_QUEST_FILE = "dev.ftb.mods.ftbquests.quest.ServerQuestFile";

    private final Game game;

    Quests(Game game) {
        this.game = game;
    }

    /** Whether FTB Quests is in this runtime with a loaded quest file. */
    public boolean loaded() {
        return game.onServer(server -> questFile() != null);
    }

    /**
     * Chapter filenames, in book order — the names of the {@code .snbt} files under
     * {@code config/ftbquests/quests/chapters/}.
     */
    public List<String> chapters() {
        return game.onServer(server -> chapterNames());
    }

    /** How many quests the book holds, across every chapter. */
    public int questCount() {
        return game.onServer(server -> countQuests());
    }

    /** Every quest's code string, in book order. */
    public List<String> allQuests() {
        return game.onServer(server -> {
            List<String> out = new ArrayList<>();
            for (Object chapter : allChapters()) {
                for (Object quest : questsIn(chapter)) out.add(codeOf(quest));
            }
            return out;
        });
    }

    /** The code strings of the quests in one chapter, named by its filename. */
    public List<String> questsInChapter(String filename) {
        return game.onServer(server -> {
            for (Object chapter : allChapters()) {
                if (!filename.equals(String.valueOf(call(chapter, "getFilename")))) continue;
                List<String> out = new ArrayList<>();
                for (Object quest : questsIn(chapter)) out.add(codeOf(quest));
                return out;
            }
            throw new IllegalStateException(
                    "no chapter is named '" + filename + "' — this book has " + String.join(", ", chapterNames()));
        });
    }

    /**
     * The code strings this quest depends on: the edge list of the progression graph. A dependency naming a quest that
     * no longer exists is the defect this finds — FTB Quests loads such a book without complaint.
     */
    public List<String> dependenciesOf(String questCode) {
        return game.onServer(server -> {
            Object stream = call(requireQuest(questCode), "streamDependencies");
            List<String> out = new ArrayList<>();
            if (stream instanceof Stream<?> s) s.forEach(dep -> out.add(codeOf(dep)));
            return out;
        });
    }

    /** Whether this quest is completed for the body (strictly: for its team). */
    public boolean isComplete(Body body, String questCode) {
        return body.onServer(player -> (boolean)
                call(teamData(player), "isCompleted", new Class<?>[] {questObjectClass()}, requireQuest(questCode)));
    }

    /** Whether the body may start this quest — its dependencies are met. */
    public boolean canStart(Body body, String questCode) {
        return body.onServer(player -> (boolean)
                call(teamData(player), "canStartTasks", new Class<?>[] {questClass()}, requireQuest(questCode)));
    }

    /**
     * Mark a quest completed for the body's team. <b>Not undone</b> when the test ends: FTB Quests has no "un-complete"
     * that also unwinds rewards and the quests it unlocked, so a test that completes quests should run in a world
     * provisioned for it.
     */
    public Quests complete(Body body, String questCode) {
        body.runOnServer(player -> call(
                teamData(player),
                "setCompleted",
                new Class<?>[] {long.class, Date.class},
                idOf(requireQuest(questCode)),
                new Date()));
        return this;
    }

    // ---- internals ----

    /** Whether a quest file is loaded, without a game: the provider's availability check. */
    static boolean bookLoaded() {
        return questFile() != null;
    }

    private static Class<?> serverQuestFileClass() {
        try {
            return Class.forName(SERVER_QUEST_FILE, false, Quests.class.getClassLoader());
        } catch (Throwable t) {
            return null;
        }
    }

    /** The loaded quest file, or null when FTB Quests is absent or has not loaded one. */
    private static Object questFile() {
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

    private static Object requireFile() {
        Object file = questFile();
        if (file == null) {
            throw new TestAbortedException(
                    "this test needs FTB Quests with a loaded quest file, which this runtime" + " does not have");
        }
        return file;
    }

    private static List<String> chapterNames() {
        List<String> out = new ArrayList<>();
        for (Object chapter : allChapters()) out.add(String.valueOf(call(chapter, "getFilename")));
        return out;
    }

    private static int countQuests() {
        int total = 0;
        for (Object chapter : allChapters()) total += questsIn(chapter).size();
        return total;
    }

    private static List<?> allChapters() {
        Object chapters = call(requireFile(), "getAllChapters");
        return chapters instanceof List<?> list ? list : List.of();
    }

    private static List<?> questsIn(Object chapter) {
        Object quests = call(chapter, "getQuests");
        return quests instanceof List<?> list ? list : List.of();
    }

    private static Object requireQuest(String questCode) {
        for (Object chapter : allChapters()) {
            for (Object quest : questsIn(chapter)) {
                if (codeOf(quest).equalsIgnoreCase(questCode)) return quest;
            }
        }
        throw new IllegalStateException("no quest has the code '" + questCode + "' — this book holds "
                + countQuests() + " quests across " + chapterNames().size() + " chapters. Codes are the"
                + " hex ids FTB Quests shows in the book, e.g. 1A2B3C4D5E6F7080.");
    }

    private static Object teamData(ServerPlayer player) {
        Object result = call(requireFile(), "getTeamData", new Class<?>[] {Player.class}, player);
        if (!(result instanceof Optional<?> optional) || optional.isEmpty()) {
            throw new TestAbortedException("FTB Quests has no team data for this player yet — team data is created"
                    + " on join, so this can also mean the player has not fully joined");
        }
        return optional.get();
    }

    private static String codeOf(Object questObject) {
        return String.valueOf(call(questObject, "getCodeString"));
    }

    /** The internal long id, read from the field FTB Quests exposes on every quest object. */
    private static long idOf(Object questObject) {
        try {
            return questObject.getClass().getField("id").getLong(questObject);
        } catch (ReflectiveOperationException e) {
            // The code string IS the id in hex — that is what getCodeString formats.
            try {
                return Long.parseUnsignedLong(codeOf(questObject), 16);
            } catch (NumberFormatException nfe) {
                throw new IllegalStateException("cannot read this quest's id: " + e);
            }
        }
    }

    private static Class<?> questClass() {
        return named("dev.ftb.mods.ftbquests.quest.Quest");
    }

    private static Class<?> questObjectClass() {
        return named("dev.ftb.mods.ftbquests.quest.QuestObject");
    }

    private static Class<?> named(String name) {
        try {
            return Class.forName(name, false, Quests.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("FTB Quests is present but " + name + " is not — the version on"
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
                throw new IllegalStateException("FTB Quests call " + method + " failed: " + cause);
            }
        }
        throw new IllegalStateException("FTB Quests has no method " + method + " on "
                + target.getClass().getName() + " — the version on this classpath has a different"
                + " API than this facet knows");
    }
}

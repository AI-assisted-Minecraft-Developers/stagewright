package net.magicterra.stagewright.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import net.magicterra.stagewright.driver.ClientReads;
import net.magicterra.stagewright.mixin.client.AdvancementToastAccessor;
import net.magicterra.stagewright.mixin.client.SystemToastAccessor;
import net.magicterra.stagewright.mixin.client.ToastComponentAccessor;
import net.magicterra.stagewright.mixin.client.ToastInstanceAccessor;
import net.magicterra.stagewright.mixin.client.TutorialToastAccessor;
import net.magicterra.stagewright.test.methods.ToastsContracts;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.AdvancementToast;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.TutorialToast;
import net.minecraft.util.FormattedCharSequence;

/**
 * Reads the client's toasts for {@code mc.test.toasts}, on the client thread, which is the only one that changes them.
 *
 * <p><b>Client-only.</b> Reached from each loader's client entry point alone, as {@link ClientDirector} is.
 */
public final class ToastReadout {

    private static final int TEXT_MAX = 4096;

    private static final int QUEUED_MAX = 1024;

    private ToastReadout() {}

    /** Makes {@code mc.test.toasts} answerable on this client. */
    public static void install() {
        ClientReads.installToasts(ToastReadout::read);
    }

    private static CompletionStage<ToastsContracts.ToastList> read() {
        Minecraft mc = Minecraft.getInstance();
        return mc.submit(() -> {
            ToastComponentAccessor toasts = (ToastComponentAccessor) mc.getToasts();
            List<ToastsContracts.Toast> visible = new ArrayList<>();
            for (Object instance : toasts.stagewright$visible()) {
                visible.add(describe(((ToastInstanceAccessor) instance).stagewright$toast()));
            }
            List<ToastsContracts.Toast> queued = new ArrayList<>();
            int omitted = 0;
            for (Toast toast : toasts.stagewright$queued()) {
                if (queued.size() < QUEUED_MAX) {
                    queued.add(describe(toast));
                } else {
                    omitted++;
                }
            }
            return new ToastsContracts.ToastList(visible, queued, omitted);
        });
    }

    /** Vanilla's system, tutorial and advancement toasts keep their text in fields; any other type is named only. */
    private static ToastsContracts.Toast describe(Toast toast) {
        String type = toast.getClass().getName();
        if (toast instanceof SystemToast) {
            SystemToastAccessor system = (SystemToastAccessor) toast;
            List<String> lines = new ArrayList<>();
            for (FormattedCharSequence line : system.stagewright$messageLines()) {
                lines.add(plain(line));
            }
            return new ToastsContracts.Toast(
                    type,
                    Optional.of(clip(system.stagewright$title().getString())),
                    lines.isEmpty() ? Optional.empty() : Optional.of(clip(String.join("\n", lines))));
        }
        if (toast instanceof TutorialToast) {
            TutorialToastAccessor tutorial = (TutorialToastAccessor) toast;
            return new ToastsContracts.Toast(
                    type,
                    Optional.of(clip(tutorial.stagewright$title().getString())),
                    Optional.ofNullable(tutorial.stagewright$message()).map(m -> clip(m.getString())));
        }
        if (toast instanceof AdvancementToast) {
            Optional<DisplayInfo> display = ((AdvancementToastAccessor) toast)
                    .stagewright$advancement()
                    .value()
                    .display();
            return new ToastsContracts.Toast(
                    type,
                    display.map(d -> clip(d.getTitle().getString())),
                    display.map(d -> clip(d.getDescription().getString())));
        }
        return new ToastsContracts.Toast(type, Optional.empty(), Optional.empty());
    }

    private static String plain(FormattedCharSequence line) {
        StringBuilder text = new StringBuilder();
        line.accept((index, style, codePoint) -> {
            text.appendCodePoint(codePoint);
            return true;
        });
        return text.toString();
    }

    private static String clip(String s) {
        return s.length() > TEXT_MAX ? s.substring(0, TEXT_MAX) : s;
    }
}

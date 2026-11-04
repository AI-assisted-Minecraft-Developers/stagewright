package net.magicterra.stagewright.driver;

import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import net.magicterra.stagewright.test.methods.ToastsContracts;

/**
 * What only a client can answer, installed by the client entry point and absent on a dedicated server. The methods that
 * ask go through here, so they load no {@code net.minecraft.client.*} type where there is none.
 */
public final class ClientReads {

    private static volatile Supplier<CompletionStage<ToastsContracts.ToastList>> toasts;

    private ClientReads() {}

    /** Installs the client's toast reader; called once from the client entry point. */
    public static void installToasts(Supplier<CompletionStage<ToastsContracts.ToastList>> reader) {
        toasts = reader;
    }

    /** The client's toast reader, or null on a dedicated server. */
    public static Supplier<CompletionStage<ToastsContracts.ToastList>> toasts() {
        return toasts;
    }
}

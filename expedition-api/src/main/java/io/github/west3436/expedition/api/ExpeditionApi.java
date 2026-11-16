package io.github.west3436.expedition.api;

import net.neoforged.fml.ModList;

/**
 * The entry point of the Expedition API.
 *
 * <p>A mod may bundle this API (jar-in-jar) and call it whether or not Expedition is installed.
 * {@link #isPresent()} says which is the case.
 */
public final class ExpeditionApi {
    /** Expedition's mod id, and the namespace of its registries and data. */
    public static final String MOD_ID = "expedition";

    private ExpeditionApi() {}

    /**
     * Whether Expedition itself is installed, and not only this API.
     *
     * <p>Thread: any. Before the game has loaded its mods, the answer is {@code false}.
     *
     * @return true if the Expedition mod is loaded
     */
    public static boolean isPresent() {
        ModList mods = ModList.get();
        return mods != null && mods.isLoaded(MOD_ID);
    }
}

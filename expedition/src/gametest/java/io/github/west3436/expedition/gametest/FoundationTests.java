package io.github.west3436.expedition.gametest;

import io.github.west3436.expedition.ExpeditionMod;
import io.github.west3436.expedition.api.ExpeditionApi;
import io.github.west3436.zas.api.ZasApi;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The Foundations gate: an empty Expedition loads on a dedicated server beside the ZAS³ release
 * jar, and each mod's API says the mod behind it is there.
 */
@GameTestHolder(ExpeditionMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FoundationTests {
    /** The compatibility matrix's server mods, by mod id: what -Pcompat.matrix=true adds to the run. */
    private static final List<String> MATRIX_MODS = List.of("modernfix", "ferritecore", "lithium");

    private FoundationTests() {}

    /** ZAS³ is loaded, and its API answers from ZAS³ itself and not from the API's fallback. */
    @GameTest(template = "empty")
    public static void loads_beside_zas(GameTestHelper helper) {
        if (!ModList.get().isLoaded("zas")) {
            throw new GameTestAssertException("ZAS³ (zas) isn't loaded");
        }
        if (!ZasApi.isPresent()) {
            throw new GameTestAssertException("the ZAS³ API answers from its fallback, not from ZAS³");
        }
        helper.succeed();
    }

    /** Expedition's own API says Expedition is installed. */
    @GameTest(template = "empty")
    public static void api_reports_expedition_present(GameTestHelper helper) {
        if (!ExpeditionApi.isPresent()) {
            throw new GameTestAssertException("ExpeditionApi.isPresent() is false with Expedition loaded");
        }
        helper.succeed();
    }

    /**
     * On a compatibility-matrix run, the matrix's mods really are on the server: the run could
     * otherwise pass without ever having loaded them. On any other run there is nothing to check.
     */
    @GameTest(template = "empty")
    public static void compat_matrix_mods_are_loaded(GameTestHelper helper) {
        if (Boolean.getBoolean("expedition.compatMatrix")) {
            for (String mod : MATRIX_MODS) {
                if (!ModList.get().isLoaded(mod)) {
                    throw new GameTestAssertException("compatibility matrix run without " + mod);
                }
            }
        }
        helper.succeed();
    }
}

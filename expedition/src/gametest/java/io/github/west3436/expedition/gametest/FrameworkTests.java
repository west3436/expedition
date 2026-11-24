package io.github.west3436.expedition.gametest;

import io.github.west3436.expedition.ExpeditionMod;
import io.github.west3436.ss13.Ss13Framework;
import io.github.west3436.ss13.api.FrameworkApi;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * SS13 Framework, which Expedition nests, loads once on a dedicated server with Expedition and the
 * ZAS³ release jar (#108). A ZAS³ release that nests the framework too puts a second copy on this
 * run, and the game must still load one.
 */
@GameTestHolder(ExpeditionMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FrameworkTests {
    private static final String API_PACKAGE = "io.github.west3436.ss13.api";
    private static final String API_CLASS = API_PACKAGE.replace('.', '/') + "/FrameworkApi.class";
    private static final String MOD_CLASS = "io/github/west3436/ss13/Ss13Framework.class";
    private static final String MOD_TOML = "META-INF/neoforge.mods.toml";

    private FrameworkTests() {}

    /**
     * The framework mod is loaded beside Expedition and ZAS³, and its API is the copy from the same
     * release as the mod.
     */
    @GameTest(template = "empty")
    public static void framework_loads_beside_expedition_and_zas(GameTestHelper h) {
        for (String mod : List.of(ExpeditionMod.MOD_ID, "zas", FrameworkApi.MOD_ID)) {
            h.assertTrue(ModList.get().isLoaded(mod), mod + " isn't loaded");
        }
        String loaded = ModList.get()
                .getModContainerById(FrameworkApi.MOD_ID)
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("none");
        h.assertValueEqual(FrameworkApi.version(), Optional.of(loaded), "the API's version beside the mod's");
        h.succeed();
    }

    /** The game finds one copy of the framework mod's classes and one of the API's, in one module. */
    @GameTest(template = "empty")
    public static void one_copy_of_the_framework_loads(GameTestHelper h) {
        ClassLoader loader = FrameworkApi.class.getClassLoader();
        h.assertTrue(
                resources(loader, MOD_TOML).size() >= 3,
                "the class loader's search doesn't see across mod files, so a count of copies means nothing");
        List<URL> api = resources(loader, API_CLASS);
        h.assertValueEqual(api.size(), 1, "copies of the API's FrameworkApi class: " + api);
        List<URL> mod = resources(loader, MOD_CLASS);
        h.assertValueEqual(mod.size(), 1, "copies of the mod's Ss13Framework class: " + mod);
        List<Module> modules = FrameworkApi.class.getModule().getLayer().modules().stream()
                .filter(m -> m.getPackages().contains(API_PACKAGE))
                .toList();
        h.assertValueEqual(modules.size(), 1, "modules holding the API's package: " + modules);
        h.assertTrue(
                FrameworkApi.class.getModule() != Ss13Framework.class.getModule(),
                "the API loaded inside the mod's module");
        h.succeed();
    }

    private static List<URL> resources(ClassLoader loader, String name) {
        try {
            return Collections.list(loader.getResources(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

package io.github.west3436.expedition.planet;

import com.mojang.logging.LogUtils;
import java.util.List;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

/**
 * Loads the planet files when a server starts and on every {@code /reload}. The files are read off
 * the server thread and checked on it, and the planets replace the last load's at once. Every
 * problem goes to the log, naming the file and the field.
 */
public final class PlanetReloadListener extends SimplePreparableReloadListener<List<PlanetLoader.Source>> {
    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    protected List<PlanetLoader.Source> prepare(ResourceManager resources, ProfilerFiller profiler) {
        return PlanetLoader.list(resources);
    }

    @Override
    protected void apply(List<PlanetLoader.Source> sources, ResourceManager resources, ProfilerFiller profiler) {
        PlanetLoader.Result result = PlanetLoader.load(sources, makeConditionalOps(), References.REGISTRIES);
        PlanetRegistry.setServer(result);
        PlanetAir.update(
                result.planets().values(), PlanetAir.zasFiles(result.planets().values(), resources));
        log(result);
    }

    /**
     * Writes a load's problems to the log, and how many planets loaded.
     *
     * @param result the load
     */
    static void log(PlanetLoader.Result result) {
        for (PlanetProblem problem : result.problems()) {
            if (problem.severity() == PlanetProblem.Severity.ERROR) {
                LOGGER.error("Planet file problem: {}", problem);
            } else {
                LOGGER.warn("Planet file problem: {}", problem);
            }
        }
        long failed = result.failedFiles();
        if (failed > 0) {
            LOGGER.error(
                    "{} planet file(s) didn't load because of the problems above. Fix them, check with "
                            + "/expedition planet validate, then /reload.",
                    failed);
        }
        LOGGER.info(
                "Loaded {} planet(s) from {} planet file(s)", result.planets().size(), result.read());
    }
}

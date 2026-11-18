package io.github.west3436.expedition.planet;

import io.github.west3436.zas.api.ZasApi;
import io.github.west3436.zas.api.atmosphere.AtmosphereState;
import io.github.west3436.zas.api.atmosphere.Atmospheres;
import io.github.west3436.zas.api.event.HazardTickEvent;
import io.github.west3436.zas.api.life.Hazard;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;

/**
 * A planet's own mobs live in its open air (#18).
 *
 * <p><b>Who is adapted.</b> An entity is adapted to the air where it is when all of these hold
 * ({@link #adapted}):
 *
 * <ul>
 *   <li>its type is in the {@code mobs} table of the planet whose dimension it is in ({@link
 *       PlanetSpawns#isNative});
 *   <li>that dimension's open air is the planet's ({@link PlanetAir#appliesTo}), not a ZAS³
 *       ambient file's or another mod's;
 *   <li>the air at its eyes is that open air: ZAS³ samples it as {@link AtmosphereState#AMBIENT}.
 * </ul>
 *
 * <p>So a native taken to another dimension suffers as anything else would, and so does one in a
 * sealed room ({@link AtmosphereState#ZONED}) on its own planet, whatever that room holds. ZAS³
 * dissolves a sealed room whose air matches the open air back into it, so a closed-off cave of the
 * planet's own air ends up ambient again.
 *
 * <p><b>What it spares.</b> ZAS³ raises a {@link HazardTickEvent} each time one of its hazards is
 * about to harm an entity. For an adapted entity this cancels every hazard of the air: toxic gas,
 * hypoxia, low and high pressure, cold and heat. Not {@link Hazard#AIRFLOW_IMPACT}: a native is
 * still thrown about by a breach's wind. Cancelling deals no damage and applies no gas's effects;
 * ZAS³ still counts the exposure and still reports the hazard from {@code Breathing.of}.
 *
 * <p><b>Spawning.</b> ZAS³ keeps a breathing mob from spawning naturally where the air would kill
 * it, which on an airless or poisonous planet is everywhere. So for an adapted mob's natural or
 * new-chunk spawn this decides the position check first, with the checks NeoForge makes when
 * nothing has decided it ({@code checkSpawnRules} and {@code checkSpawnObstruction}), which ZAS³
 * then leaves alone. A check another listener has already decided stands.
 */
public final class NativeAir {
    private NativeAir() {}

    /**
     * Listens on the game bus.
     *
     * @param gameBus NeoForge's game event bus
     */
    static void register(IEventBus gameBus) {
        gameBus.addListener(NativeAir::onHazardTick);
        // Before ZAS³'s lethal-spawn check, which listens at normal priority.
        gameBus.addListener(EventPriority.HIGH, NativeAir::onPositionCheck);
    }

    /**
     * Whether an entity is adapted to the air at its eyes: a native of the planet whose dimension it
     * is in, in that planet's open air. Any thread, though reading a live entity off the server
     * thread may see a stale position.
     *
     * @param entity the entity
     * @return whether the planet's open air spares it
     */
    public static boolean adapted(LivingEntity entity) {
        ResourceKey<Level> dimension = entity.level().dimension();
        return PlanetSpawns.isNative(dimension, entity.getType())
                && PlanetAir.appliesTo(dimension)
                && Atmospheres.of(entity).state() == AtmosphereState.AMBIENT;
    }

    /**
     * Whether a hazard is one of the air's, which adaptation spares: all but a breach's wind.
     *
     * @param hazard the hazard
     * @return whether an adapted entity is spared it
     */
    public static boolean ofTheAir(Hazard hazard) {
        return hazard != Hazard.AIRFLOW_IMPACT;
    }

    private static void onHazardTick(HazardTickEvent event) {
        if (ofTheAir(event.hazard()) && adapted(event.entity())) {
            event.setCanceled(true);
        }
    }

    private static void onPositionCheck(MobSpawnEvent.PositionCheck event) {
        MobSpawnType type = event.getSpawnType();
        if ((type != MobSpawnType.NATURAL && type != MobSpawnType.CHUNK_GENERATION)
                || event.getResult() != MobSpawnEvent.PositionCheck.Result.DEFAULT
                || !ZasApi.isPresent()) {
            return;
        }
        Mob mob = event.getEntity();
        if (!adapted(mob)) {
            return;
        }
        ServerLevelAccessor level = event.getLevel();
        event.setResult(
                mob.checkSpawnRules(level, type) && mob.checkSpawnObstruction(level)
                        ? MobSpawnEvent.PositionCheck.Result.SUCCEED
                        : MobSpawnEvent.PositionCheck.Result.FAIL);
    }
}

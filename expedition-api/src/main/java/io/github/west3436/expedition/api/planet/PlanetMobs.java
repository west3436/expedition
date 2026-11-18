package io.github.west3436.expedition.api.planet;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.world.entity.MobCategory;

/**
 * The mobs that spawn on a planet, by vanilla's mob categories.
 *
 * <p>Each category the file lists is added to what the dimension's biomes spawn in it, or replaces
 * it; a category the file leaves out keeps the biomes' spawns, so an empty list is how a file with
 * {@code replace_existing} says that category spawns nothing. Every mob listed is native: it lives
 * in the planet's open air unharmed, while it is in that air.
 *
 * @param replaceExisting whether these lists replace the spawns the dimension's biomes give in
 *     their categories, rather than adding to them; false unless the file sets it
 * @param spawns each category's mobs, in the file's order; a category the file leaves out isn't a
 *     key
 */
public record PlanetMobs(boolean replaceExisting, Map<MobCategory, List<PlanetMob>> spawns) {
    /** What a planet file gets when it leaves {@code mobs} out: the biomes' own spawns, unchanged. */
    public static final PlanetMobs DEFAULT = new PlanetMobs(false, Map.of());

    /**
     * Copies the lists into an unmodifiable map.
     *
     * @param replaceExisting whether these lists replace the biomes' spawns
     * @param spawns each category's mobs
     * @throws NullPointerException if the map, or anything in it, is null
     */
    public PlanetMobs {
        EnumMap<MobCategory, List<PlanetMob>> copy = new EnumMap<>(MobCategory.class);
        for (Map.Entry<MobCategory, List<PlanetMob>> e : spawns.entrySet()) {
            copy.put(Objects.requireNonNull(e.getKey(), "category"), List.copyOf(e.getValue()));
        }
        spawns = Collections.unmodifiableMap(copy);
    }

    /**
     * One category's mobs.
     *
     * @param category the category
     * @return its mobs, or an empty list if the file gives none
     */
    public List<PlanetMob> spawns(MobCategory category) {
        return spawns.getOrDefault(category, List.of());
    }
}

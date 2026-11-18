/**
 * Planets: what each datapack planet file says, and {@link
 * io.github.west3436.expedition.api.planet.Planets} to ask which are loaded.
 *
 * <p>A planet file is {@code data/<namespace>/expedition/planet/<path>.json}. Only {@code
 * dimension} and {@code space.center} are required; every other field has the default its value
 * type names:
 *
 * <pre>{@code
 * {
 *   "dimension": "mypack:kepler",
 *   "space": { "center": [12000, -4000], "size": 4096,
 *              "image": "mypack:textures/planet/kepler.png", "parent": "mypack:kepler_prime" },
 *   "surface": { "center": [0, 0] },
 *   "atmosphere": { "partial_pressures": { "zas:nitrogen": 60.0, "zas:methane": 4.5 },
 *                   "temperature": { "day": 210, "night": 150 } },
 *   "gravity": 0.38,
 *   "ores": [ { "block": "minecraft:iron_ore", "weight": 40, "vein_size": 9, "y": [-32, 64] } ],
 *   "ore_density": 1.5,
 *   "mobs": { "replace_existing": true,
 *             "monster": [ { "type": "minecraft:stray", "weight": 10, "group": [1, 3] } ] },
 *   "structures": { "allow": ["#mypack:kepler_ruins"], "deny": ["#minecraft:village"] },
 *   "access": { "hidden_until_scanned": true }
 * }
 * }</pre>
 *
 * <p>{@code temperature} may be one number for day and night alike, and {@code "vacuum": true}
 * with no {@code partial_pressures} makes an airless surface. The keys of {@code mobs} besides
 * {@code replace_existing} are vanilla's mob categories ({@code monster}, {@code creature}, {@code
 * ambient}, {@code axolotls}, {@code underground_water_creature}, {@code water_creature}, {@code
 * water_ambient}, {@code misc}). A file may carry NeoForge's {@code neoforge:conditions}.
 */
package io.github.west3436.expedition.api.planet;

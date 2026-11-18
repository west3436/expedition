package io.github.west3436.expedition.planet;

import java.util.List;
import java.util.Objects;
import net.minecraft.network.chat.Component;

/**
 * Something wrong with a planet file, naming the file and the field. Its text is in the mod's
 * {@code en_us.json}, under {@code expedition.planet.problem.<key>}.
 *
 * @param file the file, as {@link PlanetLoader} labels it: its path in the pack, and the pack
 * @param field where in the file, as a path such as {@code ores[1].weight}; empty for the file as a
 *     whole
 * @param severity whether the planet still loads
 * @param key the problem's code, the last part of its translation key
 * @param args the message's arguments: strings and numbers
 */
public record PlanetProblem(String file, String field, Severity severity, String key, List<Object> args) {
    /** The prefix of every problem's translation key. */
    public static final String KEY = "expedition.planet.problem.";

    /** Whether a problem stops the planet loading. */
    public enum Severity {
        /** The file doesn't load. */
        ERROR,
        /** The planet loads, but something it names isn't on this server. */
        WARNING
    }

    /**
     * Checks the parts and copies the arguments.
     *
     * @throws NullPointerException if a part is null
     */
    public PlanetProblem {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(key, "key");
        args = List.copyOf(args);
    }

    /**
     * The problem's translation key.
     *
     * @return {@code expedition.planet.problem.<key>}
     */
    public String translationKey() {
        return KEY + key;
    }

    /**
     * What is wrong, without saying where.
     *
     * @return the message
     */
    public Component message() {
        return Component.translatable(translationKey(), args.toArray());
    }

    /**
     * The file, the field and what is wrong, as the log and {@code /expedition planet validate}
     * give them.
     *
     * @return the description
     */
    public Component describe() {
        return field.isEmpty()
                ? Component.translatable("expedition.planet.problem_in_file", file, message())
                : Component.translatable("expedition.planet.problem_at_field", file, field, message());
    }

    /** {@return the description, in the server's language} */
    @Override
    public String toString() {
        return describe().getString();
    }
}

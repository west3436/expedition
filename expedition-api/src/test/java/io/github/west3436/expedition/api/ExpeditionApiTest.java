package io.github.west3436.expedition.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/** The API's entry point, without the game: Minecraft's classes are here, but nothing has loaded. */
class ExpeditionApiTest {
    @Test
    void modIdIsTheNamespace() {
        assertEquals("expedition", ExpeditionApi.MOD_ID);
    }

    @Test
    void absentBeforeTheGameLoadsItsMods() {
        assertFalse(ExpeditionApi.isPresent());
    }
}

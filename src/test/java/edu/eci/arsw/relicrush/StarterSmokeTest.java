package edu.eci.arsw.relicrush;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StarterSmokeTest {
    @Test
    void java21IsRequired() {
        assertTrue(Runtime.version().feature() >= 21);
    }
}

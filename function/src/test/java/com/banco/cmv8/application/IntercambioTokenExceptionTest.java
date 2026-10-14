package com.banco.cmv8.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntercambioTokenExceptionTest {

    @Test
    void interactionRequiredConClaimsRequiereInteraccion() {
        var e = new IntercambioTokenException("obo", "interaction_required",
                "{\"access_token\":{\"polids\":{\"essential\":true}}}", null);
        assertTrue(e.requiereInteraccion());
        assertEquals("{\"access_token\":{\"polids\":{\"essential\":true}}}", e.claims().orElseThrow());
    }

    @Test
    void otrosCodigosNoRequierenInteraccionNiTraenClaims() {
        var e = new IntercambioTokenException("obo", "invalid_grant", null);
        assertFalse(e.requiereInteraccion());
        assertTrue(e.claims().isEmpty());
        assertTrue(new IntercambioTokenException("obo", "interaction_required", "  ", null).claims().isEmpty());
    }
}

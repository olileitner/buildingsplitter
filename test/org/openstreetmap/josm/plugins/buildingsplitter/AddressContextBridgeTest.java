package org.openstreetmap.josm.plugins.buildingsplitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

class AddressContextBridgeTest {

    @BeforeAll
    static void initJosmConfig() {
        if (Config.getPref() == null) {
            Config.setPreferencesInstance(new MemoryPreferences());
        }
    }

    @AfterEach
    void clearState() {
        AddressContextBridge.setAddressContext(null, null);
        clearPreferenceFallback();
        AddressContextBridge.consumeAddressContext();
    }

    @Test
    void consumesInMemoryContextFirst() {
        Config.getPref().put(AddressContextBridge.HANDOFF_STREET_KEY, "Fallback Street");
        Config.getPref().put(AddressContextBridge.HANDOFF_POSTCODE_KEY, "99999");
        Config.getPref().putBoolean(AddressContextBridge.HANDOFF_PENDING_KEY, true);
        AddressContextBridge.setAddressContext("Bridge Street", "12345");

        AddressContextBridge.AddressContext consumed = AddressContextBridge.consumeAddressContext();

        assertNotNull(consumed);
        assertEquals("Bridge Street", consumed.getStreet());
        assertEquals("12345", consumed.getPostcode());
        assertEquals("Fallback Street", Config.getPref().get(AddressContextBridge.HANDOFF_STREET_KEY, ""));
    }

    @Test
    void consumesPreferenceFallbackAsOneShot() {
        Config.getPref().put(AddressContextBridge.HANDOFF_STREET_KEY, "Fallback Street");
        Config.getPref().put(AddressContextBridge.HANDOFF_POSTCODE_KEY, "99999");
        Config.getPref().putBoolean(AddressContextBridge.HANDOFF_PENDING_KEY, true);

        AddressContextBridge.AddressContext consumed = AddressContextBridge.consumeAddressContext();

        assertNotNull(consumed);
        assertEquals("Fallback Street", consumed.getStreet());
        assertEquals("99999", consumed.getPostcode());
        assertEquals("", Config.getPref().get(AddressContextBridge.HANDOFF_STREET_KEY, ""));
        assertEquals("", Config.getPref().get(AddressContextBridge.HANDOFF_POSTCODE_KEY, ""));
        assertNull(AddressContextBridge.consumeAddressContext());
    }

    @Test
    void ignoresEmptyPreferenceFallbackValues() {
        Config.getPref().put(AddressContextBridge.HANDOFF_STREET_KEY, "   ");
        Config.getPref().put(AddressContextBridge.HANDOFF_POSTCODE_KEY, "");
        Config.getPref().putBoolean(AddressContextBridge.HANDOFF_PENDING_KEY, true);

        assertNull(AddressContextBridge.consumeAddressContext());
        assertEquals("", Config.getPref().get(AddressContextBridge.HANDOFF_STREET_KEY, ""));
        assertEquals("", Config.getPref().get(AddressContextBridge.HANDOFF_POSTCODE_KEY, ""));
    }

    private static void clearPreferenceFallback() {
        Config.getPref().put(AddressContextBridge.HANDOFF_STREET_KEY, null);
        Config.getPref().put(AddressContextBridge.HANDOFF_POSTCODE_KEY, null);
        Config.getPref().put(AddressContextBridge.HANDOFF_PENDING_KEY, null);
    }
}


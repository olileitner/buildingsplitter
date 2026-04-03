package org.openstreetmap.josm.plugins.buildingsplitter;

import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.Logging;

/**
 * Lightweight one-shot bridge for external address context providers.
 */
public final class AddressContextBridge {

    public static final String HANDOFF_STREET_KEY = "quickaddressfill.buildingsplitter.handoff.street";
    public static final String HANDOFF_POSTCODE_KEY = "quickaddressfill.buildingsplitter.handoff.postcode";
    public static final String HANDOFF_PENDING_KEY = "quickaddressfill.buildingsplitter.handoff.pending";

    private static AddressContext pendingContext;

    private AddressContextBridge() {
        // Utility class
    }

    public static synchronized void setAddressContext(String street, String postcode) {
        pendingContext = new AddressContext(normalize(street), normalize(postcode));
    }

    public static synchronized AddressContext consumeAddressContext() {
        AddressContext inMemoryContext = pendingContext;
        pendingContext = null;
        if (inMemoryContext != null) {
            Logging.info("BuildingSplitter: bridge context consumed.");
            return inMemoryContext;
        }

        boolean pendingPreferenceHandoff = Config.getPref().getBoolean(HANDOFF_PENDING_KEY, false);
        if (!pendingPreferenceHandoff) {
            Logging.info("BuildingSplitter: no external context found.");
            return null;
        }

        String street = normalize(Config.getPref().get(HANDOFF_STREET_KEY, ""));
        String postcode = normalize(Config.getPref().get(HANDOFF_POSTCODE_KEY, ""));
        clearPreferenceFallback();

        if (street.isEmpty() && postcode.isEmpty()) {
            Logging.info("BuildingSplitter: no external context found.");
            return null;
        }

        Logging.info("BuildingSplitter: preference fallback consumed.");
        return new AddressContext(street, postcode);
    }

    private static void clearPreferenceFallback() {
        Config.getPref().put(HANDOFF_STREET_KEY, null);
        Config.getPref().put(HANDOFF_POSTCODE_KEY, null);
        Config.getPref().put(HANDOFF_PENDING_KEY, null);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    public static final class AddressContext {
        private final String street;
        private final String postcode;

        private AddressContext(String street, String postcode) {
            this.street = street;
            this.postcode = postcode;
        }

        public String getStreet() {
            return street;
        }

        public String getPostcode() {
            return postcode;
        }
    }
}


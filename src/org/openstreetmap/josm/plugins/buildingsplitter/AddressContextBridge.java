package org.openstreetmap.josm.plugins.buildingsplitter;

/**
 * Lightweight one-shot bridge for external address context providers.
 */
public final class AddressContextBridge {

    private static AddressContext pendingContext;

    private AddressContextBridge() {
        // Utility class
    }

    public static synchronized void setAddressContext(String street, String postcode) {
        pendingContext = new AddressContext(normalize(street), normalize(postcode));
    }

    public static synchronized AddressContext consumeAddressContext() {
        AddressContext context = pendingContext;
        pendingContext = null;
        return context;
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


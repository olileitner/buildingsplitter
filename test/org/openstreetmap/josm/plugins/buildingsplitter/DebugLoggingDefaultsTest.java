package org.openstreetmap.josm.plugins.buildingsplitter;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;

class DebugLoggingDefaultsTest {

    @Test
    void autoSplitActionContextDebugLoggingIsDisabledByDefault() throws Exception {
        assertFalse(readBooleanConstant(AutoSplitBuildingAction.class, "DEBUG_CONTEXT_TRANSFER"));
    }

    @Test
    void splitMapModeContextDebugLoggingIsDisabledByDefault() throws Exception {
        assertFalse(readBooleanConstant(SplitBuildingMapMode.class, "DEBUG_CONTEXT_TRANSFER"));
    }

    private boolean readBooleanConstant(Class<?> type, String fieldName) throws Exception {
        Field field = type.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getBoolean(null);
    }
}


package org.openstreetmap.josm.plugins.buildingsplitter;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Field;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

import sun.misc.Unsafe;

class SplitBuildingActionMapModeBindingTest {

    @BeforeAll
    static void initJosmConfig() {
        if (Config.getPref() == null) {
            Config.setPreferencesInstance(new MemoryPreferences());
        }
    }

    @Test
    void resolveMapModeForActivationUsesRegisteredInstance() throws Exception {
        SplitBuildingAction action = new SplitBuildingAction();
        SplitBuildingMapMode registered = allocateSplitBuildingMapModeWithoutConstructor();

        action.setRegisteredMapMode(registered);

        SplitBuildingMapMode resolved = action.resolveMapModeForActivation();
        assertSame(registered, resolved);
    }

    @Test
    void clearRegisteredMapModeHandlesNullLifecycleStateDefensively() {
        SplitBuildingAction action = new SplitBuildingAction();

        assertNull(action.getRegisteredMapMode());
        action.setRegisteredMapMode(null);
        assertNull(action.getRegisteredMapMode());
    }

    @Test
    void resolveMapModeForActivationUsesNewestRegisteredInstance() throws Exception {
        SplitBuildingAction action = new SplitBuildingAction();
        SplitBuildingMapMode firstRegistered = allocateSplitBuildingMapModeWithoutConstructor();
        SplitBuildingMapMode secondRegistered = allocateSplitBuildingMapModeWithoutConstructor();

        action.setRegisteredMapMode(firstRegistered);
        action.setRegisteredMapMode(secondRegistered);

        SplitBuildingMapMode resolved = action.resolveMapModeForActivation();
        assertSame(secondRegistered, resolved);
        assertNotSame(firstRegistered, resolved);
        assertNotNull(action.getRegisteredMapMode());
    }

    private SplitBuildingMapMode allocateSplitBuildingMapModeWithoutConstructor() throws Exception {
        return (SplitBuildingMapMode) unsafe().allocateInstance(SplitBuildingMapMode.class);
    }

    private Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }
}



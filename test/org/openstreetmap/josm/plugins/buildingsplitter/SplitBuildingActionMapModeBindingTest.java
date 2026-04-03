package org.openstreetmap.josm.plugins.buildingsplitter;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Field;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

import javax.swing.JPanel;

class SplitBuildingActionMapModeBindingTest {

    @BeforeAll
    static void initJosmConfig() {
        if (Config.getPref() == null) {
            Config.setPreferencesInstance(new MemoryPreferences());
        }
        setMainApplicationContentPaneForShortcutRegistration();
    }

    private static void setMainApplicationContentPaneForShortcutRegistration() {
        try {
            Field contentPaneField = MainApplication.class.getDeclaredField("contentPanePrivate");
            contentPaneField.setAccessible(true);
            contentPaneField.set(null, new JPanel());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Failed to initialize MainApplication test content pane.", ex);
        }
    }

    @Test
    void resolveMapModeForActivationUsesRegisteredInstance() {
        SplitBuildingAction action = new SplitBuildingAction();
        SplitBuildingMapMode registered = new SplitBuildingMapMode();

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
    void resolveMapModeForActivationUsesNewestRegisteredInstance() {
        SplitBuildingAction action = new SplitBuildingAction();
        SplitBuildingMapMode firstRegistered = new SplitBuildingMapMode();
        SplitBuildingMapMode secondRegistered = new SplitBuildingMapMode();

        action.setRegisteredMapMode(firstRegistered);
        action.setRegisteredMapMode(secondRegistered);

        SplitBuildingMapMode resolved = action.resolveMapModeForActivation();
        assertSame(secondRegistered, resolved);
        assertNotSame(firstRegistered, resolved);
        assertNotNull(action.getRegisteredMapMode());
    }

    @Test
    void resolveMapModeForActivationReturnsNullWithoutRegisteredInstance() {
        SplitBuildingAction action = new SplitBuildingAction();
        assertNull(action.resolveMapModeForActivation());
    }
}



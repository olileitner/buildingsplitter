package org.openstreetmap.josm.plugins.buildingsplitter;

import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.IconToggleButton;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.gui.preferences.ToolbarPreferences;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;

public class BuildingSplitterPlugin extends Plugin {

    private final SplitBuildingAction splitBuildingAction;
    private final AutoSplitBuildingAction autoSplitBuildingAction;
    private MapFrame registeredMapFrame;

    public BuildingSplitterPlugin(PluginInformation info) {
        super(info);
        splitBuildingAction = new SplitBuildingAction();
        autoSplitBuildingAction = new AutoSplitBuildingAction();
        MainApplication.getMenu().toolsMenu.add(splitBuildingAction);
        MainApplication.getMenu().toolsMenu.add(autoSplitBuildingAction);
        ensureAutoSplitToolbarButton();

        MainApplication.addMapFrameListener(this);
        MapFrame currentMap = MainApplication.getMap();
        if (currentMap != null) {
            mapFrameInitialized(null, currentMap);
        }
    }

    @Override
    public void mapFrameInitialized(MapFrame oldFrame, MapFrame newFrame) {
        if (newFrame == null) {
            if (oldFrame != null && oldFrame == registeredMapFrame) {
                registeredMapFrame = null;
            }
            return;
        }

        // UI components can be initialized after plugin construction.
        // Re-run toolbar wiring here to ensure the AutoSplit button appears.
        ensureAutoSplitToolbarButton();

        if (newFrame == registeredMapFrame) {
            return;
        }

        newFrame.addMapMode(new IconToggleButton(new SplitBuildingMapMode()));
        registeredMapFrame = newFrame;
    }

    private void ensureAutoSplitToolbarButton() {
        if (MainApplication.getToolbar() == null) {
            return;
        }

        Object toolbarValue = autoSplitBuildingAction.getValue("toolbar");
        String toolbarId = toolbarValue instanceof String ? (String) toolbarValue : "buildingsplitter_auto";
        boolean alreadyPresent = ToolbarPreferences.getToolString().stream()
            .anyMatch(entry -> entry != null && entry.startsWith(toolbarId));

        if (!alreadyPresent) {
            MainApplication.getToolbar().addCustomButton(toolbarId, -1, false);
            MainApplication.getToolbar().refreshToolbarControl();
        }
    }
}

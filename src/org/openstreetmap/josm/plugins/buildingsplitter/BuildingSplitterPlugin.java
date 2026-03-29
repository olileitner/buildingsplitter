package org.openstreetmap.josm.plugins.buildingsplitter;

import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.IconToggleButton;
import org.openstreetmap.josm.gui.MapFrame;
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

        if (newFrame == registeredMapFrame) {
            return;
        }

        newFrame.addMapMode(new IconToggleButton(new SplitBuildingMapMode()));
        registeredMapFrame = newFrame;
    }
}

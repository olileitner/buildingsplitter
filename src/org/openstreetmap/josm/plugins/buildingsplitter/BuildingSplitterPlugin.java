package org.openstreetmap.josm.plugins.buildingsplitter;

import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;

public class BuildingSplitterPlugin extends Plugin {

    private final SplitBuildingAction splitBuildingAction;
    private final AutoSplitBuildingAction autoSplitBuildingAction;

    public BuildingSplitterPlugin(PluginInformation info) {
        super(info);
        splitBuildingAction = new SplitBuildingAction();
        autoSplitBuildingAction = new AutoSplitBuildingAction();
        MainApplication.getMenu().toolsMenu.add(splitBuildingAction);
        MainApplication.getMenu().toolsMenu.add(autoSplitBuildingAction);
    }
}

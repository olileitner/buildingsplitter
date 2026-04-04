package org.openstreetmap.josm.plugins.buildingsplitter;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.Shortcut;

public class SplitBuildingAction extends JosmAction {

    private SplitBuildingMapMode registeredMapMode;

    public SplitBuildingAction() {
        super(
            tr("Split Building"),
            "buildingsplitter",
            tr("Draw a split line across a building"),
            Shortcut.registerShortcut(
                "tools:buildingsplitter:splitbuilding",
                tr("Tools: {0}", tr("Split Building")),
                0,
                Shortcut.NONE
            ),
            false
        );
        putValue(SMALL_ICON, ImageProvider.get("buildingsplitter"));
        putValue(LARGE_ICON_KEY, ImageProvider.get("buildingsplitter"));
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        DataSet dataSet = MainApplication.getLayerManager().getEditDataSet();
        if (dataSet == null) {
            showError(tr("No editable dataset is available."), UserNotifier.INFO_MESSAGE);
            return;
        }

        if (MainApplication.getMap() == null) {
            showError(tr("Map view is not available."), UserNotifier.ERROR_MESSAGE);
            return;
        }

        SplitBuildingMapMode mapMode = resolveMapModeForActivation();
        if (mapMode == null) {
            showError(tr("Split Building tool is not initialized yet. Please retry after the map view is ready."), UserNotifier.INFO_MESSAGE);
            return;
        }
        MainApplication.getMap().selectMapMode(mapMode);
    }

    void setRegisteredMapMode(SplitBuildingMapMode mapMode) {
        this.registeredMapMode = mapMode;
    }

    SplitBuildingMapMode getRegisteredMapMode() {
        return registeredMapMode;
    }

    SplitBuildingMapMode resolveMapModeForActivation() {
        return registeredMapMode;
    }

    @Override
    protected void updateEnabledState() {
        setEnabled(MainApplication.getLayerManager().getEditDataSet() != null);
    }


    private void showError(String message, int messageType) {
        UserNotifier.show(tr("Split Building"), message, messageType);
    }
}

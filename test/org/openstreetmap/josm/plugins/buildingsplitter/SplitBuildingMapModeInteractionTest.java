package org.openstreetmap.josm.plugins.buildingsplitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Arrays;

import javax.swing.JPanel;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

class SplitBuildingMapModeInteractionTest {

    @BeforeAll
    static void initJosmConfig() {
        if (Config.getPref() == null) {
            Config.setPreferencesInstance(new MemoryPreferences());
        }
        setMainApplicationContentPaneForShortcutRegistration();
    }

    @Test
    void clickClearlyInsideSingleBuildingTriggersAutoSplitIntent() {
        DataSet dataSet = new DataSet();
        createBuilding(dataSet, 0.0, 0.0, 0.0002, 0.0002);

        SplitBuildingMapMode mapMode = new SplitBuildingMapMode();
        SplitBuildingMapMode.ClickIntentResolution intent = mapMode.resolveClickIntentForTesting(
            dataSet,
            new LatLon(0.0001, 0.0001)
        );

        assertEquals(SplitBuildingMapMode.ClickIntentKind.AUTOSPLIT_INTERIOR, intent.kind());
    }

    @Test
    void clickNearCornerPrefersManualIntent() {
        DataSet dataSet = new DataSet();
        createBuilding(dataSet, 0.0, 0.0, 0.0002, 0.0002);

        SplitBuildingMapMode mapMode = new SplitBuildingMapMode();
        SplitBuildingMapMode.ClickIntentResolution intent = mapMode.resolveClickIntentForTesting(
            dataSet,
            new LatLon(0.000001, 0.000001)
        );

        assertEquals(SplitBuildingMapMode.ClickIntentKind.MANUAL, intent.kind());
    }

    @Test
    void clickNearEdgePrefersManualIntent() {
        DataSet dataSet = new DataSet();
        createBuilding(dataSet, 0.0, 0.0, 0.0002, 0.0002);

        SplitBuildingMapMode mapMode = new SplitBuildingMapMode();
        SplitBuildingMapMode.ClickIntentResolution intent = mapMode.resolveClickIntentForTesting(
            dataSet,
            new LatLon(0.000001, 0.0001)
        );

        assertEquals(SplitBuildingMapMode.ClickIntentKind.MANUAL, intent.kind());
    }

    @Test
    void interiorClickTooCloseToBoundaryDoesNotAutoSplit() {
        DataSet dataSet = new DataSet();
        createBuilding(dataSet, 0.0, 0.0, 0.0002, 0.0002);

        SplitBuildingMapMode mapMode = new SplitBuildingMapMode();
        SplitBuildingMapMode.ClickIntentResolution intent = mapMode.resolveClickIntentForTesting(
            dataSet,
            new LatLon(0.00001, 0.0001)
        );

        assertNotEquals(SplitBuildingMapMode.ClickIntentKind.AUTOSPLIT_INTERIOR, intent.kind());
    }

    @Test
    void ambiguousInteriorClickAcrossMultipleBuildingsDoesNotAutoSplit() {
        DataSet dataSet = new DataSet();
        createBuilding(dataSet, 0.0, 0.0, 0.0002, 0.0002);
        createBuilding(dataSet, 0.00005, 0.00005, 0.00025, 0.00025);

        SplitBuildingMapMode mapMode = new SplitBuildingMapMode();
        SplitBuildingMapMode.ClickIntentResolution intent = mapMode.resolveClickIntentForTesting(
            dataSet,
            new LatLon(0.00012, 0.00012)
        );

        assertEquals(SplitBuildingMapMode.ClickIntentKind.AMBIGUOUS, intent.kind());
    }

    @Test
    void dragGestureDetectionStillTreatsDifferentPointsAsDragSplit() {
        SplitBuildingMapMode mapMode = new SplitBuildingMapMode();
        assertTrue(mapMode.isDragSplitGesture(new LatLon(0.0, 0.0), new LatLon(0.0001, 0.0001)));
    }

    @Test
    void manualTwoClickHelpersRemainResolvable() {
        DataSet dataSet = new DataSet();
        Way building = createBuilding(dataSet, 0.0, 0.0, 0.0002, 0.0002);

        SplitBuildingMapMode mapMode = new SplitBuildingMapMode();
        assertTrue(mapMode.canResolveManualFirstClickForTesting(dataSet, new LatLon(0.000001, 0.000001)));
        assertTrue(mapMode.canResolveManualSecondClickForTesting(building, new LatLon(0.0001, 0.000001)));
    }

    private Way createBuilding(DataSet dataSet, double minLat, double minLon, double maxLat, double maxLon) {
        Node n1 = createNode(dataSet, minLat, minLon);
        Node n2 = createNode(dataSet, minLat, maxLon);
        Node n3 = createNode(dataSet, maxLat, maxLon);
        Node n4 = createNode(dataSet, maxLat, minLon);

        Way way = new Way();
        way.setNodes(Arrays.asList(n1, n2, n3, n4, n1));
        way.put("building", "yes");
        dataSet.addPrimitive(way);
        return way;
    }

    private Node createNode(DataSet dataSet, double lat, double lon) {
        Node node = new Node(new LatLon(lat, lon));
        dataSet.addPrimitive(node);
        return node;
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
}


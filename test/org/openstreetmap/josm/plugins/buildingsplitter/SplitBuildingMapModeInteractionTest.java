package org.openstreetmap.josm.plugins.buildingsplitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void clickCloseToCornerAroundTwoMetersStillPrefersManualIntent() {
        DataSet dataSet = new DataSet();
        createBuilding(dataSet, 0.0, 0.0, 0.0002, 0.0002);

        SplitBuildingMapMode mapMode = new SplitBuildingMapMode();
        SplitBuildingMapMode.ClickIntentResolution intent = mapMode.resolveClickIntentForTesting(
            dataSet,
            new LatLon(0.000012, 0.000012)
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
    void interiorClickOnNonFourCornerBuildingShowsRequirementsAndDoesNotAutoSplit() {
        DataSet dataSet = new DataSet();
        createFiveCornerBuilding(dataSet);

        SplitBuildingMapMode mapMode = new SplitBuildingMapMode();
        SplitBuildingMapMode.ClickIntentResolution intent = mapMode.resolveClickIntentForTesting(
            dataSet,
            new LatLon(0.0001, 0.0001)
        );

        assertNotEquals(SplitBuildingMapMode.ClickIntentKind.AUTOSPLIT_INTERIOR, intent.kind());
        assertEquals(SplitBuildingMapMode.ClickIntentKind.AMBIGUOUS, intent.kind());
        assertNotNull(intent.message());
        assertEquals("No selected buildings meet AutoSplit requirements.", intent.message());
    }

    @Test
    void tinyPointerJitterIsNotTreatedAsDragSplit() {
        SplitBuildingMapMode mapMode = new SplitBuildingMapMode();
        assertFalse(mapMode.isDragSplitGestureByPixels(new java.awt.Point(100, 100), new java.awt.Point(104, 104)));
    }

    @Test
    void dragGestureDetectionStillTreatsClearMovementAsDragSplit() {
        SplitBuildingMapMode mapMode = new SplitBuildingMapMode();
        assertTrue(mapMode.isDragSplitGestureByPixels(new java.awt.Point(100, 100), new java.awt.Point(120, 120)));
    }

    @Test
    void manualTwoClickHelpersRemainResolvable() {
        DataSet dataSet = new DataSet();
        Way building = createBuilding(dataSet, 0.0, 0.0, 0.0002, 0.0002);

        SplitBuildingMapMode mapMode = new SplitBuildingMapMode();
        assertTrue(mapMode.canResolveManualFirstClickForTesting(dataSet, new LatLon(0.000001, 0.000001)));
        assertTrue(mapMode.canResolveManualSecondClickForTesting(building, new LatLon(0.0001, 0.000001)));
    }

    @Test
    void hoverNearCornerPreviewsManualFirstSplitPoint() {
        DataSet dataSet = new DataSet();
        createBuilding(dataSet, 0.0, 0.0, 0.0002, 0.0002);

        SplitBuildingMapMode mapMode = new SplitBuildingMapMode();
        Node hoveredNode = mapMode.resolveHoverFirstNodeForTesting(dataSet, new LatLon(0.000001, 0.000001));

        assertNotNull(hoveredNode);
    }

    @Test
    void hoverAwayFromCornersDoesNotPreviewManualFirstSplitPoint() {
        DataSet dataSet = new DataSet();
        createBuilding(dataSet, 0.0, 0.0, 0.0002, 0.0002);

        SplitBuildingMapMode mapMode = new SplitBuildingMapMode();
        Node hoveredNode = mapMode.resolveHoverFirstNodeForTesting(dataSet, new LatLon(0.0001, 0.0001));

        assertNull(hoveredNode);
    }

    @Test
    void interiorClickOnDifferentBuildingReplacesSinglePreviousSelection() {
        DataSet dataSet = new DataSet();
        Way selectedButInvalid = createFiveCornerBuilding(dataSet);
        Way clickedAndValid = createBuilding(dataSet, 0.001, 0.001, 0.0012, 0.0012);
        dataSet.setSelected(selectedButInvalid);

        SplitBuildingMapMode mapMode = new SplitBuildingMapMode();
        LatLon clickPoint = new LatLon(0.0011, 0.0011);

        mapMode.normalizeSelectionToClickedBuildingForTesting(dataSet, clickPoint);
        SplitBuildingMapMode.ClickIntentResolution intent = mapMode.resolveClickIntentForTesting(dataSet, clickPoint);

        assertEquals(1, dataSet.getSelectedWays().size());
        assertEquals(clickedAndValid, dataSet.getSelectedWays().iterator().next());
        assertEquals(SplitBuildingMapMode.ClickIntentKind.AUTOSPLIT_INTERIOR, intent.kind());
    }

    @Test
    void interiorClickOnDifferentBuildingReplacesMultiplePreviousSelections() {
        DataSet dataSet = new DataSet();
        Way selectedA = createBuilding(dataSet, 0.0, 0.0, 0.0002, 0.0002);
        Way selectedB = createFiveCornerBuilding(dataSet);
        Way clickedAndValid = createBuilding(dataSet, 0.002, 0.002, 0.0022, 0.0022);
        dataSet.setSelected(Arrays.asList(selectedA, selectedB));

        SplitBuildingMapMode mapMode = new SplitBuildingMapMode();
        LatLon clickPoint = new LatLon(0.0021, 0.0021);

        mapMode.normalizeSelectionToClickedBuildingForTesting(dataSet, clickPoint);
        SplitBuildingMapMode.ClickIntentResolution intent = mapMode.resolveClickIntentForTesting(dataSet, clickPoint);

        assertEquals(1, dataSet.getSelectedWays().size());
        assertEquals(clickedAndValid, dataSet.getSelectedWays().iterator().next());
        assertEquals(SplitBuildingMapMode.ClickIntentKind.AUTOSPLIT_INTERIOR, intent.kind());
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

    private Way createFiveCornerBuilding(DataSet dataSet) {
        Node n1 = createNode(dataSet, 0.0, 0.0);
        Node n2 = createNode(dataSet, 0.0, 0.0002);
        Node n3 = createNode(dataSet, 0.0001, 0.00025);
        Node n4 = createNode(dataSet, 0.0002, 0.0002);
        Node n5 = createNode(dataSet, 0.0002, 0.0);

        Way way = new Way();
        way.setNodes(Arrays.asList(n1, n2, n3, n4, n5, n1));
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

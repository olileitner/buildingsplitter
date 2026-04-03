package org.openstreetmap.josm.plugins.buildingsplitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

class AutoSplitBuildingServiceTest {

    private final AutoSplitBuildingService service = new AutoSplitBuildingService();

    @BeforeAll
    static void initJosmConfig() {
        if (Config.getPref() == null) {
            Config.setPreferencesInstance(new MemoryPreferences());
        }
    }

    @Test
    void partsOtherThanTwoFails() {
        DataSet dataSet = new DataSet();
        Way building = createClosedRectBuilding(dataSet, true);

        SplitResult result = service.autoSplitBuilding(dataSet, building, 1);

        assertFalse(result.isSuccess());
        assertEquals("Number of parts must be at least 2.", result.getMessage());
    }

    @Test
    void nonFourCornerBuildingFails() {
        DataSet dataSet = new DataSet();
        Way building = createClosedFiveCornerBuilding(dataSet);

        SplitResult result = service.autoSplitBuilding(dataSet, building, 2);

        assertFalse(result.isSuccess());
        assertEquals("AutoSplit currently supports only 4-corner buildings.", result.getMessage());
    }

    @Test
    void validFourCornerBuildingSplitsAndSelectsCreatedWays() {
        DataSet dataSet = new DataSet();
        Way originalBuilding = createClosedRectBuilding(dataSet, true);

        SplitResult result = service.autoSplitBuilding(dataSet, originalBuilding, 2);

        assertTrue(result.isSuccess());
        assertNotNull(result.getMessage());
        assertFalse(originalBuilding.isDeleted());

        List<Way> createdWays = result.getCreatedWays();
        assertEquals(2, createdWays.size());
        assertTrue(createdWays.contains(originalBuilding));

        for (Way createdWay : createdWays) {
            assertTrue(createdWay.isClosed());
            assertFalse(createdWay.isDeleted());
            assertEquals("yes", createdWay.get("building"));
            assertTrue(createdWay.getNodesCount() >= 4);
        }

        List<Way> selectedWays = dataSet.getSelectedWays().stream().toList();
        assertEquals(2, selectedWays.size());
        assertTrue(selectedWays.containsAll(createdWays));
    }

    @Test
    void validFourCornerBuildingSplitsIntoThreeParts() {
        DataSet dataSet = new DataSet();
        Way originalBuilding = createClosedRectBuilding(dataSet, true);

        SplitResult result = service.autoSplitBuilding(dataSet, originalBuilding, 3);

        assertTrue(result.isSuccess());
        assertNotNull(result.getMessage());
        assertFalse(originalBuilding.isDeleted());

        List<Way> createdWays = result.getCreatedWays();
        assertEquals(3, createdWays.size());
        assertTrue(createdWays.contains(originalBuilding));
        for (Way createdWay : createdWays) {
            assertTrue(createdWay.isClosed());
            assertFalse(createdWay.isDeleted());
            assertEquals("yes", createdWay.get("building"));
            assertTrue(createdWay.getNodesCount() >= 4);
        }

        Set<Long> uniqueWayIds = createdWays.stream().map(Way::getUniqueId).collect(Collectors.toSet());
        assertEquals(3, uniqueWayIds.size());

        List<Way> selectedWays = dataSet.getSelectedWays().stream().toList();
        assertEquals(3, selectedWays.size());
        assertTrue(selectedWays.containsAll(createdWays));
    }

    @Test
    void runtimeExceptionInSplitPathReturnsRollbackFailure() throws Exception {
        DataSet dataSet = new DataSet();
        Way originalBuilding = createClosedRectBuilding(dataSet, true);

        AutoSplitBuildingService crashingService = new AutoSplitBuildingService();
        injectSplitService(crashingService, new BuildingSplitService() {
            @Override
            public SplitExecutionResult splitSelectedBuildingDetailed(DataSet ignoredDataSet) {
                throw new RuntimeException("simulated crash");
            }
        });

        SplitResult result = crashingService.autoSplitBuilding(dataSet, originalBuilding, 2);

        assertFalse(result.isSuccess());
        assertEquals("AutoSplit failed unexpectedly. Changes were rolled back.", result.getMessage());
        assertFalse(originalBuilding.isDeleted());
    }

    @Test
    void iterativeSplitIntoFourPartsKeepsOriginalAndReturnsStableResultSet() {
        DataSet dataSet = new DataSet();
        Way originalBuilding = createClosedRectBuilding(dataSet, true);

        SplitResult result = service.autoSplitBuilding(dataSet, originalBuilding, 4);

        assertTrue(result.isSuccess());
        assertFalse(originalBuilding.isDeleted());

        List<Way> createdWays = result.getCreatedWays();
        assertEquals(4, createdWays.size());
        assertTrue(createdWays.contains(originalBuilding));

        Set<Long> uniqueWayIds = createdWays.stream().map(Way::getUniqueId).collect(Collectors.toSet());
        assertEquals(4, uniqueWayIds.size());
        assertTrue(createdWays.stream().allMatch(way -> !way.isDeleted() && way.hasKey("building") && way.isClosed()));
    }

    private void injectSplitService(AutoSplitBuildingService target, BuildingSplitService replacement) throws Exception {
        Field splitServiceField = AutoSplitBuildingService.class.getDeclaredField("splitService");
        splitServiceField.setAccessible(true);
        splitServiceField.set(target, replacement);
    }

    private Way createClosedRectBuilding(DataSet dataSet, boolean withBuildingTag) {
        Node n1 = createNode(dataSet, 0.0, 0.0);
        Node n2 = createNode(dataSet, 0.0, 4.0);
        Node n3 = createNode(dataSet, 2.0, 4.0);
        Node n4 = createNode(dataSet, 2.0, 0.0);

        Way way = new Way();
        way.setNodes(Arrays.asList(n1, n2, n3, n4, n1));
        if (withBuildingTag) {
            way.put("building", "yes");
        }
        dataSet.addPrimitive(way);
        return way;
    }

    private Way createClosedFiveCornerBuilding(DataSet dataSet) {
        Node n1 = createNode(dataSet, 0.0, 0.0);
        Node n2 = createNode(dataSet, 0.0, 4.0);
        Node n3 = createNode(dataSet, 1.0, 5.0);
        Node n4 = createNode(dataSet, 2.0, 4.0);
        Node n5 = createNode(dataSet, 2.0, 0.0);

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
}


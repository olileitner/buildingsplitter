package org.openstreetmap.josm.plugins.buildingsplitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

class BuildingSplitServiceTest {

    private final BuildingSplitService service = new BuildingSplitService();

    @BeforeAll
    static void initJosmConfig() {
        if (Config.getPref() == null) {
            Config.setPreferencesInstance(new MemoryPreferences());
        }
    }

    @Test
    void validRectangularBuildingSplit() {
        DataSet dataSet = new DataSet();
        RectFixture rect = createClosedRectBuilding(dataSet, true);

        setSelection(dataSet, rect.way, rect.n1, rect.n3);

        SplitResult result = service.splitSelectedBuilding(dataSet);

        assertTrue(result.isSuccess());
        assertTrue(rect.way.isDeleted());

        List<Way> resultingWays = findActiveWaysExcluding(dataSet, rect.way);
        assertEquals(2, resultingWays.size());

        for (Way way : resultingWays) {
            assertTrue(way.isClosed());
            assertTrue(way.hasKey("building"));
            assertTrue(way.getNodesCount() >= 4);
            assertValidClosedPolygon(way);
        }
    }

    @Test
    void selectedNodesNotOnBuildingFails() {
        DataSet dataSet = new DataSet();
        RectFixture rect = createClosedRectBuilding(dataSet, true);
        Node externalNode = createNode(dataSet, 2.0, 2.0);

        setSelection(dataSet, rect.way, rect.n1, externalNode);

        SplitResult result = service.splitSelectedBuilding(dataSet);

        assertFalse(result.isSuccess());
        assertNotNull(result.getMessage());
    }

    @Test
    void sameNodeSelectedTwiceFails() {
        DataSet dataSet = new DataSet();
        RectFixture rect = createClosedRectBuilding(dataSet, true);

        setSelection(dataSet, rect.way, rect.n1, rect.n1);

        SplitResult result = service.splitSelectedBuilding(dataSet);

        assertFalse(result.isSuccess());
        assertNotNull(result.getMessage());
    }

    @Test
    void noBuildingTagFails() {
        DataSet dataSet = new DataSet();
        RectFixture rect = createClosedRectBuilding(dataSet, false);

        setSelection(dataSet, rect.way, rect.n1, rect.n3);

        SplitResult result = service.splitSelectedBuilding(dataSet);

        assertFalse(result.isSuccess());
        assertNotNull(result.getMessage());
    }

    @Test
    void openWayFails() {
        DataSet dataSet = new DataSet();
        OpenRectFixture openRect = createOpenRectBuilding(dataSet, true);

        setSelection(dataSet, openRect.way, openRect.n1, openRect.n3);

        SplitResult result = service.splitSelectedBuilding(dataSet);

        assertFalse(result.isSuccess());
        assertNotNull(result.getMessage());
    }

    @Test
    void wrongNumberOfSelectedNodesOneFails() {
        DataSet dataSet = new DataSet();
        RectFixture rect = createClosedRectBuilding(dataSet, true);

        setSelection(dataSet, rect.way, rect.n1);

        SplitResult result = service.splitSelectedBuilding(dataSet);

        assertFalse(result.isSuccess());
        assertNotNull(result.getMessage());
    }

    @Test
    void wrongNumberOfSelectedNodesThreeFails() {
        DataSet dataSet = new DataSet();
        RectFixture rect = createClosedRectBuilding(dataSet, true);

        setSelection(dataSet, rect.way, rect.n1, rect.n2, rect.n3);

        SplitResult result = service.splitSelectedBuilding(dataSet);

        assertFalse(result.isSuccess());
        assertNotNull(result.getMessage());
    }

    @Test
    void safeTagsAreCopiedAndRiskyTagsAreNot() {
        DataSet dataSet = new DataSet();
        RectFixture rect = createClosedRectBuilding(dataSet, true);

        rect.way.put("building", "yes");
        rect.way.put("building:levels", "2");
        rect.way.put("roof:shape", "gabled");
        rect.way.put("addr:housenumber", "10");
        rect.way.put("name", "Test House");

        setSelection(dataSet, rect.way, rect.n1, rect.n3);

        SplitResult result = service.splitSelectedBuilding(dataSet);

        assertTrue(result.isSuccess());
        assertNotNull(result.getMessage());

        List<Way> resultingWays = findActiveWaysExcluding(dataSet, rect.way);
        assertEquals(2, resultingWays.size());

        for (Way way : resultingWays) {
            assertEquals("yes", way.get("building"));
            assertEquals("2", way.get("building:levels"));
            assertEquals("gabled", way.get("roof:shape"));
            assertFalse(way.hasKey("addr:housenumber"));
            assertEquals("Test House", way.get("name"));
        }
    }

    private Node createNode(DataSet dataSet, double lat, double lon) {
        Node node = new Node(new LatLon(lat, lon));
        dataSet.addPrimitive(node);
        return node;
    }

    private RectFixture createClosedRectBuilding(DataSet dataSet, boolean withBuildingTag) {
        Node n1 = createNode(dataSet, 0.0, 0.0);
        Node n2 = createNode(dataSet, 0.0, 1.0);
        Node n3 = createNode(dataSet, 1.0, 1.0);
        Node n4 = createNode(dataSet, 1.0, 0.0);

        Way way = new Way();
        way.setNodes(Arrays.asList(n1, n2, n3, n4, n1));
        if (withBuildingTag) {
            way.put("building", "yes");
        }
        dataSet.addPrimitive(way);

        return new RectFixture(way, n1, n2, n3, n4);
    }

    private OpenRectFixture createOpenRectBuilding(DataSet dataSet, boolean withBuildingTag) {
        Node n1 = createNode(dataSet, 0.0, 0.0);
        Node n2 = createNode(dataSet, 0.0, 1.0);
        Node n3 = createNode(dataSet, 1.0, 1.0);
        Node n4 = createNode(dataSet, 1.0, 0.0);

        Way way = new Way();
        way.setNodes(Arrays.asList(n1, n2, n3, n4));
        if (withBuildingTag) {
            way.put("building", "yes");
        }
        dataSet.addPrimitive(way);

        return new OpenRectFixture(way, n1, n2, n3, n4);
    }

    private void setSelection(DataSet dataSet, Way way, Node... nodes) {
        List<OsmPrimitive> selected = new ArrayList<>();
        selected.add(way);
        selected.addAll(Arrays.asList(nodes));
        dataSet.setSelected(selected);
    }

    private List<Way> findActiveWaysExcluding(DataSet dataSet, Way originalWay) {
        return dataSet.getWays().stream()
            .filter(way -> !way.equals(originalWay))
            .filter(way -> !way.isDeleted())
            .collect(Collectors.toList());
    }

    private void assertValidClosedPolygon(Way way) {
        List<Node> nodes = way.getNodes();
        assertTrue(nodes.size() >= 4);
        assertEquals(nodes.get(0), nodes.get(nodes.size() - 1));
        assertTrue(nodes.stream().distinct().count() >= 3);
    }

    private static final class RectFixture {
        private final Way way;
        private final Node n1;
        private final Node n2;
        private final Node n3;
        private final Node n4;

        private RectFixture(Way way, Node n1, Node n2, Node n3, Node n4) {
            this.way = way;
            this.n1 = n1;
            this.n2 = n2;
            this.n3 = n3;
            this.n4 = n4;
        }
    }

    private static final class OpenRectFixture {
        private final Way way;
        private final Node n1;
        private final Node n2;
        private final Node n3;
        private final Node n4;

        private OpenRectFixture(Way way, Node n1, Node n2, Node n3, Node n4) {
            this.way = way;
            this.n1 = n1;
            this.n2 = n2;
            this.n3 = n3;
            this.n4 = n4;
        }
    }
}






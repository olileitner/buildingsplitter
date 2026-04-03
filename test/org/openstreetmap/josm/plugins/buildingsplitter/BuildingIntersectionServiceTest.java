package org.openstreetmap.josm.plugins.buildingsplitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

class BuildingIntersectionServiceTest {

    private final BuildingIntersectionService service = new BuildingIntersectionService();

    @BeforeAll
    static void initJosmConfig() {
        if (Config.getPref() == null) {
            Config.setPreferencesInstance(new MemoryPreferences());
        }
    }

    @Test
    void rectangleHorizontalSplitReturnsTwoIntersections() {
        DataSet dataSet = new DataSet();
        Way rectangle = createClosedRectangle(dataSet);

        IntersectionResult result = service.findSplitIntersections(
            rectangle,
            new LatLon(0.5, -1.0),
            new LatLon(0.5, 2.0)
        );

        assertTrue(result.isSuccess());
        assertEquals(2, result.getIntersections().size());
    }

    @Test
    void lineOutsideBuildingReturnsNoIntersectionsFailure() {
        DataSet dataSet = new DataSet();
        Way rectangle = createClosedRectangle(dataSet);

        IntersectionResult result = service.findSplitIntersections(
            rectangle,
            new LatLon(3.0, -1.0),
            new LatLon(3.0, 3.0)
        );

        assertFalse(result.isSuccess());
        assertEquals("Line does not intersect building", result.getMessage());
        assertTrue(result.getIntersections().isEmpty());
    }

    @Test
    void lineTouchingCornerReturnsOneIntersectionFailure() {
        DataSet dataSet = new DataSet();
        Way rectangle = createClosedRectangle(dataSet);

        IntersectionResult result = service.findSplitIntersections(
            rectangle,
            new LatLon(-1.0, -1.0),
            new LatLon(0.0, 0.0)
        );

        assertFalse(result.isSuccess());
        assertEquals("Line touches building only once", result.getMessage());
        assertTrue(result.getIntersections().isEmpty());
    }

    @Test
    void rectangleVerticalLineCrossingTwiceSucceeds() {
        DataSet dataSet = new DataSet();
        Way rectangle = createClosedRectangle(dataSet);

        IntersectionResult result = service.findSplitIntersections(
            rectangle,
            new LatLon(-1.0, 0.5),
            new LatLon(2.0, 0.5)
        );

        assertTrue(result.isSuccess());
        assertEquals(2, result.getIntersections().size());
        assertNotNull(result.getIntersections().get(0).getCoordinate());
        assertNotNull(result.getIntersections().get(1).getCoordinate());
    }

    @Test
    void concaveShapeCrossingMoreThanTwiceFails() {
        DataSet dataSet = new DataSet();
        Way concave = createClosedConcaveWay(dataSet);

        IntersectionResult result = service.findSplitIntersections(
            concave,
            new LatLon(3.0, -1.0),
            new LatLon(3.0, 7.0)
        );

        assertFalse(result.isSuccess());
        assertEquals("Line intersects building multiple times; not supported", result.getMessage());
        assertTrue(result.getIntersections().isEmpty());
    }

    @Test
    void cornerToNonAdjacentEdgeIsAllowed() {
        DataSet dataSet = new DataSet();
        Way rectangle = createClosedRectangle(dataSet);

        IntersectionResult result = service.findSplitIntersections(
            rectangle,
            new LatLon(0.0, 0.0),
            new LatLon(2.0, 1.0)
        );

        assertTrue(result.isSuccess());
        assertEquals(2, result.getIntersections().size());
        assertTrue(result.getIntersections().stream().anyMatch(IntersectionPoint::isExistingNode));
        assertTrue(result.getIntersections().stream().anyMatch(point -> !point.isExistingNode()));
    }

    @Test
    void nonAdjacentEdgeToCornerIsAllowed() {
        DataSet dataSet = new DataSet();
        Way rectangle = createClosedRectangle(dataSet);

        IntersectionResult result = service.findSplitIntersections(
            rectangle,
            new LatLon(2.0, 1.0),
            new LatLon(0.0, 0.0)
        );

        assertTrue(result.isSuccess());
        assertEquals(2, result.getIntersections().size());
        assertTrue(result.getIntersections().stream().anyMatch(IntersectionPoint::isExistingNode));
        assertTrue(result.getIntersections().stream().anyMatch(point -> !point.isExistingNode()));
    }

    @Test
    void lineOverlappingBuildingEdgeSegmentRemainsRejected() {
        DataSet dataSet = new DataSet();
        Way rectangle = createClosedRectangle(dataSet);

        IntersectionResult result = service.findSplitIntersections(
            rectangle,
            new LatLon(0.0, 0.5),
            new LatLon(0.0, 1.5)
        );

        assertFalse(result.isSuccess());
        assertEquals("Line overlaps building edge; not supported", result.getMessage());
    }

    @Test
    void collinearSharedEndpointTouchIsNotClassifiedAsOverlap() {
        DataSet dataSet = new DataSet();
        Way rectangle = createClosedRectangle(dataSet);

        IntersectionResult result = service.findSplitIntersections(
            rectangle,
            new LatLon(0.0, 0.0),
            new LatLon(-1.0, 0.0)
        );

        assertFalse(result.isSuccess());
        assertEquals("Line touches building only once", result.getMessage());
    }

    private Way createClosedRectangle(DataSet dataSet) {
        Node n1 = createNode(dataSet, 0.0, 0.0);
        Node n2 = createNode(dataSet, 0.0, 2.0);
        Node n3 = createNode(dataSet, 2.0, 2.0);
        Node n4 = createNode(dataSet, 2.0, 0.0);

        Way way = new Way();
        way.setNodes(Arrays.asList(n1, n2, n3, n4, n1));
        dataSet.addPrimitive(way);
        return way;
    }

    private Way createClosedConcaveWay(DataSet dataSet) {
        Node n1 = createNode(dataSet, 0.0, 0.0);
        Node n2 = createNode(dataSet, 0.0, 6.0);
        Node n3 = createNode(dataSet, 6.0, 6.0);
        Node n4 = createNode(dataSet, 6.0, 4.0);
        Node n5 = createNode(dataSet, 2.0, 4.0);
        Node n6 = createNode(dataSet, 2.0, 2.0);
        Node n7 = createNode(dataSet, 6.0, 2.0);
        Node n8 = createNode(dataSet, 6.0, 0.0);

        Way way = new Way();
        way.setNodes(Arrays.asList(n1, n2, n3, n4, n5, n6, n7, n8, n1));
        dataSet.addPrimitive(way);
        return way;
    }

    private Node createNode(DataSet dataSet, double lat, double lon) {
        Node node = new Node(new LatLon(lat, lon));
        dataSet.addPrimitive(node);
        return node;
    }
}


package org.openstreetmap.josm.plugins.buildingsplitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.Projection;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

class AutoSplitPreviewSessionTest {

    private Projection previousProjection;

    @BeforeAll
    static void initJosmConfig() {
        if (Config.getPref() == null) {
            Config.setPreferencesInstance(new MemoryPreferences());
        }
    }

    @BeforeEach
    void setMercatorProjection() {
        previousProjection = ProjectionRegistry.getProjection();
        Projection mercator = Projections.getProjectionByCode("EPSG:3857");
        assertNotNull(mercator);
        ProjectionRegistry.setProjection(mercator);
    }

    @AfterEach
    void restoreProjection() {
        if (previousProjection != null) {
            ProjectionRegistry.setProjection(previousProjection);
        }
    }

    @Test
    void orderWaysBySplitAxisUsesProjectedCenterCoordinates() throws Exception {
        DataSet dataSet = new DataSet();

        Way wayA = createSmallClosedWay(dataSet, -52.80956632715018, 160.80284602842931);
        Way wayB = createSmallClosedWay(dataSet, -51.93530734065405, 160.30781771999224);

        AutoSplitPreviewSession session = new AutoSplitPreviewSession(
            dataSet,
            wayA,
            new AutoSplitBuildingService(),
            new HouseNumberService()
        );

        SplitResult splitResult = SplitResult.success(
            "ok",
            Arrays.asList(wayB, wayA),
            new SplitResult.SplitAxis(0.8781640138519368, 0.478359660480956)
        );

        List<Way> ordered = invokeOrderWaysBySplitAxis(session, splitResult, false);
        assertEquals(Arrays.asList(wayA, wayB), ordered);

        List<Way> reverseOrdered = invokeOrderWaysBySplitAxis(session, splitResult, true);
        assertEquals(Arrays.asList(wayB, wayA), reverseOrdered);
    }

    @SuppressWarnings("unchecked")
    private List<Way> invokeOrderWaysBySplitAxis(
        AutoSplitPreviewSession session,
        SplitResult splitResult,
        boolean reverseOrder
    ) throws Exception {
        Method method = AutoSplitPreviewSession.class.getDeclaredMethod(
            "orderWaysBySplitAxis",
            SplitResult.class,
            boolean.class
        );
        method.setAccessible(true);
        return (List<Way>) method.invoke(session, splitResult, reverseOrder);
    }

    private Way createSmallClosedWay(DataSet dataSet, double centerLat, double centerLon) {
        double delta = 0.0001;
        Node n1 = createNode(dataSet, centerLat - delta, centerLon - delta);
        Node n2 = createNode(dataSet, centerLat - delta, centerLon + delta);
        Node n3 = createNode(dataSet, centerLat + delta, centerLon + delta);
        Node n4 = createNode(dataSet, centerLat + delta, centerLon - delta);

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
}



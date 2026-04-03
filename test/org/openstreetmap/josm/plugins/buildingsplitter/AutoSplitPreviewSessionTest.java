package org.openstreetmap.josm.plugins.buildingsplitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
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

    private static final String PREVIEW_RESET_MESSAGE =
        "AutoSplit preview was reset because other edits were made in the meantime.";

    private Projection previousProjection;

    @BeforeAll
    static void initJosmConfig() {
        if (Config.getPref() == null) {
            Config.setPreferencesInstance(new MemoryPreferences());
        }
    }

    @BeforeEach
    void setMercatorProjection() {
        UndoRedoHandler.getInstance().clean();
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

    @Test
    void refreshAndFinalizeReturnStableResultWaysWithSurvivingOriginalWay() {
        SessionFixture fixture = createSessionFixture();

        String previewError = fixture.session.refreshPreview(2, "", 1, false, false, "", "");
        assertNull(previewError);

        SplitResult finalized = fixture.session.finalizePreview(AutoSplitDialogResult.apply(2, "", 1, false, false, "", ""));
        assertTrue(finalized.isSuccess());
        assertEquals(2, finalized.getCreatedWays().size());
        assertTrue(finalized.getCreatedWays().contains(fixture.originalBuilding));
        assertFalse(fixture.originalBuilding.isDeleted());

        Set<Long> uniqueIds = finalized.getCreatedWays().stream().map(Way::getUniqueId).collect(Collectors.toSet());
        assertEquals(2, uniqueIds.size());
    }

    @Test
    void undoPreviewRollsBackOwnedPreviewCommands() {
        SessionFixture fixture = createSessionFixture();

        String previewError = fixture.session.refreshPreview(2, "", 1, false, false, "", "");
        assertNull(previewError);
        assertEquals(2, countActiveWays(fixture.dataSet));

        fixture.session.undoPreview();

        assertEquals(1, countActiveWays(fixture.dataSet));
        assertFalse(fixture.originalBuilding.isDeleted());
    }

    @Test
    void refreshReturnsResetMessageWhenForeignCommandBreaksOwnership() {
        SessionFixture fixture = createSessionFixture();

        String firstPreviewError = fixture.session.refreshPreview(2, "", 1, false, false, "", "");
        assertNull(firstPreviewError);

        int beforeForeign = UndoRedoHandler.getInstance().getUndoCommands().size();
        UndoRedoHandler.getInstance().add(new ChangePropertyCommand(List.of(fixture.originalBuilding), "name", "foreign"));
        int afterForeign = UndoRedoHandler.getInstance().getUndoCommands().size();
        assertTrue(afterForeign > beforeForeign);

        String previewError = fixture.session.refreshPreview(2, "", 1, false, false, "", "");
        assertEquals(PREVIEW_RESET_MESSAGE, previewError);
        assertEquals(afterForeign, UndoRedoHandler.getInstance().getUndoCommands().size());
    }

    @Test
    void finalizeFailsWithResetMessageAfterOwnershipBreak() {
        SessionFixture fixture = createSessionFixture();

        String firstPreviewError = fixture.session.refreshPreview(2, "", 1, false, false, "", "");
        assertNull(firstPreviewError);

        UndoRedoHandler.getInstance().add(new ChangePropertyCommand(List.of(fixture.originalBuilding), "name", "foreign"));

        SplitResult finalized = fixture.session.finalizePreview(AutoSplitDialogResult.apply(2, "", 1, false, false, "", ""));
        assertFalse(finalized.isSuccess());
        assertEquals(PREVIEW_RESET_MESSAGE, finalized.getMessage());
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

    private SessionFixture createSessionFixture() {
        DataSet dataSet = new DataSet();
        Way originalBuilding = createSmallClosedWay(dataSet, 0.0, 0.0);

        AutoSplitPreviewSession session = new AutoSplitPreviewSession(
            dataSet,
            originalBuilding,
            new AutoSplitBuildingService(),
            new HouseNumberService()
        );
        return new SessionFixture(dataSet, originalBuilding, session);
    }

    private int countActiveWays(DataSet dataSet) {
        return (int) dataSet.getWays().stream().filter(way -> !way.isDeleted()).count();
    }

    private static final class SessionFixture {
        private final DataSet dataSet;
        private final Way originalBuilding;
        private final AutoSplitPreviewSession session;

        private SessionFixture(DataSet dataSet, Way originalBuilding, AutoSplitPreviewSession session) {
            this.dataSet = dataSet;
            this.originalBuilding = originalBuilding;
            this.session = session;
        }
    }
}



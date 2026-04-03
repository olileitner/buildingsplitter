package org.openstreetmap.josm.plugins.buildingsplitter;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics2D;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import javax.swing.JOptionPane;

import org.openstreetmap.josm.actions.mapmode.MapMode;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.layer.MapViewPaintable;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Shortcut;

public class SplitBuildingMapMode extends MapMode {

    private static final double CLICK_EPSILON = 1e-9;
    private static final Cursor MANUAL_CURSOR = Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR);
    private static final Cursor AUTOSPLIT_CURSOR = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);
    private static final double SNAP_FEEDBACK_MAX_LINE_LENGTH_METERS = 1000.0;
    private static final double SNAP_FEEDBACK_CORNER_DISTANCE_METERS = 1.0;
    private static final double CLICK_NODE_TOLERANCE_METERS = 1.5;
    private static final double CLICK_EDGE_TOLERANCE_METERS = 1.5;
    private static final double AUTOSPLIT_INTERIOR_MARGIN_METERS = 3.0;
    private static final double CLICK_AMBIGUITY_DELTA_METERS = 0.25;
    private static final double EARTH_RADIUS_METERS = 6_371_000.0;
    private static final String MODE_TOOLTIP = tr("Split mode: drag for line split, click near corner/edge for manual split, click well inside a building for AutoSplit");
    // TEMP DEBUG: traces external context consume/default resolution in Ctrl AutoSplit flow.
    private static final boolean DEBUG_CONTEXT_TRANSFER = false;
    private static final Shortcut SPLIT_BUILDING_SHORTCUT = Shortcut.registerShortcut(
        "mapmode:buildingsplitter:splitbuilding",
        tr("Map mode: {0}", tr("Split Building")),
        KeyEvent.VK_S,
        Shortcut.CTRL | Shortcut.ALT | Shortcut.SHIFT
    );

    private final BuildingSplitService splitService;
    private final BuildingIntersectionService intersectionService;
    private final SplitNodePreparationService splitNodePreparationService;
    private final AutoSplitBuildingService autoSplitService;
    private final AutoSplitOptionsDialog autoSplitOptionsDialog;
    private final HouseNumberService houseNumberService;
    private final VisibleAddressContextService visibleAddressContextService;
    private final PreviewLinePaintable previewLinePaintable;

    private LatLon dragStart;
    private LatLon dragCurrent;
    private List<Node> snapCandidates;
    private Way clickFirstWay;
    private Node clickFirstNode;
    private Node clickSecondPreviewNode;
    private LatLon clickSecondPreviewPoint;
    private boolean snappingEnabled;

    private int lastAutoSplitParts = 2;
    private int lastAutoSplitIncrement = 1;
    private boolean lastAutoSplitReverseOrder;
    private boolean lastAutoSplitFirstWithoutLetter;
    private String lastAutoSplitStartHouseNumber = "";
    private String lastAutoSplitStreet = "";
    private String lastAutoSplitPostcode = "";

    private KeyEventDispatcher escKeyDispatcher;

    public SplitBuildingMapMode() {
        super(
            tr("Split Building"),
            "buildingsplitter",
            tr("Drag a line across a building to split it"),
            SPLIT_BUILDING_SHORTCUT,
            MANUAL_CURSOR
        );
        this.splitService = new BuildingSplitService();
        this.intersectionService = new BuildingIntersectionService();
        this.splitNodePreparationService = new SplitNodePreparationService();
        this.autoSplitService = new AutoSplitBuildingService();
        this.autoSplitOptionsDialog = new AutoSplitOptionsDialog();
        this.houseNumberService = new HouseNumberService();
        this.visibleAddressContextService = new VisibleAddressContextService();
        this.previewLinePaintable = new PreviewLinePaintable();
        this.snapCandidates = new ArrayList<>();
        putValue(SMALL_ICON, ImageProvider.get("mapmode", "buildingsplitter"));
        putValue(LARGE_ICON_KEY, ImageProvider.get("mapmode", "buildingsplitter"));
    }

    @Override
    public void enterMode() {
        super.enterMode();
        registerEscapeHandler();
        if (MainApplication.getMap() != null && MainApplication.getMap().mapView != null) {
            MainApplication.getMap().mapView.addMouseListener(this);
            MainApplication.getMap().mapView.addMouseMotionListener(this);
            MainApplication.getMap().mapView.addTemporaryLayer(previewLinePaintable);
            updateMapCursor();
            updateMapModeTooltip();
        }
    }

    @Override
    public void exitMode() {
        unregisterEscapeHandler();
        if (MainApplication.getMap() != null && MainApplication.getMap().mapView != null) {
            MainApplication.getMap().mapView.removeMouseListener(this);
            MainApplication.getMap().mapView.removeMouseMotionListener(this);
            MainApplication.getMap().mapView.removeTemporaryLayer(previewLinePaintable);
            MainApplication.getMap().mapView.setToolTipText(null);
            MainApplication.getMap().mapView.repaint();
        }
        super.exitMode();
    }

    @Override
    public void mousePressed(MouseEvent e) {
        if (e.getButton() != MouseEvent.BUTTON1) {
            return;
        }

        DataSet dataSet = MainApplication.getLayerManager().getEditDataSet();
        if (dataSet == null) {
            showError(tr("No editable dataset is available."));
            resetState();
            return;
        }

        LatLon start = toLatLon(e);
        if (!isValidClickedPoint(start)) {
            showError(tr("Unable to read clicked map location. Please click inside the map view."));
            resetState();
            return;
        }

        dragStart = start;
        dragCurrent = start;
        snappingEnabled = true;
        snapCandidates.clear();
        repaintMapView();
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        if (dragStart == null) {
            return;
        }

        LatLon current = toLatLon(e);
        if (!isValidClickedPoint(current)) {
            return;
        }

        dragCurrent = current;
        updateSnapFeedback();
        repaintMapView();
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        if (e.getButton() != MouseEvent.BUTTON1) {
            resetState();
            repaintMapView();
            return;
        }

        if (dragStart == null) {
            resetState();
            repaintMapView();
            return;
        }

        DataSet dataSet = MainApplication.getLayerManager().getEditDataSet();
        if (dataSet == null) {
            showError(tr("No editable dataset is available."));
            resetState();
            repaintMapView();
            return;
        }

        LatLon releasePoint = toLatLon(e);
        if (!isValidClickedPoint(releasePoint)) {
            showError(tr("Unable to read release map location. Please try again."));
            resetState();
            repaintMapView();
            return;
        }

        dragCurrent = releasePoint;
        if (!isDragSplitGesture(dragStart, dragCurrent)) {
            handleClickWithoutDrag(dataSet, releasePoint);
            resetDragState();
            repaintMapView();
            return;
        }

        finishSplit(dataSet, dragStart, dragCurrent);
        resetDragState();
        repaintMapView();
    }

    @Override
    public void mouseMoved(MouseEvent e) {
        if (clickFirstWay == null || dragStart != null) {
            if (clickSecondPreviewNode != null || clickSecondPreviewPoint != null) {
                clickSecondPreviewNode = null;
                clickSecondPreviewPoint = null;
                repaintMapView();
            }
            return;
        }

        LatLon current = toLatLon(e);
        if (!isValidClickedPoint(current)) {
            return;
        }

        SecondClickResolution previewResolution = resolveSecondClick(clickFirstWay, current, false);
        clickSecondPreviewNode = previewResolution == null ? null : previewResolution.existingNode();
        clickSecondPreviewPoint = previewResolution == null ? null : previewResolution.coordinate();
        repaintMapView();
    }

    private void handleClickWithoutDrag(DataSet dataSet, LatLon clickPoint) {
        if (clickFirstWay != null || clickFirstNode != null) {
            handleManualClickSelection(dataSet, clickPoint);
            return;
        }

        ClickIntentResolution intent = resolveClickIntent(dataSet, clickPoint);
        if (intent.kind() == ClickIntentKind.MANUAL) {
            handleManualClickSelection(dataSet, clickPoint);
            return;
        }

        if (intent.kind() == ClickIntentKind.AUTOSPLIT_INTERIOR && intent.autoSplitWay() != null) {
            openAutoSplitDialogForBuilding(dataSet, intent.autoSplitWay());
            return;
        }

        if (intent.message() != null && !intent.message().isEmpty()) {
            showError(intent.message());
            return;
        }

        showError(tr("Click near a building corner/edge for manual split, or clearly inside one building for AutoSplit."));
    }

    private void handleManualClickSelection(DataSet dataSet, LatLon clickPoint) {
        if (clickFirstWay == null || clickFirstNode == null) {
            FirstClickSelection first = resolveFirstClick(dataSet, clickPoint, true);
            if (first == null) {
                return;
            }
            clickFirstWay = first.way();
            clickFirstNode = first.node();
            clickSecondPreviewNode = null;
            clickSecondPreviewPoint = null;
            return;
        }

        SecondClickResolution second = resolveSecondClick(clickFirstWay, clickPoint, true);
        if (second == null) {
            return;
        }

        if (second.existingNode() != null && second.existingNode().equals(clickFirstNode)) {
            showError(tr("Please choose a different second split point."));
            return;
        }

        performClickSplit(dataSet, clickFirstWay, clickFirstNode, second);
    }

    private FirstClickSelection resolveFirstClick(DataSet dataSet, LatLon clickPoint, boolean showErrors) {
        List<FirstClickSelection> candidates = findFirstClickCandidates(dataSet, clickPoint);

        if (candidates.isEmpty()) {
            if (showErrors) {
                showError(tr("First click must be near an existing building corner node."));
            }
            return null;
        }

        candidates.sort(Comparator
            .comparingDouble(FirstClickSelection::distance)
            .thenComparingLong(selection -> selection.way().getUniqueId())
            .thenComparingLong(selection -> selection.node().getUniqueId()));

        if (isAmbiguousByDistance(candidates.get(0).distance(), candidates, FirstClickSelection::distance)) {
            if (showErrors) {
                showError(tr("First click is ambiguous. Please click closer to one building corner."));
            }
            return null;
        }

        return candidates.get(0);
    }

    private List<FirstClickSelection> findFirstClickCandidates(DataSet dataSet, LatLon clickPoint) {
        List<FirstClickSelection> candidates = new ArrayList<>();

        for (Way way : dataSet.getWays()) {
            if (way == null || way.isDeleted() || !way.isClosed() || !way.hasKey("building")) {
                continue;
            }

            List<Node> ringNodes = new ArrayList<>(way.getNodes());
            if (ringNodes.size() > 1 && ringNodes.get(0).equals(ringNodes.get(ringNodes.size() - 1))) {
                ringNodes.remove(ringNodes.size() - 1);
            }

            for (Node node : ringNodes) {
                if (node.getCoor() == null) {
                    continue;
                }
                double distance = distanceMeters(node.getCoor(), clickPoint);
                if (distance <= CLICK_NODE_TOLERANCE_METERS) {
                    candidates.add(new FirstClickSelection(way, node, distance));
                }
            }
        }

        candidates.sort(Comparator
            .comparingDouble(FirstClickSelection::distance)
            .thenComparingLong(selection -> selection.way().getUniqueId())
            .thenComparingLong(selection -> selection.node().getUniqueId()));
        return candidates;
    }

    private SecondClickResolution resolveSecondClick(Way way, LatLon clickPoint, boolean showErrors) {
        Node nearestNode = findNearestNodeOnWay(way, clickPoint, CLICK_NODE_TOLERANCE_METERS);
        if (nearestNode != null) {
            return new SecondClickResolution(nearestNode, null, nearestNode.getCoor());
        }

        List<SegmentCandidate> segments = findSegmentCandidates(way, clickPoint);
        if (segments.isEmpty()) {
            if (showErrors) {
                showError(tr("Second click must be near a corner node or an edge of the same building."));
            }
            return null;
        }

        segments.sort(Comparator
            .comparingDouble(SegmentCandidate::distance)
            .thenComparingInt(SegmentCandidate::segmentIndex));

        if (isAmbiguousByDistance(segments.get(0).distance(), segments, SegmentCandidate::distance)) {
            if (showErrors) {
                showError(tr("Second click is ambiguous. Please click closer to a single edge."));
            }
            return null;
        }

        SegmentCandidate best = segments.get(0);
        Node existingEndpoint = best.existingEndpoint();
        if (existingEndpoint != null) {
            return new SecondClickResolution(existingEndpoint, null, existingEndpoint.getCoor());
        }

        IntersectionPoint intersectionPoint = new IntersectionPoint(
            best.projectedPoint(),
            best.segmentIndex(),
            null,
            false
        );
        return new SecondClickResolution(null, intersectionPoint, best.projectedPoint());
    }

    private Node findNearestNodeOnWay(Way way, LatLon clickPoint, double toleranceMeters) {
        List<Node> ringNodes = new ArrayList<>(way.getNodes());
        if (ringNodes.size() > 1 && ringNodes.get(0).equals(ringNodes.get(ringNodes.size() - 1))) {
            ringNodes.remove(ringNodes.size() - 1);
        }

        Node nearest = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (Node node : ringNodes) {
            if (node.getCoor() == null) {
                continue;
            }
            double distance = distanceMeters(node.getCoor(), clickPoint);
            if (distance <= toleranceMeters && distance < nearestDistance) {
                nearest = node;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private List<SegmentCandidate> findSegmentCandidates(Way way, LatLon clickPoint) {
        List<Node> ringNodes = new ArrayList<>(way.getNodes());
        if (ringNodes.size() > 1 && ringNodes.get(0).equals(ringNodes.get(ringNodes.size() - 1))) {
            ringNodes.remove(ringNodes.size() - 1);
        }

        List<SegmentCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < ringNodes.size(); i++) {
            Node start = ringNodes.get(i);
            Node end = ringNodes.get((i + 1) % ringNodes.size());
            if (start.getCoor() == null || end.getCoor() == null) {
                continue;
            }

            SegmentProjection projection = projectPointToSegment(clickPoint, start.getCoor(), end.getCoor());
            if (projection == null || projection.distanceMeters() > CLICK_EDGE_TOLERANCE_METERS) {
                continue;
            }

            Node endpoint = null;
            if (distanceMeters(projection.projectedPoint(), start.getCoor()) <= CLICK_NODE_TOLERANCE_METERS) {
                endpoint = start;
            } else if (distanceMeters(projection.projectedPoint(), end.getCoor()) <= CLICK_NODE_TOLERANCE_METERS) {
                endpoint = end;
            }

            candidates.add(new SegmentCandidate(i, projection.projectedPoint(), projection.distanceMeters(), endpoint));
        }
        return candidates;
    }

    private SegmentProjection projectPointToSegment(LatLon point, LatLon start, LatLon end) {
        if (point == null || start == null || end == null) {
            return null;
        }

        if (ProjectionRegistry.getProjection() != null) {
            EastNorth p = ProjectionRegistry.getProjection().latlon2eastNorth(point);
            EastNorth a = ProjectionRegistry.getProjection().latlon2eastNorth(start);
            EastNorth b = ProjectionRegistry.getProjection().latlon2eastNorth(end);
            if (p != null && a != null && b != null) {
                return projectPointToSegmentProjected(p.east(), p.north(), a.east(), a.north(), b.east(), b.north(), true);
            }
        }

        double referenceLatRad = Math.toRadians(start.lat());
        XY p = toLocalMeters(point, referenceLatRad);
        XY a = toLocalMeters(start, referenceLatRad);
        XY b = toLocalMeters(end, referenceLatRad);
        return projectPointToSegmentProjected(p.x, p.y, a.x, a.y, b.x, b.y, false);
    }

    private SegmentProjection projectPointToSegmentProjected(
        double px,
        double py,
        double ax,
        double ay,
        double bx,
        double by,
        boolean projected
    ) {
        double abx = bx - ax;
        double aby = by - ay;
        double abSquared = (abx * abx) + (aby * aby);
        if (abSquared <= CLICK_EPSILON) {
            return null;
        }

        double t = ((px - ax) * abx + (py - ay) * aby) / abSquared;
        t = Math.max(0.0, Math.min(1.0, t));

        double closestX = ax + (t * abx);
        double closestY = ay + (t * aby);
        double distance = Math.hypot(px - closestX, py - closestY);

        LatLon projectedPoint;
        if (projected && ProjectionRegistry.getProjection() != null) {
            projectedPoint = ProjectionRegistry.getProjection().eastNorth2latlon(new EastNorth(closestX, closestY));
        } else {
            projectedPoint = new LatLon(closestY / EARTH_RADIUS_METERS * (180.0 / Math.PI),
                closestX / EARTH_RADIUS_METERS * (180.0 / Math.PI));
        }

        if (projectedPoint == null || !projectedPoint.isValid()) {
            return null;
        }
        return new SegmentProjection(projectedPoint, distance);
    }

    private void performClickSplit(DataSet dataSet, Way way, Node firstNode, SecondClickResolution second) {
        int undoStartSize = UndoRedoHandler.getInstance().getUndoCommands().size();
        Node secondNode = second.existingNode();
        List<Node> updatedNodes = new ArrayList<>(way.getNodes());

        if (second.intersectionToInsert() != null) {
            List<IntersectionPoint> intersections = new ArrayList<>();
            intersections.add(second.intersectionToInsert());
            SplitNodePreparationService.PreparedIntersectionNodes prepared =
                splitNodePreparationService.prepareIntersectionNodes(dataSet, way, intersections);

            if (prepared.getSplitNodes().size() != 1) {
                rollbackToUndoSize(undoStartSize);
                showError(tr("Failed to resolve the second split point on the selected edge."));
                return;
            }

            if (!prepared.getCommands().isEmpty()) {
                UndoRedoHandler.getInstance().add(
                    new SequenceCommand(tr("Insert split point on building edge"), prepared.getCommands())
                );
            }

            secondNode = prepared.getSplitNodes().get(0);
            updatedNodes = prepared.getUpdatedWayNodes();
        }

        if (secondNode == null) {
            rollbackToUndoSize(undoStartSize);
            showError(tr("Failed to resolve a valid second split point."));
            return;
        }

        if (firstNode.equals(secondNode)) {
            rollbackToUndoSize(undoStartSize);
            showError(tr("Please choose two different split points."));
            return;
        }

        if (splitNodePreparationService.areAdjacentInClosedNodeList(updatedNodes, firstNode, secondNode)) {
            rollbackToUndoSize(undoStartSize);
            showError(tr("Selected split points are adjacent. Please choose points on different edges."));
            return;
        }

        List<OsmPrimitive> splitSelection = new ArrayList<>();
        splitSelection.add(way);
        splitSelection.add(firstNode);
        splitSelection.add(secondNode);
        dataSet.setSelected(splitSelection);

        SplitExecutionResult result = splitService.splitSelectedBuildingDetailed(dataSet);
        if (!result.isSuccess()) {
            rollbackToUndoSize(undoStartSize);
            showError(result.getMessage());
            return;
        }

        if (!result.getResultWaysOrdered().isEmpty()) {
            dataSet.setSelected(result.getResultWaysOrdered());
        }
        clearClickSelection();
    }

    private void rollbackToUndoSize(int undoStartSize) {
        int undoCount = UndoRedoHandler.getInstance().getUndoCommands().size() - undoStartSize;
        if (undoCount > 0) {
            UndoRedoHandler.getInstance().undo(undoCount);
        }
    }

    private void clearClickSelection() {
        clickFirstWay = null;
        clickFirstNode = null;
        clickSecondPreviewNode = null;
        clickSecondPreviewPoint = null;
    }

    private <T> boolean isAmbiguousByDistance(double bestDistance, List<T> candidates, java.util.function.ToDoubleFunction<T> distanceFn) {
        if (candidates.size() < 2) {
            return false;
        }
        double secondDistance = distanceFn.applyAsDouble(candidates.get(1));
        return Math.abs(secondDistance - bestDistance) <= CLICK_AMBIGUITY_DELTA_METERS;
    }

    private Way findSingleClickedBuilding(DataSet dataSet, LatLon clickPoint, boolean showErrors) {
        List<Way> containingBuildings = new ArrayList<>();

        for (Way way : dataSet.getWays()) {
            if (way == null || way.isDeleted() || !way.isClosed() || !way.hasKey("building")) {
                continue;
            }
            if (containsPoint(way, clickPoint)) {
                containingBuildings.add(way);
            }
        }

        if (containingBuildings.isEmpty()) {
            if (showErrors) {
                showError(tr("No building found at the clicked location."));
            }
            return null;
        }

        if (containingBuildings.size() > 1) {
            containingBuildings.sort(Comparator.comparingLong(Way::getUniqueId));
            if (showErrors) {
                showError(tr("Multiple buildings match this click. Please click a less ambiguous location."));
            }
            return null;
        }

        return containingBuildings.get(0);
    }

    private boolean containsPoint(Way way, LatLon point) {
        List<Node> nodes = new ArrayList<>(way.getNodes());
        if (nodes.size() < 4) {
            return false;
        }

        if (nodes.get(0).equals(nodes.get(nodes.size() - 1))) {
            nodes.remove(nodes.size() - 1);
        }
        if (nodes.size() < 3) {
            return false;
        }

        double testX = point.lon();
        double testY = point.lat();
        boolean inside = false;

        int j = nodes.size() - 1;
        for (int i = 0; i < nodes.size(); i++) {
            Node current = nodes.get(i);
            Node previous = nodes.get(j);
            if (current.getCoor() == null || previous.getCoor() == null) {
                j = i;
                continue;
            }

            double xi = current.lon();
            double yi = current.lat();
            double xj = previous.lon();
            double yj = previous.lat();

            boolean intersects = ((yi > testY) != (yj > testY))
                && (testX < ((xj - xi) * (testY - yi) / (yj - yi + CLICK_EPSILON)) + xi);
            if (intersects) {
                inside = !inside;
            }
            j = i;
        }

        return inside;
    }

    private ClickIntentResolution resolveClickIntent(DataSet dataSet, LatLon clickPoint) {
        List<FirstClickSelection> firstClickCandidates = findFirstClickCandidates(dataSet, clickPoint);
        if (!firstClickCandidates.isEmpty()) {
            if (isAmbiguousByDistance(firstClickCandidates.get(0).distance(), firstClickCandidates, FirstClickSelection::distance)) {
                return ClickIntentResolution.ambiguous(
                    tr("Click is ambiguous near multiple corner nodes. Please click closer to one corner.")
                );
            }
            return ClickIntentResolution.manual();
        }

        List<EdgeManualCandidate> edgeCandidates = findManualEdgeCandidates(dataSet, clickPoint);
        if (!edgeCandidates.isEmpty()) {
            if (isAmbiguousByDistance(edgeCandidates.get(0).distance(), edgeCandidates, EdgeManualCandidate::distance)) {
                return ClickIntentResolution.ambiguous(
                    tr("Click is ambiguous near multiple building edges. Please click closer to one edge.")
                );
            }
            return ClickIntentResolution.manual();
        }

        Way clickedBuilding = findSingleClickedBuilding(dataSet, clickPoint, false);
        if (clickedBuilding == null) {
            List<Way> containingBuildings = findContainingBuildings(dataSet, clickPoint);
            if (containingBuildings.size() > 1) {
                return ClickIntentResolution.ambiguous(
                    tr("Multiple buildings match this click. Please click a less ambiguous location.")
                );
            }
            return ClickIntentResolution.none();
        }

        double minEdgeDistance = minDistanceToWayEdgesMeters(clickedBuilding, clickPoint);
        double minCornerDistance = minDistanceToWayCornersMeters(clickedBuilding, clickPoint);
        if (minEdgeDistance <= AUTOSPLIT_INTERIOR_MARGIN_METERS || minCornerDistance <= AUTOSPLIT_INTERIOR_MARGIN_METERS) {
            return ClickIntentResolution.ambiguous(
                tr("Click is too close to the building boundary. Use manual split near edges/corners or click deeper inside for AutoSplit.")
            );
        }

        return ClickIntentResolution.autoSplit(clickedBuilding);
    }

    private List<Way> findContainingBuildings(DataSet dataSet, LatLon clickPoint) {
        List<Way> containingBuildings = new ArrayList<>();
        for (Way way : dataSet.getWays()) {
            if (way == null || way.isDeleted() || !way.isClosed() || !way.hasKey("building")) {
                continue;
            }
            if (containsPoint(way, clickPoint)) {
                containingBuildings.add(way);
            }
        }
        containingBuildings.sort(Comparator.comparingLong(Way::getUniqueId));
        return containingBuildings;
    }

    private List<EdgeManualCandidate> findManualEdgeCandidates(DataSet dataSet, LatLon clickPoint) {
        List<EdgeManualCandidate> candidates = new ArrayList<>();
        for (Way way : dataSet.getWays()) {
            if (way == null || way.isDeleted() || !way.isClosed() || !way.hasKey("building")) {
                continue;
            }

            for (SegmentCandidate segmentCandidate : findSegmentCandidates(way, clickPoint)) {
                candidates.add(new EdgeManualCandidate(way, segmentCandidate.segmentIndex(), segmentCandidate.distance()));
            }
        }

        candidates.sort(Comparator
            .comparingDouble(EdgeManualCandidate::distance)
            .thenComparingLong(candidate -> candidate.way().getUniqueId())
            .thenComparingInt(EdgeManualCandidate::segmentIndex));
        return candidates;
    }

    private double minDistanceToWayEdgesMeters(Way way, LatLon clickPoint) {
        List<Node> ringNodes = new ArrayList<>(way.getNodes());
        if (ringNodes.size() > 1 && ringNodes.get(0).equals(ringNodes.get(ringNodes.size() - 1))) {
            ringNodes.remove(ringNodes.size() - 1);
        }

        double minDistance = Double.POSITIVE_INFINITY;
        for (int i = 0; i < ringNodes.size(); i++) {
            Node start = ringNodes.get(i);
            Node end = ringNodes.get((i + 1) % ringNodes.size());
            if (start.getCoor() == null || end.getCoor() == null) {
                continue;
            }
            minDistance = Math.min(minDistance, pointToSegmentDistanceMeters(clickPoint, start.getCoor(), end.getCoor()));
        }
        return minDistance;
    }

    private double minDistanceToWayCornersMeters(Way way, LatLon clickPoint) {
        List<Node> ringNodes = new ArrayList<>(way.getNodes());
        if (ringNodes.size() > 1 && ringNodes.get(0).equals(ringNodes.get(ringNodes.size() - 1))) {
            ringNodes.remove(ringNodes.size() - 1);
        }

        double minDistance = Double.POSITIVE_INFINITY;
        for (Node node : ringNodes) {
            if (node.getCoor() == null) {
                continue;
            }
            minDistance = Math.min(minDistance, distanceMeters(node.getCoor(), clickPoint));
        }
        return minDistance;
    }

    private void openAutoSplitDialogForBuilding(DataSet dataSet, Way buildingWay) {
        dataSet.setSelected(Collections.singleton(buildingWay));
        AutoSplitPreviewSession previewSession = new AutoSplitPreviewSession(
            dataSet,
            buildingWay,
            autoSplitService,
            houseNumberService
        );

        List<String> visibleStreetNames = visibleAddressContextService.collectVisibleStreetNames(dataSet);
        String suggestedPostcode = visibleAddressContextService.detectUniformVisiblePostcode(dataSet);
        AddressContextBridge.AddressContext externalContext = AddressContextBridge.consumeAddressContext();
        String externalStreet = externalContext == null ? "" : externalContext.getStreet();
        String externalPostcode = externalContext == null ? "" : externalContext.getPostcode();
        debugContext("consumeAddressContext present=" + (externalContext != null)
            + " street='" + externalStreet + "' postcode='" + externalPostcode + "'");
        String streetSource = !externalStreet.isEmpty() ? "external" : "remembered";
        String postcodeSource = !externalPostcode.isEmpty()
            ? "external"
            : (!lastAutoSplitPostcode.isEmpty() ? "remembered" : "visible");
        String defaultStreet = !externalStreet.isEmpty() ? externalStreet : lastAutoSplitStreet;
        String defaultPostcode = !externalPostcode.isEmpty()
            ? externalPostcode
            : (!lastAutoSplitPostcode.isEmpty() ? lastAutoSplitPostcode : suggestedPostcode);
        debugContext("dialog defaults street='" + defaultStreet + "' (" + streetSource + ")"
            + " postcode='" + defaultPostcode + "' (" + postcodeSource + ")");

        AutoSplitDialogResult dialogResult = autoSplitOptionsDialog.showDialog(
            MainApplication.getMainFrame(),
            lastAutoSplitParts,
            lastAutoSplitIncrement,
            lastAutoSplitReverseOrder,
            lastAutoSplitFirstWithoutLetter,
            lastAutoSplitStartHouseNumber,
            defaultStreet,
            defaultPostcode,
            visibleStreetNames,
            previewSession::refreshPreview
        );

        if (dialogResult.isCancel() || dialogResult.isSkip()) {
            previewSession.undoPreview();
            return;
        }

        SplitResult result = previewSession.finalizePreview(dialogResult);
        if (!result.isSuccess()) {
            previewSession.undoPreview();
            showError(result.getMessage());
            return;
        }

        lastAutoSplitParts = dialogResult.getParts();
        lastAutoSplitIncrement = dialogResult.getIncrement();
        lastAutoSplitReverseOrder = dialogResult.isReverseOrder();
        lastAutoSplitFirstWithoutLetter = dialogResult.isFirstWithoutLetter();
        lastAutoSplitStartHouseNumber = dialogResult.getStartHouseNumber();
        lastAutoSplitStreet = dialogResult.getStreet();
        lastAutoSplitPostcode = dialogResult.getPostcode();

        List<Way> createdWays = result.getCreatedWays();
        if (!createdWays.isEmpty()) {
            dataSet.setSelected(createdWays);
        }
        Logging.info("SplitBuildingMapMode (Ctrl AutoSplit): " + result.getMessage());
    }

    private void debugContext(String message) {
        if (!DEBUG_CONTEXT_TRANSFER) {
            return;
        }
        String fullMessage = "BuildingSplitter DEBUG (SplitMapMode): " + message;
        Logging.info(fullMessage);
    }

    private LatLon toLatLon(MouseEvent e) {
        if (MainApplication.getMap() == null || MainApplication.getMap().mapView == null) {
            return null;
        }
        return MainApplication.getMap().mapView.getLatLon(e.getX(), e.getY());
    }

    private boolean isValidClickedPoint(LatLon point) {
        return point != null && point.isValid();
    }

    private void finishSplit(DataSet dataSet, LatLon start, LatLon end) {
        SplitCandidate candidate = findUniqueSplitCandidate(dataSet, start, end);
        if (candidate == null) {
            return;
        }

        Way buildingWay = candidate.buildingWay();
        List<IntersectionPoint> intersections = candidate.intersections();

        SplitNodePreparationService.PreparedIntersectionNodes prepared =
            splitNodePreparationService.prepareIntersectionNodes(dataSet, buildingWay, intersections);
        List<Node> splitNodes = prepared.getSplitNodes();
        if (splitNodes.size() != 2 || splitNodes.get(0).equals(splitNodes.get(1))) {
            showError(tr("Failed to resolve two distinct split nodes."));
            return;
        }

        int firstIndex = prepared.getUpdatedWayNodes().indexOf(splitNodes.get(0));
        int secondIndex = prepared.getUpdatedWayNodes().indexOf(splitNodes.get(1));
        if (firstIndex < 0 || secondIndex < 0 || firstIndex >= secondIndex) {
            showError(tr("Failed to order split nodes along the building way."));
            return;
        }

        if (splitNodePreparationService.areAdjacentInClosedNodeList(
            prepared.getUpdatedWayNodes(),
            splitNodes.get(0),
            splitNodes.get(1)
        )) {
            showError(tr("The split line produced adjacent split nodes. Please draw a line that crosses two different building edges."));
            return;
        }

        if (!prepared.getCommands().isEmpty()) {
            UndoRedoHandler.getInstance().add(
                new SequenceCommand(tr("Insert split intersection nodes"), prepared.getCommands())
            );
        }

        List<OsmPrimitive> splitSelection = new ArrayList<>();
        splitSelection.add(buildingWay);
        splitSelection.add(splitNodes.get(0));
        splitSelection.add(splitNodes.get(1));
        dataSet.setSelected(splitSelection);

        SplitExecutionResult splitResult = splitService.splitSelectedBuildingDetailed(dataSet);
        if (!splitResult.isSuccess()) {
            showError(splitResult.getMessage());
            return;
        }

        List<Way> createdWays = splitResult.getResultWaysOrdered();
        if (createdWays.size() != 2 || createdWays.get(0).isDeleted() || createdWays.get(1).isDeleted()) {
            showError(tr("Split completed but resulting building ways could not be selected."));
            return;
        }

        dataSet.setSelected(createdWays);
        Logging.info("SplitBuildingMapMode: " + splitResult.getMessage());
    }

    private SplitCandidate findUniqueSplitCandidate(DataSet dataSet, LatLon start, LatLon end) {
        List<SplitCandidate> candidates = new ArrayList<>();
        List<String> targetedFailureMessages = new ArrayList<>();

        for (Way way : dataSet.getWays()) {
            if (way.isDeleted() || !way.isClosed() || !way.hasKey("building")) {
                continue;
            }

            IntersectionResult intersectionResult = intersectionService.findSplitIntersections(way, start, end);
            if (!intersectionResult.isSuccess()) {
                if (!isNoIntersectionFailure(intersectionResult) && intersectionResult.getMessage() != null) {
                    targetedFailureMessages.add(intersectionResult.getMessage());
                }
                continue;
            }

            List<IntersectionPoint> intersections = intersectionResult.getIntersections();
            if (intersections.size() == 2) {
                candidates.add(new SplitCandidate(way, intersections));
            }
        }

        if (candidates.isEmpty()) {
            if (targetedFailureMessages.size() == 1) {
                showError(targetedFailureMessages.get(0));
                return null;
            }
            showError(tr("No building could be split with this line. Draw a line that crosses a single building."));
            return null;
        }

        if (candidates.size() > 1) {
            showError(tr("The line crosses multiple buildings. Please draw a line that crosses only one building."));
            return null;
        }

        return candidates.get(0);
    }

    private boolean isNoIntersectionFailure(IntersectionResult intersectionResult) {
        return tr("Line does not intersect building").equals(intersectionResult.getMessage());
    }

    private boolean isSamePoint(LatLon first, LatLon second) {
        return Math.abs(first.lat() - second.lat()) <= CLICK_EPSILON
            && Math.abs(first.lon() - second.lon()) <= CLICK_EPSILON;
    }

    boolean isDragSplitGesture(LatLon pressPoint, LatLon releasePoint) {
        return pressPoint != null && releasePoint != null && !isSamePoint(pressPoint, releasePoint);
    }

    ClickIntentResolution resolveClickIntentForTesting(DataSet dataSet, LatLon clickPoint) {
        return resolveClickIntent(dataSet, clickPoint);
    }

    boolean canResolveManualFirstClickForTesting(DataSet dataSet, LatLon clickPoint) {
        return resolveFirstClick(dataSet, clickPoint, false) != null;
    }

    boolean canResolveManualSecondClickForTesting(Way way, LatLon clickPoint) {
        return resolveSecondClick(way, clickPoint, false) != null;
    }

    private void updateSnapFeedback() {
        snapCandidates.clear();
        if (dragStart == null || dragCurrent == null || !snappingEnabled) {
            return;
        }

        double lineLengthMeters = distanceMeters(dragStart, dragCurrent);
        if (lineLengthMeters > SNAP_FEEDBACK_MAX_LINE_LENGTH_METERS) {
            snappingEnabled = false;
            return;
        }

        DataSet dataSet = MainApplication.getLayerManager().getEditDataSet();
        if (dataSet == null) {
            return;
        }

        Way targetWay = resolveSnapTargetWay(dataSet, dragStart, dragCurrent);
        if (targetWay == null) {
            return;
        }

        snapCandidates = findNearestCornerCandidates(targetWay, dragStart, dragCurrent);
    }

    private Way resolveSnapTargetWay(DataSet dataSet, LatLon lineStart, LatLon lineEnd) {
        Way selectedBuilding = getSingleSelectedBuilding(dataSet);
        if (selectedBuilding != null) {
            return selectedBuilding;
        }

        List<Way> crossedBuildings = new ArrayList<>();
        for (Way way : dataSet.getWays()) {
            if (way == null || way.isDeleted() || !way.isClosed() || !way.hasKey("building")) {
                continue;
            }

            IntersectionResult intersectionResult = intersectionService.findSplitIntersections(way, lineStart, lineEnd);
            if (isLikelyLineTarget(intersectionResult)) {
                crossedBuildings.add(way);
            }
        }

        if (crossedBuildings.size() == 1) {
            return crossedBuildings.get(0);
        }
        return null;
    }

    private boolean isLikelyLineTarget(IntersectionResult intersectionResult) {
        if (intersectionResult == null) {
            return false;
        }
        if (intersectionResult.isSuccess()) {
            return !intersectionResult.getIntersections().isEmpty();
        }

        String message = intersectionResult.getMessage();
        return tr("Line touches building only once").equals(message)
            || tr("Line intersects building multiple times; not supported").equals(message)
            || tr("Line overlaps building edge; not supported").equals(message);
    }

    private Way getSingleSelectedBuilding(DataSet dataSet) {
        List<Way> selectedWays = new ArrayList<>(dataSet.getSelectedWays());
        if (selectedWays.size() != 1) {
            return null;
        }
        Way way = selectedWays.get(0);
        if (way.isDeleted() || !way.isClosed() || !way.hasKey("building")) {
            return null;
        }
        return way;
    }

    private List<Node> findNearestCornerCandidates(Way way, LatLon lineStart, LatLon lineEnd) {
        List<Node> ringNodes = new ArrayList<>(way.getNodes());
        if (ringNodes.size() > 1 && ringNodes.get(0).equals(ringNodes.get(ringNodes.size() - 1))) {
            ringNodes.remove(ringNodes.size() - 1);
        }

        List<NodeDistance> nearby = new ArrayList<>();
        for (Node node : ringNodes) {
            if (node.getCoor() == null) {
                continue;
            }

            double distance = pointToSegmentDistanceMeters(node.getCoor(), lineStart, lineEnd);
            if (distance <= SNAP_FEEDBACK_CORNER_DISTANCE_METERS) {
                nearby.add(new NodeDistance(node, distance));
            }
        }

        nearby.sort(Comparator
            .comparingDouble(NodeDistance::distance)
            .thenComparingLong(nd -> nd.node().getUniqueId()));

        return nearby.stream()
            .limit(2)
            .map(NodeDistance::node)
            .collect(Collectors.toList());
    }

    private double distanceMeters(LatLon first, LatLon second) {
        if (first == null || second == null) {
            return Double.POSITIVE_INFINITY;
        }

        if (ProjectionRegistry.getProjection() != null) {
            EastNorth firstEn = ProjectionRegistry.getProjection().latlon2eastNorth(first);
            EastNorth secondEn = ProjectionRegistry.getProjection().latlon2eastNorth(second);
            if (firstEn != null && secondEn != null) {
                return firstEn.distance(secondEn);
            }
        }

        return distanceMetersFallback(first, second);
    }

    private double pointToSegmentDistanceMeters(LatLon point, LatLon segmentStart, LatLon segmentEnd) {
        if (point == null || segmentStart == null || segmentEnd == null) {
            return Double.POSITIVE_INFINITY;
        }

        if (ProjectionRegistry.getProjection() != null) {
            EastNorth p = ProjectionRegistry.getProjection().latlon2eastNorth(point);
            EastNorth a = ProjectionRegistry.getProjection().latlon2eastNorth(segmentStart);
            EastNorth b = ProjectionRegistry.getProjection().latlon2eastNorth(segmentEnd);
            if (p != null && a != null && b != null) {
                return pointToSegmentDistance(p.east(), p.north(), a.east(), a.north(), b.east(), b.north());
            }
        }

        double referenceLatRad = Math.toRadians(segmentStart.lat());
        XY p = toLocalMeters(point, referenceLatRad);
        XY a = toLocalMeters(segmentStart, referenceLatRad);
        XY b = toLocalMeters(segmentEnd, referenceLatRad);
        return pointToSegmentDistance(p.x, p.y, a.x, a.y, b.x, b.y);
    }

    private double pointToSegmentDistance(double px, double py, double ax, double ay, double bx, double by) {
        double abx = bx - ax;
        double aby = by - ay;
        double abSquared = (abx * abx) + (aby * aby);
        if (abSquared <= CLICK_EPSILON) {
            return Math.hypot(px - ax, py - ay);
        }

        double t = ((px - ax) * abx + (py - ay) * aby) / abSquared;
        t = Math.max(0.0, Math.min(1.0, t));
        double closestX = ax + (t * abx);
        double closestY = ay + (t * aby);
        return Math.hypot(px - closestX, py - closestY);
    }

    private double distanceMetersFallback(LatLon first, LatLon second) {
        double referenceLatRad = Math.toRadians((first.lat() + second.lat()) / 2.0);
        XY a = toLocalMeters(first, referenceLatRad);
        XY b = toLocalMeters(second, referenceLatRad);
        return Math.hypot(a.x - b.x, a.y - b.y);
    }

    private XY toLocalMeters(LatLon point, double referenceLatRad) {
        double x = Math.toRadians(point.lon()) * EARTH_RADIUS_METERS * Math.cos(referenceLatRad);
        double y = Math.toRadians(point.lat()) * EARTH_RADIUS_METERS;
        return new XY(x, y);
    }

    private LatLon fromLocalMeters(XY point, double referenceLatRad) {
        double lat = Math.toDegrees(point.y / EARTH_RADIUS_METERS);
        double lon = Math.toDegrees(point.x / (EARTH_RADIUS_METERS * Math.cos(referenceLatRad)));
        return new LatLon(lat, lon);
    }

    private void resetDragState() {
        dragStart = null;
        dragCurrent = null;
        snapCandidates.clear();
        snappingEnabled = false;
    }

    private void resetState() {
        resetDragState();
        clearClickSelection();
    }

    private void repaintMapView() {
        if (MainApplication.getMap() != null && MainApplication.getMap().mapView != null) {
            MainApplication.getMap().mapView.repaint();
        }
    }

    private void registerEscapeHandler() {
        if (escKeyDispatcher != null) {
            return;
        }

        escKeyDispatcher = event -> {
            if (!isActiveMapMode()) {
                return false;
            }

            if (event.getID() != KeyEvent.KEY_PRESSED || event.getKeyCode() != KeyEvent.VK_ESCAPE) {
                return false;
            }

            handleEscapePressed();
            return true;
        };

        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(escKeyDispatcher);
    }

    private void unregisterEscapeHandler() {
        if (escKeyDispatcher == null) {
            return;
        }

        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(escKeyDispatcher);
        escKeyDispatcher = null;
    }

    private boolean isActiveMapMode() {
        return MainApplication.getMap() != null && MainApplication.getMap().mapMode == this;
    }

    private void handleEscapePressed() {
        resetState();
        repaintMapView();
        if (MainApplication.getMap() != null) {
            MainApplication.getMap().selectSelectTool(false);
        }
    }

    private void updateMapCursor() {
        if (MainApplication.getMap() == null || MainApplication.getMap().mapView == null) {
            return;
        }
        MainApplication.getMap().mapView.setCursor(MANUAL_CURSOR);
    }

    private void updateMapModeTooltip() {
        if (MainApplication.getMap() == null || MainApplication.getMap().mapView == null) {
            return;
        }
        MainApplication.getMap().mapView.setToolTipText(MODE_TOOLTIP);
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(
            MainApplication.getMainFrame(),
            message,
            tr("Split Building"),
            JOptionPane.ERROR_MESSAGE
        );
    }

    private static final class SplitCandidate {
        private final Way buildingWay;
        private final List<IntersectionPoint> intersections;

        private SplitCandidate(Way buildingWay, List<IntersectionPoint> intersections) {
            this.buildingWay = buildingWay;
            this.intersections = intersections;
        }

        private Way buildingWay() {
            return buildingWay;
        }

        private List<IntersectionPoint> intersections() {
            return intersections;
        }
    }

    private static final class FirstClickSelection {
        private final Way way;
        private final Node node;
        private final double distance;

        private FirstClickSelection(Way way, Node node, double distance) {
            this.way = way;
            this.node = node;
            this.distance = distance;
        }

        private Way way() {
            return way;
        }

        private Node node() {
            return node;
        }

        private double distance() {
            return distance;
        }
    }

    private static final class SegmentProjection {
        private final LatLon projectedPoint;
        private final double distanceMeters;

        private SegmentProjection(LatLon projectedPoint, double distanceMeters) {
            this.projectedPoint = projectedPoint;
            this.distanceMeters = distanceMeters;
        }

        private LatLon projectedPoint() {
            return projectedPoint;
        }

        private double distanceMeters() {
            return distanceMeters;
        }
    }

    private static final class SegmentCandidate {
        private final int segmentIndex;
        private final LatLon projectedPoint;
        private final double distance;
        private final Node existingEndpoint;

        private SegmentCandidate(int segmentIndex, LatLon projectedPoint, double distance, Node existingEndpoint) {
            this.segmentIndex = segmentIndex;
            this.projectedPoint = projectedPoint;
            this.distance = distance;
            this.existingEndpoint = existingEndpoint;
        }

        private int segmentIndex() {
            return segmentIndex;
        }

        private LatLon projectedPoint() {
            return projectedPoint;
        }

        private double distance() {
            return distance;
        }

        private Node existingEndpoint() {
            return existingEndpoint;
        }
    }

    private static final class EdgeManualCandidate {
        private final Way way;
        private final int segmentIndex;
        private final double distance;

        private EdgeManualCandidate(Way way, int segmentIndex, double distance) {
            this.way = way;
            this.segmentIndex = segmentIndex;
            this.distance = distance;
        }

        private Way way() {
            return way;
        }

        private int segmentIndex() {
            return segmentIndex;
        }

        private double distance() {
            return distance;
        }
    }

    enum ClickIntentKind {
        MANUAL,
        AUTOSPLIT_INTERIOR,
        AMBIGUOUS,
        NONE
    }

    static final class ClickIntentResolution {
        private final ClickIntentKind kind;
        private final Way autoSplitWay;
        private final String message;

        private ClickIntentResolution(ClickIntentKind kind, Way autoSplitWay, String message) {
            this.kind = kind;
            this.autoSplitWay = autoSplitWay;
            this.message = message;
        }

        private static ClickIntentResolution manual() {
            return new ClickIntentResolution(ClickIntentKind.MANUAL, null, null);
        }

        private static ClickIntentResolution autoSplit(Way way) {
            return new ClickIntentResolution(ClickIntentKind.AUTOSPLIT_INTERIOR, way, null);
        }

        private static ClickIntentResolution ambiguous(String message) {
            return new ClickIntentResolution(ClickIntentKind.AMBIGUOUS, null, message);
        }

        private static ClickIntentResolution none() {
            return new ClickIntentResolution(ClickIntentKind.NONE, null, null);
        }

        ClickIntentKind kind() {
            return kind;
        }

        Way autoSplitWay() {
            return autoSplitWay;
        }

        String message() {
            return message;
        }
    }

    private static final class SecondClickResolution {
        private final Node existingNode;
        private final IntersectionPoint intersectionToInsert;
        private final LatLon coordinate;

        private SecondClickResolution(Node existingNode, IntersectionPoint intersectionToInsert, LatLon coordinate) {
            this.existingNode = existingNode;
            this.intersectionToInsert = intersectionToInsert;
            this.coordinate = coordinate;
        }

        private Node existingNode() {
            return existingNode;
        }

        private IntersectionPoint intersectionToInsert() {
            return intersectionToInsert;
        }

        private LatLon coordinate() {
            return coordinate;
        }
    }

    private static final class XY {
        private final double x;
        private final double y;

        private XY(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }

    private static final class NodeDistance {
        private final Node node;
        private final double distance;

        private NodeDistance(Node node, double distance) {
            this.node = node;
            this.distance = distance;
        }

        private Node node() {
            return node;
        }

        private double distance() {
            return distance;
        }
    }

    private final class PreviewLinePaintable implements MapViewPaintable {
        @Override
        public void paint(Graphics2D g, MapView mapView, Bounds bounds) {
            if (mapView == null) {
                return;
            }

            if (dragStart != null && dragCurrent != null) {
                Point startPoint = mapView.getPoint(dragStart);
                Point currentPoint = mapView.getPoint(dragCurrent);
                if (startPoint != null && currentPoint != null) {
                    g.setColor(new Color(0, 120, 215));
                    g.setStroke(new BasicStroke(2.0f));
                    g.drawLine(startPoint.x, startPoint.y, currentPoint.x, currentPoint.y);
                }
            }

            LatLon secondPreview = clickSecondPreviewNode != null && clickSecondPreviewNode.getCoor() != null
                ? clickSecondPreviewNode.getCoor()
                : clickSecondPreviewPoint;

            if (clickFirstNode != null && clickFirstNode.getCoor() != null && secondPreview != null) {
                Point firstPreviewPoint = mapView.getPoint(clickFirstNode.getCoor());
                Point secondPreviewPointMap = mapView.getPoint(secondPreview);
                if (firstPreviewPoint != null && secondPreviewPointMap != null) {
                    g.setColor(new Color(0, 120, 215, 170));
                    g.setStroke(new BasicStroke(
                        2.0f,
                        BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND,
                        0.0f,
                        new float[] {6.0f, 4.0f},
                        0.0f
                    ));
                    g.drawLine(firstPreviewPoint.x, firstPreviewPoint.y, secondPreviewPointMap.x, secondPreviewPointMap.y);
                }
            }

            if (clickFirstNode != null && clickFirstNode.getCoor() != null) {
                Point firstPoint = mapView.getPoint(clickFirstNode.getCoor());
                if (firstPoint != null) {
                    int radius = 10;
                    g.setColor(new Color(245, 170, 60, 220));
                    g.setStroke(new BasicStroke(3.0f));
                    g.drawOval(firstPoint.x - radius, firstPoint.y - radius, radius * 2, radius * 2);
                }
            }
            if (secondPreview != null) {
                Point secondPoint = mapView.getPoint(secondPreview);
                if (secondPoint != null) {
                    int radius = 8;
                    g.setColor(new Color(255, 220, 90, 210));
                    g.setStroke(new BasicStroke(2.0f));
                    g.drawOval(secondPoint.x - radius, secondPoint.y - radius, radius * 2, radius * 2);
                }
            }

            for (Node snapCandidate : snapCandidates) {
                if (snapCandidate == null || snapCandidate.getCoor() == null) {
                    continue;
                }
                Point snapPoint = mapView.getPoint(snapCandidate.getCoor());
                if (snapPoint != null) {
                    int radius = 9;
                    g.setColor(new Color(84, 180, 84, 210));
                    g.setStroke(new BasicStroke(2.0f));
                    g.drawOval(snapPoint.x - radius, snapPoint.y - radius, radius * 2, radius * 2);
                }
            }
        }
    }
}

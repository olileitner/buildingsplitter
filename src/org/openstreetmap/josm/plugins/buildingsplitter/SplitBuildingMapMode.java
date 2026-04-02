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
    private static final double EARTH_RADIUS_METERS = 6_371_000.0;
    private static final String MANUAL_MODE_TOOLTIP = tr("Manual split mode: drag a line across one building");
    private static final String AUTOSPLIT_MODE_TOOLTIP = tr("AutoSplit mode (Ctrl): click inside a building");
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
    private final PreviewLinePaintable previewLinePaintable;

    private LatLon dragStart;
    private LatLon dragCurrent;
    private Node snapCandidate;
    private boolean snappingEnabled;
    private boolean ctrlAutoSplitMode;

    private int lastAutoSplitParts = 2;
    private int lastAutoSplitIncrement = 1;
    private boolean lastAutoSplitReverseOrder;
    private boolean lastAutoSplitFirstWithoutLetter;
    private String lastAutoSplitStartHouseNumber = "";

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
        this.previewLinePaintable = new PreviewLinePaintable();
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
            updateTemporaryMode(false);
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

        updateTemporaryMode(e.isControlDown());
        if (ctrlAutoSplitMode) {
            resetState();
            repaintMapView();
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
        snapCandidate = null;
        repaintMapView();
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        updateTemporaryMode(e.isControlDown());
        if (ctrlAutoSplitMode) {
            resetState();
            repaintMapView();
            return;
        }

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
        updateTemporaryMode(e.isControlDown());
        if (e.getButton() != MouseEvent.BUTTON1) {
            resetState();
            repaintMapView();
            return;
        }

        if (ctrlAutoSplitMode) {
            handleAutoSplitClick(e);
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
        if (isSamePoint(dragStart, dragCurrent)) {
            showError(tr("Please drag a line with two different points."));
            resetState();
            repaintMapView();
            return;
        }

        finishSplit(dataSet, dragStart, dragCurrent);
        resetState();
        repaintMapView();
    }

    private void handleAutoSplitClick(MouseEvent e) {
        DataSet dataSet = MainApplication.getLayerManager().getEditDataSet();
        if (dataSet == null) {
            showError(tr("No editable dataset is available."));
            return;
        }

        LatLon clickPoint = toLatLon(e);
        if (!isValidClickedPoint(clickPoint)) {
            showError(tr("Unable to read clicked map location. Please click inside the map view."));
            return;
        }

        Way clickedBuilding = findSingleClickedBuilding(dataSet, clickPoint);
        if (clickedBuilding == null) {
            return;
        }

        openAutoSplitDialogForBuilding(dataSet, clickedBuilding);
    }

    private Way findSingleClickedBuilding(DataSet dataSet, LatLon clickPoint) {
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
            showError(tr("No building found at the clicked location."));
            return null;
        }

        if (containingBuildings.size() > 1) {
            containingBuildings.sort(Comparator.comparingLong(Way::getUniqueId));
            showError(tr("Multiple buildings match this click. Please click a less ambiguous location."));
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

    private void openAutoSplitDialogForBuilding(DataSet dataSet, Way buildingWay) {
        dataSet.setSelected(Collections.singleton(buildingWay));
        AutoSplitPreviewSession previewSession = new AutoSplitPreviewSession(
            dataSet,
            buildingWay,
            autoSplitService,
            houseNumberService
        );

        AutoSplitDialogResult dialogResult = autoSplitOptionsDialog.showDialog(
            MainApplication.getMainFrame(),
            lastAutoSplitParts,
            lastAutoSplitIncrement,
            lastAutoSplitReverseOrder,
            lastAutoSplitFirstWithoutLetter,
            lastAutoSplitStartHouseNumber,
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

        List<Way> createdWays = result.getCreatedWays();
        if (!createdWays.isEmpty()) {
            dataSet.setSelected(createdWays);
        }
        Logging.info("SplitBuildingMapMode (Ctrl AutoSplit): " + result.getMessage());
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

        SplitResult splitResult = splitService.splitSelectedBuilding(dataSet);
        if (!splitResult.isSuccess()) {
            showError(splitResult.getMessage());
            return;
        }

        List<Way> createdWays = splitResult.getCreatedWays();
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

    private void updateSnapFeedback() {
        snapCandidate = null;
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

        snapCandidate = findNearestCornerCandidate(targetWay, dragStart, dragCurrent);
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

    private Node findNearestCornerCandidate(Way way, LatLon lineStart, LatLon lineEnd) {
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

            double distance = pointToSegmentDistanceMeters(node.getCoor(), lineStart, lineEnd);
            if (distance <= SNAP_FEEDBACK_CORNER_DISTANCE_METERS && distance < nearestDistance) {
                nearest = node;
                nearestDistance = distance;
            }
        }
        return nearest;
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

    private void resetState() {
        dragStart = null;
        dragCurrent = null;
        snapCandidate = null;
        snappingEnabled = false;
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

            if (event.getKeyCode() == KeyEvent.VK_CONTROL) {
                if (event.getID() == KeyEvent.KEY_PRESSED) {
                    updateTemporaryMode(true);
                } else if (event.getID() == KeyEvent.KEY_RELEASED) {
                    updateTemporaryMode(false);
                }
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
        updateTemporaryMode(false);
        repaintMapView();
        if (MainApplication.getMap() != null) {
            MainApplication.getMap().selectSelectTool(false);
        }
    }

    private void updateTemporaryMode(boolean ctrlPressed) {
        if (ctrlAutoSplitMode == ctrlPressed) {
            return;
        }
        ctrlAutoSplitMode = ctrlPressed;
        if (ctrlAutoSplitMode) {
            resetState();
        }
        updateMapCursor();
        updateMapModeTooltip();
    }

    private void updateMapCursor() {
        if (MainApplication.getMap() == null || MainApplication.getMap().mapView == null) {
            return;
        }
        MainApplication.getMap().mapView.setCursor(ctrlAutoSplitMode ? AUTOSPLIT_CURSOR : MANUAL_CURSOR);
    }

    private void updateMapModeTooltip() {
        if (MainApplication.getMap() == null || MainApplication.getMap().mapView == null) {
            return;
        }
        MainApplication.getMap().mapView.setToolTipText(ctrlAutoSplitMode ? AUTOSPLIT_MODE_TOOLTIP : MANUAL_MODE_TOOLTIP);
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

    private static final class XY {
        private final double x;
        private final double y;

        private XY(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }

    private final class PreviewLinePaintable implements MapViewPaintable {
        @Override
        public void paint(Graphics2D g, MapView mapView, Bounds bounds) {
            if (dragStart == null || dragCurrent == null || mapView == null) {
                return;
            }

            Point startPoint = mapView.getPoint(dragStart);
            Point currentPoint = mapView.getPoint(dragCurrent);
            if (startPoint == null || currentPoint == null) {
                return;
            }

            g.setColor(new Color(0, 120, 215));
            g.setStroke(new BasicStroke(2.0f));
            g.drawLine(startPoint.x, startPoint.y, currentPoint.x, currentPoint.y);

            if (snapCandidate != null && snapCandidate.getCoor() != null) {
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

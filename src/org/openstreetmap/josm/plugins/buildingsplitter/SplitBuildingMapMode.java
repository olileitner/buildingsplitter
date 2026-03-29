package org.openstreetmap.josm.plugins.buildingsplitter;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JOptionPane;

import org.openstreetmap.josm.actions.mapmode.MapMode;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.layer.MapViewPaintable;
import org.openstreetmap.josm.tools.Shortcut;

public class SplitBuildingMapMode extends MapMode {

    private static final double CLICK_EPSILON = 1e-9;
    private static final Shortcut SPLIT_BUILDING_SHORTCUT = Shortcut.registerShortcut(
        "mapmode:buildingsplitter:splitbuilding",
        tr("Map mode: {0}", tr("Split Building")),
        KeyEvent.VK_S,
        Shortcut.CTRL | Shortcut.ALT | Shortcut.SHIFT
    );

    private final BuildingSplitService splitService;
    private final BuildingIntersectionService intersectionService;
    private final SplitNodePreparationService splitNodePreparationService;
    private final PreviewLinePaintable previewLinePaintable;

    private LatLon dragStart;
    private LatLon dragCurrent;

    public SplitBuildingMapMode() {
        super(
            tr("Split Building"),
            "splitway",
            tr("Drag a line across a building to split it"),
            SPLIT_BUILDING_SHORTCUT,
            Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
        );
        this.splitService = new BuildingSplitService();
        this.intersectionService = new BuildingIntersectionService();
        this.splitNodePreparationService = new SplitNodePreparationService();
        this.previewLinePaintable = new PreviewLinePaintable();
    }

    @Override
    public void enterMode() {
        super.enterMode();
        if (MainApplication.getMap() != null && MainApplication.getMap().mapView != null) {
            MainApplication.getMap().mapView.addMouseListener(this);
            MainApplication.getMap().mapView.addMouseMotionListener(this);
            MainApplication.getMap().mapView.addTemporaryLayer(previewLinePaintable);
        }
    }

    @Override
    public void exitMode() {
        if (MainApplication.getMap() != null && MainApplication.getMap().mapView != null) {
            MainApplication.getMap().mapView.removeMouseListener(this);
            MainApplication.getMap().mapView.removeMouseMotionListener(this);
            MainApplication.getMap().mapView.removeTemporaryLayer(previewLinePaintable);
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
        repaintMapView();
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        if (e.getButton() != MouseEvent.BUTTON1 || dragStart == null) {
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
        System.out.println("SplitBuildingMapMode: " + splitResult.getMessage());
    }

    private SplitCandidate findUniqueSplitCandidate(DataSet dataSet, LatLon start, LatLon end) {
        List<SplitCandidate> candidates = new ArrayList<>();

        for (Way way : dataSet.getWays()) {
            if (way.isDeleted() || !way.isClosed() || !way.hasKey("building")) {
                continue;
            }

            IntersectionResult intersectionResult = intersectionService.findSplitIntersections(way, start, end);
            if (!intersectionResult.isSuccess()) {
                continue;
            }

            List<IntersectionPoint> intersections = intersectionResult.getIntersections();
            if (intersections.size() == 2) {
                candidates.add(new SplitCandidate(way, intersections));
            }
        }

        if (candidates.isEmpty()) {
            showError(tr("No building could be split with this line. Draw a line that crosses a single building."));
            return null;
        }

        if (candidates.size() > 1) {
            showError(tr("The line crosses multiple buildings. Please draw a line that crosses only one building."));
            return null;
        }

        return candidates.get(0);
    }

    private boolean isSamePoint(LatLon first, LatLon second) {
        return Math.abs(first.lat() - second.lat()) <= CLICK_EPSILON
            && Math.abs(first.lon() - second.lon()) <= CLICK_EPSILON;
    }

    private void resetState() {
        dragStart = null;
        dragCurrent = null;
    }

    private void repaintMapView() {
        if (MainApplication.getMap() != null && MainApplication.getMap().mapView != null) {
            MainApplication.getMap().mapView.repaint();
        }
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
        }
    }
}

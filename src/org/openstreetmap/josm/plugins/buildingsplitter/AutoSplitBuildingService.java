package org.openstreetmap.josm.plugins.buildingsplitter;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.openstreetmap.josm.actions.OrthogonalizeAction;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;

public class AutoSplitBuildingService {

    private static final double EPSILON = 1e-9;

    private final BuildingIntersectionService intersectionService;
    private final SplitNodePreparationService splitNodePreparationService;
    private final BuildingSplitService splitService;

    public AutoSplitBuildingService() {
        this.intersectionService = new BuildingIntersectionService();
        this.splitNodePreparationService = new SplitNodePreparationService();
        this.splitService = new BuildingSplitService();
    }

    public SplitResult autoSplitBuilding(DataSet dataSet, Way buildingWay, int parts) {
        final int undoStartSize = UndoRedoHandler.getInstance().getUndoCommands().size();

        if (dataSet == null) {
            return SplitResult.failure(tr("No editable dataset is available."));
        }
        if (buildingWay == null) {
            return SplitResult.failure(tr("No building way selected."));
        }
        if (parts < 2) {
            return SplitResult.failure(tr("Number of parts must be at least 2."));
        }
        if (buildingWay.getDataSet() != dataSet) {
            return SplitResult.failure(tr("Selected building is not part of the current editable dataset."));
        }
        if (!buildingWay.isClosed()) {
            return SplitResult.failure(tr("The selected way must be closed."));
        }
        if (!buildingWay.hasKey("building")) {
            return SplitResult.failure(tr("The selected way must have a building=* tag."));
        }

        List<Node> corners = extractFourCorners(buildingWay);
        if (corners == null) {
            return SplitResult.failure(tr("AutoSplit currently supports only 4-corner buildings."));
        }

        SplitGeometry geometry = buildSplitGeometry(corners);
        if (geometry == null) {
            return SplitResult.failure(tr("Unable to determine AutoSplit direction for this building geometry."));
        }

        List<Double> splitPositions = buildSplitPositions(corners, geometry.mainAxis(), parts);
        if (splitPositions.size() != parts - 1) {
            return SplitResult.failure(tr("Unable to compute internal split positions for AutoSplit."));
        }

        try {
            List<Way> workingWays = new ArrayList<>();
            workingWays.add(buildingWay);

            for (double splitPosition : splitPositions) {
                Way targetWay = findTargetWayForSplitPosition(workingWays, geometry.mainAxis(), splitPosition);
                if (targetWay == null) {
                    return rollbackAndFailure(
                        undoStartSize,
                        tr("Unable to resolve split position to exactly one building segment.")
                    );
                }

                SplitResult splitResult = splitSingleWay(dataSet, targetWay, geometry, splitPosition);
                if (!splitResult.isSuccess()) {
                    return rollbackAndFailure(undoStartSize, splitResult.getMessage());
                }

                List<Way> createdWays = new ArrayList<>(splitResult.getCreatedWays());
                if (createdWays.size() != 2) {
                    return rollbackAndFailure(
                        undoStartSize,
                        tr("AutoSplit failed to create exactly two ways during iterative split.")
                    );
                }

                workingWays.remove(targetWay);
                workingWays.addAll(orderWaysByAxis(createdWays, geometry.mainAxis()));
                workingWays = orderWaysByAxis(workingWays, geometry.mainAxis());
            }

            List<Way> orderedFinalWays = orderWaysByAxis(workingWays, geometry.mainAxis());

            SplitResult finalResult = SplitResult.success(
                tr("AutoSplit completed: building split into {0} parts.", parts),
                orderedFinalWays,
                new SplitResult.SplitAxis(geometry.mainAxis().x(), geometry.mainAxis().y())
            );

            dataSet.setSelected(orderedFinalWays);
            return finalResult;
        } catch (RuntimeException ex) {
            return rollbackAndFailure(undoStartSize, tr("AutoSplit failed unexpectedly. Changes were rolled back."));
        }
    }

    private SplitResult rollbackAndFailure(int undoStartSize, String message) {
        rollbackCommandsAddedSince(undoStartSize);
        return SplitResult.failure(message);
    }

    private void rollbackCommandsAddedSince(int undoStartSize) {
        int undoCount = UndoRedoHandler.getInstance().getUndoCommands().size() - undoStartSize;
        if (undoCount > 0) {
            // Roll back only commands created during this autoSplitBuilding call.
            UndoRedoHandler.getInstance().undo(undoCount);
        }
    }

    private SplitResult splitSingleWay(DataSet dataSet, Way targetWay, SplitGeometry geometry, double splitPosition) {
        Line splitLine = createAutoSplitLine(geometry, splitPosition);
        IntersectionResult intersectionResult =
            intersectionService.findSplitIntersections(targetWay, splitLine.start(), splitLine.end());
        if (!intersectionResult.isSuccess()) {
            return SplitResult.failure(tr("Unable to compute split intersections: {0}", intersectionResult.getMessage()));
        }

        List<IntersectionPoint> intersections = intersectionResult.getIntersections();
        if (intersections.size() != 2) {
            return SplitResult.failure(tr("AutoSplit requires exactly two intersections, but found {0}.", intersections.size()));
        }

        SplitNodePreparationService.PreparedIntersectionNodes prepared =
            splitNodePreparationService.prepareIntersectionNodes(dataSet, targetWay, intersections);
        List<Node> splitNodes = prepared.getSplitNodes();
        if (splitNodes.size() != 2 || splitNodes.get(0).equals(splitNodes.get(1))) {
            return SplitResult.failure(tr("AutoSplit failed to resolve two distinct split nodes."));
        }

        splitNodes = new ArrayList<>(splitNodes);
        List<Node> updatedNodes = prepared.getUpdatedWayNodes();
        splitNodes.sort(Comparator.comparingInt(updatedNodes::indexOf));

        int firstIndex = updatedNodes.indexOf(splitNodes.get(0));
        int secondIndex = updatedNodes.indexOf(splitNodes.get(1));
        if (firstIndex < 0 || secondIndex < 0 || firstIndex >= secondIndex) {
            return SplitResult.failure(tr("AutoSplit failed to order split nodes along the building way."));
        }

        if (splitNodePreparationService.areAdjacentInClosedNodeList(
            updatedNodes,
            splitNodes.get(0),
            splitNodes.get(1)
        )) {
            return SplitResult.failure(tr("AutoSplit produced adjacent split nodes. Try manual splitting for this geometry."));
        }

        if (!prepared.getCommands().isEmpty()) {
            UndoRedoHandler.getInstance().add(
                new SequenceCommand(tr("Insert split intersection nodes"), prepared.getCommands())
            );
        }

        List<OsmPrimitive> selection = new ArrayList<>();
        selection.add(targetWay);
        selection.add(splitNodes.get(0));
        selection.add(splitNodes.get(1));
        dataSet.setSelected(selection);

        return splitService.splitSelectedBuilding(dataSet);
    }

    private SplitResult orthogonalizeCreatedWays(DataSet dataSet, SplitResult splitResult) {
        List<Way> createdWays = splitResult.getCreatedWays();
        if (createdWays.isEmpty()) {
            return splitResult;
        }

        try {
            List<OsmPrimitive> primitivesToOrthogonalize = new ArrayList<>(createdWays);
            SequenceCommand orthogonalizeCommand = OrthogonalizeAction.orthogonalize(primitivesToOrthogonalize);
            if (orthogonalizeCommand != null) {
                UndoRedoHandler.getInstance().add(orthogonalizeCommand);
            }
        } catch (OrthogonalizeAction.InvalidUserInputException ex) {
            // Keep split success even when orthogonalization input is rejected.
        } catch (RuntimeException ex) {
            // Keep split success even when orthogonalization fails unexpectedly.
        }

        dataSet.setSelected(createdWays);
        return splitResult;
    }

    private List<Double> buildSplitPositions(List<Node> corners, Vector2D axis, int parts) {
        double axisMin = Double.POSITIVE_INFINITY;
        double axisMax = Double.NEGATIVE_INFINITY;

        for (Node corner : corners) {
            double projection = projectOnAxis(corner, axis);
            axisMin = Math.min(axisMin, projection);
            axisMax = Math.max(axisMax, projection);
        }

        double range = axisMax - axisMin;
        if (range <= EPSILON) {
            return new ArrayList<>();
        }

        double step = range / parts;
        List<Double> positions = new ArrayList<>();
        for (int i = 1; i < parts; i++) {
            positions.add(axisMin + (step * i));
        }
        return positions;
    }

    private Way findTargetWayForSplitPosition(List<Way> candidates, Vector2D axis, double splitPosition) {
        List<Way> matches = new ArrayList<>();
        for (Way candidate : candidates) {
            ProjectionInterval interval = projectionInterval(candidate, axis);
            if (!interval.isValid()) {
                continue;
            }
            if (splitPosition >= interval.min() - EPSILON && splitPosition <= interval.max() + EPSILON) {
                matches.add(candidate);
            }
        }

        if (matches.size() != 1) {
            return null;
        }

        return matches.get(0);
    }

    private ProjectionInterval projectionInterval(Way way, Vector2D axis) {
        List<Node> nodes = new ArrayList<>(way.getNodes());
        if (nodes.size() > 1 && nodes.get(0).equals(nodes.get(nodes.size() - 1))) {
            nodes.remove(nodes.size() - 1);
        }

        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (Node node : nodes) {
            if (node.getCoor() == null) {
                continue;
            }
            double projection = projectOnAxis(node, axis);
            min = Math.min(min, projection);
            max = Math.max(max, projection);
        }

        return new ProjectionInterval(min, max);
    }

    private double projectOnAxis(Node node, Vector2D axis) {
        return (node.lon() * axis.x()) + (node.lat() * axis.y());
    }

    private List<Way> orderWaysByAxis(List<Way> ways, Vector2D axis) {
        List<Way> ordered = new ArrayList<>(ways);
        ordered.sort(Comparator
            .comparingDouble((Way way) -> projectCenterOnAxis(way, axis))
            .thenComparingLong(Way::getUniqueId));
        return ordered;
    }

    private double projectCenterOnAxis(Way way, Vector2D axis) {
        List<Node> nodes = new ArrayList<>(way.getNodes());
        if (nodes.size() > 1 && nodes.get(0).equals(nodes.get(nodes.size() - 1))) {
            nodes.remove(nodes.size() - 1);
        }

        double latSum = 0.0;
        double lonSum = 0.0;
        int count = 0;
        for (Node node : nodes) {
            if (node.getCoor() == null) {
                continue;
            }
            latSum += node.lat();
            lonSum += node.lon();
            count++;
        }

        if (count == 0) {
            return 0.0;
        }

        double centerLat = latSum / count;
        double centerLon = lonSum / count;
        return (centerLon * axis.x()) + (centerLat * axis.y());
    }

    private List<Node> extractFourCorners(Way buildingWay) {
        List<Node> nodes = new ArrayList<>(buildingWay.getNodes());
        if (nodes.size() != 5) {
            return null;
        }

        if (!nodes.get(0).equals(nodes.get(nodes.size() - 1))) {
            return null;
        }

        List<Node> corners = new ArrayList<>(nodes.subList(0, 4));
        long distinctCorners = corners.stream().distinct().count();
        if (distinctCorners != 4) {
            return null;
        }

        for (Node corner : corners) {
            if (corner.getCoor() == null) {
                return null;
            }
        }

        return corners;
    }

    private SplitGeometry buildSplitGeometry(List<Node> corners) {
        Node a = corners.get(0);
        Node b = corners.get(1);
        Node c = corners.get(2);
        Node d = corners.get(3);

        Vector2D ab = vector(a, b);
        Vector2D bc = vector(b, c);
        Vector2D cd = vector(c, d);
        Vector2D da = vector(d, a);

        if (ab.length() <= EPSILON || bc.length() <= EPSILON || cd.length() <= EPSILON || da.length() <= EPSILON) {
            return null;
        }

        double pair1AverageLength = (ab.length() + cd.length()) / 2.0;
        double pair2AverageLength = (bc.length() + da.length()) / 2.0;

        Vector2D mainAxisFamilyFirst = pair1AverageLength >= pair2AverageLength ? ab : bc;
        Vector2D mainAxisFamilyOpposite = pair1AverageLength >= pair2AverageLength ? cd : da;

        Vector2D mainAxis = averageAlignedDirections(mainAxisFamilyFirst, mainAxisFamilyOpposite);
        if (mainAxis.length() <= EPSILON) {
            return null;
        }

        double centerLat = (a.lat() + b.lat() + c.lat() + d.lat()) / 4.0;
        double centerLon = (a.lon() + b.lon() + c.lon() + d.lon()) / 4.0;

        Vector2D perpendicular = mainAxis.perpendicular().normalize();
        if (perpendicular.length() <= EPSILON) {
            return null;
        }

        double maxEdgeLength = Math.max(Math.max(ab.length(), bc.length()), Math.max(cd.length(), da.length()));
        double halfLineLength = Math.max(maxEdgeLength * 2.0, EPSILON);

        return new SplitGeometry(centerLat, centerLon, mainAxis, perpendicular, halfLineLength);
    }

    private Vector2D averageAlignedDirections(Vector2D first, Vector2D second) {
        Vector2D firstNormalized = first.normalize();
        Vector2D secondNormalized = second.normalize();

        if (firstNormalized.length() <= EPSILON || secondNormalized.length() <= EPSILON) {
            return new Vector2D(0.0, 0.0);
        }

        if (firstNormalized.dot(secondNormalized) < 0.0) {
            secondNormalized = secondNormalized.multiply(-1.0);
        }

        return firstNormalized.add(secondNormalized).normalize();
    }

    private Line createAutoSplitLine(SplitGeometry geometry, double axisPosition) {
        double centerProjection = (geometry.centerLon() * geometry.mainAxis().x()) + (geometry.centerLat() * geometry.mainAxis().y());
        double alongAxisOffset = axisPosition - centerProjection;

        double lineCenterLon = geometry.centerLon() + (geometry.mainAxis().x() * alongAxisOffset);
        double lineCenterLat = geometry.centerLat() + (geometry.mainAxis().y() * alongAxisOffset);

        double startLat = lineCenterLat - (geometry.perpendicularDirection().y() * geometry.halfLineLength());
        double startLon = lineCenterLon - (geometry.perpendicularDirection().x() * geometry.halfLineLength());
        double endLat = lineCenterLat + (geometry.perpendicularDirection().y() * geometry.halfLineLength());
        double endLon = lineCenterLon + (geometry.perpendicularDirection().x() * geometry.halfLineLength());

        return new Line(new LatLon(startLat, startLon), new LatLon(endLat, endLon));
    }

    private Vector2D vector(Node from, Node to) {
        return new Vector2D(to.lon() - from.lon(), to.lat() - from.lat());
    }

    private static final class SplitGeometry {
        private final double centerLat;
        private final double centerLon;
        private final Vector2D mainAxis;
        private final Vector2D perpendicularDirection;
        private final double halfLineLength;

        private SplitGeometry(
            double centerLat,
            double centerLon,
            Vector2D mainAxis,
            Vector2D perpendicularDirection,
            double halfLineLength
        ) {
            this.centerLat = centerLat;
            this.centerLon = centerLon;
            this.mainAxis = mainAxis;
            this.perpendicularDirection = perpendicularDirection;
            this.halfLineLength = halfLineLength;
        }

        private double centerLat() {
            return centerLat;
        }

        private double centerLon() {
            return centerLon;
        }

        private Vector2D mainAxis() {
            return mainAxis;
        }

        private Vector2D perpendicularDirection() {
            return perpendicularDirection;
        }

        private double halfLineLength() {
            return halfLineLength;
        }
    }

    private static final class ProjectionInterval {
        private final double min;
        private final double max;

        private ProjectionInterval(double min, double max) {
            this.min = min;
            this.max = max;
        }

        private boolean isValid() {
            return Double.isFinite(min) && Double.isFinite(max) && max >= min;
        }

        private double min() {
            return min;
        }

        private double max() {
            return max;
        }
    }

    private static final class Vector2D {
        private final double x;
        private final double y;

        private Vector2D(double x, double y) {
            this.x = x;
            this.y = y;
        }

        private double x() {
            return x;
        }

        private double y() {
            return y;
        }

        private Vector2D add(Vector2D other) {
            return new Vector2D(x + other.x, y + other.y);
        }

        private Vector2D multiply(double scalar) {
            return new Vector2D(x * scalar, y * scalar);
        }

        private double dot(Vector2D other) {
            return (x * other.x) + (y * other.y);
        }


        private double length() {
            return Math.hypot(x, y);
        }

        private Vector2D normalize() {
            double len = length();
            if (len <= EPSILON) {
                return new Vector2D(0.0, 0.0);
            }
            return new Vector2D(x / len, y / len);
        }

        private Vector2D perpendicular() {
            return new Vector2D(-y, x);
        }
    }

    private static final class Line {
        private final LatLon start;
        private final LatLon end;

        private Line(LatLon start, LatLon end) {
            this.start = start;
            this.end = end;
        }

        private LatLon start() {
            return start;
        }

        private LatLon end() {
            return end;
        }
    }
}

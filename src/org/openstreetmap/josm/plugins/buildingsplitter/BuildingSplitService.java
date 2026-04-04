package org.openstreetmap.josm.plugins.buildingsplitter;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.SelectCommand;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.command.SplitWayCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;

public class BuildingSplitService {

    public SplitResult splitSelectedBuilding(DataSet dataSet) {
        SplitExecutionResult detailedResult = splitSelectedBuildingDetailed(dataSet);
        if (!detailedResult.isSuccess()) {
            return SplitResult.failure(detailedResult.getMessage());
        }
        return SplitResult.success(detailedResult.getMessage(), detailedResult.getResultWaysOrdered());
    }

    public SplitExecutionResult splitSelectedBuildingDetailed(DataSet dataSet) {
        ValidationResult validation = validateSelection(dataSet);
        if (!validation.isValid()) {
            return SplitExecutionResult.failure(validation.message(), null);
        }

        return splitBuildingDetailed(dataSet, validation.sourceWay(), validation.firstSplitNode(), validation.secondSplitNode());
    }

    public SplitExecutionResult splitBuildingDetailed(DataSet dataSet, Way sourceWay, Node firstSplitNode, Node secondSplitNode) {
        if (dataSet == null) {
            return SplitExecutionResult.failure(tr("No editable dataset is available."), null);
        }
        if (sourceWay == null || firstSplitNode == null || secondSplitNode == null) {
            return SplitExecutionResult.failure(tr("Invalid selection: select exactly 1 closed building way and 2 of its corner nodes (3 selected objects total)."), null);
        }

        RingPaths ringPaths = extractRingPaths(sourceWay, firstSplitNode, secondSplitNode);
        if (ringPaths == null) {
            return SplitExecutionResult.failure(
                tr("Unable to compute split paths for the selected building outline."),
                sourceWay
            );
        }

        List<Node> polygonANodes = buildClosedPolygon(ringPaths.pathFromFirstToSecond());
        List<Node> polygonBNodes = buildClosedPolygon(ringPaths.pathFromSecondToFirst());

        if (!isValidClosedPolygon(polygonANodes) || !isValidClosedPolygon(polygonBNodes)) {
            return SplitExecutionResult.failure(
                tr("Split points would create invalid polygons. Choose non-adjacent corners that form two valid areas."),
                sourceWay
            );
        }

        SplitWayCommand splitCommand;
        try {
            // Keep existing geometry preparation and delegate relation-aware split command creation to JOSM core.
            // Use splitWay with explicit strategy and relation-order policy so relation handling stays in JOSM core.
            List<List<Node>> splitChunks = Arrays.asList(polygonANodes, polygonBNodes);
            Optional<SplitWayCommand> splitCommandOptional = SplitWayCommand.splitWay(
                sourceWay,
                splitChunks,
                Arrays.asList(sourceWay, firstSplitNode, secondSplitNode),
                SplitWayCommand.Strategy.keepFirstChunk(),
                SplitWayCommand.WhenRelationOrderUncertain.SPLIT_ANYWAY
            );
            if (splitCommandOptional.isEmpty()) {
                return SplitExecutionResult.failure(tr("Building split could not be executed."), sourceWay);
            }
            splitCommand = splitCommandOptional.get();
        } catch (RuntimeException ex) {
            return SplitExecutionResult.failure(tr("Building split could not be executed."), sourceWay);
        }

        List<Way> newWays = new ArrayList<>(splitCommand.getNewWays());
        Way originalWay = splitCommand.getOriginalWay();
        List<Way> resultWaysOrdered = buildResultWaysOrdered(originalWay, newWays);

        List<Command> commands = new ArrayList<>();
        commands.add(splitCommand);
        if (!resultWaysOrdered.isEmpty()) {
            List<OsmPrimitive> resultSelection = new ArrayList<>(resultWaysOrdered);
            commands.add(new ChangePropertyCommand(resultWaysOrdered, "addr:housenumber", null));
            commands.add(new SelectCommand(dataSet, resultSelection));
        }

        UndoRedoHandler.getInstance().add(new SequenceCommand(tr("Split building"), commands));
        return SplitExecutionResult.success(
            tr("Building split completed."),
            originalWay,
            newWays,
            resultWaysOrdered
        );
    }

    private ValidationResult validateSelection(DataSet dataSet) {
        if (dataSet == null) {
            return ValidationResult.failure(tr("No editable dataset is available."));
        }

        Collection<OsmPrimitive> selected = dataSet.getSelected();
        if (selected.size() != 3) {
            return ValidationResult.failure(tr("Invalid selection: select exactly 1 closed building way and 2 of its corner nodes (3 selected objects total)."));
        }

        Collection<Way> selectedWays = dataSet.getSelectedWays();
        if (selectedWays.size() != 1) {
            return ValidationResult.failure(tr("Invalid selection: exactly one way must be selected as the building outline."));
        }

        Way selectedWay = selectedWays.iterator().next();
        if (!selectedWay.isClosed()) {
            return ValidationResult.failure(tr("The selected way is open. Please select a closed building outline."));
        }

        if (!selectedWay.hasKey("building")) {
            return ValidationResult.failure(tr("The selected closed way is missing a building=* tag."));
        }

        Collection<Node> selectedNodes = dataSet.getSelectedNodes();
        if (selectedNodes.size() != 2) {
            return ValidationResult.failure(tr("Invalid node selection: select exactly 2 split nodes on the building outline."));
        }

        List<Node> ring = getOpenRing(selectedWay);
        if (ring == null || ring.size() < 3) {
            return ValidationResult.failure(tr("The selected building outline is invalid."));
        }

        List<Node> selectedNodeList = new ArrayList<>(selectedNodes);
        Node nodeA = selectedNodeList.get(0);
        Node nodeB = selectedNodeList.get(1);

        int indexA = ring.indexOf(nodeA);
        int indexB = ring.indexOf(nodeB);
        if (indexA < 0 || indexB < 0) {
            return ValidationResult.failure(tr("Both selected split nodes must belong to the selected building way."));
        }

        if (indexA == indexB) {
            return ValidationResult.failure(tr("The two split nodes must be different nodes."));
        }

        if (areAdjacentInClosedRing(selectedWay, nodeA, nodeB)) {
            return ValidationResult.failure(tr("The selected split nodes are adjacent on the building outline. Choose two non-adjacent nodes."));
        }

        // Keep split behavior deterministic regardless of selection set iteration order.
        Node firstNode = indexA <= indexB ? nodeA : nodeB;
        Node secondNode = indexA <= indexB ? nodeB : nodeA;

        return ValidationResult.success(selectedWay, firstNode, secondNode);
    }

    private RingPaths extractRingPaths(Way way, Node firstNode, Node secondNode) {
        List<Node> ring = getOpenRing(way);
        if (ring == null || ring.size() < 3) {
            return null;
        }

        int firstIndex = ring.indexOf(firstNode);
        int secondIndex = ring.indexOf(secondNode);
        if (firstIndex < 0 || secondIndex < 0 || firstIndex == secondIndex) {
            return null;
        }

        List<Node> pathFirstToSecond = collectPath(ring, firstIndex, secondIndex);
        List<Node> pathSecondToFirst = collectPath(ring, secondIndex, firstIndex);

        return new RingPaths(pathFirstToSecond, pathSecondToFirst);
    }

    private List<Node> getOpenRing(Way way) {
        List<Node> ring = new ArrayList<>(way.getNodes());
        if (ring.size() < 4) {
            return null;
        }

        Node first = ring.get(0);
        Node last = ring.get(ring.size() - 1);
        if (!first.equals(last)) {
            return null;
        }

        ring.remove(ring.size() - 1);
        return ring;
    }

    private boolean areAdjacentInClosedRing(Way way, Node firstNode, Node secondNode) {
        List<Node> ring = getOpenRing(way);
        if (ring == null || ring.size() < 2 || firstNode.equals(secondNode)) {
            return false;
        }

        int firstIndex = ring.indexOf(firstNode);
        int secondIndex = ring.indexOf(secondNode);
        if (firstIndex < 0 || secondIndex < 0) {
            return false;
        }

        int difference = Math.abs(firstIndex - secondIndex);
        return difference == 1 || difference == ring.size() - 1;
    }

    private List<Node> collectPath(List<Node> ring, int fromIndex, int toIndex) {
        List<Node> path = new ArrayList<>();
        int index = fromIndex;
        path.add(ring.get(index));

        while (index != toIndex) {
            index = (index + 1) % ring.size();
            path.add(ring.get(index));
        }

        return path;
    }

    private List<Node> buildClosedPolygon(List<Node> path) {
        List<Node> polygon = new ArrayList<>(path);
        if (polygon.isEmpty()) {
            return polygon;
        }

        Node startNode = polygon.get(0);
        Node endNode = polygon.get(polygon.size() - 1);
        if (!startNode.equals(endNode)) {
            polygon.add(startNode);
        }

        return polygon;
    }

    private boolean isValidClosedPolygon(List<Node> nodes) {
        if (nodes.size() < 4) {
            return false;
        }

        if (!nodes.get(0).equals(nodes.get(nodes.size() - 1))) {
            return false;
        }

        return countDistinctNodesExcludingClosing(nodes) >= 3;
    }

    private long countDistinctNodesExcludingClosing(List<Node> nodes) {
        if (nodes.size() < 2) {
            return 0;
        }

        return nodes.subList(0, nodes.size() - 1).stream().distinct().count();
    }

    private List<Way> buildResultWaysOrdered(Way originalWay, List<Way> newWays) {
        List<Way> ordered = new ArrayList<>();
        if (originalWay != null) {
            ordered.add(originalWay);
        }
        if (newWays != null) {
            ordered.addAll(newWays);
        }
        if (ordered.isEmpty()) {
            return Collections.emptyList();
        }
        return ordered;
    }

    private static final class RingPaths {
        private final List<Node> pathFromFirstToSecond;
        private final List<Node> pathFromSecondToFirst;

        private RingPaths(List<Node> pathFromFirstToSecond, List<Node> pathFromSecondToFirst) {
            this.pathFromFirstToSecond = pathFromFirstToSecond;
            this.pathFromSecondToFirst = pathFromSecondToFirst;
        }

        private List<Node> pathFromFirstToSecond() {
            return pathFromFirstToSecond;
        }

        private List<Node> pathFromSecondToFirst() {
            return pathFromSecondToFirst;
        }
    }

    private static final class ValidationResult {
        private final boolean valid;
        private final String message;
        private final Way sourceWay;
        private final Node firstSplitNode;
        private final Node secondSplitNode;

        private ValidationResult(boolean valid, String message, Way sourceWay, Node firstSplitNode, Node secondSplitNode) {
            this.valid = valid;
            this.message = message;
            this.sourceWay = sourceWay;
            this.firstSplitNode = firstSplitNode;
            this.secondSplitNode = secondSplitNode;
        }

        private static ValidationResult success(Way sourceWay, Node firstSplitNode, Node secondSplitNode) {
            return new ValidationResult(true, null, sourceWay, firstSplitNode, secondSplitNode);
        }

        private static ValidationResult failure(String message) {
            return new ValidationResult(false, message, null, null, null);
        }

        private boolean isValid() {
            return valid;
        }

        private String message() {
            return message;
        }

        private Way sourceWay() {
            return sourceWay;
        }

        private Node firstSplitNode() {
            return firstSplitNode;
        }

        private Node secondSplitNode() {
            return secondSplitNode;
        }
    }
}



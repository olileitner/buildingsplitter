package org.openstreetmap.josm.plugins.buildingsplitter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.ChangeCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;

public class SplitNodePreparationService {

    public PreparedIntersectionNodes prepareIntersectionNodes(DataSet dataSet, Way way, List<IntersectionPoint> intersections) {
        List<Command> commands = new ArrayList<>();
        List<Node> splitNodes = new ArrayList<>();
        boolean changedWay = false;

        Way updatedWay = new Way(way);
        List<Node> updatedNodes = new ArrayList<>(updatedWay.getNodes());

        List<IntersectionPoint> ordered = new ArrayList<>(intersections);
        ordered.sort(Comparator.comparingInt(IntersectionPoint::getSegmentIndex).reversed());

        for (IntersectionPoint intersection : ordered) {
            if (intersection.isExistingNode() && intersection.getExistingNode() != null) {
                Node existingNode = intersection.getExistingNode();
                if (!splitNodes.contains(existingNode)) {
                    splitNodes.add(existingNode);
                }
                continue;
            }

            Node newNode = new Node(intersection.getCoordinate());
            commands.add(new AddCommand(dataSet, newNode));

            int insertIndex = calculateInsertIndex(updatedNodes, intersection.getSegmentIndex());
            updatedNodes.add(insertIndex, newNode);
            changedWay = true;
            if (!splitNodes.contains(newNode)) {
                splitNodes.add(newNode);
            }
        }

        if (changedWay) {
            updatedWay.setNodes(updatedNodes);
            commands.add(new ChangeCommand(dataSet, way, updatedWay));
        }

        splitNodes.sort(Comparator.comparingInt(node -> updatedNodes.indexOf(node)));

        if (splitNodes.size() == 2 && splitNodes.get(0).equals(splitNodes.get(1))) {
            return new PreparedIntersectionNodes(new ArrayList<>(), updatedNodes, commands, changedWay);
        }

        return new PreparedIntersectionNodes(splitNodes, updatedNodes, commands, changedWay);
    }

    public boolean areAdjacentInClosedNodeList(List<Node> closedWayNodes, Node firstNode, Node secondNode) {
        if (closedWayNodes == null || firstNode == null || secondNode == null || firstNode.equals(secondNode)) {
            return false;
        }

        List<Node> ring = new ArrayList<>(closedWayNodes);
        if (ring.size() < 4 || !ring.get(0).equals(ring.get(ring.size() - 1))) {
            return false;
        }

        ring.remove(ring.size() - 1);
        int firstIndex = ring.indexOf(firstNode);
        int secondIndex = ring.indexOf(secondNode);
        if (firstIndex < 0 || secondIndex < 0) {
            return false;
        }

        int diff = Math.abs(firstIndex - secondIndex);
        return diff == 1 || diff == ring.size() - 1;
    }

    private int calculateInsertIndex(List<Node> closedWayNodes, int segmentIndex) {
        int min = 1;
        int max = closedWayNodes.size() - 1;
        int insertIndex = segmentIndex + 1;
        if (insertIndex < min) {
            return min;
        }
        if (insertIndex > max) {
            return max;
        }
        return insertIndex;
    }

    public static final class PreparedIntersectionNodes {
        private final List<Node> splitNodes;
        private final List<Node> updatedWayNodes;
        private final List<Command> commands;
        private final boolean changedWay;

        private PreparedIntersectionNodes(
            List<Node> splitNodes,
            List<Node> updatedWayNodes,
            List<Command> commands,
            boolean changedWay
        ) {
            this.splitNodes = splitNodes;
            this.updatedWayNodes = updatedWayNodes;
            this.commands = commands;
            this.changedWay = changedWay;
        }

        public List<Node> getSplitNodes() {
            return splitNodes;
        }

        public List<Node> getUpdatedWayNodes() {
            return updatedWayNodes;
        }

        public List<Command> getCommands() {
            return commands;
        }

        public boolean isChangedWay() {
            return changedWay;
        }
    }
}


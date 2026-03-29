package org.openstreetmap.josm.plugins.buildingsplitter;

import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.Node;

public final class IntersectionPoint {

    private final LatLon coordinate;
    private final int segmentIndex;
    private final Node existingNode;
    private final boolean existingNodeIntersection;

    public IntersectionPoint(LatLon coordinate, int segmentIndex, Node existingNode, boolean existingNodeIntersection) {
        this.coordinate = coordinate;
        this.segmentIndex = segmentIndex;
        this.existingNode = existingNode;
        this.existingNodeIntersection = existingNodeIntersection;
    }

    public LatLon getCoordinate() {
        return coordinate;
    }

    public int getSegmentIndex() {
        return segmentIndex;
    }

    public Node getExistingNode() {
        return existingNode;
    }

    public boolean isExistingNode() {
        return existingNodeIntersection;
    }
}


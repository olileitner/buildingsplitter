package org.openstreetmap.josm.plugins.buildingsplitter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.openstreetmap.josm.data.osm.Way;

public final class SplitResult {

    private final boolean success;
    private final String message;
    private final List<Way> createdWays;
    private final SplitAxis splitAxis;

    private SplitResult(boolean success, String message, List<Way> createdWays, SplitAxis splitAxis) {
        this.success = success;
        this.message = message;
        this.createdWays = Collections.unmodifiableList(new ArrayList<>(createdWays));
        this.splitAxis = splitAxis;
    }

    public static SplitResult success(String message) {
        return new SplitResult(true, message, Collections.emptyList(), null);
    }

    public static SplitResult success(String message, List<Way> createdWays) {
        return new SplitResult(true, message, createdWays == null ? Collections.emptyList() : createdWays, null);
    }

    public static SplitResult success(String message, List<Way> createdWays, SplitAxis splitAxis) {
        return new SplitResult(true, message, createdWays == null ? Collections.emptyList() : createdWays, splitAxis);
    }

    public static SplitResult failure(String message) {
        return new SplitResult(false, message, Collections.emptyList(), null);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public List<Way> getCreatedWays() {
        return createdWays;
    }

    public SplitAxis getSplitAxis() {
        return splitAxis;
    }

    public boolean hasSplitAxis() {
        return splitAxis != null;
    }

    public static final class SplitAxis {
        private final double x;
        private final double y;

        public SplitAxis(double x, double y) {
            this.x = x;
            this.y = y;
        }

        public double getX() {
            return x;
        }

        public double getY() {
            return y;
        }
    }
}

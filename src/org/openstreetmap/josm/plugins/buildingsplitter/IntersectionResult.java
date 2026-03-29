package org.openstreetmap.josm.plugins.buildingsplitter;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class IntersectionResult {

    private final boolean success;
    private final String message;
    private final List<IntersectionPoint> intersections;

    private IntersectionResult(boolean success, String message, List<IntersectionPoint> intersections) {
        this.success = success;
        this.message = message;
        this.intersections = Collections.unmodifiableList(new ArrayList<>(intersections));
    }

    public static IntersectionResult success(List<IntersectionPoint> intersections) {
        return new IntersectionResult(true, tr("Intersections computed"), intersections);
    }

    public static IntersectionResult failure(String message) {
        return new IntersectionResult(false, message, Collections.emptyList());
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public List<IntersectionPoint> getIntersections() {
        return intersections;
    }
}


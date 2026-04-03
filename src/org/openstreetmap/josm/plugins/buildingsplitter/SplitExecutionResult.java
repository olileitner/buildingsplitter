package org.openstreetmap.josm.plugins.buildingsplitter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.openstreetmap.josm.data.osm.Way;

public final class SplitExecutionResult {

    private final boolean success;
    private final String message;
    private final Way originalWay;
    private final List<Way> newWays;
    private final List<Way> resultWaysOrdered;

    private SplitExecutionResult(
        boolean success,
        String message,
        Way originalWay,
        List<Way> newWays,
        List<Way> resultWaysOrdered
    ) {
        this.success = success;
        this.message = message;
        this.originalWay = originalWay;
        this.newWays = Collections.unmodifiableList(new ArrayList<>(newWays));
        this.resultWaysOrdered = Collections.unmodifiableList(new ArrayList<>(resultWaysOrdered));
    }

    public static SplitExecutionResult success(
        String message,
        Way originalWay,
        List<Way> newWays,
        List<Way> resultWaysOrdered
    ) {
        List<Way> safeNewWays = newWays == null ? Collections.emptyList() : newWays;
        List<Way> safeOrdered = resultWaysOrdered == null ? Collections.emptyList() : resultWaysOrdered;
        return new SplitExecutionResult(true, message, originalWay, safeNewWays, safeOrdered);
    }

    public static SplitExecutionResult failure(String message, Way originalWay) {
        return new SplitExecutionResult(false, message, originalWay, Collections.emptyList(), Collections.emptyList());
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    /**
     * Way that was selected as split source before execution.
     * It may be deleted by the current implementation and can become the surviving way after SplitWayCommand migration.
     */
    public Way getOriginalWay() {
        return originalWay;
    }

    public List<Way> getNewWays() {
        return newWays;
    }

    /**
     * Deterministic result ordering consumed by callers for selection and follow-up processing.
     */
    public List<Way> getResultWaysOrdered() {
        return resultWaysOrdered;
    }
}


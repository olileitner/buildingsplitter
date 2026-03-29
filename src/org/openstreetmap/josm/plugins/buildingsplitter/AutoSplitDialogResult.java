package org.openstreetmap.josm.plugins.buildingsplitter;

public final class AutoSplitDialogResult {

    private enum Outcome {
        APPLY,
        SKIP,
        CANCEL
    }

    private final Outcome outcome;
    private final int parts;
    private final String startHouseNumber;
    private final int increment;

    private AutoSplitDialogResult(Outcome outcome, int parts, String startHouseNumber, int increment) {
        this.outcome = outcome;
        this.parts = parts;
        this.startHouseNumber = startHouseNumber;
        this.increment = increment;
    }

    public static AutoSplitDialogResult apply(int parts, String startHouseNumber, int increment) {
        return new AutoSplitDialogResult(Outcome.APPLY, parts, startHouseNumber, increment);
    }

    public static AutoSplitDialogResult skip() {
        return new AutoSplitDialogResult(Outcome.SKIP, 0, "", 0);
    }

    public static AutoSplitDialogResult cancel() {
        return new AutoSplitDialogResult(Outcome.CANCEL, 0, "", 0);
    }

    public boolean isSkip() {
        return outcome == Outcome.SKIP;
    }

    public boolean isCancel() {
        return outcome == Outcome.CANCEL;
    }

    public int getParts() {
        return parts;
    }

    public String getStartHouseNumber() {
        return startHouseNumber;
    }

    public int getIncrement() {
        return increment;
    }
}


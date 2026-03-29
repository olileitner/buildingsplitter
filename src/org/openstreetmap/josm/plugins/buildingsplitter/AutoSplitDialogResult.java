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
    private final boolean reverseOrder;
    private final boolean firstWithoutLetter;

    private AutoSplitDialogResult(
        Outcome outcome,
        int parts,
        String startHouseNumber,
        int increment,
        boolean reverseOrder,
        boolean firstWithoutLetter
    ) {
        this.outcome = outcome;
        this.parts = parts;
        this.startHouseNumber = startHouseNumber;
        this.increment = increment;
        this.reverseOrder = reverseOrder;
        this.firstWithoutLetter = firstWithoutLetter;
    }

    public static AutoSplitDialogResult apply(
        int parts,
        String startHouseNumber,
        int increment,
        boolean reverseOrder,
        boolean firstWithoutLetter
    ) {
        return new AutoSplitDialogResult(Outcome.APPLY, parts, startHouseNumber, increment, reverseOrder, firstWithoutLetter);
    }

    public static AutoSplitDialogResult skip() {
        return new AutoSplitDialogResult(Outcome.SKIP, 0, "", 0, false, false);
    }

    public static AutoSplitDialogResult cancel() {
        return new AutoSplitDialogResult(Outcome.CANCEL, 0, "", 0, false, false);
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

    public boolean isReverseOrder() {
        return reverseOrder;
    }

    public boolean isFirstWithoutLetter() {
        return firstWithoutLetter;
    }
}


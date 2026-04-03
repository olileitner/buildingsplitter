package org.openstreetmap.josm.plugins.buildingsplitter;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;

public class AutoSplitPreviewSession {

    private static final String PREVIEW_RESET_MESSAGE =
        tr("AutoSplit preview was reset because other edits were made in the meantime.");

    private final DataSet dataSet;
    private final Way sourceWay;
    private final AutoSplitBuildingService autoSplitService;
    private final HouseNumberService houseNumberService;
    private final UndoRedoHandler undoRedoHandler;

    private final List<Command> previewOwnedCommands;

    private SplitResult currentPreviewResult;
    private AppliedOptions currentAppliedOptions;

    public AutoSplitPreviewSession(
        DataSet dataSet,
        Way sourceWay,
        AutoSplitBuildingService autoSplitService,
        HouseNumberService houseNumberService
    ) {
        this.dataSet = dataSet;
        this.sourceWay = sourceWay;
        this.autoSplitService = autoSplitService;
        this.houseNumberService = houseNumberService;
        this.undoRedoHandler = UndoRedoHandler.getInstance();
        this.previewOwnedCommands = new ArrayList<>();
    }

    public String refreshPreview(
        Integer parts,
        String startHouseNumber,
        int increment,
        boolean reverseOrder,
        boolean firstWithoutLetter,
        String street,
        String postcode
    ) {
        if (!undoPreviewOwnedCommands()) {
            return PREVIEW_RESET_MESSAGE;
        }
        clearPreviewState();

        if (parts == null) {
            return null;
        }

        int refreshStartUndoSize = undoRedoHandler.getUndoCommands().size();

        SplitResult splitResult = autoSplitService.autoSplitBuilding(dataSet, sourceWay, parts);
        if (!splitResult.isSuccess()) {
            rollbackRefreshAttempt(refreshStartUndoSize);
            return splitResult.getMessage();
        }

        captureCommandsAddedSince(refreshStartUndoSize);

        List<Way> createdWays = splitResult.getCreatedWays();
        String normalizedStreet = normalizeValue(street);
        String normalizedPostcode = normalizeValue(postcode);
        if (!normalizedStreet.isEmpty() || !normalizedPostcode.isEmpty()) {
            List<Command> addressCommands = new ArrayList<>();
            if (!normalizedStreet.isEmpty()) {
                addressCommands.add(new ChangePropertyCommand(createdWays, "addr:street", normalizedStreet));
            }
            if (!normalizedPostcode.isEmpty()) {
                addressCommands.add(new ChangePropertyCommand(createdWays, "addr:postcode", normalizedPostcode));
            }
            if (!addressCommands.isEmpty()) {
                int beforeAddressUndoSize = undoRedoHandler.getUndoCommands().size();
                undoRedoHandler.add(new SequenceCommand(tr("Assign address tags"), addressCommands));
                captureCommandsAddedSince(beforeAddressUndoSize);
            }
        }

        String normalizedStart = normalizeValue(startHouseNumber);
        if (!normalizedStart.isEmpty()) {
            List<Way> orderedWays = orderWaysBySplitAxis(splitResult, reverseOrder);
            if (orderedWays == null) {
                rollbackRefreshAttempt(refreshStartUndoSize);
                return tr("Failed to determine AutoSplit axis ordering for house numbers.");
            }
            List<String> houseNumbers;
            try {
                houseNumbers = houseNumberService.generateSequence(
                    normalizedStart,
                    increment,
                    orderedWays.size(),
                    firstWithoutLetter
                );
            } catch (IllegalArgumentException ex) {
                rollbackRefreshAttempt(refreshStartUndoSize);
                return ex.getMessage();
            }

            List<Command> houseNumberCommands = new ArrayList<>();
            for (int i = 0; i < orderedWays.size(); i++) {
                houseNumberCommands.add(new ChangePropertyCommand(orderedWays.get(i), "addr:housenumber", houseNumbers.get(i)));
            }
            if (!houseNumberCommands.isEmpty()) {
                int beforeAssignmentUndoSize = undoRedoHandler.getUndoCommands().size();
                undoRedoHandler.add(new SequenceCommand(tr("Assign house numbers"), houseNumberCommands));
                captureCommandsAddedSince(beforeAssignmentUndoSize);
            }
        }

        currentPreviewResult = splitResult;
        currentAppliedOptions = new AppliedOptions(
            parts,
            normalizedStart,
            increment,
            reverseOrder,
            firstWithoutLetter,
            normalizedStreet,
            normalizedPostcode
        );
        return null;
    }

    public void undoPreview() {
        undoPreviewOwnedCommands();
        clearPreviewState();
    }

    public SplitResult finalizePreview(AutoSplitDialogResult dialogResult) {
        if (dialogResult == null || dialogResult.isCancel() || dialogResult.isSkip()) {
            return SplitResult.failure(tr("AutoSplit preview was not confirmed."));
        }

        if (!matchesCurrent(dialogResult)) {
            String previewError = refreshPreview(
                dialogResult.getParts(),
                dialogResult.getStartHouseNumber(),
                dialogResult.getIncrement(),
                dialogResult.isReverseOrder(),
                dialogResult.isFirstWithoutLetter(),
                dialogResult.getStreet(),
                dialogResult.getPostcode()
            );
            if (previewError != null) {
                return SplitResult.failure(previewError);
            }
        }

        if (currentPreviewResult == null || !currentPreviewResult.isSuccess()) {
            return SplitResult.failure(tr("Failed to finalize AutoSplit preview."));
        }

        return currentPreviewResult;
    }

    private boolean matchesCurrent(AutoSplitDialogResult dialogResult) {
        if (currentAppliedOptions == null) {
            return false;
        }

        String normalizedStart = normalizeValue(dialogResult.getStartHouseNumber());
        String normalizedStreet = normalizeValue(dialogResult.getStreet());
        String normalizedPostcode = normalizeValue(dialogResult.getPostcode());
        return currentAppliedOptions.parts == dialogResult.getParts()
            && currentAppliedOptions.increment == dialogResult.getIncrement()
            && currentAppliedOptions.reverseOrder == dialogResult.isReverseOrder()
            && currentAppliedOptions.firstWithoutLetter == dialogResult.isFirstWithoutLetter()
            && currentAppliedOptions.startHouseNumber.equals(normalizedStart)
            && currentAppliedOptions.street.equals(normalizedStreet)
            && currentAppliedOptions.postcode.equals(normalizedPostcode);
    }

    private String normalizeValue(String value) {
        return value == null ? "" : value.trim();
    }

    private void rollbackRefreshAttempt(int refreshStartUndoSize) {
        int undoCount = undoRedoHandler.getUndoCommands().size() - refreshStartUndoSize;
        if (undoCount > 0) {
            undoRedoHandler.undo(undoCount);
            removeLastOwnedCommands(undoCount);
        }
        clearPreviewState();
    }

    private void captureCommandsAddedSince(int previousUndoSize) {
        List<Command> undoCommands = undoRedoHandler.getUndoCommands();
        for (int i = previousUndoSize; i < undoCommands.size(); i++) {
            previewOwnedCommands.add(undoCommands.get(i));
        }
    }

    private boolean undoPreviewOwnedCommands() {
        if (previewOwnedCommands.isEmpty()) {
            return true;
        }

        int contiguousOwnedOnTop = countContiguousOwnedCommandsOnTop();
        if (contiguousOwnedOnTop != previewOwnedCommands.size()) {
            resetPreviewSessionState();
            return false;
        }

        undoRedoHandler.undo(contiguousOwnedOnTop);
        removeLastOwnedCommands(contiguousOwnedOnTop);
        return true;
    }

    private int countContiguousOwnedCommandsOnTop() {
        List<Command> undoCommands = undoRedoHandler.getUndoCommands();
        int undoIndex = undoCommands.size() - 1;
        int ownedIndex = previewOwnedCommands.size() - 1;
        int count = 0;

        while (undoIndex >= 0 && ownedIndex >= 0) {
            if (undoCommands.get(undoIndex) != previewOwnedCommands.get(ownedIndex)) {
                break;
            }
            count++;
            undoIndex--;
            ownedIndex--;
        }

        return count;
    }

    private void removeLastOwnedCommands(int count) {
        for (int i = 0; i < count && !previewOwnedCommands.isEmpty(); i++) {
            previewOwnedCommands.remove(previewOwnedCommands.size() - 1);
        }
    }

    private void clearPreviewState() {
        currentPreviewResult = null;
        currentAppliedOptions = null;
    }

    private void resetPreviewSessionState() {
        previewOwnedCommands.clear();
        clearPreviewState();
    }

    private List<Way> orderWaysBySplitAxis(SplitResult splitResult, boolean reverseOrder) {
        if (splitResult == null || !splitResult.hasSplitAxis()) {
            return null;
        }

        SplitResult.SplitAxis axis = splitResult.getSplitAxis();
        List<Way> ordered = new ArrayList<>(splitResult.getCreatedWays());
        ordered.sort(Comparator
            .comparingDouble((Way way) -> projectCenterOnAxis(way, axis))
            .thenComparingLong(Way::getUniqueId));
        if (reverseOrder) {
            Collections.reverse(ordered);
        }
        return ordered;
    }

    private double projectCenterOnAxis(Way way, SplitResult.SplitAxis axis) {
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
        return (centerLon * axis.getX()) + (centerLat * axis.getY());
    }

    private static final class AppliedOptions {
        private final int parts;
        private final String startHouseNumber;
        private final int increment;
        private final boolean reverseOrder;
        private final boolean firstWithoutLetter;
        private final String street;
        private final String postcode;

        private AppliedOptions(
            int parts,
            String startHouseNumber,
            int increment,
            boolean reverseOrder,
            boolean firstWithoutLetter,
            String street,
            String postcode
        ) {
            this.parts = parts;
            this.startHouseNumber = startHouseNumber;
            this.increment = increment;
            this.reverseOrder = reverseOrder;
            this.firstWithoutLetter = firstWithoutLetter;
            this.street = street;
            this.postcode = postcode;
        }
    }
}


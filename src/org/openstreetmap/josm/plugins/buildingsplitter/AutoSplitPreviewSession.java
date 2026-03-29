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

    private final DataSet dataSet;
    private final Way sourceWay;
    private final AutoSplitBuildingService autoSplitService;
    private final HouseNumberService houseNumberService;
    private final UndoRedoHandler undoRedoHandler;

    private final int baselineUndoSize;

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
        this.baselineUndoSize = undoRedoHandler.getUndoCommands().size();
    }

    public String refreshPreview(
        Integer parts,
        String startHouseNumber,
        int increment,
        boolean reverseOrder,
        boolean firstWithoutLetter
    ) {
        undoPreview();

        if (parts == null) {
            return null;
        }

        SplitResult splitResult = autoSplitService.autoSplitBuilding(dataSet, sourceWay, parts);
        if (!splitResult.isSuccess()) {
            return splitResult.getMessage();
        }

        String normalizedStart = normalizeStartValue(startHouseNumber);
        if (!normalizedStart.isEmpty()) {
            List<Way> createdWays = orderWaysBySplitAxis(splitResult, reverseOrder);
            if (createdWays == null) {
                undoPreview();
                return tr("Failed to determine AutoSplit axis ordering for house numbers.");
            }
            List<String> houseNumbers;
            try {
                houseNumbers = houseNumberService.generateSequence(
                    normalizedStart,
                    increment,
                    createdWays.size(),
                    firstWithoutLetter
                );
            } catch (IllegalArgumentException ex) {
                undoPreview();
                return ex.getMessage();
            }

            List<Command> commands = new ArrayList<>();
            for (int i = 0; i < createdWays.size(); i++) {
                commands.add(new ChangePropertyCommand(createdWays.get(i), "addr:housenumber", houseNumbers.get(i)));
            }
            if (!commands.isEmpty()) {
                undoRedoHandler.add(new SequenceCommand(tr("Assign house numbers"), commands));
            }
        }

        currentPreviewResult = splitResult;
        currentAppliedOptions = new AppliedOptions(parts, normalizedStart, increment, reverseOrder, firstWithoutLetter);
        return null;
    }

    public void undoPreview() {
        int currentSize = undoRedoHandler.getUndoCommands().size();
        int toUndo = currentSize - baselineUndoSize;
        if (toUndo > 0) {
            undoRedoHandler.undo(toUndo);
        }
        currentPreviewResult = null;
        currentAppliedOptions = null;
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
                dialogResult.isFirstWithoutLetter()
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

        String normalizedStart = normalizeStartValue(dialogResult.getStartHouseNumber());
        return currentAppliedOptions.parts == dialogResult.getParts()
            && currentAppliedOptions.increment == dialogResult.getIncrement()
            && currentAppliedOptions.reverseOrder == dialogResult.isReverseOrder()
            && currentAppliedOptions.firstWithoutLetter == dialogResult.isFirstWithoutLetter()
            && currentAppliedOptions.startHouseNumber.equals(normalizedStart);
    }

    private String normalizeStartValue(String startHouseNumber) {
        return startHouseNumber == null ? "" : startHouseNumber.trim();
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

        private AppliedOptions(
            int parts,
            String startHouseNumber,
            int increment,
            boolean reverseOrder,
            boolean firstWithoutLetter
        ) {
            this.parts = parts;
            this.startHouseNumber = startHouseNumber;
            this.increment = increment;
            this.reverseOrder = reverseOrder;
            this.firstWithoutLetter = firstWithoutLetter;
        }
    }
}


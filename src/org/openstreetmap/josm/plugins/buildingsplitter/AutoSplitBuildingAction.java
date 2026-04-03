package org.openstreetmap.josm.plugins.buildingsplitter;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import javax.swing.JOptionPane;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Shortcut;

public class AutoSplitBuildingAction extends JosmAction {

    private final AutoSplitBuildingService autoSplitService;
    private final AutoSplitOptionsDialog optionsDialog;
    private final HouseNumberService houseNumberService;
    private final VisibleAddressContextService visibleAddressContextService;

    public AutoSplitBuildingAction() {
        super(
            tr("AutoSplit Building"),
            "buildingsplitter_auto",
            tr("Automatically split a selected building into multiple parts"),
            Shortcut.registerShortcut(
                "tools:buildingsplitter:autosplitbuilding",
                tr("Tools: {0}", tr("AutoSplit Building")),
                0,
                Shortcut.NONE
            ),
            true
        );
        putValue(SMALL_ICON, ImageProvider.get("buildingsplitter_auto"));
        putValue(LARGE_ICON_KEY, ImageProvider.get("buildingsplitter_auto"));
        this.autoSplitService = new AutoSplitBuildingService();
        this.optionsDialog = new AutoSplitOptionsDialog();
        this.houseNumberService = new HouseNumberService();
        this.visibleAddressContextService = new VisibleAddressContextService();
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        DataSet dataSet = MainApplication.getLayerManager().getEditDataSet();
        if (dataSet == null) {
            showError(tr("No editable dataset is available."), JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        List<Way> selectedBuildings = collectValidSelectedBuildingWays(dataSet);
        if (selectedBuildings.isEmpty()) {
            showError(tr("No selected buildings meet AutoSplit requirements."), JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        int lastPartsValue = 2;
        int lastIncrementValue = 1;
        boolean lastReverseOrder = false;
        boolean lastFirstWithoutLetter = false;
        String lastStartHouseNumber = "";
        String lastStreet = "";
        String lastPostcode = visibleAddressContextService.detectUniformVisiblePostcode(dataSet);

        AddressContextBridge.AddressContext externalContext = AddressContextBridge.consumeAddressContext();
        String externalStreet = externalContext == null ? "" : externalContext.getStreet();
        String externalPostcode = externalContext == null ? "" : externalContext.getPostcode();

        List<Way> lastSuccessfulCreatedWays = Collections.emptyList();

        for (Way buildingWay : selectedBuildings) {
            if (buildingWay.isDeleted()) {
                continue;
            }

            dataSet.setSelected(Collections.singleton(buildingWay));
            AutoSplitPreviewSession previewSession = new AutoSplitPreviewSession(
                dataSet,
                buildingWay,
                autoSplitService,
                houseNumberService
            );

            List<String> visibleStreetNames = visibleAddressContextService.collectVisibleStreetNames(dataSet);
            String suggestedPostcode = visibleAddressContextService.detectUniformVisiblePostcode(dataSet);
            String defaultStreet = !externalStreet.isEmpty() ? externalStreet : lastStreet;
            String defaultPostcode = !externalPostcode.isEmpty()
                ? externalPostcode
                : (!lastPostcode.isEmpty() ? lastPostcode : suggestedPostcode);

            AutoSplitDialogResult dialogResult = optionsDialog.showDialog(
                MainApplication.getMainFrame(),
                lastPartsValue,
                lastIncrementValue,
                lastReverseOrder,
                lastFirstWithoutLetter,
                lastStartHouseNumber,
                defaultStreet,
                defaultPostcode,
                visibleStreetNames,
                previewSession::refreshPreview
            );
            if (dialogResult.isCancel()) {
                previewSession.undoPreview();
                break;
            }
            if (dialogResult.isSkip()) {
                previewSession.undoPreview();
                continue;
            }

            int requestedParts = dialogResult.getParts();
            lastPartsValue = requestedParts;
            lastIncrementValue = dialogResult.getIncrement();
            lastReverseOrder = dialogResult.isReverseOrder();
            lastFirstWithoutLetter = dialogResult.isFirstWithoutLetter();
            lastStartHouseNumber = dialogResult.getStartHouseNumber();
            lastStreet = dialogResult.getStreet();
            lastPostcode = dialogResult.getPostcode();

            SplitResult result = previewSession.finalizePreview(dialogResult);
            if (!result.isSuccess()) {
                previewSession.undoPreview();
                showError(result.getMessage(), JOptionPane.ERROR_MESSAGE);
                if (!askContinueAfterFailure()) {
                    break;
                }
                continue;
            }

            Logging.info("AutoSplitBuildingAction: " + result.getMessage());
            if (!result.getCreatedWays().isEmpty()) {
                lastSuccessfulCreatedWays = new ArrayList<>(result.getCreatedWays());
            }
        }

        if (!lastSuccessfulCreatedWays.isEmpty()) {
            dataSet.setSelected(lastSuccessfulCreatedWays);
        }
    }

    @Override
    protected void updateEnabledState() {
        setEnabled(MainApplication.getLayerManager().getEditDataSet() != null);
    }

    private List<Way> collectValidSelectedBuildingWays(DataSet dataSet) {
        List<Way> validWays = new ArrayList<>();
        Collection<Way> selectedWays = dataSet.getSelectedWays();
        for (Way way : selectedWays) {
            if (isValidAutoSplitCandidate(way)) {
                validWays.add(way);
            }
        }

        validWays.sort(Comparator.comparingLong(Way::getUniqueId));
        return validWays;
    }

    private boolean isValidAutoSplitCandidate(Way way) {
        if (way == null || way.isDeleted() || !way.isClosed() || !way.hasKey("building")) {
            return false;
        }

        return hasExactlyFourDistinctCorners(way);
    }

    private boolean hasExactlyFourDistinctCorners(Way way) {
        List<Node> nodes = new ArrayList<>(way.getNodes());
        if (nodes.size() != 5) {
            return false;
        }

        if (!nodes.get(0).equals(nodes.get(nodes.size() - 1))) {
            return false;
        }

        List<Node> corners = new ArrayList<>(nodes.subList(0, 4));
        if (corners.stream().distinct().count() != 4) {
            return false;
        }

        for (Node corner : corners) {
            if (corner.getCoor() == null) {
                return false;
            }
        }

        return true;
    }

    private boolean askContinueAfterFailure() {
        int choice = JOptionPane.showConfirmDialog(
            MainApplication.getMainFrame(),
            tr("AutoSplit failed for this building. Continue with the next selected building?"),
            tr("AutoSplit Building"),
            JOptionPane.YES_NO_OPTION,
            JOptionPane.QUESTION_MESSAGE
        );
        return choice == JOptionPane.YES_OPTION;
    }

    private void showError(String message, int messageType) {
        JOptionPane.showMessageDialog(
            MainApplication.getMainFrame(),
            message,
            tr("AutoSplit Building"),
            messageType
        );
    }

}


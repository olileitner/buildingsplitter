package org.openstreetmap.josm.plugins.buildingsplitter;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.data.osm.DataSet;
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
    // TEMP DEBUG: traces external context consume/default selection.
    private static final boolean DEBUG_CONTEXT_TRANSFER = false;

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
            showError(tr("No editable dataset is available."), UserNotifier.INFO_MESSAGE);
            return;
        }

        List<Way> selectedBuildings = collectValidSelectedBuildingWays(dataSet);
        if (selectedBuildings.isEmpty()) {
            showError(tr("No selected buildings meet AutoSplit requirements."), UserNotifier.INFO_MESSAGE);
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
        debugContext("consumeAddressContext present=" + (externalContext != null)
            + " street='" + externalStreet + "' postcode='" + externalPostcode + "'");

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
            String streetSource = !externalStreet.isEmpty() ? "external" : "remembered";
            String postcodeSource = !externalPostcode.isEmpty()
                ? "external"
                : (!lastPostcode.isEmpty() ? "remembered" : "visible");
            String defaultStreet = !externalStreet.isEmpty() ? externalStreet : lastStreet;
            String defaultPostcode = !externalPostcode.isEmpty()
                ? externalPostcode
                : (!lastPostcode.isEmpty() ? lastPostcode : suggestedPostcode);
            debugContext("dialog defaults street='" + defaultStreet + "' (" + streetSource + ")"
                + " postcode='" + defaultPostcode + "' (" + postcodeSource + ")");

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
                showError(result.getMessage(), UserNotifier.ERROR_MESSAGE);
                showError(
                    tr("AutoSplit failed for this building. Continuing with the next selected building."),
                    UserNotifier.INFO_MESSAGE
                );
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
            if (autoSplitService.isAutoSplitCandidate(way)) {
                validWays.add(way);
            }
        }

        validWays.sort(Comparator.comparingLong(Way::getUniqueId));
        return validWays;
    }

    private void showError(String message, int messageType) {
        UserNotifier.show(tr("AutoSplit Building"), message, messageType);
    }

    private void debugContext(String message) {
        if (!DEBUG_CONTEXT_TRANSFER) {
            return;
        }
        String fullMessage = "BuildingSplitter DEBUG (AutoSplitAction): " + message;
        Logging.info(fullMessage);
    }

}

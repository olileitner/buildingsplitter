package org.openstreetmap.josm.plugins.buildingsplitter;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

public class AutoSplitOptionsDialog {

    @FunctionalInterface
    public interface PreviewChangeListener {
        String onPreviewChanged(
            Integer parts,
            String startHouseNumber,
            int increment,
            boolean reverseOrder,
            boolean firstWithoutLetter,
            String street,
            String postcode
        );
    }

    private final HouseNumberService houseNumberService;

    public AutoSplitOptionsDialog() {
        this.houseNumberService = new HouseNumberService();
    }

    public AutoSplitDialogResult showDialog(
        Component parent,
        int defaultParts,
        int defaultIncrement,
        boolean defaultReverseOrder,
        boolean defaultFirstWithoutLetter,
        String defaultStartHouseNumber,
        String defaultStreet,
        String defaultPostcode,
        List<String> availableStreets,
        PreviewChangeListener previewChangeListener
    ) {
        Frame owner = JOptionPane.getFrameForComponent(parent);
        JDialog dialog = new JDialog(owner, tr("AutoSplit Building"), true);

        JTextField houseNumberField = new JTextField(defaultStartHouseNumber == null ? "" : defaultStartHouseNumber, 16);
        JTextField postcodeField = new JTextField(defaultPostcode == null ? "" : defaultPostcode, 16);
        JComboBox<String> streetCombo = createStreetCombo(defaultStreet, availableStreets);

        JLabel incrementValueLabel = new JLabel();
        JLabel validationLabel = new JLabel(" ");
        validationLabel.setForeground(new Color(180, 0, 0));

        final int[] selectedParts = {normalizeParts(defaultParts)};
        final int[] increment = {normalizeIncrement(defaultIncrement)};
        final boolean[] reverseOrder = {defaultReverseOrder};
        final boolean[] firstWithoutLetter = {defaultFirstWithoutLetter};
        final AutoSplitDialogResult[] result = {AutoSplitDialogResult.cancel()};

        JToggleButton firstWithoutLetterToggle = new JToggleButton(tr("First number without letter"));
        firstWithoutLetterToggle.setSelected(defaultFirstWithoutLetter);

        Runnable updatePreview = () -> {
            incrementValueLabel.setText(tr("Current increment: {0}", increment[0]));
            validationLabel.setText(" ");

            int parts = selectedParts[0];
            String startValue = normalizeText(houseNumberField.getText());
            String streetValue = normalizeText(getComboText(streetCombo));
            String postcodeValue = normalizeText(postcodeField.getText());

            boolean enableFirstWithoutLetter = houseNumberService.supportsFirstWithoutLetter(startValue);
            firstWithoutLetterToggle.setEnabled(enableFirstWithoutLetter);
            if (!enableFirstWithoutLetter) {
                firstWithoutLetter[0] = false;
                firstWithoutLetterToggle.setSelected(false);
            } else {
                firstWithoutLetter[0] = firstWithoutLetterToggle.isSelected();
            }

            String previewError = previewChangeListener == null
                ? null
                : previewChangeListener.onPreviewChanged(
                    parts,
                    startValue,
                    increment[0],
                    reverseOrder[0],
                    firstWithoutLetter[0],
                    streetValue,
                    postcodeValue
                );

            if (previewError != null) {
                validationLabel.setText(previewError);
                return;
            }

            if (!startValue.isEmpty()) {
                try {
                    houseNumberService.generateSequence(startValue, increment[0], parts, firstWithoutLetter[0]);
                } catch (IllegalArgumentException ex) {
                    validationLabel.setText(ex.getMessage());
                }
            }
        };

        DocumentListener previewUpdateListener = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                updatePreview.run();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                updatePreview.run();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                updatePreview.run();
            }
        };

        houseNumberField.getDocument().addDocumentListener(previewUpdateListener);
        postcodeField.getDocument().addDocumentListener(previewUpdateListener);
        streetCombo.addActionListener(e -> updatePreview.run());
        JTextField streetEditor = getComboEditorField(streetCombo);
        if (streetEditor != null) {
            streetEditor.getDocument().addDocumentListener(previewUpdateListener);
        }

        PartsPanelState partsPanelState = createPartsPanel(selectedParts, updatePreview);
        JPanel partsPanel = partsPanelState.panel;
        JButton plusOneButton = createIncrementButton("+1", 1, increment, updatePreview);
        JButton plusTwoButton = createIncrementButton("+2", 2, increment, updatePreview);
        JToggleButton reverseOrderToggle = createReverseToggle(reverseOrder, updatePreview);
        reverseOrderToggle.setSelected(defaultReverseOrder);
        firstWithoutLetterToggle.addActionListener(e -> {
            firstWithoutLetter[0] = firstWithoutLetterToggle.isSelected();
            updatePreview.run();
        });

        JButton okButton = new JButton(tr("Apply"));
        JButton skipButton = new JButton(tr("Skip"));
        JButton cancelButton = new JButton(tr("Cancel"));
        Runnable cancelDialog = () -> {
            result[0] = AutoSplitDialogResult.cancel();
            dialog.dispose();
        };

        okButton.addActionListener(e -> {
            int parts = selectedParts[0];
            String startValue = normalizeText(houseNumberField.getText());
            String streetValue = normalizeText(getComboText(streetCombo));
            String postcodeValue = normalizeText(postcodeField.getText());

            if (!startValue.isEmpty()) {
                try {
                    houseNumberService.generateSequence(startValue, increment[0], parts, firstWithoutLetter[0]);
                } catch (IllegalArgumentException ex) {
                    validationLabel.setText(ex.getMessage());
                    return;
                }
            }

            result[0] = AutoSplitDialogResult.apply(
                parts,
                startValue,
                increment[0],
                reverseOrder[0],
                firstWithoutLetter[0],
                streetValue,
                postcodeValue
            );
            dialog.dispose();
        });

        skipButton.addActionListener(e -> {
            result[0] = AutoSplitDialogResult.skip();
            dialog.dispose();
        });

        cancelButton.addActionListener(e -> cancelDialog.run());

        JPanel content = new JPanel(new BorderLayout(10, 10));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel formPanel = createFormPanel(
            partsPanel,
            houseNumberField,
            streetCombo,
            postcodeField,
            incrementValueLabel,
            plusOneButton,
            plusTwoButton,
            reverseOrderToggle,
            firstWithoutLetterToggle,
            validationLabel
        );

        content.add(formPanel, BorderLayout.NORTH);
        content.add(createButtonPanel(okButton, skipButton, cancelButton), BorderLayout.SOUTH);

        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(okButton);
        dialog.getRootPane().registerKeyboardAction(
            e -> cancelDialog.run(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW
        );
        dialog.pack();
        dialog.setLocationRelativeTo(parent);

        updatePreview.run();

        SwingUtilities.invokeLater(() -> partsPanelState.selectedButton.requestFocusInWindow());
        dialog.setVisible(true);

        return result[0];
    }

    private JComboBox<String> createStreetCombo(String defaultStreet, List<String> availableStreets) {
        Set<String> entries = new LinkedHashSet<>();
        entries.add("");
        if (availableStreets != null) {
            for (String street : availableStreets) {
                String normalized = normalizeText(street);
                if (!normalized.isEmpty()) {
                    entries.add(normalized);
                }
            }
        }
        String normalizedDefault = normalizeText(defaultStreet);
        if (!normalizedDefault.isEmpty()) {
            entries.add(normalizedDefault);
        }

        JComboBox<String> combo = new JComboBox<>(entries.toArray(new String[0]));
        combo.setEditable(true);
        combo.setSelectedItem(normalizedDefault);
        return combo;
    }

    private JTextField getComboEditorField(JComboBox<String> combo) {
        Object editor = combo.getEditor().getEditorComponent();
        return editor instanceof JTextField ? (JTextField) editor : null;
    }

    private String getComboText(JComboBox<String> combo) {
        Object selectedItem = combo.getEditor().getItem();
        return selectedItem == null ? "" : selectedItem.toString();
    }

    private String normalizeText(String text) {
        return text == null ? "" : text.trim();
    }

    private JButton createIncrementButton(String label, int value, int[] increment, Runnable updatePreview) {
        JButton button = new JButton(label);
        button.addActionListener(e -> {
            increment[0] = value;
            updatePreview.run();
        });
        return button;
    }

    private JToggleButton createReverseToggle(boolean[] reverseOrder, Runnable updatePreview) {
        JToggleButton toggle = new JToggleButton(tr("Reverse order"));
        toggle.addActionListener(e -> {
            reverseOrder[0] = toggle.isSelected();
            updatePreview.run();
        });
        return toggle;
    }

    private PartsPanelState createPartsPanel(int[] selectedParts, Runnable updatePreview) {
        JPanel partsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        ButtonGroup partsGroup = new ButtonGroup();
        Map<Integer, JToggleButton> partButtons = new HashMap<>();
        JToggleButton selectedButton = null;

        for (int value = 2; value <= 8; value++) {
            final int partValue = value;
            JToggleButton button = new JToggleButton(Integer.toString(value));
            button.addActionListener(e -> {
                selectedParts[0] = partValue;
                updatePreview.run();
            });
            if (value == selectedParts[0]) {
                button.setSelected(true);
                selectedButton = button;
            }
            partsGroup.add(button);
            partsPanel.add(button);
            partButtons.put(value, button);
        }

        if (selectedButton == null) {
            selectedButton = partButtons.get(2);
            if (selectedButton != null) {
                selectedButton.setSelected(true);
                selectedParts[0] = 2;
            }
        }

        bindPartsKeyShortcuts(partsPanel, partButtons, selectedParts, updatePreview);
        return new PartsPanelState(partsPanel, selectedButton);
    }

    private void bindPartsKeyShortcuts(
        JPanel partsPanel,
        Map<Integer, JToggleButton> partButtons,
        int[] selectedParts,
        Runnable updatePreview
    ) {
        for (int value = 2; value <= 8; value++) {
            final int partValue = value;
            JToggleButton button = partButtons.get(value);
            if (button == null) {
                continue;
            }
            String actionKey = "select-parts-" + partValue;
            partsPanel.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_0 + partValue, 0), actionKey);
            partsPanel.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_NUMPAD0 + partValue, 0), actionKey);
            partsPanel.getActionMap().put(actionKey, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    selectedParts[0] = partValue;
                    button.setSelected(true);
                    button.requestFocusInWindow();
                    updatePreview.run();
                }
            });
        }
    }

    private static final class PartsPanelState {
        private final JPanel panel;
        private final JToggleButton selectedButton;

        private PartsPanelState(JPanel panel, JToggleButton selectedButton) {
            this.panel = panel;
            this.selectedButton = selectedButton;
        }
    }

    private JPanel createFormPanel(
        JPanel partsPanel,
        JTextField houseNumberField,
        JComboBox<String> streetCombo,
        JTextField postcodeField,
        JLabel incrementValueLabel,
        JButton plusOneButton,
        JButton plusTwoButton,
        JToggleButton reverseOrderToggle,
        JToggleButton firstWithoutLetterToggle,
        JLabel validationLabel
    ) {
        JPanel formPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.WEST;

        partsPanel.setPreferredSize(new Dimension(320, partsPanel.getPreferredSize().height));
        houseNumberField.setPreferredSize(new Dimension(220, houseNumberField.getPreferredSize().height));
        postcodeField.setPreferredSize(new Dimension(220, postcodeField.getPreferredSize().height));
        streetCombo.setPreferredSize(new Dimension(220, streetCombo.getPreferredSize().height));

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        formPanel.add(new JLabel(tr("Parts:")), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        formPanel.add(partsPanel, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        formPanel.add(new JLabel(tr("Street (optional):")), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        formPanel.add(streetCombo, gbc);

        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        formPanel.add(new JLabel(tr("Postcode (optional):")), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        formPanel.add(postcodeField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        formPanel.add(new JLabel(tr("Starting house number (optional):")), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        formPanel.add(houseNumberField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 4;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        formPanel.add(new JLabel(tr("Increment:")), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        formPanel.add(createIncrementPanel(incrementValueLabel, plusOneButton, plusTwoButton), gbc);

        gbc.gridx = 0;
        gbc.gridy = 5;
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        formPanel.add(new JLabel(""), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.NONE;
        formPanel.add(reverseOrderToggle, gbc);

        gbc.gridx = 0;
        gbc.gridy = 6;
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        formPanel.add(new JLabel(""), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        formPanel.add(firstWithoutLetterToggle, gbc);

        gbc.gridx = 0;
        gbc.gridy = 7;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        formPanel.add(validationLabel, gbc);

        return formPanel;
    }

    private JPanel createIncrementPanel(
        JLabel incrementValueLabel,
        JButton plusOneButton,
        JButton plusTwoButton
    ) {
        JPanel incrementPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        incrementPanel.add(plusOneButton);
        incrementPanel.add(plusTwoButton);
        incrementPanel.add(incrementValueLabel);
        return incrementPanel;
    }

    private int normalizeParts(int parts) {
        if (parts < 2) {
            return 2;
        }
        if (parts > 8) {
            return 8;
        }
        return parts;
    }

    private int normalizeIncrement(int increment) {
        return increment == 2 ? 2 : 1;
    }

    private JPanel createButtonPanel(JButton okButton, JButton skipButton, JButton cancelButton) {
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttonPanel.add(okButton);
        buttonPanel.add(skipButton);
        buttonPanel.add(cancelButton);
        return buttonPanel;
    }
}

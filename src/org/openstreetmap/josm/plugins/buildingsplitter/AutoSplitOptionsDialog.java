package org.openstreetmap.josm.plugins.buildingsplitter;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

public class AutoSplitOptionsDialog {

    @FunctionalInterface
    public interface PreviewChangeListener {
        String onPreviewChanged(Integer parts, String startHouseNumber, int increment);
    }

    private final HouseNumberService houseNumberService;

    public AutoSplitOptionsDialog() {
        this.houseNumberService = new HouseNumberService();
    }

    public AutoSplitDialogResult showDialog(
        Component parent,
        int defaultParts,
        int defaultIncrement,
        String defaultStartHouseNumber,
        PreviewChangeListener previewChangeListener
    ) {
        Frame owner = JOptionPane.getFrameForComponent(parent);
        JDialog dialog = new JDialog(owner, tr("AutoSplit Building"), true);

        JTextField partsField = new JTextField(Integer.toString(Math.max(2, defaultParts)), 8);
        JTextField houseNumberField = new JTextField(defaultStartHouseNumber == null ? "" : defaultStartHouseNumber, 12);
        JLabel incrementValueLabel = new JLabel();
        JLabel validationLabel = new JLabel(" ");
        validationLabel.setForeground(new Color(180, 0, 0));

        JTextArea previewArea = new JTextArea(8, 28);
        previewArea.setEditable(false);
        previewArea.setLineWrap(true);
        previewArea.setWrapStyleWord(true);

        final int[] increment = {defaultIncrement == 0 ? 1 : defaultIncrement};
        final AutoSplitDialogResult[] result = {AutoSplitDialogResult.cancel()};

        Runnable updatePreview = () -> {
            incrementValueLabel.setText(tr("Current increment: {0}", increment[0]));
            validationLabel.setText(" ");

            int parts;
            try {
                parts = Integer.parseInt(partsField.getText().trim());
                if (parts < 2) {
                    if (previewChangeListener != null) {
                        previewChangeListener.onPreviewChanged(null, houseNumberField.getText().trim(), increment[0]);
                    }
                    previewArea.setText(tr("Enter a parts value >= 2."));
                    return;
                }
            } catch (NumberFormatException ex) {
                if (previewChangeListener != null) {
                    previewChangeListener.onPreviewChanged(null, houseNumberField.getText().trim(), increment[0]);
                }
                previewArea.setText(tr("Enter a valid integer for parts."));
                return;
            }

            String startValue = houseNumberField.getText().trim();
            if (startValue.isEmpty()) {
                String previewError = previewChangeListener == null
                    ? null
                    : previewChangeListener.onPreviewChanged(parts, startValue, increment[0]);
                if (previewError != null) {
                    previewArea.setText(previewError);
                    return;
                }
                previewArea.setText(tr("No house numbers will be assigned."));
                return;
            }

            try {
                List<String> numbers = houseNumberService.generateSequence(startValue, increment[0], parts);
                String previewError = previewChangeListener == null
                    ? null
                    : previewChangeListener.onPreviewChanged(parts, startValue, increment[0]);
                if (previewError != null) {
                    previewArea.setText(previewError);
                    return;
                }
                StringBuilder builder = new StringBuilder();
                builder.append(tr("Preview (assignment order):")).append('\n');
                for (int i = 0; i < numbers.size(); i++) {
                    builder.append(i + 1).append(": ").append(numbers.get(i)).append('\n');
                }
                previewArea.setText(builder.toString());
            } catch (IllegalArgumentException ex) {
                if (previewChangeListener != null) {
                    previewChangeListener.onPreviewChanged(null, startValue, increment[0]);
                }
                previewArea.setText(ex.getMessage());
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

        partsField.getDocument().addDocumentListener(previewUpdateListener);
        houseNumberField.getDocument().addDocumentListener(previewUpdateListener);

        JButton plusOneButton = createIncrementButton("+1", 1, increment, updatePreview);
        JButton plusTwoButton = createIncrementButton("+2", 2, increment, updatePreview);
        JButton minusOneButton = createIncrementButton("-1", -1, increment, updatePreview);
        JButton minusTwoButton = createIncrementButton("-2", -2, increment, updatePreview);

        JButton okButton = new JButton(tr("OK"));
        JButton skipButton = new JButton(tr("Skip"));
        JButton cancelButton = new JButton(tr("Cancel"));

        okButton.addActionListener(e -> {
            int parts;
            try {
                parts = Integer.parseInt(partsField.getText().trim());
            } catch (NumberFormatException ex) {
                validationLabel.setText(tr("Please enter a valid integer for number of parts."));
                return;
            }

            if (parts < 2) {
                validationLabel.setText(tr("Number of parts must be at least 2."));
                return;
            }

            String startValue = houseNumberField.getText().trim();
            if (!startValue.isEmpty()) {
                try {
                    houseNumberService.generateSequence(startValue, increment[0], parts);
                } catch (IllegalArgumentException ex) {
                    validationLabel.setText(ex.getMessage());
                    return;
                }
            }

            result[0] = AutoSplitDialogResult.apply(parts, startValue, increment[0]);
            dialog.dispose();
        });

        skipButton.addActionListener(e -> {
            result[0] = AutoSplitDialogResult.skip();
            dialog.dispose();
        });

        cancelButton.addActionListener(e -> {
            result[0] = AutoSplitDialogResult.cancel();
            dialog.dispose();
        });

        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel formPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 3, 3, 3);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0;
        gbc.gridy = 0;
        formPanel.add(new JLabel(tr("Split into how many parts?")), gbc);
        gbc.gridx = 1;
        formPanel.add(partsField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        formPanel.add(new JLabel(tr("Starting house number (optional):")), gbc);
        gbc.gridx = 1;
        formPanel.add(houseNumberField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 2;
        formPanel.add(new JLabel(tr("Increment:")), gbc);

        JPanel incrementPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        incrementPanel.add(plusOneButton);
        incrementPanel.add(plusTwoButton);
        incrementPanel.add(minusOneButton);
        incrementPanel.add(minusTwoButton);
        incrementPanel.add(incrementValueLabel);

        gbc.gridx = 1;
        formPanel.add(incrementPanel, gbc);

        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.gridwidth = 2;
        formPanel.add(new JLabel(tr("For left-right buildings, numbering runs from left to right; otherwise from top to bottom.")), gbc);

        gbc.gridy = 4;
        formPanel.add(validationLabel, gbc);

        content.add(formPanel, BorderLayout.NORTH);
        content.add(new JScrollPane(previewArea), BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(okButton);
        buttonPanel.add(skipButton);
        buttonPanel.add(cancelButton);
        content.add(buttonPanel, BorderLayout.SOUTH);

        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(okButton);
        dialog.pack();
        dialog.setLocationRelativeTo(parent);

        updatePreview.run();

        SwingUtilities.invokeLater(partsField::requestFocusInWindow);
        dialog.setVisible(true);

        return result[0];
    }

    private JButton createIncrementButton(String label, int value, int[] increment, Runnable updatePreview) {
        JButton button = new JButton(label);
        button.addActionListener(e -> {
            increment[0] = value;
            updatePreview.run();
        });
        return button;
    }
}


package com.example.unifiedqarunner;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class RunnerWindow {
    private final Path projectRoot;
    private final ExecutionService executionService;
    private final JFrame frame = new JFrame("Unified QA Runner");
    private final JComboBox<ExecutionService.Kind> kindBox =
            new JComboBox<>(ExecutionService.Kind.values());
    private final JComboBox<String> selectionBox = new JComboBox<>();
    private final JTextField targetField = new JTextField("http://127.0.0.1:8080", 34);
    private final JCheckBox authorizedBox =
            new JCheckBox("I am authorized to run tests against this non-local target");
    private final JCheckBox headlessBox = new JCheckBox("Run browser tests headlessly", true);
    private final JTextArea outputArea = new JTextArea(18, 90);
    private final JButton runButton = new JButton("Run");
    private final JButton stopButton = new JButton("Stop");
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "qa-runner");
        thread.setDaemon(true);
        return thread;
    });

    RunnerWindow(Path projectRoot) {
        this.projectRoot = projectRoot;
        this.executionService = new ExecutionService(projectRoot);
    }

    void show() {
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout(12, 12));
        frame.add(buildControls(), BorderLayout.NORTH);
        outputArea.setEditable(false);
        outputArea.setLineWrap(true);
        outputArea.setWrapStyleWord(true);
        frame.add(new JScrollPane(outputArea), BorderLayout.CENTER);
        frame.add(buildButtons(), BorderLayout.SOUTH);
        frame.setJMenuBar(null);
        kindBox.addActionListener(event -> refreshSelections());
        runButton.addActionListener(event -> runSelected());
        stopButton.addActionListener(event -> executionService.cancel());
        stopButton.setEnabled(false);
        refreshSelections();
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        append("Local project: " + projectRoot);
        append("Reports are saved to target/runner-reports. Only aggregate test counts are retained.");
    }

    private JPanel buildControls() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 0, 12));
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(4, 4, 4, 8);
        constraints.anchor = GridBagConstraints.WEST;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        addRow(panel, constraints, 0, "Runner", kindBox);
        addRow(panel, constraints, 1, "Test / collection", selectionBox);
        addRow(panel, constraints, 2, "Target URL", targetField);
        constraints.gridx = 1;
        constraints.gridy = 3;
        constraints.weightx = 1;
        panel.add(authorizedBox, constraints);
        constraints.gridy = 4;
        panel.add(headlessBox, constraints);
        return panel;
    }

    private void addRow(JPanel panel, GridBagConstraints constraints, int row,
                        String label, java.awt.Component component) {
        constraints.gridx = 0;
        constraints.gridy = row;
        constraints.weightx = 0;
        panel.add(new JLabel(label), constraints);
        constraints.gridx = 1;
        constraints.weightx = 1;
        panel.add(component, constraints);
    }

    private JPanel buildButtons() {
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.setBorder(BorderFactory.createEmptyBorder(0, 12, 12, 12));
        buttons.add(stopButton);
        buttons.add(runButton);
        return buttons;
    }

    private void refreshSelections() {
        ExecutionService.Kind kind = (ExecutionService.Kind) kindBox.getSelectedItem();
        selectionBox.removeAllItems();
        try {
            for (String selection : selections(kind)) {
                selectionBox.addItem(selection);
            }
        } catch (IOException exception) {
            append("Could not discover project tests: " + exception.getMessage());
        }
    }

    private List<String> selections(ExecutionService.Kind kind) throws IOException {
        return ProjectCatalog.discover(projectRoot).get(kind);
    }

    private void runSelected() {
        ExecutionService.Kind kind = (ExecutionService.Kind) kindBox.getSelectedItem();
        String selection = (String) selectionBox.getSelectedItem();
        if (kind == null || selection == null) {
            showNoTestsError();
            return;
        }
        runButton.setEnabled(false);
        stopButton.setEnabled(true);
        append("");
        append("Starting " + kind.label() + ": " + selection);
        executor.submit(() -> {
            try {
                ExecutionService.RunResult result = executionService.execute(kind, selection,
                        targetField.getText(), authorizedBox.isSelected(),
                        headlessBox.isSelected(), List.of("chrome"),
                        line -> SwingUtilities.invokeLater(() -> append(line)));
                SwingUtilities.invokeLater(() -> {
                    append("Finished with exit code " + result.exitCode());
                    append("HTML report: " + result.reportDirectory().resolve("index.html"));
                    append("CSV summary: " + result.reportDirectory().resolve("summary.csv"));
                    runButton.setEnabled(true);
                    stopButton.setEnabled(false);
                });
            } catch (Exception exception) {
                SwingUtilities.invokeLater(() -> {
                    append("Run could not be completed: " + exception.getMessage());
                    runButton.setEnabled(true);
                    stopButton.setEnabled(false);
                });
            }
        });
    }

    private void showNoTestsError() {
        JOptionPane.showMessageDialog(frame,
                "No compatible tests or collections were found in this project.",
                "Unified QA Runner", JOptionPane.WARNING_MESSAGE);
    }

    private void append(String message) {
        outputArea.append(message + System.lineSeparator());
        outputArea.setCaretPosition(outputArea.getDocument().getLength());
    }
}

package com.example.unifiedqarunner;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import java.nio.file.Path;
import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        Path projectRoot = findProjectRoot(args);
        if (projectRoot == null) {
            projectRoot = chooseProjectRoot();
        }
        if (projectRoot == null) {
            return;
        }
        try (RunnerServer server = new RunnerServer(projectRoot)) {
            URI address = server.start();
            System.out.println("Unified QA Runner is available at " + address);
            if (Desktop.isDesktopSupported()) {
                try {
                    RunnerServer.openBrowser(address);
                } catch (IOException exception) {
                    System.err.println("Could not open a browser: " + exception.getMessage());
                    System.err.println("Open the runner manually at " + address);
                }
            } else {
                System.out.println("Open the address above in a browser.");
            }
            server.awaitTermination();
        } catch (IOException exception) {
            showError("Could not start Unified QA Runner: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static Path findProjectRoot(String[] args) {
        List<Path> candidates = new ArrayList<>();
        if (args.length > 0) {
            candidates.add(Path.of(args[0]));
        }
        String configuredRoot = System.getProperty("qa.runner.projectRoot");
        if (configuredRoot != null && !configuredRoot.isBlank()) {
            candidates.add(Path.of(configuredRoot));
        }
        String environmentRoot = System.getenv("QA_RUNNER_PROJECT_ROOT");
        if (environmentRoot != null && !environmentRoot.isBlank()) {
            candidates.add(Path.of(environmentRoot));
        }
        candidates.add(Path.of(System.getProperty("user.dir")));
        try {
            candidates.add(Path.of(Main.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI()));
        } catch (Exception ignored) {
            // Fall back to the project folder chooser if the application location is unavailable.
        }
        for (Path candidate : candidates) {
            Path root = findPom(candidate);
            if (root != null) {
                return root;
            }
        }
        return null;
    }

    private static Path findPom(Path candidate) {
        Path current = candidate.toAbsolutePath().normalize();
        if (!java.nio.file.Files.isDirectory(current)) {
            current = current.getParent();
        }
        while (current != null) {
            if (java.nio.file.Files.isRegularFile(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private static Path chooseProjectRoot() {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            System.err.println("Select a project folder containing pom.xml to start the runner.");
            return null;
        }
        JFileChooser chooser = new JFileChooser(System.getProperty("user.home"));
        chooser.setDialogTitle("Select the project to run tests from");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        Path selected = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
        if (!java.nio.file.Files.isRegularFile(selected.resolve("pom.xml"))) {
            JOptionPane.showMessageDialog(null,
                    "Choose the project folder that contains pom.xml.",
                    "Unified QA Runner", JOptionPane.WARNING_MESSAGE);
            return chooseProjectRoot();
        }
        return selected;
    }

    private static void showError(String message) {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            System.err.println(message);
        } else {
            JOptionPane.showMessageDialog(null, message, "Unified QA Runner",
                    JOptionPane.ERROR_MESSAGE);
        }
    }
}

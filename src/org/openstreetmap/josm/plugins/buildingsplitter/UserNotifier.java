package org.openstreetmap.josm.plugins.buildingsplitter;

import javax.swing.JOptionPane;
import javax.swing.Timer;

import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.Notification;
import org.openstreetmap.josm.gui.util.GuiHelper;

/**
 * Shows short, non-blocking user messages. Prefer the status line to mimic
 * JOSM's save feedback; fall back to Notification when no map/status line exists.
 */
final class UserNotifier {

    static final int ERROR_MESSAGE = JOptionPane.ERROR_MESSAGE;
    static final int WARNING_MESSAGE = JOptionPane.WARNING_MESSAGE;
    static final int INFO_MESSAGE = JOptionPane.INFORMATION_MESSAGE;

    private static final int STATUS_MESSAGE_DURATION_MS = 4_000;
    private static final Object STATUS_TEXT_OWNER = UserNotifier.class;
    private static Timer statusResetTimer;

    private UserNotifier() {
        // Utility class.
    }

    static void show(String title, String message, int messageType) {
        if (message == null || message.isEmpty()) {
            return;
        }

        GuiHelper.runInEDT(() -> showInUi(title, message, messageType));
    }

    private static void showInUi(String title, String message, int messageType) {
        boolean shownInStatusLine = false;
        if (MainApplication.getMap() != null && MainApplication.getMap().statusLine != null) {
            MainApplication.getMap().statusLine.setHelpText(STATUS_TEXT_OWNER, message);
            ensureStatusResetTimer().restart();
            shownInStatusLine = true;
        }

        // Mirror higher-severity messages as notifications for visibility.
        if (!shownInStatusLine || messageType != INFO_MESSAGE) {
            new Notification(formatNotificationMessage(title, message))
                .setIcon(messageType)
                .setDuration(Notification.TIME_SHORT)
                .show();
        }
    }

    private static Timer ensureStatusResetTimer() {
        if (statusResetTimer != null) {
            return statusResetTimer;
        }
        statusResetTimer = new Timer(STATUS_MESSAGE_DURATION_MS, event -> {
            if (MainApplication.getMap() != null && MainApplication.getMap().statusLine != null) {
                MainApplication.getMap().statusLine.resetHelpText(STATUS_TEXT_OWNER);
            }
        });
        statusResetTimer.setRepeats(false);
        return statusResetTimer;
    }

    private static String formatNotificationMessage(String title, String message) {
        if (title == null || title.isEmpty()) {
            return message;
        }
        return title + ": " + message;
    }
}



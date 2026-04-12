package io.github.dsheirer.gui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;

public class NotificationManager {
    private static final Logger mLog = LoggerFactory.getLogger(NotificationManager.class);
    private static NotificationManager instance;

    private boolean useSystemTray = false;
    private TrayIcon trayIcon;

    private NotificationManager() {
        if (SystemTray.isSupported()) {
            try {
                SystemTray tray = SystemTray.getSystemTray();
                // We need a dummy image for the tray icon
                Image image = Toolkit.getDefaultToolkit().createImage(new byte[0]); 
                trayIcon = new TrayIcon(image, "SDRTrunk");
                trayIcon.setImageAutoSize(true);
                tray.add(trayIcon);
                useSystemTray = true;
                mLog.info("SystemTray initialized for native notifications.");
            } catch (AWTException e) {
                mLog.error("SystemTray is supported, but could not be added.", e);
            }
        } else {
            mLog.info("SystemTray is not supported on this platform. Falling back to Swing popups.");
        }
    }

    public static synchronized NotificationManager getInstance() {
        if (instance == null) {
            instance = new NotificationManager();
        }
        return instance;
    }

    public void showNotification(String title, String message, TrayIcon.MessageType messageType) {
        if (useSystemTray && trayIcon != null) {
            trayIcon.displayMessage(title, message, messageType);
        } else {
            // Fallback to legacy Swing popup
            int jOptionType = JOptionPane.INFORMATION_MESSAGE;
            if (messageType == TrayIcon.MessageType.ERROR) {
                jOptionType = JOptionPane.ERROR_MESSAGE;
            } else if (messageType == TrayIcon.MessageType.WARNING) {
                jOptionType = JOptionPane.WARNING_MESSAGE;
            }

            JOptionPane.showMessageDialog(null, message, title, jOptionType);
        }
    }
}

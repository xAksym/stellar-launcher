package pl.stellarlauncher;

import javax.swing.*;
import java.io.*;
import java.util.*;

public class StellarLauncher {
    public static final String VERSION = "1.0.0";
    public static final File LAUNCHER_DIR = new File(System.getProperty("user.home"), ".stellarlauncher");
    public static final File MINECRAFT_DIR = new File(LAUNCHER_DIR, "minecraft");
    public static final File VERSIONS_DIR = new File(MINECRAFT_DIR, "versions");
    public static final File LIBRARIES_DIR = new File(MINECRAFT_DIR, "libraries");
    public static final File ASSETS_DIR = new File(MINECRAFT_DIR, "assets");
    public static final File MODS_DIR = new File(MINECRAFT_DIR, "mods");
    public static final File NATIVES_DIR = new File(LAUNCHER_DIR, "natives");

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                // Nowoczesny wygląd
                for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                    if ("Nimbus".equals(info.getName())) {
                        UIManager.setLookAndFeel(info.getClassName());
                        break;
                    }
                }
            } catch (Exception e) {
                try {
                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }

            LAUNCHER_DIR.mkdirs();
            MINECRAFT_DIR.mkdirs();
            VERSIONS_DIR.mkdirs();
            LIBRARIES_DIR.mkdirs();
            ASSETS_DIR.mkdirs();
            MODS_DIR.mkdirs();
            NATIVES_DIR.mkdirs();

            new LauncherGUI();
        });
    }
}

class Account {
    String username;
    String uuid;
    boolean isPremium;
    String accessToken;

    public Account(String username, boolean isPremium) {
        this.username = username;
        this.isPremium = isPremium;
        this.uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes()).toString().replace("-", "");
        this.accessToken = null;
    }
}

class DiscordRPC {
    private boolean initialized = false;

    public void init() {
        // Discord RPC initialization would go here
        // Requires discord-rpc library: https://github.com/Vatuu/discord-rpc
        initialized = true;
    }

    public void update(String details, String state) {
        if (!initialized)
            return;

        // Update Discord Rich Presence
        // Example with discord-rpc library:
        /*
         * DiscordRichPresence presence = new DiscordRichPresence();
         * presence.details = details;
         * presence.state = state;
         * presence.largeImageKey = "stellarlauncher_logo";
         * presence.largeImageText = "StellarLauncher";
         * DiscordRPC.discordUpdatePresence(presence);
         */
    }

    public void shutdown() {
        if (!initialized)
            return;
        // DiscordRPC.discordShutdown();
        initialized = false;
    }
}
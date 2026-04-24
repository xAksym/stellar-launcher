package pl.stellarlauncher;

import com.google.gson.*;
import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;

public class AutoUpdater {

    private static final String REPO = "xAksym/stellar-launcher"; // ← zmień
    private static final String API_URL = "https://api.github.com/repos/" + REPO + "/releases/latest";

    private final LauncherGUI gui;
    private final File launcherJar;

    public AutoUpdater(LauncherGUI gui) {
        this.gui = gui;
        // Znajdź JAR którym jesteśmy uruchomieni
        this.launcherJar = findCurrentJar();
    }

    public void checkAsync() {
        new Thread(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(API_URL).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setRequestProperty("User-Agent", "StellarLauncher/" + StellarLauncher.VERSION);

                if (conn.getResponseCode() != 200)
                    return;

                String body = new String(conn.getInputStream().readAllBytes());
                JsonObject release = JsonParser.parseString(body).getAsJsonObject();
                String latestTag = release.get("tag_name").getAsString().replaceAll("^v", "");

                if (!isNewer(latestTag, StellarLauncher.VERSION))
                    return;

                String downloadUrl = null;
                JsonArray assets = release.getAsJsonArray("assets");
                for (JsonElement el : assets) {
                    String name = el.getAsJsonObject().get("name").getAsString();
                    if (name.endsWith(".jar") || name.endsWith(".exe")) {
                        downloadUrl = el.getAsJsonObject()
                                .get("browser_download_url").getAsString();
                        break;
                    }
                }
                if (downloadUrl == null)
                    return;

                final String url = downloadUrl;
                final String tag = latestTag;
                SwingUtilities.invokeLater(() -> promptUpdate(tag, url));

            } catch (Exception e) {
                System.out.println("[AutoUpdater] Check failed: " + e.getMessage());
            }
        }, "UpdateChecker").start();
    }

    private void promptUpdate(String newVersion, String downloadUrl) {
        int choice = JOptionPane.showConfirmDialog(
                gui,
                "Dostępna jest nowa wersja StellarLauncher: v" + newVersion + "\n" +
                        "Aktualna: v" + StellarLauncher.VERSION + "\n\n" +
                        "Zainstalować teraz? Launcher uruchomi się ponownie.",
                "Aktualizacja dostępna",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.INFORMATION_MESSAGE);
        if (choice == JOptionPane.YES_OPTION) {
            downloadAndReplace(downloadUrl);
        }
    }

    private void downloadAndReplace(String downloadUrl) {
        JProgressBar bar = new JProgressBar(0, 100);
        bar.setStringPainted(true);
        bar.setString("Pobieranie...");

        JDialog dlg = new JDialog(gui, "Aktualizacja", true);
        dlg.setLayout(new BorderLayout(10, 10));
        dlg.add(new JLabel("  Pobieranie aktualizacji...  "), BorderLayout.NORTH);
        dlg.add(bar, BorderLayout.CENTER);
        dlg.setSize(340, 100);
        dlg.setLocationRelativeTo(gui);
        dlg.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

        new Thread(() -> {
            try {
                File updateJar = new File(StellarLauncher.LAUNCHER_DIR, "update.jar");

                HttpURLConnection conn = (HttpURLConnection) new URL(downloadUrl).openConnection();
                long total = conn.getContentLengthLong();

                try (InputStream in = conn.getInputStream();
                        FileOutputStream fos = new FileOutputStream(updateJar)) {
                    byte[] buf = new byte[8192];
                    long downloaded = 0;
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        fos.write(buf, 0, n);
                        downloaded += n;
                        if (total > 0) {
                            int pct = (int) (downloaded * 100 / total);
                            SwingUtilities.invokeLater(() -> {
                                bar.setValue(pct);
                                bar.setString(pct + "%");
                            });
                        }
                    }
                }

                // Napisz skrypt który podmieni JAR i zrestartuje
                String restartScript = buildRestartScript(updateJar);
                launchRestartScript(restartScript);

                SwingUtilities.invokeLater(() -> {
                    dlg.dispose();
                    System.exit(0);
                });

            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    dlg.dispose();
                    JOptionPane.showMessageDialog(gui,
                            "Aktualizacja nie powiodła się:\n" + e.getMessage(),
                            "Błąd", JOptionPane.ERROR_MESSAGE);
                });
            }
        }, "Updater-Download").start();

        dlg.setVisible(true);
    }

    private String buildRestartScript(File updateJar) throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase();
        File target = launcherJar != null ? launcherJar
                : new File(StellarLauncher.LAUNCHER_DIR, "StellarLauncher.jar");

        if (os.contains("win")) {
            File script = new File(StellarLauncher.LAUNCHER_DIR, "updater.bat");
            String content = "@echo off\r\n" +
                    "timeout /t 2 /nobreak >nul\r\n" +
                    "copy /Y \"" + updateJar.getAbsolutePath() + "\" \"" + target.getAbsolutePath() + "\"\r\n" +
                    "del \"" + updateJar.getAbsolutePath() + "\"\r\n" +
                    "start javaw -jar \"" + target.getAbsolutePath() + "\"\r\n" +
                    "del \"%~f0\"\r\n";
            Files.writeString(script.toPath(), content);
            return script.getAbsolutePath();
        } else {
            File script = new File(StellarLauncher.LAUNCHER_DIR, "updater.sh");
            String content = "#!/bin/bash\n" +
                    "sleep 2\n" +
                    "cp -f \"" + updateJar.getAbsolutePath() + "\" \"" + target.getAbsolutePath() + "\"\n" +
                    "rm -f \"" + updateJar.getAbsolutePath() + "\"\n" +
                    "java -jar \"" + target.getAbsolutePath() + "\" &\n" +
                    "rm -f \"$0\"\n";
            Files.writeString(script.toPath(), content);
            script.setExecutable(true);
            return script.getAbsolutePath();
        }
    }

    private void launchRestartScript(String scriptPath) throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            new ProcessBuilder("cmd", "/c", "start", scriptPath).start();
        } else {
            new ProcessBuilder("bash", scriptPath).start();
        }
    }

    private File findCurrentJar() {
        try {
            return new File(AutoUpdater.class
                    .getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            return null;
        }
    }

    // "1.0.1" > "1.0.0" → true
    private boolean isNewer(String remote, String local) {
        try {
            int[] r = parseVer(remote), l = parseVer(local);
            for (int i = 0; i < Math.max(r.length, l.length); i++) {
                int rv = i < r.length ? r[i] : 0;
                int lv = i < l.length ? l[i] : 0;
                if (rv != lv)
                    return rv > lv;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private int[] parseVer(String v) {
        String[] parts = v.split("\\.");
        int[] nums = new int[parts.length];
        for (int i = 0; i < parts.length; i++)
            nums[i] = Integer.parseInt(parts[i].replaceAll("[^0-9]", ""));
        return nums;
    }
}
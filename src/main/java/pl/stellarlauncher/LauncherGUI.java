package pl.stellarlauncher;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.regex.*;
import com.google.gson.*;
import pl.stellarlauncher.discord.*;

class LauncherGUI extends JFrame {
    private JComboBox<String> versionCombo;
    private JButton launchButton;
    private JTextArea logArea;
    private JProgressBar progressBar;
    private JLabel statusLabel;
    private JLabel ramLabel;
    private JSlider ramSlider;
    private String selectedVersion = "1.20.1";
    private volatile boolean isLaunching = false;
    private Process minecraftProcess;
    private int allocatedRAM = 4096;

    private volatile int currentFPS = 0;
    private volatile int currentPing = 0;
    private volatile String currentBiome = "Unknown";
    private volatile String currentDimension = "Overworld";
    private volatile int posX = 0;
    private volatile int posY = 0;
    private volatile int posZ = 0;
    private volatile int playersOnline = 0;
    private volatile long gameStartTimestamp = 0;

    private JScrollPane scrollPane;

    private File configFile;
    private JsonObject config;

    private final Color MC_DARK = new Color(32, 32, 32);
    private final Color MC_GRAY = new Color(85, 85, 85);
    private final Color MC_TEXT = new Color(255, 255, 255);
    private final Color MC_GREEN = new Color(85, 255, 85);

    private final java.util.concurrent.BlockingQueue<Runnable> discordUpdateQueue = new java.util.concurrent.LinkedBlockingQueue<>();

    private boolean autoCloseLauncher = true;
    private JCheckBox autoCloseCheckbox;

    private volatile boolean discordRunning = false;
    private Thread discordThread;
    private Thread discordUpdateProcessor;
    private Thread periodicUpdater;
    private final String DISCORD_APP_ID = "1465372435174916220";
    private pl.stellarlauncher.discord.DiscordRPC discordRPC;

    private String currentServer = "SinglePlayer";

    // ─── FABRIC LOADER VERSIONS PER MINECRAFT VERSION ────────────────────────────
    private static final java.util.Map<String, String> FABRIC_LOADER_VERSIONS;
    static {
        FABRIC_LOADER_VERSIONS = new java.util.HashMap<>();
        FABRIC_LOADER_VERSIONS.put("1.19.2", "0.14.24");
        FABRIC_LOADER_VERSIONS.put("1.20.1", "0.18.4");
        FABRIC_LOADER_VERSIONS.put("1.21.3", "0.18.4");
        // 1.8.9 nie używa Fabric — vanilla launch wrapper
    }

    private String getFabricLoaderVersion(String vanillaVersion) {
        return FABRIC_LOADER_VERSIONS.getOrDefault(vanillaVersion, "0.18.4");
    }

    private BufferedImage backgroundImage = null;

    // ─── LOG (null-safe before initComponents) ───────────────────────────────────

    private void log(String message) {
        if (logArea == null) {
            System.out.println(message);
            return;
        }
        SwingUtilities.invokeLater(() -> {
            String ts = new java.text.SimpleDateFormat("HH:mm:ss").format(new Date());
            logArea.append("[" + ts + "] " + message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    // ─── CONSTRUCTOR ─────────────────────────────────────────────────────────────

    public LauncherGUI() {
        setTitle("Stellar Launcher");
        setSize(1280, 720);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setResizable(false);
        setUndecorated(true);

        configFile = new File(StellarLauncher.LAUNCHER_DIR, "config.json");
        loadConfig();
        loadBackgroundImage();

        initComponents();
        loadIcon();
        createModsFolders();
        loadLastVersion();
        loadRAMSettings();
        loadAutoCloseSettings();
        initDiscordRPC();

        setVisible(true);
    }

    // ─── ICON ────────────────────────────────────────────────────────────────────

    private void loadIcon() {
        try {
            String[] resourcePaths = {
                    "/stellarlauncher_icon.png",
                    "/images/stellarlauncher_icon.png",
                    "/icon.png",
                    "/images/icon.png",
                    "/logo.png",
            };

            BufferedImage icon = null;

            for (String path : resourcePaths) {
                try (InputStream is = getClass().getResourceAsStream(path)) {
                    if (is != null) {
                        BufferedImage img = ImageIO.read(is);
                        if (img != null) {
                            icon = img;
                            log("✅ Icon loaded: " + path);
                            break;
                        }
                    }
                } catch (Exception ignored) {
                }
            }

            if (icon == null) {
                for (String path : new String[] { "/stellarlauncher_icon.ico", "/images/stellarlauncher_icon.ico" }) {
                    try (InputStream is = getClass().getResourceAsStream(path)) {
                        if (is != null) {
                            BufferedImage img = ImageIO.read(is);
                            if (img != null) {
                                icon = img;
                                log("✅ Icon loaded from .ico: " + path);
                                break;
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            }

            if (icon == null) {
                for (String name : new String[] { "stellarlauncher_icon.png", "stellarlauncher_icon.ico",
                        "icon.png" }) {
                    File f = new File(StellarLauncher.LAUNCHER_DIR, name);
                    if (f.exists()) {
                        try {
                            icon = ImageIO.read(f);
                            if (icon != null) {
                                log("✅ Icon from disk: " + name);
                                break;
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
            }

            if (icon == null) {
                icon = createDefaultIcon();
                log("⚠️ Using default icon");
            }

            List<Image> icons = new ArrayList<>();
            for (int size : new int[] { 16, 20, 24, 32, 40, 48, 64, 128, 256 })
                icons.add(icon.getScaledInstance(size, size, Image.SCALE_SMOOTH));
            setIconImage(icon);
            setIconImages(icons);

        } catch (Exception e) {
            log("❌ Icon error: " + e.getMessage());
        }
    }

    private BufferedImage createDefaultIcon() {
        BufferedImage icon = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = icon.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(0, 0, new Color(50, 150, 255), 256, 256, new Color(30, 100, 200)));
        g.fillRoundRect(0, 0, 256, 256, 50, 50);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(8));
        g.drawRoundRect(4, 4, 248, 248, 50, 50);
        g.setFont(new Font("Arial", Font.BOLD, 72));
        FontMetrics fm = g.getFontMetrics();
        String t = "SL";
        g.drawString(t, (256 - fm.stringWidth(t)) / 2, (256 + fm.getAscent()) / 2 - 10);
        g.dispose();
        return icon;
    }

    // ─── MODS FOLDERS ────────────────────────────────────────────────────────────

    private void createModsFolders() {
        for (String v : new String[] { "1.8.9", "1.19.2", "1.20.1", "1.21.3" })
            new File(StellarLauncher.LAUNCHER_DIR, "mods/" + v).mkdirs();
        copyModsFromResources();
    }

    private void copyModsFromResources() {
        try {
            URL location = getClass().getProtectionDomain().getCodeSource().getLocation();
            File locationFile = new File(location.toURI());

            if (locationFile.isFile()) {
                copyModsFromJarFile(locationFile);
            } else {
                copyModsFromClassesDirectory(locationFile);
            }
        } catch (Exception e) {
            log("⚠️ Mods resources copy: " + e.getMessage());
        }
    }

    private void copyModsFromJarFile(File jarFile) {
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarFile)) {
            java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries();
            Map<String, Integer> copiedPerVersion = new java.util.LinkedHashMap<>();

            while (entries.hasMoreElements()) {
                java.util.jar.JarEntry entry = entries.nextElement();
                if (entry.isDirectory())
                    continue;

                String name = entry.getName();
                if (!name.startsWith("mods/") || !name.endsWith(".jar"))
                    continue;

                String[] segments = name.split("/");
                if (segments.length != 3)
                    continue;

                String version = segments[1];
                String fileName = segments[2];

                File targetDir = new File(StellarLauncher.LAUNCHER_DIR, "mods/" + version + "/.preinstalled");
                targetDir.mkdirs();
                hideDirectory(targetDir);
                File targetFile = new File(targetDir, fileName);

                try (InputStream in = jar.getInputStream(entry);
                        FileOutputStream fos = new FileOutputStream(targetFile)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1)
                        fos.write(buf, 0, n);
                    copiedPerVersion.merge(version, 1, Integer::sum);
                } catch (Exception ex) {
                    log("  ⚠️ Nie można skopiować moda: " + fileName + " → " + ex.getMessage());
                }
            }

            if (copiedPerVersion.isEmpty()) {
                log("  ℹ️ Brak modów osadzonych w JAR (resources/mods/)");
            } else {
                for (Map.Entry<String, Integer> e : copiedPerVersion.entrySet())
                    log("✅ Skopiowano " + e.getValue() + " preinstalled modów → mods/" + e.getKey() + "/.preinstalled");
            }
        } catch (Exception e) {
            log("❌ Błąd odczytu JAR dla modów: " + e.getMessage());
        }
    }

    private void copyModsFromClassesDirectory(File classesDir) {
        File[] candidates = {
                new File(classesDir, "mods"),
                new File(classesDir.getParentFile(), "resources/mods"),
                new File(classesDir.getParentFile(), "src/main/resources/mods"),
                new File("resources/mods"),
                new File("src/main/resources/mods"),
        };

        for (File modsRoot : candidates) {
            if (!modsRoot.exists() || !modsRoot.isDirectory())
                continue;

            File[] versionDirs = modsRoot.listFiles(File::isDirectory);
            if (versionDirs == null)
                continue;

            for (File versionDir : versionDirs) {
                File targetDir = new File(StellarLauncher.LAUNCHER_DIR,
                        "mods/" + versionDir.getName() + "/.preinstalled");
                targetDir.mkdirs();
                hideDirectory(targetDir);

                File[] mods = versionDir.listFiles((d, n) -> n.endsWith(".jar"));
                if (mods == null || mods.length == 0)
                    continue;

                int copied = 0;
                for (File mod : mods) {
                    try {
                        Files.copy(mod.toPath(),
                                new File(targetDir, mod.getName()).toPath(),
                                StandardCopyOption.REPLACE_EXISTING);
                        copied++;
                    } catch (Exception ignored) {
                    }
                }
                if (copied > 0)
                    log("✅ Skopiowano " + copied + " modów (IDE) → mods/" + versionDir.getName());
            }
            return;
        }
        log("  ℹ️ Nie znaleziono resources/mods/ (IDE mode)");
    }

    // ─── INIT COMPONENTS ─────────────────────────────────────────────────────────

    private void initComponents() {
        JPanel mainPanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2d = (Graphics2D) g;
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (backgroundImage != null) {
                    g2d.drawImage(backgroundImage, 0, 0, getWidth(), getHeight(), null);
                } else {
                    g2d.setPaint(new GradientPaint(0, 0, new Color(15, 15, 35), 0, getHeight(), new Color(40, 40, 70)));
                    g2d.fillRect(0, 0, getWidth(), getHeight());
                    g2d.setColor(new Color(255, 255, 255, 10));
                    Random rand = new Random(42);
                    for (int i = 0; i < 100; i++) {
                        int x = rand.nextInt(getWidth()), y = rand.nextInt(getHeight()), s = rand.nextInt(3) + 1;
                        g2d.fillOval(x, y, s, s);
                    }
                }
                g2d.setColor(new Color(0, 0, 0, 120));
                g2d.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        mainPanel.setLayout(null);
        mainPanel.setOpaque(false);

        createTitleBar(mainPanel);

        JPanel centerCard = createModernCard(240, 160, 800, 380);
        mainPanel.add(centerCard);

        JLabel titleLabel = new JLabel("STELLAR LAUNCHER", SwingConstants.CENTER);
        titleLabel.setFont(MinecraftFont.getBold(32));
        titleLabel.setForeground(new Color(100, 200, 255));
        titleLabel.setBounds(0, 20, 800, 40);
        centerCard.add(titleLabel);

        JLabel versionTag = new JLabel("v" + StellarLauncher.VERSION, SwingConstants.CENTER);
        versionTag.setFont(MinecraftFont.getFont(12));
        versionTag.setForeground(new Color(150, 150, 150));
        versionTag.setBounds(0, 62, 800, 20);
        centerCard.add(versionTag);

        JLabel versionLabelText = new JLabel("Minecraft Version:");
        versionLabelText.setFont(MinecraftFont.getBold(14));
        versionLabelText.setForeground(MC_TEXT);
        versionLabelText.setBounds(50, 105, 300, 25);
        centerCard.add(versionLabelText);

        versionCombo = new JComboBox<>(new String[] { "1.8.9", "1.19.2", "1.20.1", "1.21.3" });
        versionCombo.setFont(MinecraftFont.getFont(14));
        versionCombo.setBounds(50, 133, 300, 40);
        styleModernComboBox(versionCombo);
        versionCombo.addActionListener(e -> {
            String sel = (String) versionCombo.getSelectedItem();
            if (sel != null) {
                selectedVersion = sel;
                saveLastVersion();
            }
        });
        centerCard.add(versionCombo);

        ramLabel = new JLabel("Memory: " + allocatedRAM + " MB");
        ramLabel.setFont(MinecraftFont.getBold(14));
        ramLabel.setForeground(MC_TEXT);
        ramLabel.setBounds(450, 105, 300, 25);
        centerCard.add(ramLabel);

        ramSlider = new JSlider(1024, 16384, allocatedRAM);
        ramSlider.setMajorTickSpacing(4096);
        ramSlider.setPaintTicks(true);
        ramSlider.setOpaque(false);
        ramSlider.setForeground(new Color(100, 200, 255));
        ramSlider.setBounds(450, 133, 300, 40);
        ramSlider.addChangeListener(e -> {
            allocatedRAM = ramSlider.getValue();
            ramLabel.setText("Memory: " + allocatedRAM + " MB");
            saveRAMSettings();
        });
        centerCard.add(ramSlider);

        autoCloseCheckbox = new JCheckBox("Close launcher when game starts");
        autoCloseCheckbox.setFont(MinecraftFont.getFont(12));
        autoCloseCheckbox.setForeground(new Color(200, 200, 200));
        autoCloseCheckbox.setOpaque(false);
        autoCloseCheckbox.setBounds(50, 195, 320, 25);
        autoCloseCheckbox.setFocusPainted(false);
        autoCloseCheckbox.setSelected(autoCloseLauncher);
        autoCloseCheckbox.addActionListener(e -> {
            autoCloseLauncher = autoCloseCheckbox.isSelected();
            saveAutoCloseSettings();
        });
        centerCard.add(autoCloseCheckbox);

        launchButton = createModernButton("LAUNCH GAME", 200, 250, 400, 65);
        launchButton.setFont(MinecraftFont.getBold(24));
        launchButton.addActionListener(e -> launchMinecraft());
        centerCard.add(launchButton);
        JLabel infoLabel = new JLabel(
                "<html><center>Stellar Launcher is an open-source Minecraft launcher focused on performance and simplicity.<br>"
                        + "It uses Fabric Loader and is compatible with most mods.</center></html>",
                SwingConstants.CENTER);
        infoLabel.setFont(MinecraftFont.getFont(11));
        infoLabel.setForeground(new Color(100, 100, 100));
        infoLabel.setBounds(0, 340, 800, 20);
        centerCard.add(infoLabel);

        JPanel bottomToolbar = createModernCard(240, 558, 800, 52);
        mainPanel.add(bottomToolbar);

        JButton modsButton = createSmallButton("📁 Mods", 20, 11, 110, 30);
        modsButton.addActionListener(e -> openModsFolder());
        bottomToolbar.add(modsButton);

        JButton settingsButton = createSmallButton("⚙️ Settings", 145, 11, 120, 30);
        settingsButton.addActionListener(e -> openSettings());
        bottomToolbar.add(settingsButton);

        JButton repairButton = createSmallButton("🔧 Repair", 280, 11, 110, 30);
        repairButton.addActionListener(e -> openRepairMenu());
        bottomToolbar.add(repairButton);

        JButton killButton = createSmallButton("⚠️ Kill", 405, 11, 90, 30);
        killButton.addActionListener(e -> killMinecraftInstance());
        bottomToolbar.add(killButton);

        JButton logsButton = createSmallButton("📋 Logs", 510, 11, 110, 30);
        logsButton.addActionListener(e -> {
            boolean visible = scrollPane.isVisible();
            scrollPane.setVisible(!visible);
            logsButton.setText(visible ? "📋 Logs" : "❌ Hide");
        });
        bottomToolbar.add(logsButton);

        JButton copyLogsButton = createSmallButton("📄 Copy", 635, 11, 100, 30);
        copyLogsButton.addActionListener(e -> copyLogs());
        bottomToolbar.add(copyLogsButton);

        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setBackground(new Color(20, 20, 20));
        logArea.setForeground(MC_GREEN);
        logArea.setFont(new Font("Consolas", Font.PLAIN, 11));
        logArea.setMargin(new Insets(10, 10, 10, 10));

        scrollPane = new JScrollPane(logArea);
        scrollPane.setBounds(240, 160, 800, 380);
        scrollPane.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(new Color(100, 200, 255, 100), 2, true),
                BorderFactory.createEmptyBorder(5, 5, 5, 5)));
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(false);
        scrollPane.setVisible(false);
        mainPanel.add(scrollPane);

        progressBar = new JProgressBar() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2d = (Graphics2D) g;
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2d.setColor(new Color(30, 30, 30));
                g2d.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                int pw = (int) ((getWidth() - 4) * (getValue() / (double) getMaximum()));
                if (pw > 0) {
                    g2d.setPaint(new GradientPaint(0, 0, new Color(100, 200, 255), pw, 0, new Color(50, 150, 255)));
                    g2d.fillRoundRect(2, 2, pw, getHeight() - 4, 8, 8);
                }
                if (getString() != null && !getString().isEmpty()) {
                    g2d.setColor(Color.WHITE);
                    g2d.setFont(getFont());
                    FontMetrics fm = g2d.getFontMetrics();
                    g2d.drawString(getString(), (getWidth() - fm.stringWidth(getString())) / 2,
                            (getHeight() + fm.getAscent()) / 2 - 2);
                }
            }
        };
        progressBar.setStringPainted(true);
        progressBar.setBounds(240, 622, 800, 22);
        progressBar.setFont(MinecraftFont.getFont(10));
        progressBar.setVisible(false);
        progressBar.setOpaque(false);
        progressBar.setBorderPainted(false);
        mainPanel.add(progressBar);

        statusLabel = new JLabel("Ready to launch", SwingConstants.CENTER);
        statusLabel.setFont(MinecraftFont.getFont(12));
        statusLabel.setForeground(new Color(150, 150, 150));
        statusLabel.setBounds(240, 646, 800, 20);
        mainPanel.add(statusLabel);

        add(mainPanel);
        log("✨ StellarLauncher v" + StellarLauncher.VERSION + " started!");
    }

    // ─── UI HELPERS ──────────────────────────────────────────────────────────────

    private JPanel createModernCard(int x, int y, int width, int height) {
        JPanel card = new JPanel(null) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2d = (Graphics2D) g;
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2d.setColor(new Color(20, 20, 30, 200));
                g2d.fillRoundRect(0, 0, getWidth(), getHeight(), 20, 20);
                g2d.setPaint(new GradientPaint(0, 0, new Color(100, 200, 255, 100), 0, getHeight(),
                        new Color(50, 150, 255, 50)));
                g2d.setStroke(new BasicStroke(2));
                g2d.drawRoundRect(1, 1, getWidth() - 2, getHeight() - 2, 20, 20);
            }
        };
        card.setBounds(x, y, width, height);
        card.setOpaque(false);
        return card;
    }

    private JButton createModernButton(String text, int x, int y, int width, int height) {
        JButton btn = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2d = (Graphics2D) g;
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color base = getModel().isRollover() ? new Color(100, 200, 255) : new Color(50, 150, 255);
                g2d.setPaint(new GradientPaint(0, 0, base, 0, getHeight(), base.darker()));
                g2d.fillRoundRect(0, 0, getWidth(), getHeight(), 15, 15);
                g2d.setColor(new Color(255, 255, 255, 50));
                g2d.setStroke(new BasicStroke(2));
                g2d.drawRoundRect(1, 1, getWidth() - 2, getHeight() - 2, 15, 15);
                g2d.setFont(getFont());
                FontMetrics fm = g2d.getFontMetrics();
                int tx = (getWidth() - fm.stringWidth(getText())) / 2;
                int ty = (getHeight() + fm.getAscent()) / 2 - 2;
                g2d.setColor(new Color(0, 0, 0, 100));
                g2d.drawString(getText(), tx + 2, ty + 2);
                g2d.setColor(Color.WHITE);
                g2d.drawString(getText(), tx, ty);
            }
        };
        btn.setBounds(x, y, width, height);
        btn.setFont(MinecraftFont.getBold(14));
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setContentAreaFilled(false);
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btn.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) {
                btn.repaint();
            }

            public void mouseExited(MouseEvent e) {
                btn.repaint();
            }
        });
        return btn;
    }

    private JButton createSmallButton(String text, int x, int y, int width, int height) {
        JButton btn = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2d = (Graphics2D) g;
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (getModel().isPressed())
                    g2d.setColor(new Color(40, 40, 60));
                else if (getModel().isRollover())
                    g2d.setColor(new Color(60, 60, 80));
                else
                    g2d.setColor(new Color(50, 50, 70));
                g2d.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                g2d.setColor(new Color(100, 200, 255, 50));
                g2d.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
                g2d.setFont(getFont());
                g2d.setColor(new Color(200, 200, 200));
                FontMetrics fm = g2d.getFontMetrics();
                g2d.drawString(getText(), (getWidth() - fm.stringWidth(getText())) / 2,
                        (getHeight() + fm.getAscent()) / 2 - 2);
            }
        };
        btn.setBounds(x, y, width, height);
        btn.setFont(MinecraftFont.getFont(11));
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setContentAreaFilled(false);
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        return btn;
    }

    private void styleModernComboBox(JComboBox<?> combo) {
        combo.setBackground(new Color(40, 40, 60));
        combo.setForeground(Color.WHITE);
        combo.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(new Color(100, 200, 255, 50), 2, true),
                BorderFactory.createEmptyBorder(5, 10, 5, 10)));
        combo.setFocusable(false);
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel,
                    boolean focus) {
                super.getListCellRendererComponent(list, value, index, sel, focus);
                setBackground(sel ? new Color(100, 200, 255) : new Color(40, 40, 60));
                setForeground(Color.WHITE);
                setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
                return this;
            }
        });
    }

    // ─── TITLE BAR ───────────────────────────────────────────────────────────────

    private void createTitleBar(JPanel parent) {
        JPanel titleBar = new JPanel(null) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2d = (Graphics2D) g;
                g2d.setPaint(new GradientPaint(0, 0, new Color(20, 20, 40, 220), 0, getHeight(),
                        new Color(10, 10, 30, 220)));
                g2d.fillRect(0, 0, getWidth(), getHeight());
                g2d.setColor(new Color(100, 200, 255, 50));
                g2d.fillRect(0, getHeight() - 1, getWidth(), 1);
            }
        };
        titleBar.setBounds(0, 0, 1280, 40);
        titleBar.setOpaque(false);

        JLabel title = new JLabel("Stellar Launcher");
        title.setFont(MinecraftFont.getBold(14));
        title.setForeground(new Color(100, 200, 255));
        title.setBounds(15, 10, 300, 20);
        titleBar.add(title);

        JButton minimizeBtn = createWindowButton("_", 1160, 5, 30, 30);
        minimizeBtn.addActionListener(e -> setState(JFrame.ICONIFIED));
        titleBar.add(minimizeBtn);

        JButton maximizeBtn = createWindowButton("□", 1200, 5, 30, 30);
        maximizeBtn.setEnabled(false);
        titleBar.add(maximizeBtn);

        JButton closeBtn = createWindowButton("X", 1240, 5, 30, 30);
        closeBtn.addActionListener(e -> {
            dispose();
            System.exit(0);
        });
        titleBar.add(closeBtn);

        final Point[] dragPoint = { null };
        titleBar.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                dragPoint[0] = e.getPoint();
            }
        });
        titleBar.addMouseMotionListener(new MouseMotionAdapter() {
            public void mouseDragged(MouseEvent e) {
                if (dragPoint[0] != null) {
                    Point loc = getLocation();
                    setLocation(loc.x + e.getX() - dragPoint[0].x, loc.y + e.getY() - dragPoint[0].y);
                }
            }
        });
        parent.add(titleBar);
    }

    private JButton createWindowButton(String type, int x, int y, int w, int h) {
        JButton btn = new JButton() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2d = (Graphics2D) g;
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (getModel().isPressed())
                    g2d.setColor(new Color(255, 60, 60));
                else if (getModel().isRollover())
                    g2d.setColor(new Color(255, 255, 255, 30));
                else
                    g2d.setColor(new Color(0, 0, 0, 0));
                g2d.fillRect(0, 0, getWidth(), getHeight());
                g2d.setColor(MC_TEXT);
                g2d.setStroke(new BasicStroke(2));
                int cx = getWidth() / 2, cy = getHeight() / 2, s = 8;
                if (type.equals("_"))
                    g2d.drawLine(cx - s, cy, cx + s, cy);
                else if (type.equals("□"))
                    g2d.drawRect(cx - s / 2, cy - s / 2, s, s);
                else {
                    g2d.drawLine(cx - s, cy - s, cx + s, cy + s);
                    g2d.drawLine(cx - s, cy + s, cx + s, cy - s);
                }
            }
        };
        btn.setBounds(x, y, w, h);
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setContentAreaFilled(false);
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btn.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) {
                btn.repaint();
            }

            public void mouseExited(MouseEvent e) {
                btn.repaint();
            }
        });
        return btn;
    }

    // ─── SETTINGS ────────────────────────────────────────────────────────────────

    private void loadAutoCloseSettings() {
        if (config.has("autoClose")) {
            autoCloseLauncher = config.get("autoClose").getAsBoolean();
            if (autoCloseCheckbox != null)
                autoCloseCheckbox.setSelected(autoCloseLauncher);
        }
    }

    private void saveAutoCloseSettings() {
        config.addProperty("autoClose", autoCloseLauncher);
        saveConfig();
    }

    private void openRepairMenu() {
        String[] options = { "Open Mods Folder", "Reinstall Version", "Settings", "Cancel" };
        int choice = JOptionPane.showOptionDialog(this, "Repair Options:", "Repair",
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        switch (choice) {
            case 0:
                openModsFolder();
                break;
            case 1:
                reinstallVersion();
                break;
            case 2:
                openSettings();
                break;
        }
    }

    private void reinstallVersion() {
        int confirm = JOptionPane.showConfirmDialog(this,
                "This will delete and reinstall Minecraft " + selectedVersion
                        + "\nYour mods and worlds will be preserved.\n\nContinue?",
                "Reinstall Version", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION)
            return;
        new Thread(() -> {
            try {
                log("🔄 Reinstalling " + selectedVersion + "...");
                String vanilla = selectedVersion.contains("-")
                        ? selectedVersion.substring(selectedVersion.lastIndexOf("-") + 1)
                        : selectedVersion;
                deleteDirectory(new File(StellarLauncher.VERSIONS_DIR, vanilla));
                deleteDirectory(new File(StellarLauncher.VERSIONS_DIR,
                        "fabric-loader-" + getFabricLoaderVersion(vanilla) + "-" + vanilla));
                deleteDirectory(StellarLauncher.NATIVES_DIR);
                StellarLauncher.NATIVES_DIR.mkdirs();
                if (selectedVersion.equals("1.8.9"))
                    downloadMinecraft();
                else
                    installFabricLoader();
                log("✅ Reinstallation complete!");
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                        "Reinstalled " + selectedVersion, "Success", JOptionPane.INFORMATION_MESSAGE));
            } catch (Exception e) {
                log("❌ Reinstallation failed: " + e.getMessage());
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                        "Failed:\n" + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE));
            }
        }).start();
    }

    /**
     * Ukrywa folder systemowo.
     * Windows: attrib +h (ukryty atrybut)
     * Linux/Mac: folder zaczynający się od '.' jest już ukryty z natury
     */
    private void hideDirectory(File dir) {
        if (!dir.exists())
            return;
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            try {
                new ProcessBuilder("attrib", "+h", dir.getAbsolutePath())
                        .start();
            } catch (Exception ignored) {
            }
        }
        // Na Linux/Mac '.' prefix = już ukryty, nic nie robimy
    }

    private void deleteDirectory(File dir) {
        if (dir == null || !dir.exists())
            return;
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null)
                for (File f : files)
                    deleteDirectory(f);
        }
        dir.delete();
    }

    // ─── LAUNCH ──────────────────────────────────────────────────────────────────

    private void launchMinecraft() {
        synchronized (this) {
            if (isLaunching) {
                JOptionPane.showMessageDialog(this,
                        "Launch already in progress!\nUse '⚠️ Kill' to force-close if stuck.",
                        "Already Launching", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (minecraftProcess != null && minecraftProcess.isAlive()) {
                JOptionPane.showMessageDialog(this, "Minecraft is already running!", "Already Running",
                        JOptionPane.WARNING_MESSAGE);
                return;
            }
            isLaunching = true;
        }

        SwingUtilities.invokeLater(() -> {
            launchButton.setEnabled(false);
            launchButton.setText("Launching...");
            statusLabel.setText("Launching...");
            progressBar.setVisible(true);
        });

        new Thread(() -> {
            try {
                log("🚀 Launching " + selectedVersion + " | RAM: " + allocatedRAM + "MB");

                if (selectedVersion.equals("1.8.9"))
                    downloadMinecraft();
                else
                    installFabricLoader();

                setupModsForVersion(selectedVersion);
                downloadAssets();
                extractNatives();

                currentServer = "In menu";
                updateDiscordRPC();

                launchMinecraftProcess();

                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Minecraft is running...");
                    progressBar.setVisible(false);
                    launchButton.setText("RUNNING");
                });

                new Thread(this::monitorMinecraftProcess, "Minecraft-Monitor").start();

            } catch (Exception e) {
                log("❌ Launch failed: " + e.getMessage());
                e.printStackTrace();
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(this, "Failed to launch:\n" + e.getMessage(), "Error",
                            JOptionPane.ERROR_MESSAGE);
                    statusLabel.setText("Launch failed");
                    launchButton.setEnabled(true);
                    launchButton.setText("LAUNCH GAME");
                    progressBar.setVisible(false);
                });
                synchronized (LauncherGUI.this) {
                    isLaunching = false;
                }
            }
        }, "Minecraft-Launcher").start();
    }

    // ─── FABRIC INSTALL ──────────────────────────────────────────────────────────

    private void installFabricLoader() throws Exception {
        String vanillaVersion = selectedVersion.contains("-")
                ? selectedVersion.substring(selectedVersion.lastIndexOf("-") + 1)
                : selectedVersion;

        String fabricLoaderVersion = getFabricLoaderVersion(vanillaVersion);
        String fabricId = "fabric-loader-" + fabricLoaderVersion + "-" + vanillaVersion;
        File fabricDir = new File(StellarLauncher.VERSIONS_DIR, fabricId);
        File fabricJson = new File(fabricDir, fabricId + ".json");

        if (fabricJson.exists()) {
            log("✅ Fabric already installed: " + fabricId);
            selectedVersion = fabricId;
            return;
        }

        log("📥 Installing Fabric " + fabricLoaderVersion + " for " + vanillaVersion + "...");
        downloadMinecraft();
        fabricDir.mkdirs();

        String url = "https://meta.fabricmc.net/v2/versions/loader/" + vanillaVersion + "/"
                + fabricLoaderVersion + "/profile/json";
        log("  Fetching: " + url);
        String fabricData = downloadString(url);
        Files.write(fabricJson.toPath(), fabricData.getBytes());

        JsonArray libraries = JsonParser.parseString(fabricData).getAsJsonObject().getAsJsonArray("libraries");
        log("📚 Downloading Fabric libraries (" + libraries.size() + ")...");
        for (int i = 0; i < libraries.size(); i++) {
            JsonObject lib = libraries.get(i).getAsJsonObject();
            if (!lib.has("url") || !lib.has("name"))
                continue;
            String libUrl = lib.get("url").getAsString();
            String[] parts = lib.get("name").getAsString().split(":");
            if (parts.length < 3)
                continue;
            String path = parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2]
                    + "/" + parts[1] + "-" + parts[2] + ".jar";
            File libFile = new File(StellarLauncher.LIBRARIES_DIR, path);
            if (!libFile.exists()) {
                libFile.getParentFile().mkdirs();
                try {
                    downloadFile(libUrl + path, libFile);
                } catch (Exception e) {
                    log("  ⚠️ Failed lib: " + parts[1]);
                }
            }
        }

        selectedVersion = fabricId;
        log("✅ Fabric installed: " + fabricId);
    }

    // ─── VANILLA DOWNLOAD ────────────────────────────────────────────────────────

    private void downloadMinecraft() throws Exception {
        String vanillaVersion = selectedVersion.contains("-")
                ? selectedVersion.substring(selectedVersion.lastIndexOf("-") + 1)
                : selectedVersion;

        File versionDir = new File(StellarLauncher.VERSIONS_DIR, vanillaVersion);
        File versionJson = new File(versionDir, vanillaVersion + ".json");
        File versionJar = new File(versionDir, vanillaVersion + ".jar");

        if (versionJson.exists() && versionJar.exists()) {
            log("✅ Minecraft " + vanillaVersion + " already installed");
            return;
        }

        log("⬇️ Downloading Minecraft " + vanillaVersion + "...");
        versionDir.mkdirs();

        JsonObject manifestJson = JsonParser
                .parseString(downloadString("https://launchermeta.mojang.com/mc/game/version_manifest.json"))
                .getAsJsonObject();
        JsonArray versions = manifestJson.getAsJsonArray("versions");
        String versionUrl = null;
        for (int i = 0; i < versions.size(); i++) {
            JsonObject v = versions.get(i).getAsJsonObject();
            if (v.get("id").getAsString().equals(vanillaVersion)) {
                versionUrl = v.get("url").getAsString();
                break;
            }
        }
        if (versionUrl == null)
            throw new Exception("Version not found: " + vanillaVersion);

        String versionData = downloadString(versionUrl);
        Files.write(versionJson.toPath(), versionData.getBytes());
        JsonObject versionJsonObj = JsonParser.parseString(versionData).getAsJsonObject();

        log("  Downloading client jar...");
        downloadFile(versionJsonObj.getAsJsonObject("downloads").getAsJsonObject("client").get("url").getAsString(),
                versionJar);

        log("📚 Downloading libraries...");
        JsonArray libraries = versionJsonObj.getAsJsonArray("libraries");
        for (int i = 0; i < libraries.size(); i++) {
            JsonObject lib = libraries.get(i).getAsJsonObject();
            if (lib.has("rules") && !shouldIncludeLibrary(lib))
                continue;
            if (!lib.has("downloads"))
                continue;
            JsonObject downloads = lib.getAsJsonObject("downloads");

            if (downloads.has("artifact")) {
                JsonObject artifact = downloads.getAsJsonObject("artifact");
                File libFile = new File(StellarLauncher.LIBRARIES_DIR, artifact.get("path").getAsString());
                if (!libFile.exists()) {
                    libFile.getParentFile().mkdirs();
                    try {
                        downloadFile(artifact.get("url").getAsString(), libFile);
                    } catch (Exception e) {
                        log("  ⚠️ Failed: " + libFile.getName());
                    }
                }
            }

            String nativeKey = getNativeKey();
            if (downloads.has("classifiers")) {
                JsonObject classifiers = downloads.getAsJsonObject("classifiers");
                if (classifiers.has(nativeKey)) {
                    JsonObject nat = classifiers.getAsJsonObject(nativeKey);
                    File natFile = new File(StellarLauncher.LIBRARIES_DIR, nat.get("path").getAsString());
                    if (!natFile.exists()) {
                        natFile.getParentFile().mkdirs();
                        try {
                            downloadFile(nat.get("url").getAsString(), natFile);
                        } catch (Exception e) {
                            log("  ⚠️ Failed native: " + natFile.getName());
                        }
                    }
                }
            }
        }
        log("✅ Minecraft " + vanillaVersion + " downloaded!");
    }

    private boolean shouldIncludeLibrary(JsonObject lib) {
        if (!lib.has("rules"))
            return true;
        JsonArray rules = lib.getAsJsonArray("rules");
        String osName = System.getProperty("os.name").toLowerCase();
        String currentOS = osName.contains("win") ? "windows" : osName.contains("mac") ? "osx" : "linux";
        boolean allowed = false;
        for (int i = 0; i < rules.size(); i++) {
            JsonObject rule = rules.get(i).getAsJsonObject();
            String action = rule.get("action").getAsString();
            if (rule.has("os")) {
                if (rule.getAsJsonObject("os").get("name").getAsString().equals(currentOS))
                    allowed = action.equals("allow");
            } else
                allowed = action.equals("allow");
        }
        return allowed;
    }

    private String pathToArtifactKey(String path) {
        String[] parts = path.replace("\\", "/").split("/");
        if (parts.length < 3)
            return path;
        String artifact = parts[parts.length - 3];
        StringBuilder group = new StringBuilder();
        for (int i = 0; i < parts.length - 3; i++) {
            if (i > 0)
                group.append(".");
            group.append(parts[i]);
        }
        return group + ":" + artifact;
    }

    private String getNativeKey() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win"))
            return "natives-windows";
        if (os.contains("mac"))
            return "natives-osx";
        return "natives-linux";
    }

    // ─── NATIVES ─────────────────────────────────────────────────────────────────

    private void extractNativesFromJar(File jar) {
        if (jar == null || !jar.exists())
            return;
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar)) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || name.contains("META-INF"))
                    continue;
                if (!name.endsWith(".dll") && !name.endsWith(".so") && !name.endsWith(".dylib"))
                    continue;
                File outFile = new File(StellarLauncher.NATIVES_DIR, new File(name).getName());
                if (!outFile.exists()) {
                    try (InputStream in = zip.getInputStream(entry);
                            FileOutputStream fos = new FileOutputStream(outFile)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1)
                            fos.write(buf, 0, n);
                    }
                }
            }
        } catch (Exception e) {
            log("⚠️ Native extract: " + jar.getName() + " — " + e.getMessage());
        }
    }

    private void extractNatives() throws Exception {
        String vanillaVersion = selectedVersion.contains("-")
                ? selectedVersion.substring(selectedVersion.lastIndexOf("-") + 1)
                : selectedVersion;

        File versionJson = new File(StellarLauncher.VERSIONS_DIR, vanillaVersion + "/" + vanillaVersion + ".json");
        if (!versionJson.exists())
            return;

        StellarLauncher.NATIVES_DIR.mkdirs();
        String nativeKey = getNativeKey();
        JsonArray libraries = JsonParser.parseString(new String(Files.readAllBytes(versionJson.toPath())))
                .getAsJsonObject().getAsJsonArray("libraries");

        for (int i = 0; i < libraries.size(); i++) {
            JsonObject lib = libraries.get(i).getAsJsonObject();
            if (!lib.has("downloads"))
                continue;
            JsonObject downloads = lib.getAsJsonObject("downloads");

            if (downloads.has("classifiers")) {
                JsonObject classifiers = downloads.getAsJsonObject("classifiers");
                if (classifiers.has(nativeKey)) {
                    File natJar = new File(StellarLauncher.LIBRARIES_DIR,
                            classifiers.getAsJsonObject(nativeKey).get("path").getAsString());
                    extractNativesFromJar(natJar);
                }
            }

            if (downloads.has("artifact")) {
                String path = downloads.getAsJsonObject("artifact").get("path").getAsString();
                String jarName = path.toLowerCase();
                if (jarName.contains("lwjgl") || jarName.contains("tinyfd")
                        || jarName.contains("jemalloc") || jarName.contains("stb")
                        || jarName.contains("openal") || jarName.contains("glfw")) {
                    File artJar = new File(StellarLauncher.LIBRARIES_DIR, path);
                    extractNativesFromJar(artJar);
                }
            }
        }
        log("✅ Natives ready");
    }

    // ─── ASSETS ──────────────────────────────────────────────────────────────────

    private void downloadAssets() throws Exception {
        log("🎨 Checking assets...");
        String vanillaVersion = selectedVersion.contains("-")
                ? selectedVersion.substring(selectedVersion.lastIndexOf("-") + 1)
                : selectedVersion;

        File versionJson = new File(StellarLauncher.VERSIONS_DIR, vanillaVersion + "/" + vanillaVersion + ".json");
        if (!versionJson.exists()) {
            log("⚠️ Version JSON missing, skipping assets");
            return;
        }

        JsonObject versionJsonObj = JsonParser.parseString(new String(Files.readAllBytes(versionJson.toPath())))
                .getAsJsonObject();
        if (!versionJsonObj.has("assetIndex")) {
            log("⚠️ No assetIndex");
            return;
        }

        JsonObject assetIndexInfo = versionJsonObj.getAsJsonObject("assetIndex");
        String assetIndexId = assetIndexInfo.get("id").getAsString();
        String assetIndexUrl = assetIndexInfo.get("url").getAsString();

        File indexDir = new File(StellarLauncher.ASSETS_DIR, "indexes");
        indexDir.mkdirs();
        File indexFile = new File(indexDir, assetIndexId + ".json");
        if (!indexFile.exists()) {
            log("📥 Asset index: " + assetIndexId);
            downloadFile(assetIndexUrl, indexFile);
        }

        JsonObject objects = JsonParser.parseString(new String(Files.readAllBytes(indexFile.toPath())))
                .getAsJsonObject().getAsJsonObject("objects");
        File objectsDir = new File(StellarLauncher.ASSETS_DIR, "objects");
        int downloaded = 0, skipped = 0, total = objects.size();
        log("🎨 Checking " + total + " assets...");

        for (String assetName : objects.keySet()) {
            String hash = objects.getAsJsonObject(assetName).get("hash").getAsString();
            String prefix = hash.substring(0, 2);
            File assetFile = new File(objectsDir, prefix + "/" + hash);
            if (!assetFile.exists()) {
                assetFile.getParentFile().mkdirs();
                try {
                    downloadFile("https://resources.download.minecraft.net/" + prefix + "/" + hash, assetFile);
                    downloaded++;
                    if (downloaded % 100 == 0)
                        log("  📦 " + downloaded + "/" + total);
                } catch (Exception e) {
                    log("  ⚠️ Failed: " + assetName);
                }
            } else
                skipped++;
        }
        log("✅ Assets ready (new: " + downloaded + ", existing: " + skipped + ")");
    }

    // ─── MODS ────────────────────────────────────────────────────────────────────

    private void setupModsForVersion(String version) {
        log("📦 Setting up mods for " + version + "...");
        File instanceModsDir = new File(StellarLauncher.MINECRAFT_DIR, "mods");
        instanceModsDir.mkdirs();
        clearModsFolder(instanceModsDir);

        String cleanVersion = version.contains("-") ? version.substring(version.lastIndexOf("-") + 1) : version;

        File preinstalledDir = new File(StellarLauncher.LAUNCHER_DIR, "mods/" + cleanVersion + "/.preinstalled");
        if (preinstalledDir.exists()) {
            File[] pre = preinstalledDir.listFiles((d, n) -> n.endsWith(".jar"));
            if (pre != null && pre.length > 0) {
                log("  📦 Preinstalled: " + pre.length + " modów");
                for (File mod : pre) {
                    try {
                        Files.copy(mod.toPath(), new File(instanceModsDir, mod.getName()).toPath(),
                                StandardCopyOption.REPLACE_EXISTING);
                    } catch (Exception e) {
                        log("  ⚠️ " + mod.getName() + ": " + e.getMessage());
                    }
                }
            }
        }

        File launcherModsDir = new File(StellarLauncher.LAUNCHER_DIR, "mods/" + cleanVersion);
        launcherModsDir.mkdirs();

        File[] mods = launcherModsDir.listFiles((d, n) -> n.endsWith(".jar"));
        if (mods == null || mods.length == 0) {
            log("  ℹ️ Brak modów gracza — dodaj .jar do: " + launcherModsDir.getAbsolutePath());
        } else {
            log("  🧩 Mody gracza: " + mods.length);
            for (File mod : mods) {
                try {
                    Files.copy(mod.toPath(), new File(instanceModsDir, mod.getName()).toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                    log("  ✅ " + mod.getName());
                } catch (Exception e) {
                    log("  ❌ " + mod.getName());
                }
            }
        }

        log("✅ Mods ready");
    }

    private void clearModsFolder(File modsDir) {
        if (!modsDir.exists())
            return;
        File[] old = modsDir.listFiles((d, n) -> n.endsWith(".jar"));
        if (old != null)
            for (File f : old)
                f.delete();
    }

    // ─── PROCESS LAUNCH ──────────────────────────────────────────────────────────

    private void launchMinecraftProcess() throws Exception {
        synchronized (this) {
            if (minecraftProcess != null && minecraftProcess.isAlive())
                throw new Exception("Minecraft is already running!");
        }

        log("🎮 Building launch command...");

        String vanillaVersion = selectedVersion.contains("-")
                ? selectedVersion.substring(selectedVersion.lastIndexOf("-") + 1)
                : selectedVersion;

        File vanillaDir = new File(StellarLauncher.VERSIONS_DIR, vanillaVersion);
        File vanillaJar = new File(vanillaDir, vanillaVersion + ".jar");

        File versionDir = new File(StellarLauncher.VERSIONS_DIR, selectedVersion);
        File versionJson = new File(versionDir, selectedVersion + ".json");
        if (!versionJson.exists())
            versionJson = new File(vanillaDir, vanillaVersion + ".json");

        JsonObject versionJsonObj = JsonParser.parseString(new String(Files.readAllBytes(versionJson.toPath())))
                .getAsJsonObject();

        JsonObject vanillaJsonObj = null;
        File vanillaJsonFile = new File(vanillaDir, vanillaVersion + ".json");
        if (vanillaJsonFile.exists())
            vanillaJsonObj = JsonParser.parseString(new String(Files.readAllBytes(vanillaJsonFile.toPath())))
                    .getAsJsonObject();

        StringBuilder classpath = new StringBuilder();
        Set<String> addedArtifacts = new HashSet<>();

        JsonArray libraries = versionJsonObj.getAsJsonArray("libraries");
        for (int i = 0; i < libraries.size(); i++) {
            JsonObject lib = libraries.get(i).getAsJsonObject();
            if (lib.has("url") && lib.has("name")) {
                String[] parts = lib.get("name").getAsString().split(":");
                if (parts.length >= 3) {
                    String artifactKey = parts[0] + ":" + parts[1];
                    String path = parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2]
                            + "/" + parts[1] + "-" + parts[2] + ".jar";
                    File f = new File(StellarLauncher.LIBRARIES_DIR, path);
                    if (f.exists()) {
                        classpath.append(f.getAbsolutePath()).append(File.pathSeparator);
                        addedArtifacts.add(artifactKey);
                    } else {
                        log("  ⚠️ Missing Fabric lib: " + parts[1] + "-" + parts[2]);
                    }
                }
            } else if (lib.has("downloads") && lib.getAsJsonObject("downloads").has("artifact")) {
                String path = lib.getAsJsonObject("downloads").getAsJsonObject("artifact").get("path").getAsString();
                String artifactKey = pathToArtifactKey(path);
                if (!addedArtifacts.contains(artifactKey)) {
                    File f = new File(StellarLauncher.LIBRARIES_DIR, path);
                    if (f.exists()) {
                        classpath.append(f.getAbsolutePath()).append(File.pathSeparator);
                        addedArtifacts.add(artifactKey);
                    }
                }
            }
        }

        if (selectedVersion.contains("fabric") && vanillaJsonObj != null) {
            JsonArray vanillaLibs = vanillaJsonObj.getAsJsonArray("libraries");
            for (int i = 0; i < vanillaLibs.size(); i++) {
                JsonObject lib = vanillaLibs.get(i).getAsJsonObject();
                if (lib.has("rules") && !shouldIncludeLibrary(lib))
                    continue;
                if (!lib.has("downloads") || !lib.getAsJsonObject("downloads").has("artifact"))
                    continue;
                String path = lib.getAsJsonObject("downloads").getAsJsonObject("artifact").get("path").getAsString();
                String artifactKey = pathToArtifactKey(path);
                if (addedArtifacts.contains(artifactKey))
                    continue;
                File f = new File(StellarLauncher.LIBRARIES_DIR, path);
                if (f.exists()) {
                    classpath.append(f.getAbsolutePath()).append(File.pathSeparator);
                    addedArtifacts.add(artifactKey);
                }
            }
        }

        classpath.append(vanillaJar.getAbsolutePath());

        String assetIndex = vanillaVersion;
        if (vanillaJsonObj != null && vanillaJsonObj.has("assetIndex"))
            assetIndex = vanillaJsonObj.getAsJsonObject("assetIndex").get("id").getAsString();
        else if (versionJsonObj.has("assetIndex"))
            assetIndex = versionJsonObj.getAsJsonObject("assetIndex").get("id").getAsString();

        String mainClass = versionJsonObj.get("mainClass").getAsString();
        log("🎮 Main class: " + mainClass);

        List<String> command = new ArrayList<>();
        command.add(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
        command.add("-Xmx" + allocatedRAM + "M");
        command.add("-Xms" + Math.min(allocatedRAM / 2, 2048) + "M");
        command.add("-XX:+UnlockExperimentalVMOptions");
        command.add("-XX:+UseG1GC");
        command.add("-XX:G1NewSizePercent=20");
        command.add("-XX:G1ReservePercent=20");
        command.add("-XX:MaxGCPauseMillis=50");
        command.add("-XX:G1HeapRegionSize=32M");
        command.add("-Djava.library.path=" + StellarLauncher.NATIVES_DIR.getAbsolutePath());
        command.add("-Dlog4j2.formatMsgNoLookups=true");
        command.add("-cp");
        command.add(classpath.toString());
        command.add(mainClass);

        command.add("--username");
        command.add("Player");
        command.add("--version");
        command.add(selectedVersion);
        command.add("--gameDir");
        command.add(StellarLauncher.MINECRAFT_DIR.getAbsolutePath());
        command.add("--assetsDir");
        command.add(StellarLauncher.ASSETS_DIR.getAbsolutePath());
        command.add("--assetIndex");
        command.add(assetIndex);
        command.add("--uuid");
        command.add("00000000-0000-0000-0000-000000000000");
        command.add("--accessToken");
        command.add("0");
        command.add("--userType");
        command.add("legacy");

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(StellarLauncher.MINECRAFT_DIR);
        pb.redirectErrorStream(true);

        synchronized (this) {
            minecraftProcess = pb.start();
        }

        Thread logReader = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(minecraftProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    final String logLine = line;
                    SwingUtilities.invokeLater(() -> {
                        logArea.append("[" + new java.text.SimpleDateFormat("HH:mm:ss").format(new Date()) + "] "
                                + logLine + "\n");
                        logArea.setCaretPosition(logArea.getDocument().getLength());
                    });
                    detectServerFromLog(logLine);
                }
            } catch (IOException e) {
                if (e.getMessage() != null && !e.getMessage().contains("Stream closed"))
                    log("⚠️ Log stream: " + e.getMessage());
            }
        }, "Minecraft-Log-Reader");
        logReader.setDaemon(true);
        logReader.start();

        log("✅ Minecraft process started!");
    }

    // ─── SERVER/STATS DETECTION ──────────────────────────────────────────────────

    private void detectServerFromLog(String logLine) {
        if (logLine == null || logLine.trim().isEmpty())
            return;
        if (logLine.toLowerCase().contains("voice chat") || logLine.toLowerCase().contains("voicechat"))
            return;

        if (logLine.contains("StellarStats: Dimension:")) {
            Matcher m = Pattern.compile("Dimension:\\s*([a-z_:]+)").matcher(logLine);
            if (m.find()) {
                String dim = m.group(1);
                if (dim.contains(":"))
                    dim = dim.substring(dim.lastIndexOf(":") + 1);
                String newDim = dim.equals("the_nether") ? "The Nether"
                        : dim.equals("the_end") ? "The End" : "Overworld";
                if (!newDim.equals(currentDimension)) {
                    currentDimension = newDim;
                    updateDiscordRPC();
                }
            }
            return;
        }
        if (logLine.contains("StellarStats: FPS:")) {
            Matcher m = Pattern.compile("FPS:\\s*([0-9]+)").matcher(logLine);
            if (m.find()) {
                int fps = Integer.parseInt(m.group(1));
                if (Math.abs(fps - currentFPS) > 5) {
                    currentFPS = fps;
                    updateDiscordRPC();
                }
            }
            return;
        }
        if (logLine.contains("StellarStats: Ping:")) {
            Matcher m = Pattern.compile("Ping:\\s*([0-9]+)").matcher(logLine);
            if (m.find()) {
                int ping = Integer.parseInt(m.group(1));
                if (Math.abs(ping - currentPing) > 2) {
                    currentPing = ping;
                    updateDiscordRPC();
                }
            }
            return;
        }
        if (logLine.contains("StellarStats: Biome:")) {
            Matcher m = Pattern.compile("Biome:\\s*([a-z_:]+)").matcher(logLine);
            if (m.find()) {
                String biome = m.group(1);
                if (biome.contains(":"))
                    biome = biome.substring(biome.lastIndexOf(":") + 1);
                String newBiome = capitalizeFirst(biome.replace("_", " "));
                if (!newBiome.equals(currentBiome)) {
                    currentBiome = newBiome;
                    updateDiscordRPC();
                }
            }
            return;
        }
        if (logLine.contains("StellarStats: Position:")) {
            Matcher m = Pattern.compile("x=(-?[0-9]+),\\s*y=(-?[0-9]+),\\s*z=(-?[0-9]+)").matcher(logLine);
            if (m.find()) {
                posX = Integer.parseInt(m.group(1));
                posY = Integer.parseInt(m.group(2));
                posZ = Integer.parseInt(m.group(3));
            }
            return;
        }
        if (logLine.contains("Starting integrated minecraft server")
                || logLine.contains("Starting Integrated Server")) {
            currentServer = "SinglePlayer";
            currentDimension = "Overworld";
            updateDiscordRPC();
            return;
        }
        if (logLine.contains("Connecting to") || logLine.contains("Attempting to connect")) {
            Matcher m = Pattern.compile("Connecting to ([a-zA-Z0-9._\\-:]+)", Pattern.CASE_INSENSITIVE)
                    .matcher(logLine);
            if (m.find()) {
                String server = m.group(1);
                if (server.contains(":"))
                    server = server.substring(0, server.indexOf(":"));
                currentServer = (server.equals("localhost") || server.equals("127.0.0.1")) ? "SinglePlayer" : server;
                currentDimension = "Overworld";
                updateDiscordRPC();
            }
            return;
        }
        if (logLine.contains("Stopping server") || logLine.contains("Returning to main menu")
                || logLine.contains("Disconnecting")) {
            currentServer = "In menu";
            resetStats();
            updateDiscordRPC();
        }
    }

    private String capitalizeFirst(String str) {
        if (str == null || str.isEmpty())
            return str;
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }

    private void resetStats() {
        currentFPS = 0;
        currentPing = 0;
        currentBiome = "Unknown";
        currentDimension = "Overworld";
        posX = 0;
        posY = 0;
        posZ = 0;
        playersOnline = 0;
        gameStartTimestamp = 0;
    }

    // ─── MONITOR ─────────────────────────────────────────────────────────────────

    private void monitorMinecraftProcess() {
        try {
            log("👀 Monitoring Minecraft...");
            synchronized (this) {
                if (minecraftProcess == null || !minecraftProcess.isAlive()) {
                    isLaunching = false;
                    return;
                }
            }

            Thread.sleep(3000);
            if (gameStartTimestamp == 0)
                gameStartTimestamp = System.currentTimeMillis() / 1000;
            currentServer = "Loading";
            updateDiscordRPC();

            if (autoCloseLauncher)
                SwingUtilities.invokeLater(() -> setVisible(false));

            int exitCode = minecraftProcess.waitFor();
            log("🎮 Minecraft closed (exit: " + exitCode + ")");

            if (gameStartTimestamp > 0) {
                long played = System.currentTimeMillis() / 1000 - gameStartTimestamp;
                log("⏱️ Played: " + formatPlayTime(played));
                savePlayTimeStats(played);
            }

            currentServer = "In menu";
            gameStartTimestamp = 0;
            resetStats();
            updateDiscordRPC();

            SwingUtilities.invokeLater(() -> {
                statusLabel.setText("Ready to launch");
                launchButton.setEnabled(true);
                launchButton.setText("LAUNCH GAME");
                progressBar.setVisible(false);
                if (autoCloseLauncher) {
                    setVisible(true);
                    setState(JFrame.NORMAL);
                    toFront();
                    requestFocus();
                }
            });

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log("❌ Monitor error: " + e.getMessage());
        } finally {
            synchronized (LauncherGUI.this) {
                isLaunching = false;
                minecraftProcess = null;
            }
            gameStartTimestamp = 0;
            SwingUtilities.invokeLater(() -> {
                if (!launchButton.isEnabled()) {
                    launchButton.setEnabled(true);
                    launchButton.setText("LAUNCH GAME");
                    statusLabel.setText("Ready to launch");
                }
            });
        }
    }

    private String formatPlayTime(long seconds) {
        long h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }

    private void savePlayTimeStats(long seconds) {
        try {
            File statsFile = new File(StellarLauncher.LAUNCHER_DIR, "stats.json");
            JsonObject stats = statsFile.exists()
                    ? JsonParser.parseString(new String(Files.readAllBytes(statsFile.toPath()))).getAsJsonObject()
                    : new JsonObject();
            long total = stats.has("totalPlayTime") ? stats.get("totalPlayTime").getAsLong() : 0;
            int sessions = stats.has("sessions") ? stats.get("sessions").getAsInt() : 0;
            stats.addProperty("totalPlayTime", total + seconds);
            stats.addProperty("sessions", sessions + 1);
            stats.addProperty("lastPlayed", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
            stats.addProperty("lastSessionDuration", seconds);
            Files.write(statsFile.toPath(), new GsonBuilder().setPrettyPrinting().create().toJson(stats).getBytes());
        } catch (Exception e) {
            log("⚠️ Stats save: " + e.getMessage());
        }
    }

    // ─── DISCORD RPC ─────────────────────────────────────────────────────────────

    private void initDiscordRPC() {
        try {
            if (!NativeLoader.loadDiscordRPC()) {
                log("⚠️ Discord RPC library not available");
                return;
            }

            discordRPC = pl.stellarlauncher.discord.DiscordRPC.INSTANCE;
            DiscordEventHandlers handlers = new DiscordEventHandlers();
            handlers.ready = user -> log("✅ Discord RPC: " + user.username);
            discordRPC.Discord_Initialize(DISCORD_APP_ID, handlers, true, null);
            discordRunning = true;

            discordThread = new Thread(() -> {
                while (discordRunning) {
                    try {
                        discordRPC.Discord_RunCallbacks();
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        break;
                    } catch (Exception e) {
                        log("⚠️ Discord callback: " + e.getMessage());
                    }
                }
            }, "Discord-RPC-Callback");
            discordThread.setDaemon(false);
            discordThread.start();

            discordUpdateProcessor = new Thread(() -> {
                while (discordRunning) {
                    try {
                        Runnable update = discordUpdateQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                        if (update != null)
                            update.run();
                    } catch (InterruptedException e) {
                        break;
                    } catch (Exception e) {
                        log("⚠️ Discord update: " + e.getMessage());
                    }
                }
            }, "Discord-RPC-Updater");
            discordUpdateProcessor.setDaemon(false);
            discordUpdateProcessor.start();

            periodicUpdater = new Thread(() -> {
                while (discordRunning) {
                    try {
                        Thread.sleep(15000);
                        synchronized (LauncherGUI.this) {
                            if (minecraftProcess != null && minecraftProcess.isAlive())
                                updateDiscordRPC();
                        }
                    } catch (InterruptedException e) {
                        break;
                    } catch (Exception ignored) {
                    }
                }
            }, "Discord-RPC-Periodic");
            periodicUpdater.setDaemon(true);
            periodicUpdater.start();

            updateDiscordRPC("In menu", "Selecting version");
            log("✅ Discord RPC initialized");

        } catch (Exception e) {
            log("⚠️ Discord RPC unavailable: " + e.getMessage());
            discordRunning = false;
        }
    }

    private void updateDiscordRPC() {
        if (!discordRunning || discordRPC == null)
            return;
        discordUpdateQueue.offer(() -> {
            try {
                DiscordRichPresence p = new DiscordRichPresence();

                if (currentServer.equals("In menu") || currentServer.equals("Loading")) {
                    p.state = currentServer.equals("Loading") ? "Loading..." : "In main menu";
                    p.details = "StellarLauncher";
                    gameStartTimestamp = 0;
                } else if (currentServer.equals("SinglePlayer")) {
                    if (gameStartTimestamp == 0)
                        gameStartTimestamp = System.currentTimeMillis() / 1000;
                    StringBuilder state = new StringBuilder("Singleplayer");
                    if (currentFPS > 0)
                        state.append(" | ").append(currentFPS).append(" FPS");
                    p.state = state.toString();
                    StringBuilder details = new StringBuilder();
                    if (!currentBiome.equals("Unknown"))
                        details.append(currentBiome);
                    if (!currentDimension.equals("Overworld")) {
                        if (details.length() > 0)
                            details.append(" | ");
                        details.append(currentDimension);
                    }
                    p.details = details.length() > 0 ? details.toString() : "Playing solo";
                } else {
                    if (gameStartTimestamp == 0)
                        gameStartTimestamp = System.currentTimeMillis() / 1000;
                    StringBuilder state = new StringBuilder("On ").append(currentServer);
                    if (currentFPS > 0)
                        state.append(" | ").append(currentFPS).append(" FPS");
                    p.state = state.toString();
                    StringBuilder details = new StringBuilder();
                    if (currentPing > 0)
                        details.append(currentPing).append("ms");
                    if (!currentBiome.equals("Unknown")) {
                        if (details.length() > 0)
                            details.append(" | ");
                        details.append(currentBiome);
                    }
                    if (!currentDimension.equals("Overworld")) {
                        if (details.length() > 0)
                            details.append(" | ");
                        details.append(currentDimension);
                    }
                    p.details = details.length() > 0 ? details.toString() : "Multiplayer";
                }

                if (gameStartTimestamp > 0)
                    p.startTimestamp = gameStartTimestamp;
                p.largeImageKey = "stellarlauncher_logo";
                p.largeImageText = "StellarLauncher v" + StellarLauncher.VERSION;
                if (currentDimension.equals("The Nether")) {
                    p.smallImageKey = "nether";
                    p.smallImageText = "The Nether";
                } else if (currentDimension.equals("The End")) {
                    p.smallImageKey = "end";
                    p.smallImageText = "The End";
                }

                discordRPC.Discord_UpdatePresence(p);
            } catch (Exception e) {
                log("⚠️ Discord presence: " + e.getMessage());
            }
        });
    }

    private void updateDiscordRPC(String state, String details) {
        if (!discordRunning || discordRPC == null)
            return;
        discordUpdateQueue.offer(() -> {
            try {
                DiscordRichPresence p = new DiscordRichPresence();
                p.state = state;
                p.details = details;
                p.largeImageKey = "stellarlauncher_logo";
                p.largeImageText = "StellarLauncher v" + StellarLauncher.VERSION;
                discordRPC.Discord_UpdatePresence(p);
            } catch (Exception ignored) {
            }
        });
    }

    // ─── MISC ACTIONS ────────────────────────────────────────────────────────────

    private void openModsFolder() {
        try {
            Desktop.getDesktop().open(new File(StellarLauncher.LAUNCHER_DIR, "mods"));
        } catch (Exception e) {
            log("❌ " + e.getMessage());
        }
    }

    private void openSettings() {
        JOptionPane.showMessageDialog(this,
                "RAM: " + allocatedRAM + " MB\n" +
                        "Java: " + System.getProperty("java.version") + "\n" +
                        "Version: " + selectedVersion + "\n" +
                        "Game dir: " + StellarLauncher.MINECRAFT_DIR.getAbsolutePath(),
                "Settings", JOptionPane.INFORMATION_MESSAGE);
    }

    private void copyLogs() {
        try {
            java.awt.datatransfer.StringSelection sel = new java.awt.datatransfer.StringSelection(logArea.getText());
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, null);
            log("📋 Logs copied");
        } catch (Exception e) {
            log("❌ Copy failed");
        }
    }

    private void killMinecraftInstance() {
        synchronized (this) {
            if (minecraftProcess == null || !minecraftProcess.isAlive()) {
                isLaunching = false;
                SwingUtilities.invokeLater(() -> {
                    launchButton.setEnabled(true);
                    launchButton.setText("LAUNCH GAME");
                    statusLabel.setText("Ready to launch");
                });
                JOptionPane.showMessageDialog(this, "No Minecraft instance running.", "Info",
                        JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            int confirm = JOptionPane.showConfirmDialog(this, "Force-close Minecraft?\nThis may cause data loss!",
                    "Kill Instance", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (confirm != JOptionPane.YES_OPTION)
                return;
            try {
                minecraftProcess.destroy();
                if (!minecraftProcess.waitFor(2, java.util.concurrent.TimeUnit.SECONDS))
                    minecraftProcess.destroyForcibly();
                log("✅ Minecraft killed");
                minecraftProcess = null;
                isLaunching = false;
                currentServer = "In menu";
                resetStats();
                updateDiscordRPC();
                SwingUtilities.invokeLater(() -> {
                    launchButton.setEnabled(true);
                    launchButton.setText("LAUNCH GAME");
                    statusLabel.setText("Ready to launch");
                    progressBar.setVisible(false);
                    JOptionPane.showMessageDialog(this, "Minecraft terminated.", "Success",
                            JOptionPane.INFORMATION_MESSAGE);
                });
            } catch (Exception e) {
                log("❌ Kill failed: " + e.getMessage());
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                        "Failed:\n" + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE));
            }
        }
    }

    // ─── CONFIG ──────────────────────────────────────────────────────────────────

    private void loadConfig() {
        try {
            if (configFile.exists())
                config = JsonParser.parseString(new String(Files.readAllBytes(configFile.toPath()))).getAsJsonObject();
            else
                config = new JsonObject();
        } catch (Exception e) {
            config = new JsonObject();
        }
    }

    private void saveConfig() {
        try {
            Files.write(configFile.toPath(), new GsonBuilder().setPrettyPrinting().create().toJson(config).getBytes());
        } catch (Exception e) {
            log("⚠️ Config save: " + e.getMessage());
        }
    }

    private void loadLastVersion() {
        if (!config.has("lastVersion"))
            return;
        String last = config.get("lastVersion").getAsString();
        selectedVersion = last;
        for (int i = 0; i < versionCombo.getItemCount(); i++)
            if (versionCombo.getItemAt(i).equals(last)) {
                versionCombo.setSelectedIndex(i);
                break;
            }
    }

    private void saveLastVersion() {
        String cleanVersion = selectedVersion.contains("-")
                ? selectedVersion.substring(selectedVersion.lastIndexOf("-") + 1)
                : selectedVersion;
        config.addProperty("lastVersion", cleanVersion);
        saveConfig();
    }

    private void loadRAMSettings() {
        if (!config.has("allocatedRAM"))
            return;
        allocatedRAM = config.get("allocatedRAM").getAsInt();
        if (ramSlider != null)
            ramSlider.setValue(allocatedRAM);
        if (ramLabel != null)
            ramLabel.setText("Memory: " + allocatedRAM + " MB");
    }

    private void saveRAMSettings() {
        config.addProperty("allocatedRAM", allocatedRAM);
        saveConfig();
    }

    // ─── DOWNLOAD HELPERS ────────────────────────────────────────────────────────

    private void loadBackgroundImage() {
        try (InputStream is = getClass().getResourceAsStream("/background.png")) {
            if (is != null)
                backgroundImage = ImageIO.read(is);
        } catch (Exception ignored) {
        }
        if (backgroundImage == null) {
            try {
                File f = new File(StellarLauncher.LAUNCHER_DIR, "background.png");
                if (f.exists())
                    backgroundImage = ImageIO.read(f);
            } catch (Exception ignored) {
            }
        }
    }

    private String downloadString(String urlStr) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "StellarLauncher/" + StellarLauncher.VERSION);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null)
                sb.append(line);
            return sb.toString();
        }
    }

    private void downloadFile(String urlStr, File destination) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("User-Agent", "StellarLauncher/" + StellarLauncher.VERSION);
        long fileSize = conn.getContentLengthLong();
        String fileName = destination.getName();
        boolean showProgress = fileSize > 100_000;

        try (InputStream in = conn.getInputStream(); FileOutputStream fos = new FileOutputStream(destination)) {
            if (showProgress)
                SwingUtilities.invokeLater(() -> {
                    progressBar.setVisible(true);
                    progressBar.setValue(0);
                    progressBar.setString(fileName);
                });
            byte[] buf = new byte[8192];
            int n;
            long total = 0;
            while ((n = in.read(buf)) != -1) {
                fos.write(buf, 0, n);
                total += n;
                if (fileSize > 0 && showProgress) {
                    int pct = (int) ((total * 100) / fileSize);
                    long ft = total;
                    SwingUtilities.invokeLater(() -> {
                        progressBar.setValue(pct);
                        progressBar.setString(fileName + ": " + pct + "% (" + formatFileSize(ft)
                                + "/" + formatFileSize(fileSize) + ")");
                    });
                }
            }
        }
        SwingUtilities.invokeLater(() -> {
            progressBar.setValue(0);
            progressBar.setString("");
        });
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024)
            return bytes + " B";
        if (bytes < 1024 * 1024)
            return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    // ─── DISPOSE ─────────────────────────────────────────────────────────────────

    @Override
    public void dispose() {
        log("🛑 Closing...");
        discordRunning = false;
        if (discordThread != null) {
            discordThread.interrupt();
            try {
                discordThread.join(1000);
            } catch (Exception ignored) {
            }
        }
        if (discordUpdateProcessor != null)
            discordUpdateProcessor.interrupt();
        if (periodicUpdater != null)
            periodicUpdater.interrupt();
        try {
            if (discordRPC != null)
                discordRPC.Discord_Shutdown();
        } catch (Exception ignored) {
        }
        super.dispose();
    }
}
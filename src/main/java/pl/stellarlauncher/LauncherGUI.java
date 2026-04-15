package pl.stellarlauncher;

import javax.imageio.ImageIO;
import javax.swing.*;
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

    private final Color BG_DEEP = new Color(9, 11, 16);
    private final Color BG_SIDEBAR = new Color(13, 15, 22);
    private final Color BG_CARD = new Color(14, 16, 24);
    private final Color ACCENT = new Color(0, 194, 255);
    private final Color ACCENT_DIM = new Color(0, 194, 255, 40);
    private final Color BORDER = new Color(255, 255, 255, 18);
    private final Color TEXT_PRI = new Color(255, 255, 255);
    private final Color TEXT_SEC = new Color(255, 255, 255, 90);
    private final Color TEXT_DIM = new Color(255, 255, 255, 50);
    private final Color SUCCESS = new Color(39, 200, 64);

    private File configFile;
    private JsonObject config;

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

    // Mody blokowane zależnie od GPU vendora
    // klucz = fragment nazwy pliku (lowercase), wartość = set vendorów które go NIE
    // obsługują
    private static final java.util.Map<String, java.util.Set<String>> GPU_MOD_BLACKLIST = new java.util.HashMap<>();
    static {
        // Nvidium — tylko NVIDIA, crashuje na Intel i AMD
        GPU_MOD_BLACKLIST.put("nvidium", new java.util.HashSet<>(java.util.Arrays.asList("INTEL", "AMD")));
        // ImmediatelyFast — agresywne optymalizacje OpenGL, crashuje na Intel iGPU
        GPU_MOD_BLACKLIST.put("immediatelyFast", new java.util.HashSet<>(java.util.Arrays.asList("INTEL")));
        // Exordium — osobny framebuffer, problemy z Intel HD
        GPU_MOD_BLACKLIST.put("exordium", new java.util.HashSet<>(java.util.Arrays.asList("INTEL")));
        // Iris — shadery wymagają OpenGL 4.6, Intel HD 630 go nie ogarnia
        GPU_MOD_BLACKLIST.put("iris", new java.util.HashSet<>(java.util.Arrays.asList("INTEL")));
        // EntityCulling — occlusion queries buggy na starych Intel driverach
        GPU_MOD_BLACKLIST.put("entityculling", new java.util.HashSet<>(java.util.Arrays.asList("INTEL")));
    }

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
                if (backgroundImage != null) {
                    g2d.drawImage(backgroundImage, 0, 0, getWidth(), getHeight(), null);
                    g2d.setColor(new Color(9, 11, 16, 200));
                    g2d.fillRect(0, 0, getWidth(), getHeight());
                } else {
                    g2d.setColor(BG_DEEP);
                    g2d.fillRect(0, 0, getWidth(), getHeight());
                }
            }
        };
        mainPanel.setLayout(null);
        mainPanel.setOpaque(false);

        createTitleBar(mainPanel);

        // ── Sidebar ──────────────────────────────────────────────────────────────
        JPanel sidebar = new JPanel(null) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2d = (Graphics2D) g;
                g2d.setColor(BG_SIDEBAR);
                g2d.fillRect(0, 0, getWidth(), getHeight());
                g2d.setColor(BORDER);
                g2d.fillRect(getWidth() - 1, 0, 1, getHeight());
            }
        };
        sidebar.setBounds(0, 38, 200, 682);
        sidebar.setOpaque(false);

        // Logo
        JLabel logoLabel = new JLabel("STELLAR");
        logoLabel.setFont(MinecraftFont.getBold(15));
        logoLabel.setForeground(TEXT_PRI);
        logoLabel.setBounds(20, 20, 160, 22);
        sidebar.add(logoLabel);

        JLabel verLabel = new JLabel("v" + StellarLauncher.VERSION);
        verLabel.setFont(new Font("Consolas", Font.PLAIN, 10));
        verLabel.setForeground(new Color(255, 255, 255, 50));
        verLabel.setBounds(20, 44, 160, 14);
        sidebar.add(verLabel);

        JSeparator sep1 = new JSeparator();
        sep1.setBounds(0, 68, 200, 1);
        sep1.setForeground(BORDER);
        sidebar.add(sep1);

        // Nav buttons
        String[][] navItems = {
                { "> Play", "play" },
                { "= Logs", "logs" },
                { "# Mods", "mods" },
                { "* Settings", "settings" },
                { "@ Repair", "repair" },
        };
        int[] navY = { 80, 110, 140, 170, 200 };
        for (int i = 0; i < navItems.length; i++) {
            JButton nb = createNavButton(navItems[i][0], 0, navY[i], 200, 30);
            boolean isActive = navItems[i][1].equals("play");
            nb.setForeground(isActive ? ACCENT : TEXT_SEC);
            final int idx = i;
            nb.addActionListener(e -> {
                switch (navItems[idx][1]) {
                    case "logs":
                        scrollPane.setVisible(!scrollPane.isVisible());
                        break;
                    case "mods":
                        openModsFolder();
                        break;
                    case "settings":
                        openSettings();
                        break;
                    case "repair":
                        openRepairMenu();
                        break;
                }
            });
            sidebar.add(nb);
        }

        // GPU badge at bottom of sidebar
        JPanel gpuBadge = new JPanel(null) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2d = (Graphics2D) g;
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2d.setColor(new Color(39, 200, 64, 18));
                g2d.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);
                g2d.setColor(new Color(39, 200, 64, 50));
                g2d.setStroke(new BasicStroke(1));
                g2d.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 6, 6);
                g2d.setColor(SUCCESS);
                g2d.fillOval(10, getHeight() / 2 - 3, 6, 6);
            }
        };
        gpuBadge.setBounds(8, 640, 184, 24);
        gpuBadge.setOpaque(false);
        JLabel gpuLabel = new JLabel("GPU: detecting...");
        gpuLabel.setFont(new Font("Consolas", Font.PLAIN, 10));
        gpuLabel.setForeground(new Color(39, 200, 64, 180));
        gpuLabel.setBounds(24, 5, 156, 14);
        gpuBadge.add(gpuLabel);
        sidebar.add(gpuBadge);

        new Thread(() -> {
            String fullName = detectGPUVendor();
            SwingUtilities.invokeLater(() -> {
                // Skróć do max 22 znaków, resztę w toolltipie
                String display = fullName.length() > 22
                        ? fullName.substring(0, 20) + "…"
                        : fullName;
                gpuLabel.setText("GPU: " + display);
                gpuBadge.setToolTipText(fullName);
                gpuLabel.setToolTipText(fullName);
            });
        }).start();

        mainPanel.add(sidebar);

        // ── Main area ────────────────────────────────────────────────────────────
        int mx = 216, mw = 1064;

        // Section: Version
        JLabel versionSectionLabel = makeSectionLabel("VERSION", mx, 52, mw);
        mainPanel.add(versionSectionLabel);

        String[] versions = { "1.8.9", "1.19.2", "1.20.1", "1.21.3" };
        versionCombo = new JComboBox<>(versions);
        versionCombo.setVisible(false); // hidden, replaced by toggle buttons
        JButton[] verBtns = new JButton[4];
        int vBtnW = (mw - 24) / 4;
        for (int i = 0; i < versions.length; i++) {
            final String v = versions[i];
            verBtns[i] = createVersionButton(v, mx + i * (vBtnW + 8), 68, vBtnW, 36, v.equals("1.20.1"));
            final int fi = i;
            verBtns[i].addActionListener(e -> {
                for (JButton b : verBtns)
                    b.putClientProperty("selected", false);
                verBtns[fi].putClientProperty("selected", true);
                for (JButton b : verBtns)
                    b.repaint();
                selectedVersion = v;
                saveLastVersion();
            });
            mainPanel.add(verBtns[i]);
        }
        verBtns[2].putClientProperty("selected", true); // 1.20.1 default

        // Section: Memory card
        JPanel memCard = createSlimCard(mx, 120, mw, 80);
        mainPanel.add(memCard);

        JLabel memSection = makeSectionLabel("MEMORY ALLOCATION", 0, 12, mw - 40);
        memSection.setBounds(16, 12, mw - 40, 14);
        memCard.add(memSection);

        ramLabel = new JLabel("4096 MB");
        ramLabel.setFont(new Font("Consolas", Font.PLAIN, 13));
        ramLabel.setForeground(ACCENT);
        ramLabel.setBounds(mw - 90, 10, 80, 18);
        ramLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        memCard.add(ramLabel);

        ramSlider = new JSlider(1024, 16384, allocatedRAM) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2d = (Graphics2D) g;
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int track = getHeight() / 2;
                int filled = (int) ((getWidth() - 16)
                        * ((getValue() - getMinimum()) / (double) (getMaximum() - getMinimum())));
                g2d.setColor(new Color(255, 255, 255, 20));
                g2d.fillRoundRect(8, track - 1, getWidth() - 16, 2, 2, 2);
                g2d.setColor(ACCENT);
                g2d.fillRoundRect(8, track - 1, filled, 2, 2, 2);
                g2d.setColor(ACCENT);
                g2d.fillOval(8 + filled - 6, track - 6, 12, 12);
                g2d.setColor(new Color(0, 0, 0, 100));
                g2d.fillOval(8 + filled - 4, track - 4, 8, 8);
            }
        };
        ramSlider.setOpaque(false);
        ramSlider.setBounds(16, 36, mw - 110, 28);
        ramSlider.addChangeListener(e -> {
            allocatedRAM = ramSlider.getValue();
            ramLabel.setText(allocatedRAM + " MB");
            saveRAMSettings();
        });
        memCard.add(ramSlider);

        autoCloseCheckbox = new JCheckBox("Close launcher when game starts") {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2d = (Graphics2D) g;
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                // box
                g2d.setColor(isSelected() ? ACCENT_DIM : new Color(255, 255, 255, 12));
                g2d.fillRoundRect(0, (getHeight() - 14) / 2, 14, 14, 4, 4);
                g2d.setColor(isSelected() ? new Color(0, 194, 255, 100) : BORDER);
                g2d.setStroke(new BasicStroke(1));
                g2d.drawRoundRect(0, (getHeight() - 14) / 2, 13, 13, 4, 4);
                if (isSelected()) {
                    g2d.setColor(ACCENT);
                    g2d.setStroke(new BasicStroke(1.5f));
                    int by = (getHeight() - 14) / 2;
                    g2d.drawLine(3, by + 7, 5, by + 10);
                    g2d.drawLine(5, by + 10, 11, by + 4);
                }
                g2d.setFont(getFont());
                g2d.setColor(TEXT_SEC);
                FontMetrics fm = g2d.getFontMetrics();
                g2d.drawString(getText(), 20, (getHeight() + fm.getAscent()) / 2 - 2);
            }
        };
        autoCloseCheckbox.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        autoCloseCheckbox.setOpaque(false);
        autoCloseCheckbox.setBounds(16, 56, 320, 20);
        autoCloseCheckbox.setFocusPainted(false);
        autoCloseCheckbox.setSelected(autoCloseLauncher);
        autoCloseCheckbox.addActionListener(e -> {
            autoCloseLauncher = autoCloseCheckbox.isSelected();
            saveAutoCloseSettings();
            autoCloseCheckbox.repaint();
        });
        memCard.add(autoCloseCheckbox);

        // Launch button
        launchButton = new JButton("LAUNCH GAME") {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2d = (Graphics2D) g;
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color base = isEnabled()
                        ? (getModel().isPressed() ? new Color(0, 160, 210)
                                : getModel().isRollover() ? new Color(40, 210, 255) : ACCENT)
                        : new Color(60, 70, 90);
                g2d.setColor(base);
                g2d.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                g2d.setFont(getFont());
                FontMetrics fm = g2d.getFontMetrics();
                g2d.setColor(new Color(6, 8, 16));
                int tx = (getWidth() - fm.stringWidth(getText())) / 2;
                int ty = (getHeight() + fm.getAscent()) / 2 - 2;
                g2d.drawString(getText(), tx, ty);
            }
        };
        launchButton.setBounds(mx, 218, mw, 44);
        launchButton.setFont(new Font("Segoe UI", Font.BOLD, 13));
        launchButton.setFocusPainted(false);
        launchButton.setBorderPainted(false);
        launchButton.setContentAreaFilled(false);
        launchButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
        launchButton.addActionListener(e -> launchMinecraft());
        launchButton.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) {
                launchButton.repaint();
            }

            public void mouseExited(MouseEvent e) {
                launchButton.repaint();
            }
        });
        mainPanel.add(launchButton);

        // Status row
        statusLabel = new JLabel("● Ready to launch");
        statusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        statusLabel.setForeground(new Color(39, 200, 64, 160));
        statusLabel.setBounds(mx, 268, mw, 16);
        mainPanel.add(statusLabel);

        // Separator
        JPanel divider = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(BORDER);
                g.fillRect(0, 0, getWidth(), 1);
            }
        };
        divider.setBounds(mx, 294, mw, 1);
        divider.setOpaque(false);
        mainPanel.add(divider);

        // Toolbar
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        toolbar.setBounds(mx, 303, mw, 30);
        toolbar.setOpaque(false);
        String[] toolNames = { "Mods folder", "Repair", "Kill instance", "Copy logs" };
        for (String name : toolNames) {
            JButton tb = createToolbarButton(name);
            tb.addActionListener(e -> {
                switch (name) {
                    case "Mods folder":
                        openModsFolder();
                        break;
                    case "Repair":
                        openRepairMenu();
                        break;
                    case "Kill instance":
                        killMinecraftInstance();
                        break;
                    case "Copy logs":
                        copyLogs();
                        break;
                }
            });
            toolbar.add(tb);
        }
        mainPanel.add(toolbar);

        // Progress bar
        progressBar = new JProgressBar() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2d = (Graphics2D) g;
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2d.setColor(new Color(255, 255, 255, 12));
                g2d.fillRoundRect(0, 0, getWidth(), getHeight(), 4, 4);
                int pw = (int) ((getWidth() - 2) * (getValue() / (double) getMaximum()));
                if (pw > 0) {
                    g2d.setColor(ACCENT);
                    g2d.fillRoundRect(1, 1, pw, getHeight() - 2, 3, 3);
                }
                if (getString() != null && !getString().isEmpty()) {
                    g2d.setColor(TEXT_PRI);
                    g2d.setFont(getFont());
                    FontMetrics fm = g2d.getFontMetrics();
                    g2d.drawString(getString(), (getWidth() - fm.stringWidth(getString())) / 2,
                            (getHeight() + fm.getAscent()) / 2 - 2);
                }
            }
        };
        progressBar.setStringPainted(true);
        progressBar.setBounds(mx, 342, mw, 14);
        progressBar.setFont(new Font("Consolas", Font.PLAIN, 10));
        progressBar.setVisible(false);
        progressBar.setOpaque(false);
        progressBar.setBorderPainted(false);
        mainPanel.add(progressBar);

        // Log area
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setBackground(new Color(8, 10, 15));
        logArea.setForeground(new Color(0, 220, 130));
        logArea.setFont(new Font("Consolas", Font.PLAIN, 11));
        logArea.setMargin(new Insets(10, 12, 10, 12));
        logArea.setCaretColor(ACCENT);

        scrollPane = new JScrollPane(logArea);
        scrollPane.setBounds(mx, 360, mw, 290);
        scrollPane.setBorder(BorderFactory.createLineBorder(BORDER, 1));
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(false);
        scrollPane.setVisible(false);
        mainPanel.add(scrollPane);

        add(mainPanel);
        log("✨ StellarLauncher v" + StellarLauncher.VERSION + " started!");
    }

    // ─── UI HELPERS ──────────────────────────────────────────────────────────────

    private JLabel makeSectionLabel(String text, int x, int y, int w) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        l.setForeground(new Color(255, 255, 255, 50));
        l.setBounds(x, y, w, 14);
        return l;
    }

    private JPanel createSlimCard(int x, int y, int w, int h) {
        JPanel card = new JPanel(null) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2d = (Graphics2D) g;
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2d.setColor(BG_CARD);
                g2d.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                g2d.setColor(BORDER);
                g2d.setStroke(new BasicStroke(1));
                g2d.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
            }
        };
        card.setBounds(x, y, w, h);
        card.setOpaque(false);
        return card;
    }

    private JButton createVersionButton(String text, int x, int y, int w, int h, boolean selected) {
        JButton btn = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2d = (Graphics2D) g;
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                boolean sel = Boolean.TRUE.equals(getClientProperty("selected"));
                boolean hover = getModel().isRollover();
                g2d.setColor(sel ? ACCENT_DIM : hover ? new Color(255, 255, 255, 10) : new Color(255, 255, 255, 5));
                g2d.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);
                g2d.setColor(sel ? new Color(0, 194, 255, 90) : hover ? new Color(255, 255, 255, 25) : BORDER);
                g2d.setStroke(new BasicStroke(1));
                g2d.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 6, 6);
                g2d.setFont(new Font("Consolas", Font.BOLD, 12));
                g2d.setColor(sel ? ACCENT : hover ? TEXT_PRI : TEXT_SEC);
                FontMetrics fm = g2d.getFontMetrics();
                g2d.drawString(getText(), (getWidth() - fm.stringWidth(getText())) / 2,
                        (getHeight() + fm.getAscent()) / 2 - 2);
            }
        };
        btn.putClientProperty("selected", selected);
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

    private JButton createNavButton(String text, int x, int y, int w, int h) {
        JButton btn = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2d = (Graphics2D) g;
                boolean hover = getModel().isRollover();
                boolean active = getForeground().equals(ACCENT);
                if (active) {
                    g2d.setColor(new Color(0, 194, 255, 12));
                    g2d.fillRect(0, 0, getWidth(), getHeight());
                    g2d.setColor(ACCENT);
                    g2d.fillRect(0, 0, 2, getHeight());
                } else if (hover) {
                    g2d.setColor(new Color(255, 255, 255, 8));
                    g2d.fillRect(0, 0, getWidth(), getHeight());
                }
                g2d.setFont(getFont());
                g2d.setColor(getForeground());
                FontMetrics fm = g2d.getFontMetrics();
                g2d.drawString(getText(), 20, (getHeight() + fm.getAscent()) / 2 - 2);
            }
        };
        btn.setBounds(x, y, w, h);
        btn.setFont(new Font("Segoe UI", Font.PLAIN, 12));
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

    private JButton createToolbarButton(String text) {
        JButton btn = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2d = (Graphics2D) g;
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2d.setColor(getModel().isPressed() ? new Color(255, 255, 255, 12)
                        : getModel().isRollover() ? new Color(255, 255, 255, 8) : new Color(255, 255, 255, 4));
                g2d.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);
                g2d.setColor(getModel().isRollover() ? new Color(255, 255, 255, 30) : BORDER);
                g2d.setStroke(new BasicStroke(1));
                g2d.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 6, 6);
                g2d.setFont(getFont());
                g2d.setColor(getModel().isRollover() ? TEXT_PRI : TEXT_SEC);
                FontMetrics fm = g2d.getFontMetrics();
                g2d.drawString(getText(), (getWidth() - fm.stringWidth(getText())) / 2,
                        (getHeight() + fm.getAscent()) / 2 - 2);
            }
        };
        btn.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        btn.setPreferredSize(new Dimension(100, 26));
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

    // ─── TITLE BAR ───────────────────────────────────────────────────────────────

    private void createTitleBar(JPanel parent) {
        JPanel titleBar = new JPanel(null) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2d = (Graphics2D) g;
                g2d.setColor(new Color(6, 8, 16));
                g2d.fillRect(0, 0, getWidth(), getHeight());
                g2d.setColor(BORDER);
                g2d.fillRect(0, getHeight() - 1, getWidth(), 1);
            }
        };
        titleBar.setBounds(0, 0, 1280, 38);
        titleBar.setOpaque(false);

        JButton minimizeBtn = createWindowButton("_", 1190, 4, 28, 28);
        minimizeBtn.addActionListener(e -> setState(JFrame.ICONIFIED));
        titleBar.add(minimizeBtn);

        JButton closeBtn = createWindowButton("X", 1244, 4, 28, 28);
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
                boolean hover = getModel().isRollover();
                boolean press = getModel().isPressed();
                Color bg = type.equals("X")
                        ? (hover || press ? new Color(220, 50, 50, 180) : new Color(0, 0, 0, 0))
                        : (hover || press ? new Color(255, 255, 255, 20) : new Color(0, 0, 0, 0));
                g2d.setColor(bg);
                g2d.fillRoundRect(0, 0, getWidth(), getHeight(), 4, 4);
                g2d.setColor(hover ? TEXT_PRI : TEXT_DIM);
                g2d.setStroke(new BasicStroke(1.2f));
                int cx = getWidth() / 2, cy = getHeight() / 2, s = 5;
                if (type.equals("_"))
                    g2d.drawLine(cx - s, cy + 2, cx + s, cy + 2);
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

    // ─── REPAIR MENU ─────────────────────────────────────────────────────────────

    private void openRepairMenu() {
        String[] options = {
                "Open Mods Folder",
                "Reinstall Version",
                "Disable All User Mods",
                "Disable All Preinstalled Mods",
                "Settings",
                "Cancel"
        };
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
                disableAllUserMods();
                break;
            case 3:
                disableAllPreinstalledMods();
                break;
            case 4:
                openSettings();
                break;
        }
    }

    private void disableAllUserMods() {
        String cleanVersion = selectedVersion.contains("-")
                ? selectedVersion.substring(selectedVersion.lastIndexOf("-") + 1)
                : selectedVersion;

        File userModsDir = new File(StellarLauncher.LAUNCHER_DIR, "mods/" + cleanVersion);
        File[] mods = userModsDir.listFiles((d, n) -> n.endsWith(".jar"));

        if (mods == null || mods.length == 0) {
            JOptionPane.showMessageDialog(this, "Brak aktywnych modów gracza dla wersji " + cleanVersion + ".",
                    "Brak modów", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(this,
                "Wyłączyć wszystkie mody gracza dla " + cleanVersion + "? (" + mods.length + " modów)\n"
                        + "Pliki zostaną przemianowane na .jar.disabled — można cofnąć ręcznie.",
                "Disable All User Mods", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION)
            return;

        int disabled = 0;
        for (File mod : mods) {
            File target = new File(mod.getParentFile(), mod.getName() + ".disabled");
            if (mod.renameTo(target)) {
                log("🔕 Wyłączono: " + mod.getName());
                disabled++;
            } else {
                log("⚠️ Nie można wyłączyć: " + mod.getName());
            }
        }
        JOptionPane.showMessageDialog(this, "Wyłączono " + disabled + " modów gracza.",
                "Gotowe", JOptionPane.INFORMATION_MESSAGE);
    }

    // Wyjątki — te preinstalled mody NIGDY nie są wyłączane
    private static final java.util.Set<String> PREINSTALLED_WHITELIST = new java.util.HashSet<>(
            java.util.Arrays.asList("sodium", "sodium-extra", "fabric-api", "lithium"));

    private boolean isWhitelisted(String fileName) {
        String lower = fileName.toLowerCase();
        for (String key : PREINSTALLED_WHITELIST) {
            if (lower.startsWith(key))
                return true;
        }
        return false;
    }

    private void disableAllPreinstalledMods() {
        String cleanVersion = selectedVersion.contains("-")
                ? selectedVersion.substring(selectedVersion.lastIndexOf("-") + 1)
                : selectedVersion;

        File preinstalledDir = new File(StellarLauncher.LAUNCHER_DIR, "mods/" + cleanVersion + "/.preinstalled");
        File[] mods = preinstalledDir.listFiles((d, n) -> n.endsWith(".jar"));

        if (mods == null || mods.length == 0) {
            JOptionPane.showMessageDialog(this, "Brak aktywnych preinstalled modów dla wersji " + cleanVersion + ".",
                    "Brak modów", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        long toDisable = java.util.Arrays.stream(mods).filter(f -> !isWhitelisted(f.getName())).count();

        int confirm = JOptionPane.showConfirmDialog(this,
                "Wyłączyć preinstalled mody dla " + cleanVersion + "? (" + toDisable + " modów)\n"
                        + "Pozostawione zostaną: sodium, sodium-extra, fabric-api, lithium.\n"
                        + "Pliki zostaną przemianowane na .jar.disabled — można cofnąć ręcznie.",
                "Disable All Preinstalled Mods", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION)
            return;

        int disabled = 0, skipped = 0;
        for (File mod : mods) {
            if (isWhitelisted(mod.getName())) {
                log("✅ Pozostawiono: " + mod.getName());
                skipped++;
                continue;
            }
            File target = new File(mod.getParentFile(), mod.getName() + ".disabled");
            if (mod.renameTo(target)) {
                log("🔕 Wyłączono: " + mod.getName());
                disabled++;
            } else {
                log("⚠️ Nie można wyłączyć: " + mod.getName());
            }
        }
        JOptionPane.showMessageDialog(this,
                "Wyłączono " + disabled + " modów.\nPominięto (whitelist): " + skipped + " modów.",
                "Gotowe", JOptionPane.INFORMATION_MESSAGE);
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

    /**
     * Wykrywa producenta GPU.
     * Windows: wmic path win32_VideoController
     * Linux: lspci
     * Zwraca: "NVIDIA", "INTEL", "AMD" lub "UNKNOWN"
     */
    private String detectGPUVendor() {
        try {
            String os = System.getProperty("os.name", "").toLowerCase();

            if (os.contains("win")) {
                Process p = new ProcessBuilder("wmic", "path", "win32_VideoController", "get", "name")
                        .redirectErrorStream(true).start();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty() || line.equalsIgnoreCase("name"))
                            continue;
                        // Zwraca pierwszą kartę graficzną (ignoruje Microsoft Basic Display)
                        if (line.toLowerCase().contains("microsoft basic"))
                            continue;
                        return formatGPUName(line);
                    }
                }
            } else {
                Process p = new ProcessBuilder("lspci")
                        .redirectErrorStream(true).start();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        String lower = line.toLowerCase();
                        if (lower.contains("vga") || lower.contains("display") || lower.contains("3d")) {
                            // Wyciągamy część po ostatnim ":"
                            int idx = line.lastIndexOf(":");
                            if (idx != -1)
                                return formatGPUName(line.substring(idx + 1).trim());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log("⚠️ GPU detection failed: " + e.getMessage());
        }
        return "UNKNOWN";
    }

    private String formatGPUName(String raw) {
        if (raw == null || raw.isEmpty())
            return "UNKNOWN";
        // Skróć zbędne końcówki
        raw = raw.replaceAll("(?i)\\s*/\\s*.*", ""); // np. "RTX 3060 / something" → "RTX 3060"
        raw = raw.trim();
        return raw;
    }

    /**
     * Sprawdza czy dany mod jest kompatybilny z wykrytym GPU.
     */
    private boolean isModCompatibleWithGPU(String fileName, String gpuName) {
        String vendor = gpuName.toLowerCase().contains("nvidia") ? "NVIDIA"
                : gpuName.toLowerCase().contains("intel") ? "INTEL"
                        : gpuName.toLowerCase().contains("amd") || gpuName.toLowerCase().contains("radeon") ? "AMD"
                                : "UNKNOWN";
        if (vendor.equals("UNKNOWN") || vendor.equals("NVIDIA"))
            return true;
        String lower = fileName.toLowerCase();
        for (java.util.Map.Entry<String, java.util.Set<String>> entry : GPU_MOD_BLACKLIST.entrySet()) {
            if (lower.contains(entry.getKey()) && entry.getValue().contains(vendor))
                return false;
        }
        return true;
    }

    private void setupModsForVersion(String version) {
        log("📦 Setting up mods for " + version + "...");

        String gpuVendor = detectGPUVendor();
        log("🖥️ Wykryty GPU vendor: " + gpuVendor);
        if (!gpuVendor.equals("NVIDIA") && !gpuVendor.equals("UNKNOWN")) {
            log("⚠️ Niekompatybilne mody zostaną pominięte dla: " + gpuVendor);
        }

        File instanceModsDir = new File(StellarLauncher.MINECRAFT_DIR, "mods");
        instanceModsDir.mkdirs();
        clearModsFolder(instanceModsDir);

        String cleanVersion = version.contains("-") ? version.substring(version.lastIndexOf("-") + 1) : version;

        // ── Preinstalled mods ──────────────────────────────────────────────────────
        File preinstalledDir = new File(StellarLauncher.LAUNCHER_DIR, "mods/" + cleanVersion + "/.preinstalled");
        if (preinstalledDir.exists()) {
            File[] pre = preinstalledDir.listFiles((d, n) -> n.endsWith(".jar"));
            if (pre != null && pre.length > 0) {
                int copied = 0, skipped = 0;
                for (File mod : pre) {
                    if (!isModCompatibleWithGPU(mod.getName(), gpuVendor)) {
                        log("  🚫 Pominięto (GPU): " + mod.getName());
                        skipped++;
                        continue;
                    }
                    try {
                        Files.copy(mod.toPath(), new File(instanceModsDir, mod.getName()).toPath(),
                                StandardCopyOption.REPLACE_EXISTING);
                        copied++;
                    } catch (Exception e) {
                        log("  ⚠️ " + mod.getName() + ": " + e.getMessage());
                    }
                }
                log("  📦 Preinstalled: " + copied + " skopiowano"
                        + (skipped > 0 ? ", " + skipped + " pominięto (GPU)" : ""));
            }
        }

        // ── User mods ──────────────────────────────────────────────────────────────
        File launcherModsDir = new File(StellarLauncher.LAUNCHER_DIR, "mods/" + cleanVersion);
        launcherModsDir.mkdirs();

        File[] mods = launcherModsDir.listFiles((d, n) -> n.endsWith(".jar"));
        if (mods == null || mods.length == 0) {
            log("  ℹ️ Brak modów gracza — dodaj .jar do: " + launcherModsDir.getAbsolutePath());
        } else {
            log("  🧩 Mody gracza: " + mods.length);
            for (File mod : mods) {
                if (!isModCompatibleWithGPU(mod.getName(), gpuVendor)) {
                    log("  🚫 Pominięto (GPU): " + mod.getName());
                    continue;
                }
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
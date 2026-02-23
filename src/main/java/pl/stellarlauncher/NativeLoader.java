package pl.stellarlauncher;

import java.io.*;

public class NativeLoader {

    private static final String DLL_NAME = "discord-rpc.dll";
    private static final String SO_NAME = "libdiscord-rpc.so";
    private static final String DYLIB_NAME = "libdiscord-rpc.dylib";

    /**
     * Automatycznie wypakowuje i ładuje natywną bibliotekę Discord RPC
     * 
     * @return true jeśli udało się załadować, false jeśli nie
     */
    public static boolean loadDiscordRPC() {
        try {
            String osName = System.getProperty("os.name").toLowerCase();
            String libName;

            if (osName.contains("win")) {
                libName = DLL_NAME;
            } else if (osName.contains("mac")) {
                libName = DYLIB_NAME;
            } else {
                libName = SO_NAME;
            }

            // Sprawdź czy już istnieje w folderze launchera
            File nativeFile = new File(StellarLauncher.LAUNCHER_DIR, libName);

            if (!nativeFile.exists()) {
                System.out.println("📦 Extracting Discord RPC library...");
                extractNativeLibrary(libName, nativeFile);
            }

            // Załaduj bibliotekę
            System.load(nativeFile.getAbsolutePath());
            System.out.println("✅ Discord RPC library loaded!");
            return true;

        } catch (Exception e) {
            System.err.println("⚠️ Failed to load Discord RPC: " + e.getMessage());
            return false;
        }
    }

    /**
     * Wypakowuje natywną bibliotekę z resources do pliku
     */
    private static void extractNativeLibrary(String resourceName, File targetFile) throws IOException {
        // Spróbuj załadować z resources
        InputStream in = NativeLoader.class.getResourceAsStream("/" + resourceName);

        if (in == null) {
            throw new FileNotFoundException("Native library not found in resources: " + resourceName);
        }

        // Stwórz folder jeśli nie istnieje
        targetFile.getParentFile().mkdirs();

        // Kopiuj do pliku
        try (FileOutputStream out = new FileOutputStream(targetFile)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        } finally {
            in.close();
        }

        // Ustaw uprawnienia (dla Linux/Mac)
        targetFile.setExecutable(true);
        targetFile.setReadable(true);

        System.out.println("✅ Extracted: " + resourceName);
    }

    /**
     * Opcjonalna metoda do czyszczenia starych wersji DLL
     */
    public static void cleanOldNatives() {
        try {
            File[] oldFiles = StellarLauncher.LAUNCHER_DIR
                    .listFiles((dir, name) -> name.startsWith("discord-rpc") || name.startsWith("libdiscord-rpc"));

            if (oldFiles != null) {
                for (File f : oldFiles) {
                    if (f.isFile() && isFileLocked(f)) {
                        continue; // Pomiń jeśli używany
                    }
                    // Usuń stare wersje jeśli potrzeba
                }
            }
        } catch (Exception e) {
            // Ignore
        }
    }

    /**
     * Sprawdza czy plik jest zablokowany (używany)
     */
    private static boolean isFileLocked(File file) {
        if (!file.exists())
            return false;
        try {
            // Spróbuj otworzyć w trybie zapisu
            try (FileOutputStream fos = new FileOutputStream(file, true)) {
                return false;
            }
        } catch (IOException e) {
            return true; // Plik zablokowany
        }
    }
}
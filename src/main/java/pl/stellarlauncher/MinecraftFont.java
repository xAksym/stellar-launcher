package pl.stellarlauncher;

import java.awt.*;
import java.io.*;

public class MinecraftFont {
    private static Font minecraftFont;
    private static Font minecraftBold;

    static {
        try {
            // Próbuj załadować OTF czcionkę
            InputStream fontStream = MinecraftFont.class.getResourceAsStream("/fonts/Minecraft.otf");

            if (fontStream == null) {
                fontStream = MinecraftFont.class.getResourceAsStream("/fonts/MinecraftRegular.otf");
            }

            if (fontStream == null) {
                fontStream = MinecraftFont.class.getResourceAsStream("/fonts/MinecraftBold.otf");
            }

            if (fontStream != null) {
                Font baseFont = Font.createFont(Font.TRUETYPE_FONT, fontStream);
                minecraftFont = baseFont.deriveFont(Font.PLAIN, 14f);
                minecraftBold = baseFont.deriveFont(Font.BOLD, 14f);
                fontStream.close();
            } else {
                // Fallback - Consolas wygląda podobnie do Minecraft
                minecraftFont = new Font("Consolas", Font.PLAIN, 14);
                minecraftBold = new Font("Consolas", Font.BOLD, 14);
            }
        } catch (Exception e) {
            // Fallback
            minecraftFont = new Font("Consolas", Font.PLAIN, 14);
            minecraftBold = new Font("Consolas", Font.BOLD, 14);
        }
    }

    public static Font getFont(float size) {
        if (minecraftFont == null) {
            return new Font("Consolas", Font.PLAIN, (int) size);
        }
        return minecraftFont.deriveFont(size);
    }

    public static Font getBold(float size) {
        if (minecraftBold == null) {
            return new Font("Consolas", Font.BOLD, (int) size);
        }
        return minecraftBold.deriveFont(Font.BOLD, size);
    }
}
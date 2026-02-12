package com.dadoirie.trueauth.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.minecraft.client.Minecraft;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@OnlyIn(Dist.CLIENT)
public class PasswordStorage {
    private static final String PASSWORD_FILE = ".private/trueauth/hashed_password.txt";
    
    public static void savePassword(String password) {
        try {
            // Hash the password
            String hashedPassword = hashPassword(password);
            
            // Get the Minecraft game directory
            File gameDir = Minecraft.getInstance().gameDirectory;
            String filePath = gameDir.getAbsolutePath() + File.separator + PASSWORD_FILE;
            
            // Create directories if they don't exist
            File file = new File(filePath);
            file.getParentFile().mkdirs();
            
            // Write the hashed password to the file
            try (FileWriter writer = new FileWriter(file)) {
                writer.write(hashedPassword);
            }
        } catch (IOException | NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
    }
    
    public static String loadPassword() {
        try {
            // Get the Minecraft game directory
            File gameDir = Minecraft.getInstance().gameDirectory;
            String filePath = gameDir.getAbsolutePath() + File.separator + PASSWORD_FILE;
            
            // Check if the file exists
            if (Files.exists(Paths.get(filePath))) {
                // Read the hashed password from the file
                return new String(Files.readAllBytes(Paths.get(filePath))).trim();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        
        return "";
    }
    
    public static boolean passwordExists() {
        try {
            // Get the Minecraft game directory
            File gameDir = Minecraft.getInstance().gameDirectory;
            String filePath = gameDir.getAbsolutePath() + File.separator + PASSWORD_FILE;
            return Files.exists(Paths.get(filePath));
        } catch (Exception e) {
            return false;
        }
    }
    
    private static String hashPassword(String password) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hashBytes = digest.digest(password.getBytes());
        
        // Convert byte array to hex string
        StringBuilder hexString = new StringBuilder();
        for (byte b : hashBytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        
        return hexString.toString();
    }
}

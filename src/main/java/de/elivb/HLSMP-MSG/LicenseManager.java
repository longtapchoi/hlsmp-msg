package de.elivb.donutMSG;

import org.bukkit.plugin.java.JavaPlugin;

public class LicenseManager {
   private final JavaPlugin plugin;

   public LicenseManager(JavaPlugin plugin) {
      this.plugin = plugin;
   }

   public boolean validateLicenseOnStartup() {
      return true;
   }

   public boolean isLicenseValid() {
      return true;
   }

   public String getServerId() {
      return "HLSMP";
   }

   public String getLicenseKey() {
      return "HLSMP";
   }

   public void setLicenseKey(String key) {
      // bypassed
   }
}

package de.elivb.donutMSG;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class DonutMSG extends JavaPlugin implements TabCompleter {
   private static DonutMSG instance;
   private Map<UUID, UUID> lastMessage;
   private Set<UUID> msgDisabled;
   private Map<UUID, Set<UUID>> blockedPlayers;
   private boolean isFolia;
   private Connection database;
   private LicenseManager licenseManager;

   @Override
   public void onEnable() {
      this.licenseManager = new LicenseManager(this);
      instance = this;
      this.saveDefaultConfig();

      try {
         Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
         this.isFolia = true;
      } catch (ClassNotFoundException e) {
         this.isFolia = false;
      }

      this.lastMessage = new ConcurrentHashMap<>();
      this.msgDisabled = ConcurrentHashMap.newKeySet();
      this.blockedPlayers = new ConcurrentHashMap<>();

      this.getCommand("msg").setTabCompleter(this);
      this.getCommand("reply").setTabCompleter(this);
      this.getCommand("msgblock").setTabCompleter(this);
      this.getCommand("msgunblock").setTabCompleter(this);
      this.getCommand("msgtoggle").setTabCompleter(this);
      this.getCommand("hlsmpmsg").setTabCompleter(this);

      this.initDatabase();
      this.loadDataFromDatabase();
      getLogger().info("HLSMP-MSG đã khởi động!");
   }

   @Override
   public void onDisable() {
      this.saveAllToDatabase();
      this.closeDatabase();
      getLogger().info("HLSMP-MSG đã tắt!");
   }

   public LicenseManager getLicenseManager() {
      return this.licenseManager;
   }

   @Override
   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      if (!(sender instanceof Player player)) return new ArrayList<>();
      return switch (command.getName().toLowerCase()) {
         case "msg" -> handleMsgTabComplete(player, args);
         case "reply" -> handleReplyTabComplete(player, args);
         case "msgblock" -> handleMsgBlockTabComplete(player, args);
         case "msgunblock" -> handleMsgUnblockTabComplete(player, args);
         case "hlsmpmsg" -> handleReloadTabComplete(player, args);
         default -> new ArrayList<>();
      };
   }

   private List<String> handleMsgTabComplete(Player player, String[] args) {
      if (args.length == 1) {
         return Bukkit.getOnlinePlayers().stream()
            .filter(p -> !p.equals(player))
            .map(Player::getName)
            .filter(name -> name.toLowerCase().startsWith(args[0].toLowerCase()))
            .collect(Collectors.toList());
      }
      if (args.length >= 2) {
         List<String> suggestions = this.getConfig().getStringList("tab-suggestions.msg");
         if (!suggestions.isEmpty()) {
            String lastArg = args[args.length - 1];
            return suggestions.stream()
               .filter(s -> s.toLowerCase().startsWith(lastArg.toLowerCase()))
               .collect(Collectors.toList());
         }
      }
      return new ArrayList<>();
   }

   private List<String> handleReplyTabComplete(Player player, String[] args) {
      if (args.length >= 1) {
         List<String> suggestions = this.getConfig().getStringList("tab-suggestions.reply");
         if (!suggestions.isEmpty()) {
            String lastArg = args[args.length - 1];
            return suggestions.stream()
               .filter(s -> s.toLowerCase().startsWith(lastArg.toLowerCase()))
               .collect(Collectors.toList());
         }
      }
      return new ArrayList<>();
   }

   private List<String> handleMsgBlockTabComplete(Player player, String[] args) {
      if (args.length == 1) {
         Set<UUID> blocked = this.blockedPlayers.getOrDefault(player.getUniqueId(), new HashSet<>());
         return Bukkit.getOnlinePlayers().stream()
            .filter(p -> !p.equals(player))
            .filter(p -> !blocked.contains(p.getUniqueId()))
            .map(Player::getName)
            .filter(name -> name.toLowerCase().startsWith(args[0].toLowerCase()))
            .collect(Collectors.toList());
      }
      return new ArrayList<>();
   }

   private List<String> handleMsgUnblockTabComplete(Player player, String[] args) {
      if (args.length == 1) {
         Set<UUID> blocked = this.blockedPlayers.getOrDefault(player.getUniqueId(), new HashSet<>());
         return blocked.stream()
            .map(Bukkit::getPlayer)
            .filter(Objects::nonNull)
            .map(Player::getName)
            .filter(name -> name.toLowerCase().startsWith(args[0].toLowerCase()))
            .collect(Collectors.toList());
      }
      return new ArrayList<>();
   }

   private List<String> handleReloadTabComplete(Player player, String[] args) {
      if (args.length == 1 && player.hasPermission("msg.reload")) {
         return List.of("reload").stream()
            .filter(s -> s.startsWith(args[0].toLowerCase()))
            .collect(Collectors.toList());
      }
      return new ArrayList<>();
   }

   private void initDatabase() {
      try {
         File dataFolder = this.getDataFolder();
         if (!dataFolder.exists()) dataFolder.mkdirs();
         File dbFile = new File(dataFolder, "data.db");
         this.database = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
         try (Statement stmt = this.database.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS blocked_players (" +
               "id INTEGER PRIMARY KEY AUTOINCREMENT," +
               "player_uuid VARCHAR(36) NOT NULL," +
               "blocked_uuid VARCHAR(36) NOT NULL," +
               "blocked_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
               "UNIQUE(player_uuid, blocked_uuid))");
            stmt.execute("CREATE TABLE IF NOT EXISTS msg_disabled (" +
               "id INTEGER PRIMARY KEY AUTOINCREMENT," +
               "player_uuid VARCHAR(36) NOT NULL UNIQUE," +
               "disabled_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
         }
      } catch (SQLException e) {
         getLogger().severe("Không thể khởi tạo database: " + e.getMessage());
      }
   }

   private void loadDataFromDatabase() {
      this.runTaskAsync(() -> {
         try {
            // Load blocked players
            try (Statement stmt1 = this.database.createStatement();
                 ResultSet rs1 = stmt1.executeQuery("SELECT player_uuid, blocked_uuid FROM blocked_players")) {
               while (rs1.next()) {
                  UUID playerUUID = UUID.fromString(rs1.getString("player_uuid"));
                  UUID blockedUUID = UUID.fromString(rs1.getString("blocked_uuid"));
                  this.blockedPlayers.computeIfAbsent(playerUUID, k -> ConcurrentHashMap.newKeySet()).add(blockedUUID);
               }
            }
            // Load msg disabled
            try (Statement stmt2 = this.database.createStatement();
                 ResultSet rs2 = stmt2.executeQuery("SELECT player_uuid FROM msg_disabled")) {
               while (rs2.next()) {
                  this.msgDisabled.add(UUID.fromString(rs2.getString("player_uuid")));
               }
            }
         } catch (SQLException e) {
            getLogger().severe("Lỗi load data: " + e.getMessage());
         }
      });
   }

   private void saveAllToDatabase() {
      try {
         try (Statement stmt = this.database.createStatement()) {
            stmt.execute("DELETE FROM blocked_players");
            stmt.execute("DELETE FROM msg_disabled");
         }
         try (PreparedStatement pstmt1 = this.database.prepareStatement(
               "INSERT INTO blocked_players (player_uuid, blocked_uuid) VALUES (?, ?)")) {
            for (Map.Entry<UUID, Set<UUID>> entry : this.blockedPlayers.entrySet()) {
               for (UUID blocked : entry.getValue()) {
                  pstmt1.setString(1, entry.getKey().toString());
                  pstmt1.setString(2, blocked.toString());
                  pstmt1.addBatch();
               }
            }
            pstmt1.executeBatch();
         }
         try (PreparedStatement pstmt2 = this.database.prepareStatement(
               "INSERT INTO msg_disabled (player_uuid) VALUES (?)")) {
            for (UUID uuid : this.msgDisabled) {
               pstmt2.setString(1, uuid.toString());
               pstmt2.addBatch();
            }
            pstmt2.executeBatch();
         }
      } catch (SQLException e) {
         getLogger().severe("Lỗi lưu data: " + e.getMessage());
      }
   }

   private void saveBlockedPlayer(UUID playerUUID, UUID blockedUUID) {
      this.runTaskAsync(() -> {
         try (PreparedStatement pstmt = this.database.prepareStatement(
               "INSERT OR REPLACE INTO blocked_players (player_uuid, blocked_uuid) VALUES (?, ?)")) {
            pstmt.setString(1, playerUUID.toString());
            pstmt.setString(2, blockedUUID.toString());
            pstmt.executeUpdate();
         } catch (SQLException e) {
            getLogger().severe("Lỗi lưu blocked player: " + e.getMessage());
         }
      });
   }

   private void removeBlockedPlayer(UUID playerUUID, UUID blockedUUID) {
      this.runTaskAsync(() -> {
         try (PreparedStatement pstmt = this.database.prepareStatement(
               "DELETE FROM blocked_players WHERE player_uuid = ? AND blocked_uuid = ?")) {
            pstmt.setString(1, playerUUID.toString());
            pstmt.setString(2, blockedUUID.toString());
            pstmt.executeUpdate();
         } catch (SQLException e) {
            getLogger().severe("Lỗi xóa blocked player: " + e.getMessage());
         }
      });
   }

   private void saveMsgToggleStatus(UUID playerUUID, boolean enabled) {
      this.runTaskAsync(() -> {
         try {
            if (enabled) {
               try (PreparedStatement pstmt = this.database.prepareStatement(
                     "DELETE FROM msg_disabled WHERE player_uuid = ?")) {
                  pstmt.setString(1, playerUUID.toString());
                  pstmt.executeUpdate();
               }
            } else {
               try (PreparedStatement pstmt = this.database.prepareStatement(
                     "INSERT OR REPLACE INTO msg_disabled (player_uuid) VALUES (?)")) {
                  pstmt.setString(1, playerUUID.toString());
                  pstmt.executeUpdate();
               }
            }
         } catch (SQLException e) {
            getLogger().severe("Lỗi lưu trạng thái msgtoggle: " + e.getMessage());
         }
      });
   }

   private void closeDatabase() {
      try {
         if (this.database != null && !this.database.isClosed()) {
            this.database.close();
         }
      } catch (SQLException e) {
         getLogger().severe("Lỗi đóng database: " + e.getMessage());
      }
   }

   public static DonutMSG getInstance() {
      return instance;
   }

   public boolean isFolia() {
      return this.isFolia;
   }

   public void runTask(Player player, Runnable task) {
      if (this.isFolia) {
         player.getScheduler().run(this, scheduledTask -> task.run(), null);
      } else {
         Bukkit.getScheduler().runTask(this, task);
      }
   }

   public void runTaskAsync(Runnable task) {
      if (this.isFolia) {
         Bukkit.getGlobalRegionScheduler().run(this, scheduledTask -> task.run());
      } else {
         Bukkit.getScheduler().runTaskAsynchronously(this, task);
      }
   }

   public String formatMessage(String message) {
      return message == null ? "" : HexColorCode.translateAllColorCodes(message);
   }

   public void sendMessage(Player player, String message) {
      if (message == null || message.isEmpty()) return;
      String formatted = this.formatMessage(message);
      if (this.isFolia) {
         this.runTask(player, () -> {
            Component component = LegacyComponentSerializer.legacySection().deserialize(formatted);
            player.sendMessage(component);
         });
      } else {
         player.sendMessage(formatted);
      }
   }

   @Override
   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (!(sender instanceof Player player)) return true;
      return switch (command.getName().toLowerCase()) {
         case "msg" -> handleMsgCommand(player, args);
         case "reply" -> handleReplyCommand(player, args);
         case "msgblock" -> handleMsgBlockCommand(player, args);
         case "msgunblock" -> handleMsgUnblockCommand(player, args);
         case "msgtoggle" -> handleMsgToggleCommand(player);
         case "hlsmpmsg" -> handleReloadCommand(player, args);
         default -> false;
      };
   }

   private boolean handleMsgCommand(Player sender, String[] args) {
      if (args.length < 2) {
         this.sendMessage(sender, this.getConfig().getString("messages.usage-msg"));
         return true;
      }
      Player receiver = Bukkit.getPlayer(args[0]);
      if (receiver == null) {
         this.sendMessage(sender, this.getConfig().getString("messages.no-target"));
         return true;
      }
      if (sender.equals(receiver)) {
         this.sendMessage(sender, this.getConfig().getString("messages.self-message"));
         return true;
      }
      StringBuilder sb = new StringBuilder();
      for (int i = 1; i < args.length; i++) sb.append(args[i]).append(" ");
      String messageText = sb.toString().trim();

      if (this.msgDisabled.contains(receiver.getUniqueId())) {
         this.sendMessage(sender, this.getConfig().getString("messages.msg-toggle-player", "").replace("%player%", receiver.getName()));
         return true;
      }
      Set<UUID> blocked = this.blockedPlayers.getOrDefault(receiver.getUniqueId(), new HashSet<>());
      if (blocked.contains(sender.getUniqueId())) {
         this.sendMessage(sender, this.getConfig().getString("messages.msg-blocked", "").replace("%player%", receiver.getName()));
         return true;
      }
      String msgFormat = this.getConfig().getString("messages.msg-normal", "");
      String senderMsg = msgFormat.replace("%sender%", sender.getName()).replace("%receiver%", receiver.getName()).replace("%message%", messageText);
      String receiverMsg = msgFormat.replace("%sender%", sender.getName()).replace("%receiver%", receiver.getName()).replace("%message%", messageText);
      this.runTask(sender, () -> this.sendMessage(sender, senderMsg));
      this.runTask(receiver, () -> this.sendMessage(receiver, receiverMsg));
      this.lastMessage.put(sender.getUniqueId(), receiver.getUniqueId());
      this.lastMessage.put(receiver.getUniqueId(), sender.getUniqueId());
      return true;
   }

   private boolean handleReplyCommand(Player sender, String[] args) {
      if (args.length < 1) {
         this.sendMessage(sender, this.getConfig().getString("messages.usage-msg"));
         return true;
      }
      UUID lastReceiverId = this.lastMessage.get(sender.getUniqueId());
      if (lastReceiverId == null) return true;
      Player receiver = Bukkit.getPlayer(lastReceiverId);
      if (receiver == null) {
         this.sendMessage(sender, this.getConfig().getString("messages.no-target"));
         return true;
      }
      StringBuilder sb = new StringBuilder();
      for (String arg : args) sb.append(arg).append(" ");
      String messageText = sb.toString().trim();

      if (this.msgDisabled.contains(receiver.getUniqueId())) {
         this.sendMessage(sender, this.getConfig().getString("messages.msg-toggle-player", "").replace("%player%", receiver.getName()));
         return true;
      }
      Set<UUID> blocked = this.blockedPlayers.getOrDefault(receiver.getUniqueId(), new HashSet<>());
      if (blocked.contains(sender.getUniqueId())) {
         this.sendMessage(sender, this.getConfig().getString("messages.msg-blocked", "").replace("%player%", receiver.getName()));
         return true;
      }
      String msgFormat = this.getConfig().getString("messages.msg-normal", "");
      String senderMsg = msgFormat.replace("%sender%", sender.getName()).replace("%receiver%", receiver.getName()).replace("%message%", messageText);
      String receiverMsg = msgFormat.replace("%sender%", sender.getName()).replace("%receiver%", receiver.getName()).replace("%message%", messageText);
      this.runTask(sender, () -> this.sendMessage(sender, senderMsg));
      this.runTask(receiver, () -> this.sendMessage(receiver, receiverMsg));
      return true;
   }

   private boolean handleMsgBlockCommand(Player sender, String[] args) {
      if (args.length < 1) {
         this.sendMessage(sender, this.getConfig().getString("messages.usage-msgblock"));
         return true;
      }
      Player target = Bukkit.getPlayer(args[0]);
      if (target == null) {
         this.sendMessage(sender, this.getConfig().getString("messages.no-target"));
         return true;
      }
      if (sender.equals(target)) {
         this.sendMessage(sender, this.getConfig().getString("messages.self-block"));
         return true;
      }
      Set<UUID> blocked = this.blockedPlayers.computeIfAbsent(sender.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
      if (blocked.add(target.getUniqueId())) {
         this.sendMessage(sender, this.getConfig().getString("messages.blocked", "").replace("%player%", target.getName()));
         this.saveBlockedPlayer(sender.getUniqueId(), target.getUniqueId());
      }
      return true;
   }

   private boolean handleMsgUnblockCommand(Player sender, String[] args) {
      if (args.length < 1) {
         this.sendMessage(sender, this.getConfig().getString("messages.usage-msgunblock"));
         return true;
      }
      Player target = Bukkit.getPlayer(args[0]);
      if (target == null) {
         this.sendMessage(sender, this.getConfig().getString("messages.no-target"));
         return true;
      }
      Set<UUID> blocked = this.blockedPlayers.get(sender.getUniqueId());
      if (blocked != null && blocked.remove(target.getUniqueId())) {
         this.sendMessage(sender, this.getConfig().getString("messages.unblocked", "").replace("%player%", target.getName()));
         this.removeBlockedPlayer(sender.getUniqueId(), target.getUniqueId());
      }
      return true;
   }

   private boolean handleMsgToggleCommand(Player sender) {
      boolean wasDisabled = this.msgDisabled.contains(sender.getUniqueId());
      if (wasDisabled) {
         this.msgDisabled.remove(sender.getUniqueId());
         this.sendMessage(sender, this.getConfig().getString("messages.msg-toggle-on"));
      } else {
         this.msgDisabled.add(sender.getUniqueId());
         this.sendMessage(sender, this.getConfig().getString("messages.msg-toggle-off"));
      }
      this.saveMsgToggleStatus(sender.getUniqueId(), wasDisabled);
      return true;
   }

   private boolean handleReloadCommand(Player sender, String[] args) {
      if (!sender.hasPermission("msg.reload")) {
         this.sendMessage(sender, this.getConfig().getString("messages.no-permission"));
         return true;
      }
      if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
         this.runTaskAsync(() -> {
            this.reloadConfig();
            this.blockedPlayers.clear();
            this.msgDisabled.clear();
            this.loadDataFromDatabase();
            getLogger().info("Đang tải lại cấu hình...");
            getLogger().info("╠ Đã tải lại config.yml!");
            getLogger().info("╚ Tải lại thành công!");
            this.runTask(sender, () -> this.sendMessage(sender, this.getConfig().getString("messages.plugin-reloaded")));
         });
      }
      return true;
   }
}

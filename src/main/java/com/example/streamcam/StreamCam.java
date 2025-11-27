package com.example.streamcam;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class StreamCam extends JavaPlugin implements CommandExecutor, Listener {

    private final Map<UUID, ScheduledTask> activeTasks = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> currentTargets = new ConcurrentHashMap<>();
    private final Set<UUID> focusModePlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastActiveTime = new ConcurrentHashMap<>();

    // 黑名单存储和配置键
    private Set<UUID> blacklist = new HashSet<>();
    private static final String CONFIG_KEY_BLACKLIST = "blacklist-players";

    private static final long AFK_THRESHOLD_CYCLE = 30 * 1000;
    private static final long AFK_THRESHOLD_FOCUS = 15 * 1000;

    @Override
    public void onEnable() {
        // 注册指令
        if (getCommand("streamcycle") != null) getCommand("streamcycle").setExecutor(this);
        if (getCommand("streamfocus") != null) getCommand("streamfocus").setExecutor(this);
        if (getCommand("streambl") != null) getCommand("streambl").setExecutor(this);
        if (getCommand("streamnext") != null) getCommand("streamnext").setExecutor(this); // 注册新指令

        getServer().getPluginManager().registerEvents(this, this);

        // 配置和黑名单处理
        this.saveDefaultConfig();
        loadBlacklist();

        getLogger().info("StreamCam Pro - 已启用 (新增手动切换功能)");
    }

    @Override
    public void onDisable() {
        saveBlacklist();
        activeTasks.values().forEach(ScheduledTask::cancel);
        activeTasks.clear();
        currentTargets.clear();
        focusModePlayers.clear();
    }

    // --- 黑名单配置管理 ---

    private void loadBlacklist() {
        List<String> uuidStrings = getConfig().getStringList(CONFIG_KEY_BLACKLIST);
        blacklist = uuidStrings.stream()
                .map(s -> {
                    try {
                        return UUID.fromString(s);
                    } catch (IllegalArgumentException e) {
                        getLogger().warning("无效的UUID格式在配置中: " + s);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        getLogger().info("加载了 " + blacklist.size() + " 个黑名单玩家。");
    }

    private void saveBlacklist() {
        List<String> uuidStrings = blacklist.stream()
                .map(UUID::toString)
                .collect(Collectors.toList());
        getConfig().set(CONFIG_KEY_BLACKLIST, uuidStrings);
        saveConfig();
    }

    // --- 事件监听 (保持不变) ---

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        lastActiveTime.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getFrom().getBlockX() != event.getTo().getBlockX() ||
                event.getFrom().getBlockY() != event.getTo().getBlockY() ||
                event.getFrom().getBlockZ() != event.getTo().getBlockZ()) {

            lastActiveTime.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        lastActiveTime.remove(event.getPlayer().getUniqueId());
        if (activeTasks.containsKey(event.getPlayer().getUniqueId())) {
            stopCam(event.getPlayer(), false);
        }
    }

    @EventHandler
    public void onPlayerToggleSneak(PlayerToggleSneakEvent event) {
        Player player = event.getPlayer();
        if (event.isSneaking() && activeTasks.containsKey(player.getUniqueId())) {
            stopCam(player, true);
            player.sendMessage(Component.text("已停止自动轮播，进入自由观察模式。", NamedTextColor.YELLOW));
        }
    }

    @EventHandler
    public void onPlayerGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        if (activeTasks.containsKey(player.getUniqueId()) && event.getNewGameMode() != GameMode.SPECTATOR) {
            stopCam(player, false);
            player.sendMessage(Component.text("检测到模式变更，轮播已停止。", NamedTextColor.YELLOW));
        }
    }

    @EventHandler
    public void onPlayerChangeWorld(PlayerChangedWorldEvent event) {
        Player target = event.getPlayer();

        for (Map.Entry<UUID, UUID> entry : currentTargets.entrySet()) {
            if (entry.getValue().equals(target.getUniqueId())) {
                Player streamer = Bukkit.getPlayer(entry.getKey());
                if (streamer != null && streamer.isOnline()) {
                    streamer.sendActionBar(Component.text(target.getName() + " 切换了世界，正在寻找下一位...", NamedTextColor.DARK_AQUA));
                    switchNextPlayer(streamer, AFK_THRESHOLD_FOCUS);
                    break;
                }
            }
        }
    }

    @EventHandler
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.getFrom().getWorld().equals(event.getTo().getWorld())) {
            if (event.getFrom().distanceSquared(event.getTo()) < 100 * 100) {
                return;
            }
        }

        Player target = event.getPlayer();
        for (Map.Entry<UUID, UUID> entry : currentTargets.entrySet()) {
            if (entry.getValue().equals(target.getUniqueId())) {
                Player streamer = Bukkit.getPlayer(entry.getKey());
                if (streamer != null && streamer.isOnline()) {
                    streamer.sendActionBar(Component.text(target.getName() + " 传送了，正在寻找下一位...", NamedTextColor.DARK_AQUA));
                    switchNextPlayer(streamer, AFK_THRESHOLD_FOCUS);
                    break;
                }
            }
        }
    }


    // --- 指令处理 (更新) ---

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("仅限玩家使用。", NamedTextColor.RED));
            return true;
        }

        String commandName = command.getName().toLowerCase();

        if (commandName.equals("streambl")) {
            return handleBlacklistCommand(player, args);
        }

        if (!player.hasPermission("streamcam.use")) {
            player.sendMessage(Component.text("无权使用。", NamedTextColor.RED));
            return true;
        }

        if (commandName.equals("streamnext")) {
            return handleStreamNextCommand(player); // 处理新指令
        }

        // 停止/重启模式指令
        if (activeTasks.containsKey(player.getUniqueId())) {
            stopCam(player, true);
            return true;
        }

        // 启动模式指令
        if (commandName.equals("streamfocus")) {
            startFocusMode(player);
        } else if (commandName.equals("streamcycle")) {
            long interval = 15;
            if (args.length > 0) {
                try {
                    interval = Long.parseLong(args[0]);
                    if (interval < 5) interval = 5;
                } catch (NumberFormatException ignored) {}
            }
            startCycleMode(player, interval);
        }
        return true;
    }

    // --- 新增：手动切换指令逻辑 ---
    private boolean handleStreamNextCommand(Player player) {
        if (!activeTasks.containsKey(player.getUniqueId())) {
            player.sendMessage(Component.text("当前未开启导播模式 (使用 /streamcycle 或 /streamfocus 开启)。", NamedTextColor.YELLOW));
            return true;
        }

        // 立即触发切换，使用焦点模式的阈值，确保所有过滤条件生效
        switchNextPlayer(player, AFK_THRESHOLD_FOCUS);
        player.sendActionBar(Component.text("⏩ 手动切换到下一个目标...", NamedTextColor.AQUA));

        return true;
    }


    // --- 黑名单指令逻辑 (保持不变) ---
    private boolean handleBlacklistCommand(Player sender, String[] args) {
        if (!sender.hasPermission("streamcam.admin")) {
            sender.sendMessage(Component.text("无权使用黑名单指令。", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            sender.sendMessage(Component.text("--- 导播黑名单 (StreamCam Blacklist) ---", NamedTextColor.YELLOW));
            if (blacklist.isEmpty()) {
                sender.sendMessage(Component.text("黑名单为空。", NamedTextColor.GRAY));
            } else {
                for (UUID uuid : blacklist) {
                    @SuppressWarnings("deprecation")
                    OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
                    String name = op.getName() != null ? op.getName() : uuid.toString();
                    sender.sendMessage(Component.text("- " + name, NamedTextColor.AQUA));
                }
            }
            sender.sendMessage(Component.text("使用 /streambl add/remove [玩家名]", NamedTextColor.YELLOW));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(Component.text("用法: /streambl <add|remove> <玩家名>", NamedTextColor.RED));
            return true;
        }

        String action = args[0].toLowerCase();
        String targetName = args[1];

        @SuppressWarnings("deprecation")
        OfflinePlayer targetOp = Bukkit.getOfflinePlayer(targetName);
        if (!targetOp.hasPlayedBefore() && !targetOp.isOnline()) {
            sender.sendMessage(Component.text("找不到玩家 " + targetName + " 的记录。", NamedTextColor.RED));
            return true;
        }

        UUID targetId = targetOp.getUniqueId();
        String displayName = targetOp.getName() != null ? targetOp.getName() : targetName;

        if (action.equals("add")) {
            if (blacklist.add(targetId)) {
                saveBlacklist();
                sender.sendMessage(Component.text("已将玩家 " + displayName + " 加入黑名单。", NamedTextColor.GREEN));
            } else {
                sender.sendMessage(Component.text("玩家 " + displayName + " 已在黑名单中。", NamedTextColor.YELLOW));
            }
        } else if (action.equals("remove")) {
            if (blacklist.remove(targetId)) {
                saveBlacklist();
                sender.sendMessage(Component.text("已将玩家 " + displayName + " 移出黑名单。", NamedTextColor.GREEN));
            } else {
                sender.sendMessage(Component.text("玩家 " + displayName + " 不在黑名单中。", NamedTextColor.YELLOW));
            }
        } else {
            sender.sendMessage(Component.text("未知操作: " + args[0] + ". 使用 add, remove, 或 list。", NamedTextColor.RED));
        }

        return true;
    }

    // --- 核心切换逻辑 (保持不变) ---

    private void startCycleMode(Player streamer, long interval) {
        streamer.sendMessage(Component.text(">>> 开启轮播 (按Shift停止)", NamedTextColor.GREEN));
        initCam(streamer);

        ScheduledTask task = streamer.getScheduler().runAtFixedRate(this, (t) -> {
            switchNextPlayer(streamer, AFK_THRESHOLD_CYCLE);
        }, null, 1L, interval * 20L);

        activeTasks.put(streamer.getUniqueId(), task);
    }

    private void startFocusMode(Player streamer) {
        streamer.sendMessage(Component.text(">>> 开启聚焦 (按Shift停止)", NamedTextColor.GOLD));
        initCam(streamer);
        focusModePlayers.add(streamer.getUniqueId());

        switchNextPlayer(streamer, AFK_THRESHOLD_FOCUS);

        ScheduledTask task = streamer.getScheduler().runAtFixedRate(this, (t) -> {
            checkFocusTarget(streamer);
        }, null, 20L, 20L);

        activeTasks.put(streamer.getUniqueId(), task);
    }

    private void initCam(Player streamer) {
        if (streamer.getGameMode() != GameMode.SPECTATOR) {
            streamer.setGameMode(GameMode.SPECTATOR);
        }
    }

    private void stopCam(Player streamer, boolean enforceSpectator) {
        ScheduledTask task = activeTasks.remove(streamer.getUniqueId());
        if (task != null) task.cancel();

        currentTargets.remove(streamer.getUniqueId());
        focusModePlayers.remove(streamer.getUniqueId());

        streamer.setSpectatorTarget(null);

        if (enforceSpectator) {
            if (streamer.getGameMode() != GameMode.SPECTATOR) {
                streamer.setGameMode(GameMode.SPECTATOR);
            }
        }
    }

    private void checkFocusTarget(Player streamer) {
        UUID targetId = currentTargets.get(streamer.getUniqueId());
        if (targetId == null) {
            switchNextPlayer(streamer, AFK_THRESHOLD_FOCUS);
            return;
        }
        Player target = Bukkit.getPlayer(targetId);

        // 检查黑名单
        if (blacklist.contains(targetId)) {
            streamer.sendActionBar(Component.text(target.getName() + " 在黑名单中，寻找下一位...", NamedTextColor.DARK_RED));
            switchNextPlayer(streamer, AFK_THRESHOLD_FOCUS);
            return;
        }

        if (target == null || !target.isOnline()) {
            switchNextPlayer(streamer, AFK_THRESHOLD_FOCUS);
            return;
        }

        long lastMove = lastActiveTime.getOrDefault(targetId, System.currentTimeMillis());
        boolean isAfk = (System.currentTimeMillis() - lastMove > AFK_THRESHOLD_FOCUS);
        boolean isFishing = (target.getFishHook() != null);

        if (isAfk || isFishing) {
            String reason = isFishing ? "目标开始钓鱼" : "目标静止";
            streamer.sendActionBar(Component.text(reason + "，寻找下一位...", NamedTextColor.GOLD));
            switchNextPlayer(streamer, AFK_THRESHOLD_FOCUS);
        }
    }

    private void switchNextPlayer(Player streamer, long afkThreshold) {
        if (!activeTasks.containsKey(streamer.getUniqueId())) return;

        List<Player> allPlayers = new ArrayList<>(Bukkit.getOnlinePlayers());

        allPlayers.removeIf(p ->
                p.getUniqueId().equals(streamer.getUniqueId()) ||
                        p.getGameMode() == GameMode.SPECTATOR ||
                        blacklist.contains(p.getUniqueId())
        );

        if (allPlayers.isEmpty()) {
            streamer.sendActionBar(Component.text("无可用玩家...", NamedTextColor.RED));
            return;
        }

        long now = System.currentTimeMillis();

        List<Player> activeCandidates = allPlayers.stream()
                .filter(p -> {
                    long lastMove = lastActiveTime.getOrDefault(p.getUniqueId(), now);
                    boolean isAfk = (now - lastMove) >= afkThreshold;
                    boolean isFishing = (p.getFishHook() != null);

                    return !isAfk && !isFishing;
                })
                .collect(Collectors.toList());

        List<Player> finalCandidates = activeCandidates.isEmpty() ? allPlayers : activeCandidates;

        World currentWorld = streamer.getWorld();
        finalCandidates.sort((p1, p2) -> {
            boolean p1Same = p1.getWorld().equals(currentWorld);
            boolean p2Same = p2.getWorld().equals(currentWorld);
            if (p1Same && !p2Same) return -1;
            if (!p1Same && p2Same) return 1;
            int w = p1.getWorld().getName().compareTo(p2.getWorld().getName());
            return w != 0 ? w : p1.getName().compareTo(p2.getName());
        });

        Player target = getNextTarget(streamer, finalCandidates);

        UUID lastId = currentTargets.get(streamer.getUniqueId());
        if (lastId != null && target.getUniqueId().equals(lastId) && focusModePlayers.contains(streamer.getUniqueId())) {
            return;
        }

        currentTargets.put(streamer.getUniqueId(), target.getUniqueId());

        boolean isActive = activeCandidates.contains(target);
        performTeleport(streamer, target, isActive);
    }

    private Player getNextTarget(Player streamer, List<Player> candidates) {
        UUID lastId = currentTargets.get(streamer.getUniqueId());
        int nextIndex = 0;
        if (lastId != null) {
            for (int i = 0; i < candidates.size(); i++) {
                if (candidates.get(i).getUniqueId().equals(lastId)) {
                    nextIndex = i + 1;
                    break;
                }
            }
        }
        if (nextIndex >= candidates.size()) nextIndex = 0;
        return candidates.get(nextIndex);
    }

    private void performTeleport(Player streamer, Player target, boolean isActive) {
        streamer.setSpectatorTarget(null);
        streamer.teleportAsync(target.getLocation()).thenAccept(success -> {
            if (success) {
                streamer.getScheduler().runDelayed(this, (task) -> {
                    if (!activeTasks.containsKey(streamer.getUniqueId())) return;

                    if (target.isValid() && target.isOnline()) {
                        if (streamer.getGameMode() != GameMode.SPECTATOR) {
                            streamer.setGameMode(GameMode.SPECTATOR);
                        }
                        streamer.setSpectatorTarget(target);

                        String statusText;
                        NamedTextColor color;

                        if (isActive) {
                            statusText = focusModePlayers.contains(streamer.getUniqueId()) ? "聚焦模式" : "正在观察";
                            color = NamedTextColor.GRAY;
                        } else {
                            if (target.getFishHook() != null) {
                                statusText = "正在观察 (钓鱼中)";
                            } else {
                                statusText = "正在观察 (挂机中)";
                            }
                            color = NamedTextColor.RED;
                        }

                        streamer.showTitle(Title.title(
                                Component.text(target.getName(), isActive ? NamedTextColor.AQUA : NamedTextColor.DARK_GRAY),
                                Component.text(statusText + " [" + target.getWorld().getName() + "]", color),
                                Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(2000), Duration.ofMillis(500))
                        ));
                    }
                }, null, 10L);
            }
        });
    }
}
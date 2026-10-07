package com.tty.ari.states;

import com.destroystokyo.paper.event.player.PlayerPickupExperienceEvent;
import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import com.tty.api.NbtManager;
import com.tty.api.state.State;
import com.tty.api.state.StateService;
import com.tty.ari.Ari;
import com.tty.ari.configuration.FunctionConfig;
import com.tty.ari.configuration.lang.LangConfig;
import com.tty.ari.dto.state.player.PlayerVanishState;
import com.tty.ari.enumType.PlayerNbt;
import com.tty.ari.tool.ConfigUtils;
import fr.skytasul.glowingentities.GlowingEntities;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockReceiveGameEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.raid.RaidTriggerEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class PlayerVanishService extends StateService<State> implements Listener {

    /**
     * 拥有该权限的玩家可以看见隐身中的玩家
     */
    public static final String SEE_PERMISSION = "ari.vanish.see";

    private final GlowingEntities glowing;

    public PlayerVanishService(long rate, long c, boolean isAsync) {
        super(rate, c, isAsync, Ari.instance);
        Ari.instance.getServer().getPluginManager().registerEvents(this, Ari.instance);
        this.glowing = new GlowingEntities(Ari.instance);
    }

    /**
     * 玩家是否处于隐身状态
     */
    public boolean isVanished(Player player) {
        return !this.isNotHaveState(player);
    }

    /**
     * 对应的指令发送者是否能看见隐身中的玩家（控制台始终可以）
     */
    public static boolean canSeeVanished(CommandSender viewer) {
        if (!(viewer instanceof Player player)) return true;
        return Ari.PERMISSION_SERVICE.hasPermission(player, SEE_PERMISSION);
    }

    /**
     * 目标玩家对于观察者来说是否处于隐身（不可见）状态
     */
    public boolean isHiddenFrom(CommandSender viewer, Player target) {
        if (target == null || target.equals(viewer)) return false;
        return this.isVanished(target) && !canSeeVanished(viewer);
    }

    @Override
    protected boolean canAddState(State state) {
        return this.isNotHaveState(state.getOwner());
    }

    @Override
    protected void loopExecution(State state) {
        if (!(state.getOwner() instanceof Player player) || !player.isOnline()) {
            state.setOver(true);
            return;
        }
        GameMode gameMode = player.getGameMode();
        if (gameMode.equals(GameMode.ADVENTURE) || gameMode.equals(GameMode.SURVIVAL)) {
            player.setAllowFlight(true);
        }
    }

    @Override
    protected void abortAddState(State state) {

    }

    @Override
    protected void passAddState(State state) {
        if (!(state.getOwner() instanceof Player player)) return;
        this.hide(player);
        this.giveEffect(player);
        ConfigUtils.t("function.vanish.enable").thenAccept(player::sendMessage);
        boolean restored = state instanceof PlayerVanishState vanishState && vanishState.isRestored();
        if (!restored) {
            this.broadcastFakeMessage(player, false);
        }
        Ari.instance.getLog().debug("player {} is vanish.", player.getName());
    }

    @Override
    protected void onEarlyExit(State state) {
        this.reveal(state);
    }

    @Override
    protected void onFinished(State state) {
        this.reveal(state);
    }

    @Override
    protected void onServiceAbort(State state) {
        this.glowing.disable();
        if (!(state.getOwner() instanceof Player player)) return;
        this.removeEffect(player);
    }

    @Override
    public void onReload() {

    }

    private void reveal(State state) {
        if (!(state.getOwner() instanceof Player player)) return;
        this.show(player);
        if (!player.isOnline()) {
            // 玩家是在隐身状态下退出的，保留隐身标记，便于重新进入时恢复
            Ari.instance.getLog().debug("player {} left the game while vanished.", player.getName());
            return;
        }
        Ari.instance.getNbtManager().removeNbt(PlayerNbt.VANISH, player);
        this.removeEffect(player);
        ConfigUtils.t("function.vanish.disable").thenAccept(player::sendMessage);
        this.broadcastFakeMessage(player, true);
        Ari.instance.getLog().debug("player {} is show up.", player.getName());
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.isCancelled() || !(event.getEntity() instanceof Player player)) return;
        if (this.isNotHaveState(player)) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player joinPlayer = event.getPlayer();
        NbtManager nbtManager = Ari.instance.getNbtManager();
        if (nbtManager.hasNbt(PlayerNbt.VANISH, joinPlayer)) {
            if (this.canRestoreVanish(joinPlayer)) {
                this.addState(new PlayerVanishState(joinPlayer, true));
            } else {
                nbtManager.removeNbt(PlayerNbt.VANISH, joinPlayer);
                this.removeEffect(joinPlayer);
            }
        }
        for (State state : this.getAllStates()) {
            if (!(state.getOwner() instanceof Player player) || player.equals(joinPlayer)) continue;
            if (canSeeVanished(joinPlayer)) continue;
            this.hideForPlayer(player, joinPlayer);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoinMessage(PlayerJoinEvent event) {
        if (this.isVanished(event.getPlayer())) {
            event.joinMessage(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuitMessage(PlayerQuitEvent event) {
        if (this.isVanished(event.getPlayer())) {
            event.quitMessage(null);
        }
    }

    @EventHandler
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (this.isVanished(event.getPlayer())) {
            event.message(null);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!this.isVanished(player)) return;
        if (Ari.instance.getConfigurationManager().get(FunctionConfig.class).vanishAllowChat()) return;
        event.setCancelled(true);
        String message = Ari.instance.getConfigurationManager().get(LangConfig.class).getString("function.vanish.chat-blocked", "&c你正处于隐身状态，消息未发送");
        player.sendMessage(Ari.instance.getEngine().directRender(message, player));
    }

    @EventHandler
    public void onServerListPing(PaperServerListPingEvent event) {
        if (this.stateIsEmpty()) return;
        Iterator<Player> iterator = event.iterator();
        while (iterator.hasNext()) {
            if (this.isVanished(iterator.next())) {
                iterator.remove();
            }
        }
    }

    @EventHandler
    public void onPotionEffectRemove(EntityPotionEffectEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (this.isNotHaveState(player)) return;
        if (!event.getModifiedType().equals(PotionEffectType.NIGHT_VISION)) return;
        EntityPotionEffectEvent.Action action = event.getAction();
        if (action != EntityPotionEffectEvent.Action.REMOVED && action != EntityPotionEffectEvent.Action.CLEARED) return;
        event.setCancelled(true);
    }

    @EventHandler
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (event.getTarget() instanceof Player player && !this.isNotHaveState(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        if (this.isNotHaveState(player)) return;
        if (event.getEntity() instanceof Mob mob) {
            Ari.instance.getScheduler().runAtEntityLater(mob, i -> {
                if (mob.isValid() && mob.getTarget() == player) {
                    mob.setTarget(null);
                }
            }, null, 20L);
        } else if (event.getEntity() instanceof Player p) {
            Ari.ATTACK_SERVICE.cancelPvpTag(p);
            Ari.ATTACK_SERVICE.cancelPvpTag(player);
        }
    }

    /**
     * 阻止隐身玩家触发压力板、绊线、踩坏耕地和海龟蛋
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPhysicalInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.PHYSICAL) return;
        if (this.isVanished(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /**
     * 阻止隐身玩家触发幽匿感测体等振动方块
     */
    @EventHandler(ignoreCancelled = true)
    public void onGameEvent(BlockReceiveGameEvent event) {
        if (event.getEntity() instanceof Player player && this.isVanished(player)) {
            event.setCancelled(true);
        }
    }

    /**
     * 阻止隐身玩家触发袭击
     */
    @EventHandler(ignoreCancelled = true)
    public void onRaidTrigger(RaidTriggerEvent event) {
        if (this.isVanished(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Player player = event.getPlayer();
        if (!this.isNotHaveState(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBucketFill(PlayerBucketFillEvent event) {
        Player player = event.getPlayer();
        if (!this.isNotHaveState(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBedEnter(PlayerBedEnterEvent event) {
        Player player = event.getPlayer();
        if (!this.isNotHaveState(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        if (!this.isNotHaveState(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (!this.isNotHaveState(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPickItem(PlayerAttemptPickupItemEvent event) {
        Player player = event.getPlayer();
        if (!this.isNotHaveState(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPickupExperience(PlayerPickupExperienceEvent event) {
        Player player = event.getPlayer();
        if (!this.isNotHaveState(player)) {
            event.setCancelled(true);
        }
    }

    /**
     * 无法看见隐身玩家的人，在受保护的指令里指向隐身玩家时，提示玩家不存在
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInputCommand(PlayerCommandPreprocessEvent event) {
        if (this.stateIsEmpty()) return;
        Player sender = event.getPlayer();
        if (canSeeVanished(sender)) return;

        String[] args = event.getMessage().substring(1).trim().split("\\s+");
        if (args.length < 2) return;

        String label = args[0].toLowerCase(Locale.ROOT);
        int namespaceIndex = label.indexOf(':');
        if (namespaceIndex >= 0) {
            label = label.substring(namespaceIndex + 1);
        }

        Set<String> protectedCommands = new HashSet<>();
        for (String command : Ari.instance.getConfigurationManager().get(FunctionConfig.class).getVanishProtectedCommands()) {
            protectedCommands.add(command.toLowerCase(Locale.ROOT));
        }
        if (!protectedCommands.contains(label)) return;

        for (int i = 1; i < args.length; i++) {
            Player target = Bukkit.getPlayerExact(args[i]);
            if (this.isHiddenFrom(sender, target)) {
                sender.sendMessage(Ari.instance.getEngine().directRender(Ari.DATA_SERVICE.getValue("base.on-player.not-exist"), sender));
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerKick(PlayerKickEvent event) {
        Player player = event.getPlayer();
        if (this.getStates(player).isEmpty()) return;
        PlayerKickEvent.Cause cause = event.getCause();
        if (cause.equals(PlayerKickEvent.Cause.BANNED) ||
                cause.equals(PlayerKickEvent.Cause.WHITELIST) ||
                cause.equals(PlayerKickEvent.Cause.KICK_COMMAND) ||
                cause.equals(PlayerKickEvent.Cause.IP_BANNED)) {
            event.setCancelled(true);
        }
    }

    private boolean canRestoreVanish(Player player) {
        FunctionConfig config = Ari.instance.getConfigurationManager().get(FunctionConfig.class);
        return config.vanishIsEnable()
                && config.vanishKeepOnRejoin()
                && Ari.PERMISSION_SERVICE.hasPermission(player, "ari.command.vanish");
    }

    /**
     * 广播伪造的加入/退出信息，让其他玩家以为隐身玩家真的离开或进入了服务器
     * @param player 隐身玩家
     * @param join true 为加入信息，false 为退出信息
     */
    private void broadcastFakeMessage(Player player, boolean join) {
        if (!Ari.instance.getConfigurationManager().get(FunctionConfig.class).vanishFakeMessage()) return;
        String customPath = join ? "server.message.on-login" : "server.message.on-leave";
        if (Ari.instance.getConfig().getBoolean(customPath, false)) {
            ConfigUtils.t(customPath, player).thenAccept(i -> Ari.instance.getScheduler().run(t -> Bukkit.broadcast(i)));
        } else {
            String translationKey = join ? "multiplayer.player.joined" : "multiplayer.player.left";
            Bukkit.broadcast(Component.translatable(translationKey, NamedTextColor.YELLOW, player.displayName()));
        }
    }

    private void hide(Player player) {
        for (Player onlinePlayer : Bukkit.getServer().getOnlinePlayers()) {
            if (player.equals(onlinePlayer)) continue;
            if (canSeeVanished(onlinePlayer)) continue;
            this.hideForPlayer(player, onlinePlayer);
        }
    }

    private void show(Player player) {
        for (Player onlinePlayer : Bukkit.getServer().getOnlinePlayers()) {
            if (onlinePlayer.equals(player)) continue;
            this.showForPlayer(player, onlinePlayer);
        }
    }

    private void hideForPlayer(Player vanishPlayer, Player player) {
        player.hidePlayer(Ari.instance, vanishPlayer);
        try {
            this.glowing.unsetGlowing(player, vanishPlayer);
            this.glowing.setGlowing(player, vanishPlayer, ChatColor.WHITE);
        } catch (ReflectiveOperationException e) {
            Ari.instance.getLog().error(e);
        }
    }

    private void showForPlayer(Player vanishPlayer, Player player) {
        player.showPlayer(Ari.instance, vanishPlayer);
        try {
            this.glowing.unsetGlowing(player, vanishPlayer);
        } catch (ReflectiveOperationException e) {
            Ari.instance.getLog().error(e);
        }
    }

    private void giveEffect(Player player) {
        PotionEffect nightVersion = new PotionEffect(
                PotionEffectType.NIGHT_VISION,
                PotionEffect.INFINITE_DURATION,
                0,
                false,
                false
        );
        PotionEffect invisibility = new PotionEffect(
                PotionEffectType.INVISIBILITY,
                PotionEffect.INFINITE_DURATION,
                0,
                false,
                false
        );
        player.addPotionEffect(nightVersion);
        player.addPotionEffect(invisibility);
        player.setAllowFlight(true);
        player.setFlying(true);
        // 不影响刷怪、不计入睡眠人数、不会推动其他实体
        player.setAffectsSpawning(false);
        player.setSleepingIgnored(true);
        player.setCollidable(false);
        Ari.instance.getNbtManager().setNbt(PlayerNbt.VANISH, player, PersistentDataType.BOOLEAN, true);
        Collection<Entity> nearby = player.getNearbyEntities(24, 24, 24);
        for (Entity entity : nearby) {
            if (entity instanceof Mob mob && mob.getTarget() == player) {
                mob.setTarget(null);
            }
        }
    }

    private void removeEffect(Player player) {
        player.removePotionEffect(PotionEffectType.NIGHT_VISION);
        player.removePotionEffect(PotionEffectType.INVISIBILITY);
        player.setAffectsSpawning(true);
        player.setSleepingIgnored(false);
        player.setCollidable(true);
        List<String> nodes = Ari.instance.getConfigurationManager().get(FunctionConfig.class).getVanishFlyPermissionNodes();
        boolean hasPerm = false;
        for (String node : nodes) {
            if (Ari.PERMISSION_SERVICE.hasPermission(player, node) && !player.isOp()) {
                hasPerm = true;
                break;
            }
        }

        boolean keepFlight = player.getGameMode() == GameMode.CREATIVE
                || player.getGameMode() == GameMode.SPECTATOR
                || hasPerm;

        if (keepFlight) {
            player.setAllowFlight(true);
        } else {
            player.setFlying(false);
            player.setAllowFlight(false);
        }
    }

}

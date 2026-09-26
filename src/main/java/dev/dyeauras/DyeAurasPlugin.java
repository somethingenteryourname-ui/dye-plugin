package dev.dyeauras;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class DyeAurasPlugin extends JavaPlugin implements Listener, TabExecutor {

    /** One configured dye: which item, which effect, what level, what trail color. */
    private record DyeAura(String id, Material material, PotionEffectType effect, int amplifier,
                           Color color, Particle.DustOptions dust) {}

    private final Map<Material, DyeAura> auras = new EnumMap<>(Material.class);
    /** Effects this plugin gave each player, so we can take them away again. */
    private final Map<UUID, Map<PotionEffectType, Integer>> applied = new HashMap<>();

    private NamespacedKey auraKey;
    private BukkitTask task;
    private int tickCounter;

    private boolean handOnly;
    private boolean requireSpecialDye;
    private boolean trailEnabled;
    private int particlesPerColor;
    private int effectDuration;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        auraKey = new NamespacedKey(this, "aura_dye");
        saveDefaultConfig();
        loadSettings();

        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand cmd = getCommand("dyeaura");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        // Runs every 2 ticks: trail every run, effects every 10 ticks.
        task = getServer().getScheduler().runTaskTimer(this, this::tickAll, 20L, 2L);
    }

    @Override
    public void onDisable() {
        if (task != null) task.cancel();
        for (Player p : getServer().getOnlinePlayers()) clearApplied(p);
        applied.clear();
    }

    private void loadSettings() {
        reloadConfig();
        FileConfiguration c = getConfig();

        handOnly = "hand".equalsIgnoreCase(c.getString("check-mode", "inventory"));
        requireSpecialDye = c.getBoolean("require-special-dye", false);
        effectDuration = Math.max(40, c.getInt("effect-duration-ticks", 300));
        trailEnabled = c.getBoolean("trail.enabled", true);
        particlesPerColor = Math.max(1, c.getInt("trail.particles-per-color", 3));
        float size = (float) Math.max(0.1, c.getDouble("trail.size", 1.2));

        auras.clear();
        ConfigurationSection dyes = c.getConfigurationSection("dyes");
        if (dyes == null) {
            getLogger().warning("No 'dyes' section in config.yml - nothing will happen.");
            return;
        }

        Registry<PotionEffectType> effects = RegistryAccess.registryAccess().getRegistry(RegistryKey.MOB_EFFECT);

        for (String id : dyes.getKeys(false)) {
            ConfigurationSection s = dyes.getConfigurationSection(id);
            if (s == null || !s.getBoolean("enabled", true)) continue;

            Material mat = Material.matchMaterial(id.toUpperCase(Locale.ROOT) + "_DYE");
            if (mat == null) {
                getLogger().warning("Unknown dye color '" + id + "' - skipping.");
                continue;
            }

            String effectName = s.getString("effect", "").toLowerCase(Locale.ROOT).trim();
            NamespacedKey effectKey = NamespacedKey.fromString(effectName);
            PotionEffectType type = effectKey == null ? null : effects.get(effectKey);
            if (type == null) {
                getLogger().warning("Unknown effect '" + effectName + "' for dye '" + id + "' - skipping.");
                continue;
            }

            int level = Math.max(1, s.getInt("level", 1));
            Color color = parseColor(s.getString("particle-color"), id);
            auras.put(mat, new DyeAura(id.toLowerCase(Locale.ROOT), mat, type, level - 1, color,
                    new Particle.DustOptions(color, size)));
        }
        getLogger().info("Loaded " + auras.size() + " dye auras.");
    }

    private Color parseColor(String hex, String dyeId) {
        if (hex != null && !hex.isBlank()) {
            try {
                return Color.fromRGB(Integer.parseInt(hex.replace("#", "").trim(), 16));
            } catch (IllegalArgumentException e) {
                getLogger().warning("Bad particle-color '" + hex + "' for " + dyeId + ", using the dye's color.");
            }
        }
        try {
            return DyeColor.valueOf(dyeId.toUpperCase(Locale.ROOT)).getColor();
        } catch (IllegalArgumentException e) {
            return Color.WHITE;
        }
    }

    // ------------------------------------------------------------------ main loop

    private void tickAll() {
        tickCounter++;
        boolean doEffects = tickCounter % 5 == 0; // every 10 ticks (0.5s)

        for (Player p : getServer().getOnlinePlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR) {
                if (doEffects) updateEffects(p, List.of());
                continue;
            }
            List<DyeAura> active = findAuras(p);
            if (doEffects) updateEffects(p, active);
            if (trailEnabled && !active.isEmpty()) spawnTrail(p, active);
        }
    }

    private List<DyeAura> findAuras(Player p) {
        if (auras.isEmpty()) return List.of();
        Set<DyeAura> found = new LinkedHashSet<>();
        PlayerInventory inv = p.getInventory();
        if (handOnly) {
            check(inv.getItemInMainHand(), found);
            check(inv.getItemInOffHand(), found);
        } else {
            for (ItemStack item : inv.getContents()) check(item, found);
        }
        return new ArrayList<>(found);
    }

    private void check(ItemStack item, Set<DyeAura> found) {
        if (item == null) return;
        DyeAura aura = auras.get(item.getType());
        if (aura == null) return;
        if (requireSpecialDye && !isAuraDye(item)) return;
        found.add(aura);
    }

    private boolean isAuraDye(ItemStack item) {
        if (!item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(auraKey, PersistentDataType.BYTE);
    }

    private void updateEffects(Player p, List<DyeAura> active) {
        // If two dyes give the same effect, the higher level wins.
        Map<PotionEffectType, Integer> wanted = new HashMap<>();
        for (DyeAura a : active) wanted.merge(a.effect(), a.amplifier(), Math::max);

        // Take away effects from dyes the player no longer has (or that dropped a level).
        Map<PotionEffectType, Integer> previous = applied.getOrDefault(p.getUniqueId(), Map.of());
        for (Map.Entry<PotionEffectType, Integer> e : previous.entrySet()) {
            Integer now = wanted.get(e.getKey());
            if (now == null || now < e.getValue()) removeOurs(p, e.getKey(), e.getValue());
        }

        for (Map.Entry<PotionEffectType, Integer> e : wanted.entrySet()) {
            // ambient = true marks it as ours; particles off so the dye trail is the only visual
            p.addPotionEffect(new PotionEffect(e.getKey(), effectDuration, e.getValue(), true, false, true));
        }

        if (wanted.isEmpty()) applied.remove(p.getUniqueId());
        else applied.put(p.getUniqueId(), wanted);
    }

    /** Only removes the effect if it still looks like the one we gave (so real potions aren't wiped). */
    private void removeOurs(Player p, PotionEffectType type, int amplifier) {
        PotionEffect current = p.getPotionEffect(type);
        if (current != null && current.isAmbient() && current.getAmplifier() == amplifier
                && current.getDuration() <= effectDuration) {
            p.removePotionEffect(type);
        }
    }

    private void clearApplied(Player p) {
        Map<PotionEffectType, Integer> previous = applied.remove(p.getUniqueId());
        if (previous == null) return;
        previous.forEach((type, amp) -> removeOurs(p, type, amp));
    }

    private void spawnTrail(Player p, List<DyeAura> active) {
        Location base = p.getLocation().add(0, 0.15, 0);
        for (DyeAura a : active) {
            p.getWorld().spawnParticle(Particle.DUST, base, particlesPerColor, 0.25, 0.1, 0.25, 0, a.dust());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clearApplied(event.getPlayer());
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text("/" + label + " <give|list|reload>", NamedTextColor.YELLOW));
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                sender.sendMessage(Component.text("Dye auras:", NamedTextColor.GOLD));
                for (DyeAura a : auras.values()) {
                    sender.sendMessage(Component.text(" • " + pretty(a.id()) + " Dye", TextColor.color(a.color().asRGB()))
                            .append(Component.text(" → " + effectLabel(a), NamedTextColor.GRAY)));
                }
            }
            case "reload" -> {
                if (!checkAdmin(sender)) return true;
                for (Player p : getServer().getOnlinePlayers()) clearApplied(p);
                loadSettings();
                sender.sendMessage(Component.text("DyeAuras reloaded (" + auras.size() + " dyes).", NamedTextColor.GREEN));
            }
            case "give" -> {
                if (!checkAdmin(sender)) return true;
                if (args.length < 3) {
                    sender.sendMessage(Component.text("/" + label + " give <player> <color> [amount]", NamedTextColor.YELLOW));
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(Component.text("Player not found: " + args[1], NamedTextColor.RED));
                    return true;
                }
                DyeAura aura = findById(args[2]);
                if (aura == null) {
                    sender.sendMessage(Component.text("Unknown dye color: " + args[2], NamedTextColor.RED));
                    return true;
                }
                int amount = 1;
                if (args.length >= 4) {
                    try {
                        amount = Math.max(1, Math.min(64, Integer.parseInt(args[3])));
                    } catch (NumberFormatException ignored) { }
                }
                ItemStack item = makeAuraDye(aura, amount);
                target.getInventory().addItem(item).values()
                        .forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
                sender.sendMessage(Component.text("Gave " + amount + "x " + pretty(aura.id()) + " Aura Dye to "
                        + target.getName(), NamedTextColor.GREEN));
            }
            default -> sender.sendMessage(Component.text("/" + label + " <give|list|reload>", NamedTextColor.YELLOW));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return filter(List.of("give", "list", "reload"), args[0]);
        if (args[0].equalsIgnoreCase("give")) {
            if (args.length == 2) return null; // default = online player names
            if (args.length == 3) return filter(auras.values().stream().map(DyeAura::id).toList(), args[2]);
        }
        return List.of();
    }

    private boolean checkAdmin(CommandSender sender) {
        if (sender.hasPermission("dyeaura.admin")) return true;
        sender.sendMessage(Component.text("You don't have permission to do that.", NamedTextColor.RED));
        return false;
    }

    private ItemStack makeAuraDye(DyeAura aura, int amount) {
        ItemStack item = new ItemStack(aura.material(), amount);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(pretty(aura.id()) + " Aura Dye", TextColor.color(aura.color().asRGB()))
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text(effectLabel(aura), NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(auraKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private DyeAura findById(String id) {
        for (DyeAura a : auras.values()) {
            if (a.id().equalsIgnoreCase(id)) return a;
        }
        return null;
    }

    // ------------------------------------------------------------------ helpers

    private static List<String> filter(List<String> options, String typed) {
        String t = typed.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.startsWith(t)).toList();
    }

    private static String pretty(String id) {
        StringBuilder sb = new StringBuilder();
        for (String part : id.split("_")) {
            if (part.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    private static String effectLabel(DyeAura a) {
        return pretty(a.effect().getKey().getKey()) + " " + roman(a.amplifier() + 1);
    }

    private static String roman(int n) {
        String[] r = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return n >= 1 && n <= r.length ? r[n - 1] : String.valueOf(n);
    }
}

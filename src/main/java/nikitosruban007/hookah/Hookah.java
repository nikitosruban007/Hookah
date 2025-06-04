/*
 * Copyright (c) 2025 Ivan Ivanov
 *
 * Licensed under the MIT License.
 * See LICENSE file for details.
 */

package nikitosruban007.hookah;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BrewingStand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.stream.Collectors;

public final class Hookah extends JavaPlugin implements Listener {
    private Map<UUID, Long> cooldownMap = new HashMap<>();
    private NamespacedKey hookahKey;
    private NamespacedKey usersKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        hookahKey = new NamespacedKey(this, "hookah");
        usersKey = new NamespacedKey(this, "hookah_users");
        Bukkit.getPluginManager().registerEvents(this, this);
        registerCustomBrewingStandRecipe();
        Bukkit.getScheduler().runTaskTimer(this, this::cleanupStands, 20L, 20L);
    }

    private void cleanupStands() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasMetadata("hookahStand")) {
                Location loc = (Location) player.getMetadata("hookahStand").get(0).value();
                if (!player.getWorld().equals(loc.getWorld()) || player.getLocation().distance(loc) > 7) {
                    removeUserFromStand(player.getUniqueId(), loc);
                    player.removeMetadata("hookahStand", this);
                }
            }
        }
    }

    private void registerCustomBrewingStandRecipe() {
        ItemStack hookahBrewingStand = new ItemStack(Material.BREWING_STAND);
        ItemMeta meta = hookahBrewingStand.getItemMeta();
        meta.setDisplayName(getConfig().getString("brewingStandDisplayName"));
        hookahBrewingStand.setItemMeta(meta);
        ShapedRecipe recipe;
        ConfigurationSection section = getConfig().getConfigurationSection("recipe.brewingStand");
        if (section != null) {
            List<String> shape = section.getStringList("shape");
            if (shape.size() != 3) {
                getLogger().warning("Неверное число строк в рецепте. Используются значения по умолчанию.");
                shape = Arrays.asList(" B ", " S ", " B ");
            }
            recipe = new ShapedRecipe(new NamespacedKey(this, "hookah_brewing_stand"), hookahBrewingStand);
            recipe.shape(shape.get(0), shape.get(1), shape.get(2));
            ConfigurationSection ing = section.getConfigurationSection("ingredients");
            if (ing != null) {
                for (String key : ing.getKeys(false)) {
                    try {
                        recipe.setIngredient(key.charAt(0), Material.valueOf(ing.getString(key)));
                    } catch (Exception e) {
                        getLogger().warning("Неверный материал для ингредиента '" + key + "': " + ing.getString(key));
                    }
                }
            }
        } else {
            recipe = new ShapedRecipe(new NamespacedKey(this, "hookah_brewing_stand"), hookahBrewingStand);
            recipe.shape(" B ", " S ", " B ");
            recipe.setIngredient('B', Material.BAMBOO);
            recipe.setIngredient('S', Material.BREWING_STAND);
        }
        Bukkit.addRecipe(recipe);
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (item.hasItemMeta() && getConfig().getString("brewingStandDisplayName").equals(item.getItemMeta().getDisplayName())
                && event.getBlockPlaced().getType() == Material.BREWING_STAND) {
            BrewingStand stand = (BrewingStand) event.getBlockPlaced().getState();
            stand.getPersistentDataContainer().set(hookahKey, PersistentDataType.STRING, "true");
            stand.getPersistentDataContainer().set(usersKey, PersistentDataType.STRING, "");
            stand.update();
        }
    }

    @EventHandler
    public void onBrewingStandRightClick(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null
                && event.getClickedBlock().getType() == Material.BREWING_STAND) {
            BrewingStand stand = (BrewingStand) event.getClickedBlock().getState();
            if (stand.getPersistentDataContainer().has(hookahKey, PersistentDataType.STRING)) {
                event.setCancelled(true);
                List<UUID> users = getStandUsers(stand);
                Player player = event.getPlayer();
                UUID id = player.getUniqueId();
                if (users.contains(id)) {
                    sendActionBar(player, getConfig().getString("message.alreadyHaveBamboo"));
                    return;
                }
                if (users.size() >= 4) {
                    sendActionBar(player, getConfig().getString("message.alreadyTaken"));
                    return;
                }
                giveBamboo(player);
                users.add(id);
                setStandUsers(stand, users);
                player.setMetadata("hookahStand", new FixedMetadataValue(this, event.getClickedBlock().getLocation()));
                sendActionBar(player, getConfig().getString("message.giveBamboo"));
            }
        }
    }

    @EventHandler
    public void onBambooUse(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (item != null && item.getType() == Material.BAMBOO && item.hasItemMeta()
                && getConfig().getString("bambooDisplayName").equals(item.getItemMeta().getDisplayName())
                && (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK)) {
            UUID id = player.getUniqueId();
            long now = System.currentTimeMillis();
            if (cooldownMap.containsKey(id) && now - cooldownMap.get(id) < 3000) {
                sendActionBar(player, getConfig().getString("message.wait"));
                return;
            }
            cooldownMap.put(id, now);
            player.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.NAUSEA, 120, 3));
            player.getWorld().spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, player.getLocation().add(0,1,0), 27, 0.675,0.675,0.675,0);
            sendActionBar(player, getConfig().getString("message.puff"));
        }
    }

    @EventHandler
    public void onBambooDrop(PlayerDropItemEvent event) {
        ItemStack dropped = event.getItemDrop().getItemStack();
        if (dropped.getType() == Material.BAMBOO && dropped.hasItemMeta()
                && getConfig().getString("bambooDisplayName").equals(dropped.getItemMeta().getDisplayName())) {
            event.getItemDrop().remove();
            Player player = event.getPlayer();
            if (player.hasMetadata("hookahStand")) {
                Location loc = (Location) player.getMetadata("hookahStand").get(0).value();
                removeUserFromStand(player.getUniqueId(), loc);
                player.removeMetadata("hookahStand", this);
            }
            sendActionBar(player, getConfig().getString("message.bambooDropped"));
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        // remove bamboo manually
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == Material.BAMBOO && item.hasItemMeta()
                    && getConfig().getString("bambooDisplayName").equals(item.getItemMeta().getDisplayName())) {
                player.getInventory().remove(item);
            }
        }
        cooldownMap.remove(player.getUniqueId());
        if (player.hasMetadata("hookahStand")) {
            Location loc = (Location) player.getMetadata("hookahStand").get(0).value();
            removeUserFromStand(player.getUniqueId(), loc);
            player.removeMetadata("hookahStand", this);
        }
    }

    @EventHandler
    public void onBrewingStandBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() == Material.BREWING_STAND) {
            BrewingStand stand = (BrewingStand) block.getState();
            if (stand.getPersistentDataContainer().has(hookahKey, PersistentDataType.STRING)) {
                List<UUID> users = getStandUsers(stand);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (users.contains(p.getUniqueId())) {
                        for (ItemStack item : p.getInventory().getContents()) {
                            if (item != null && item.getType() == Material.BAMBOO && item.hasItemMeta()
                                    && getConfig().getString("bambooDisplayName").equals(item.getItemMeta().getDisplayName())) {
                                p.getInventory().remove(item);
                            }
                        }
                        p.removeMetadata("hookahStand", this);
                    }
                }
            }
        }
    }

    private List<UUID> getStandUsers(BrewingStand stand) {
        String data = stand.getPersistentDataContainer().getOrDefault(usersKey, PersistentDataType.STRING, "");
        if (data.isEmpty()) return new ArrayList<>();
        return Arrays.stream(data.split(","))
                .filter(s -> !s.isEmpty())
                .map(UUID::fromString)
                .collect(Collectors.toList());
    }

    private void setStandUsers(BrewingStand stand, List<UUID> users) {
        String data = users.stream().map(UUID::toString).collect(Collectors.joining(","));
        stand.getPersistentDataContainer().set(usersKey, PersistentDataType.STRING, data);
        stand.update();
    }

    private void removeUserFromStand(UUID id, Location loc) {
        Block block = loc.getBlock();
        if (block.getType() != Material.BREWING_STAND) return;
        BrewingStand stand = (BrewingStand) block.getState();
        List<UUID> users = getStandUsers(stand);
        if (users.remove(id)) setStandUsers(stand, users);
    }

    private void giveBamboo(Player player) {
        ItemStack bamboo = new ItemStack(Material.BAMBOO);
        ItemMeta meta = bamboo.getItemMeta();
        meta.setDisplayName(getConfig().getString("bambooDisplayName"));
        bamboo.setItemMeta(meta);
        player.getInventory().addItem(bamboo);
    }

    public void sendActionBar(Player player, String message) {
        Component comp = MiniMessage.miniMessage().deserialize(message);
        ((Audience) player).sendActionBar(comp);
    }

    @Override
    public void onDisable() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            for (ItemStack item : player.getInventory().getContents()) {
                if (item != null && item.getType() == Material.BAMBOO && item.hasItemMeta()
                        && getConfig().getString("bambooDisplayName").equals(item.getItemMeta().getDisplayName())) {
                    player.getInventory().remove(item);
                }
            }
            cooldownMap.remove(player.getUniqueId());
            if (player.hasMetadata("hookahStand")) {
                Location loc = (Location) player.getMetadata("hookahStand").get(0).value();
                removeUserFromStand(player.getUniqueId(), loc);
                player.removeMetadata("hookahStand", this);
            }
        }
    }
}

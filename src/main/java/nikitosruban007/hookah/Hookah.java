/*
 * Copyright (c) 2025 nikitosruban007_
 *
 * Licensed under the MIT License.
 * See LICENSE file for details.
 */

package nikitosruban007.hookah;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.*;
import org.bukkit.block.BrewingStand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

public final class Hookah extends JavaPlugin implements Listener {
    private final Map<UUID, Long> cooldownMap = new HashMap<>();
    private NamespacedKey hookahKey;
    private NamespacedKey usersKey;
    private NamespacedKey mouthpieceKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        hookahKey = new NamespacedKey(this, "hookah");
        usersKey = new NamespacedKey(this, "hookah_users");
        mouthpieceKey = new NamespacedKey(this, "hookah_mouthpiece");
        Bukkit.getPluginManager().registerEvents(this, this);
        registerCustomBrewingStandRecipe();
        Bukkit.getScheduler().runTaskTimer(this, this::cleanupStands, 20L, 20L);
    }

    private void cleanupStands() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.hasMetadata("hookahStand")) continue;
            Location loc = (Location) player.getMetadata("hookahStand").get(0).value();
            if (loc.getWorld() == null || !loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4) || player.getLocation().distanceSquared(loc) > 49) {
                removeUserFromStand(player.getUniqueId(), loc);
                player.removeMetadata("hookahStand", this);
                for (ItemStack item : player.getInventory()){
                    if (item.getItemMeta().getPersistentDataContainer().has(mouthpieceKey, PersistentDataType.STRING)){
                        player.getInventory().remove(item);
                        sendActionBar(player, getConfig().getString("message.bambooDropped"));
                    }
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
                shape = Arrays.asList(" B ", " S ", " B ");
            }
            recipe = new ShapedRecipe(new NamespacedKey(this, "hookah_brewing_stand"), hookahBrewingStand);
            recipe.shape(shape.get(0), shape.get(1), shape.get(2));
            ConfigurationSection ing = section.getConfigurationSection("ingredients");
            if (ing != null) {
                for (String key : ing.getKeys(false)) {
                    try {
                        recipe.setIngredient(key.charAt(0), Material.valueOf(ing.getString(key)));
                    } catch (Exception ignored) {
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
        if (isMouthpiece(item)) {
            event.setCancelled(true);
            return;
        }
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
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        if (event.getClickedBlock().getType() != Material.BREWING_STAND) return;
        BrewingStand stand = (BrewingStand) event.getClickedBlock().getState();
        if (!stand.getPersistentDataContainer().has(hookahKey, PersistentDataType.STRING)) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        List<UUID> users = getStandUsers(stand);

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

    @EventHandler
    public void onBambooUse(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (!isMouthpiece(item)) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        if (cooldownMap.containsKey(id) && now - cooldownMap.get(id) < 3000) {
            sendActionBar(player, getConfig().getString("message.wait"));
            return;
        }
        cooldownMap.put(id, now);
        player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 120, 3));
        player.getWorld().spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, player.getLocation().add(0, 1, 0), 27, 0.675, 0.675, 0.675, 0);
        sendActionBar(player, getConfig().getString("message.puff"));
    }

    @EventHandler
    public void onBambooDrop(PlayerDropItemEvent event) {
        ItemStack dropped = event.getItemDrop().getItemStack();
        if (!isMouthpiece(dropped)) return;

        event.getItemDrop().remove();
        Player player = event.getPlayer();
        if (player.hasMetadata("hookahStand")) {
            Location loc = (Location) player.getMetadata("hookahStand").get(0).value();
            removeUserFromStand(player.getUniqueId(), loc);
            player.removeMetadata("hookahStand", this);
        }
        sendActionBar(player, getConfig().getString("message.bambooDropped"));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isMouthpiece(contents[i])) {
                contents[i] = null;
            }
        }
        player.getInventory().setContents(contents);
        cooldownMap.remove(player.getUniqueId());
        if (player.hasMetadata("hookahStand")) {
            Location loc = (Location) player.getMetadata("hookahStand").get(0).value();
            removeUserFromStand(player.getUniqueId(), loc);
            player.removeMetadata("hookahStand", this);
        }
    }

    private boolean isMouthpiece(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(mouthpieceKey, PersistentDataType.STRING);
    }

    private void giveBamboo(Player player) {
        ItemStack item = new ItemStack(Material.BAMBOO);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(getConfig().getString("bambooDisplayName"));
        meta.getPersistentDataContainer().set(mouthpieceKey, PersistentDataType.STRING, "true");
        item.setItemMeta(meta);
        player.getInventory().addItem(item);
    }

    private List<UUID> getStandUsers(BrewingStand stand) {
        String data = stand.getPersistentDataContainer().getOrDefault(usersKey, PersistentDataType.STRING, "");
        List<UUID> result = new ArrayList<>();
        for (String uuid : data.split(",")) {
            try {
                if (!uuid.isEmpty()) result.add(UUID.fromString(uuid));
            } catch (IllegalArgumentException ignored) {}
        }
        return result;
    }

    private void setStandUsers(BrewingStand stand, List<UUID> users) {
        String data = String.join(",", users.stream().map(UUID::toString).toArray(String[]::new));
        stand.getPersistentDataContainer().set(usersKey, PersistentDataType.STRING, data);
        stand.update();
    }

    private void removeUserFromStand(UUID playerId, Location loc) {
        if (loc.getBlock().getType() != Material.BREWING_STAND) return;
        BrewingStand stand = (BrewingStand) loc.getBlock().getState();
        List<UUID> users = getStandUsers(stand);
        users.remove(playerId);
        setStandUsers(stand, users);
    }

    private void sendActionBar(Player player, String msg) {
        if (msg == null) return;
        Component comp = MiniMessage.miniMessage().deserialize(msg);
        ((Audience) player).sendActionBar(comp);
    }
}

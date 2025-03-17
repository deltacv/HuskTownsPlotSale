package io.github.deltacv.hkplotsale;

import com.google.gson.Gson;
import it.unimi.dsi.fastutil.Hash;
import net.kyori.adventure.text.Component;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import net.milkbowl.vault.economy.Economy;
import net.william278.husktowns.HuskTowns;
import net.william278.husktowns.api.HuskTownsAPI;
import net.william278.husktowns.claim.Chunk;
import net.william278.husktowns.claim.Claim;
import net.william278.husktowns.claim.Position;
import net.william278.husktowns.claim.TownClaim;
import net.william278.husktowns.events.UnClaimAllEvent;
import net.william278.husktowns.events.UnClaimEvent;
import net.william278.husktowns.libraries.cloplib.operation.OperationType;
import net.william278.husktowns.town.Member;
import net.william278.husktowns.town.Privilege;
import net.william278.husktowns.town.Role;
import net.william278.husktowns.town.Town;
import net.william278.husktowns.user.OnlineUser;
import net.william278.husktowns.user.User;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Husk;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import javax.annotation.Nullable;
import java.io.File;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class HuskTownsPlotSale extends JavaPlugin implements Listener {

    public static class PlotData {
        public UUID firstOwner;
        public boolean forSale;

        public int saleSignX = 0;
        public int saleSignY = 0;
        public int saleSignZ = 0;
    }

    HuskTowns huskTowns;
    Economy economy;

    private HashMap<Player, Long> playerMoveCache = new HashMap<>();

    private HashMap<Long, PlotData> plotDataCache = new HashMap<>();

    @Override
    public void onEnable() {
        huskTowns = (HuskTowns) getServer().getPluginManager().getPlugin("HuskTowns");
        if (huskTowns == null) {
            getLogger().severe("HuskTowns not found! Disabling plugin...");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        economy = getServer().getServicesManager().getRegistration(Economy.class).getProvider();

        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        // check if player moved into a plot
        Location loc = event.getPlayer().getLocation();
        Position position = Position.at(loc.getX(), loc.getY(), loc.getZ(), getHuskTownsAPI().getWorld(loc.getWorld().getName()));

        TownClaim claim = getHuskTownsAPI().getClaimAt(position).orElse(null);

        if (claim != null) {
            long chunkLong = claim.claim().getChunk().asLong();

            if (playerMoveCache.containsKey(event.getPlayer())) {
                if (playerMoveCache.get(event.getPlayer()).equals(chunkLong)) {
                    return;
                }
            }
            playerMoveCache.put(event.getPlayer(), chunkLong);

            if (claim.claim().getType() == Claim.Type.PLOT) {
                Set<UUID> members = claim.claim().getPlotMembers();

                PlotData data = getCachedPlotData(claim.claim().getChunk());

                if(data != null && data.forSale) {
                    // check if sign still exists
                    org.bukkit.block.Block block = loc.getWorld().getBlockAt(data.saleSignX, data.saleSignY, data.saleSignZ);

                    if(!(block.getState() instanceof org.bukkit.block.Sign)) {
                        data.forSale = false;
                        savePlotData(claim.claim().getChunk(), data);
                        getLogger().warning("Plot sale sign for chunk " + claim.claim().getChunk().asLong() + " was missing. Disabling sale.");
                    }
                }

                String suffix = data != null && data.forSale ? ChatColor.GOLD + " (For Sale!)" : "";

                if (data != null && data.firstOwner != null) {
                    String ownerName = Bukkit.getOfflinePlayer(data.firstOwner).getName();
                    event.getPlayer().spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(ChatColor.GREEN + ownerName + "'s Plot" + suffix));
                } else {
                    if (members.size() == 1) {
                        String ownerName = Bukkit.getOfflinePlayer(members.iterator().next()).getName();
                        event.getPlayer().spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(ChatColor.GREEN + ownerName + "'s Plot" + suffix));
                    } else if (members.isEmpty()) {
                        event.getPlayer().spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(ChatColor.GRAY + "Unowned Plot" + suffix));
                    }
                }
            }
        } else {
            if (playerMoveCache.containsKey(event.getPlayer())) {
                if (playerMoveCache.get(event.getPlayer()) == 0) {
                    return;
                }
            }

            event.getPlayer().spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(ChatColor.GRAY + "Wilderness"));
            playerMoveCache.put(event.getPlayer(), 0L);
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        if (event.getClickedBlock().getState() instanceof org.bukkit.block.Sign) {
            org.bukkit.block.Sign sign = (org.bukkit.block.Sign) event.getClickedBlock().getState();
            String firstLine = sign.getLine(0);

            if (firstLine != null && firstLine.equalsIgnoreCase(ChatColor.DARK_BLUE + "[Plot Sale]")) {
                event.setCancelled(true);

                // check if the player has permission to buy a plot
                if (!event.getPlayer().hasPermission("husktowns.plotsale.buy")) {
                    event.getPlayer().sendMessage("You do not have permission to buy a plot!");
                    return;
                }

                int value;

                try {
                    value = Integer.parseInt(sign.getLine(2).replace(ChatColor.GREEN + "Price:" + ChatColor.GOLD +" $", ""));
                } catch (NumberFormatException e) {
                    event.getPlayer().sendMessage("Invalid plot value!");
                    return;
                }

                Location loc = event.getClickedBlock().getLocation();
                Position position = Position.at(loc.getX(), loc.getY(), loc.getZ(), getHuskTownsAPI().getWorld(loc.getWorld().getName()));

                TownClaim claim = getHuskTownsAPI().getClaimAt(position).orElse(null);
                PlotData data = getCachedPlotData(claim.claim().getChunk());

                if (data == null || !data.forSale) {
                    event.getPlayer().sendMessage(ChatColor.RED + "Invalid sign. This plot is not for sale!");
                    return;
                }

                if (claim == null) {
                    event.getPlayer().sendMessage("There is no claim here!");
                    return;
                }

                Player player = event.getPlayer();
                OnlineUser user = getHuskTownsAPI().getOnlineUser(player.getUniqueId());

                if (user != null) {
                    if (claim.claim().isPlotManager(user.getUuid()) || claim.claim().isPlotMember(user.getUuid())) {
                        event.getPlayer().sendMessage(ChatColor.RED + "You cannot buy a plot you already own!");
                        return;
                    }

                    if (getHuskTownsAPI().isOperationAllowed(user, OperationType.BLOCK_BREAK, position) && !event.getPlayer().hasPermission("husktowns.plotsale.bypass")) {
                        event.getPlayer().sendMessage(ChatColor.RED + "You cannot buy a plot in a town you have high privileges for!");
                        return;
                    }

                    if (claim.town().getMembers().size() >= claim.town().getMaxMembers(huskTowns)) {
                        event.getPlayer().sendMessage(ChatColor.RED + "The town is full! You cannot buy a plot here. Notify the town owner to increase the town size limit.");
                        return;
                    }

                    List<TownClaim> userClaims = getHuskTownsAPI().getClaims(user.getWorld()).stream()
                            .filter(c -> c.claim().getType() == Claim.Type.PLOT)
                            .filter(c -> c.claim().getPlotMembers().contains(user.getUuid()))
                            .toList();

                    if (userClaims.size() >= 2) {
                        event.getPlayer().sendMessage(ChatColor.RED + "You have reached the maximum number of plots you can own!");
                        return;
                    }

                    if (economy.getBalance(player) >= value) {
                        if (data.firstOwner != null) {
                            // add the funds to the owner
                            OfflinePlayer ownerPlayer = Bukkit.getOfflinePlayer(data.firstOwner);

                            if (!ownerPlayer.hasPlayedBefore()) {
                                event.getPlayer().sendMessage(ChatColor.RED + "Huh? This shouldn't happen! Please tell an admin.");
                                getLogger().warning("Huh? Owner not found in metadata: " + data.firstOwner);
                                return;
                            }

                            economy.depositPlayer(ownerPlayer, value); // all good
                        } else {
                            // add the funds to the town
                            claim.town().setMoney(claim.town().getMoney().add(BigDecimal.valueOf(value)));
                        }

                        economy.withdrawPlayer(player, value);

                        if (!claim.town().getMembers().containsKey(user.getUuid())) {
                            claim.town().addMember(user.getUuid(), huskTowns.getRoles().getDefaultRole());
                            player.sendMessage(ChatColor.GREEN + "You are now a member of " + ChatColor.BOLD + "" + ChatColor.BOLD + claim.town().getName());
                        }

                        for (UUID uuid : claim.claim().getPlotMembers()) {
                            claim.claim().removePlotMember(uuid); // bye bye old owner
                        }

                        claim.claim().setPlotMember(user.getUuid(), false);
                        Bukkit.getServer().broadcastMessage(ChatColor.YELLOW + player.getName() + " has bought a plot in " + claim.town().getName());

                        // remove the sign
                        event.getClickedBlock().setType(org.bukkit.Material.AIR);

                        // celebrate, yay!
                        event.getPlayer().getWorld().spawn(event.getClickedBlock().getLocation(), Firework.class, firework -> {
                            FireworkMeta fm = firework.getFireworkMeta();
                            fm.addEffect(FireworkEffect.builder()
                                    .withColor(Color.GREEN)
                                    .with(FireworkEffect.Type.BURST)
                                    .withFlicker()
                                    .withTrail()
                                    .build()
                            );
                            fm.setPower(0);
                            firework.setFireworkMeta(fm);
                        });

                        getHuskTownsAPI().updateTown(user, claim.town());

                        if(data == null) {
                            data = new PlotData();
                        }

                        data.firstOwner = user.getUuid();
                        data.forSale = false;

                        savePlotData(claim.claim().getChunk(), data);
                    } else {
                        event.getPlayer().sendMessage(ChatColor.RED + "You do not have enough money to buy this plot!");
                    }
                } else {
                    event.getPlayer().sendMessage("Huh? This shouldn't happen! Please tell an admin.");
                    getLogger().warning("Huh? User not found in HuskTowns: " + event.getPlayer().getName());
                    return;
                }
            }
        }
    }

    // sign creation detection
    @EventHandler
    public void onSignCreation(SignChangeEvent event) {
        String line1 = event.getLine(0);
        String line2 = event.getLine(1);

        if (line1 == null || line2 == null) {
            return;
        }

        if(line1.equalsIgnoreCase(ChatColor.DARK_BLUE + "[Plot Sale]")) {
            event.getPlayer().sendMessage("You cannot modify a plot sale sign!");
            event.setCancelled(true);
            return;
        }

        if (line1.equalsIgnoreCase("[plot sale]") || line1.equalsIgnoreCase("[plotsale]") || line1.equalsIgnoreCase("[sale]")) {
            // check if the player has permission to create a plot sale sign
            if (!event.getPlayer().hasPermission("husktowns.plotsale.create")) {
                event.getPlayer().sendMessage("You do not have permission to create a plot sale sign!");
                event.setCancelled(true);
                return;
            }

            Location loc = event.getBlock().getLocation();
            Position position = Position.at(loc.getX(), loc.getY(), loc.getZ(), getHuskTownsAPI().getWorld(loc.getWorld().getName()));

            TownClaim claim = getHuskTownsAPI().getClaimAt(position).orElse(null);

            int value;

            try {
                value = Integer.parseInt(line2);
            } catch (NumberFormatException e) {
                event.getPlayer().sendMessage(ChatColor.RED + "Invalid cost value!");
                event.setLine(1, "Invalid value!");
                event.setLine(2, "");
                event.setLine(3, "");
                return;
            }

            // check if player is owner of the claim
            if (claim == null && claim.claim() != null) {
                event.getPlayer().sendMessage(ChatColor.RED + "There is no claim here!");
                event.setLine(1, "No claim here!");
                return;
            }

            if (claim.claim().getType() != Claim.Type.PLOT) {
                event.getPlayer().sendMessage(ChatColor.RED + "This is not a plot claim! Mark it as such with /t plot");
                event.setLine(1, "Not a plot!");
                return;
            }

            PlotData plotData = getCachedPlotData(claim.claim().getChunk());

            if (plotData != null && plotData.forSale) {
                event.getPlayer().sendMessage(ChatColor.RED + "This plot is already for sale!");
                event.setLine(1, "Already for sale!");
                return;
            }

            OnlineUser user = getHuskTownsAPI().getOnlineUser(event.getPlayer().getUniqueId());
            Member userMembership = getHuskTownsAPI().getUserTown(user).orElse(null);
            Town userTown = userMembership == null ? null : userMembership.town();

            if (user != null || userTown != null) {
                if (userTown == claim.town() && (getHuskTownsAPI().isPrivilegeAllowed(Privilege.INVITE, user) || claim.claim().isPlotMember(user.getUuid()))) {
                    // set the sign text
                    if (getHuskTownsAPI().isPrivilegeAllowed(Privilege.INVITE, user)) {
                        event.getPlayer().sendMessage(ChatColor.YELLOW + "Sign created! Funds will be added to the town.");

                        PlotData data = new PlotData();
                        data.forSale = true;
                        data.firstOwner = null;
                        data.saleSignX = loc.getBlockX();
                        data.saleSignY = loc.getBlockY();
                        data.saleSignZ = loc.getBlockZ();

                        savePlotData(claim.claim().getChunk(), data);
                    } else {
                        PlotData data = loadPlotData(claim.claim().getChunk());

                        if (data != null && !data.firstOwner.equals(event.getPlayer().getUniqueId()) && !event.getPlayer().hasPermission("husktowns.plotsale.bypass")) {
                            event.getPlayer().sendMessage(ChatColor.RED + "You cannot sell this plot! You are not the original owner.");
                            event.setLine(1, "Not original owner!");
                            return;
                        }

                        event.getPlayer().sendMessage(ChatColor.YELLOW + "Sign created! Funds will be added to your account.");

                        PlotData newData = new PlotData();
                        newData.forSale = true;
                        newData.firstOwner = event.getPlayer().getUniqueId();
                        newData.saleSignX = loc.getBlockX();
                        newData.saleSignY = loc.getBlockY();
                        newData.saleSignZ = loc.getBlockZ();

                        savePlotData(claim.claim().getChunk(), newData);
                    }

                    event.setLine(0, ChatColor.DARK_BLUE + "[Plot Sale]");
                    event.setLine(1, ChatColor.GREEN + "For Sale!");
                    event.setLine(2, ChatColor.GREEN + "Price:" + ChatColor.GOLD +" $" + value);
                    event.setLine(3, ChatColor.GREEN + "" + ChatColor.UNDERLINE + "Click to buy!");

                    event.getPlayer().sendMessage(ChatColor.YELLOW + "Note: Any existing members will be removed when the plot is sold.");
                } else {
                    event.getPlayer().sendMessage(ChatColor.RED + "You do not have permission to sell this plot!");
                    event.setLine(1, "No permission!");
                }
            } else {
                event.getPlayer().sendMessage(ChatColor.RED + "You do not have an associated town!");
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onUnclaim(UnClaimEvent evt) {
        if(evt.getPlayer().hasPermission("husktowns.plotsale.bypass")) {
            return;
        }

        Claim claim = evt.getClaim();

        if (claim.getType() == Claim.Type.PLOT) {
            if(!claim.getPlotMembers().isEmpty() && !claim.getPlotMembers().contains(evt.getPlayer().getUniqueId())) {
                evt.getPlayer().sendMessage(ChatColor.RED + "You cannot unclaim this plot!");
                evt.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onBlockPlace(BlockPlaceEvent evt) {
        Block block = evt.getBlock();

        if(evt.getPlayer().hasPermission("husktowns.plotsale.bypass")) {
            return;
        }

        // find if claim
        Position position = Position.at(block.getX(), block.getY(), block.getZ(), getHuskTownsAPI().getWorld(block.getWorld().getName()));
        TownClaim claim = getHuskTownsAPI().getClaimAt(position).orElse(null);

        if(claim != null && claim.claim().getType() == Claim.Type.PLOT && !claim.claim().getPlotMembers().isEmpty() && !claim.claim().getPlotMembers().contains(evt.getPlayer().getUniqueId())) {
            evt.getPlayer().sendMessage(ChatColor.RED + "You cannot destroy blocks in a plot you do not own!");
            evt.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onBlockBreak(BlockBreakEvent evt) {
        Block block = evt.getBlock();

        if(!evt.getPlayer().hasPermission("husktowns.plotsale.bypass")) {
            // find if claim
            Position position = Position.at(block.getX(), block.getY(), block.getZ(), getHuskTownsAPI().getWorld(block.getWorld().getName()));
            TownClaim claim = getHuskTownsAPI().getClaimAt(position).orElse(null);

            if (claim != null && claim.claim().getType() == Claim.Type.PLOT && !claim.claim().getPlotMembers().isEmpty() && !claim.claim().getPlotMembers().contains(evt.getPlayer().getUniqueId())) {
                evt.getPlayer().sendMessage(ChatColor.RED + "You cannot destroy blocks in a plot you do not own!");
                evt.setCancelled(true);
            }
        }

        if (evt.getBlock().getState() instanceof org.bukkit.block.Sign) {
            org.bukkit.block.Sign sign = (org.bukkit.block.Sign) evt.getBlock().getState();

            if(sign.getLine(0).equals(ChatColor.DARK_BLUE + "[Plot Sale]")) {
                org.bukkit.Chunk bukkitChunk = evt.getBlock().getChunk();
                PlotData data = getCachedPlotData(Chunk.at(bukkitChunk.getX(), bukkitChunk.getZ()));

                if (data == null || !data.forSale) {
                    evt.getPlayer().sendMessage(ChatColor.RED + "Destroying this sign will not remove the plot from sale.");
                    return;
                }

                if (evt.getBlock().getX() != data.saleSignX || evt.getBlock().getY() != data.saleSignY || evt.getBlock().getZ() != data.saleSignZ) {
                    evt.getPlayer().sendMessage(ChatColor.RED + "Destroying this sign will not remove the plot from sale.");
                    return;
                }

                if (data.firstOwner != null && !data.firstOwner.equals(evt.getPlayer().getUniqueId())) {
                    evt.getPlayer().sendMessage(ChatColor.RED + "You cannot remove this sign! You are not the original owner.");
                    evt.setCancelled(true);
                    return;
                }

                evt.getPlayer().sendMessage(ChatColor.YELLOW + "Plot sale sign removed.");

                if (data == null) {
                    data = new PlotData();
                }
                data.forSale = false;

                savePlotData(Chunk.at(bukkitChunk.getX(), bukkitChunk.getZ()), data);
            }
        }
    }

    private static Gson gson = new Gson();

    public void savePlotData(Chunk chunk, PlotData data) {
        File dataFolder = new File(getDataFolder(), "sales");
        dataFolder.mkdirs();

        File file = new File(dataFolder, chunk.asLong() + ".json");

        try {
            file.createNewFile();
            String json = gson.toJson(data);
            java.nio.file.Files.writeString(file.toPath(), json);

            plotDataCache.put(chunk.asLong(), data);
        } catch (Exception e) {
            getLogger().warning("Failed to save plot data for chunk " + chunk.asLong());
            getLogger().log(java.util.logging.Level.WARNING, e.getMessage(), e);
        }
    }

    @Nullable
    public PlotData loadPlotData(Chunk chunk) {
        File dataFolder = new File(getDataFolder(), "sales");
        dataFolder.mkdirs();

        File file = new File(dataFolder, chunk.asLong() + ".json");

        try {
            String json = java.nio.file.Files.readString(file.toPath());
            PlotData data = gson.fromJson(json, PlotData.class);
            plotDataCache.put(chunk.asLong(), data);

            return data;
        } catch (Exception e) {
            return null;
        }
    }

    public PlotData getCachedPlotData(Chunk chunk) {
        return plotDataCache.getOrDefault(chunk.asLong(), loadPlotData(chunk));
    }

    public HuskTownsAPI getHuskTownsAPI() {
        return HuskTownsAPI.getInstance();
    }

}

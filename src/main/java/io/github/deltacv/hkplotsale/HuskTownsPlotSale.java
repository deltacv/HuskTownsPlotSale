package io.github.deltacv.hkplotsale;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import net.milkbowl.vault.economy.Economy;
import net.william278.husktowns.HuskTowns;
import net.william278.husktowns.api.HuskTownsAPI;
import net.william278.husktowns.claim.Chunk;
import net.william278.husktowns.claim.Claim;
import net.william278.husktowns.claim.Position;
import net.william278.husktowns.claim.TownClaim;
import net.william278.husktowns.libraries.cloplib.operation.OperationType;
import net.william278.husktowns.town.Member;
import net.william278.husktowns.town.Privilege;
import net.william278.husktowns.town.Role;
import net.william278.husktowns.town.Town;
import net.william278.husktowns.user.OnlineUser;
import net.william278.husktowns.user.User;
import org.bukkit.*;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Husk;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class HuskTownsPlotSale extends JavaPlugin implements Listener {

    HuskTowns huskTowns;
    Economy economy;

    private HashMap<Player, Long> playerMoveCache = new HashMap<>();

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

                if (members.size() == 1) {
                    String ownerName = Bukkit.getPlayer(members.iterator().next()).getName();
                    event.getPlayer().spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(ChatColor.GREEN + ownerName + "'s Plot"));
                } else if (members.size() == 0) {
                    event.getPlayer().spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(ChatColor.GRAY + "Unowned Plot"));
                } else {
                    event.getPlayer().spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(ChatColor.GRAY + "Shared Plot"));
                }
            }
        } else {
            playerMoveCache.remove(event.getPlayer());
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
                // check if the player has permission to buy a plot
                if (!event.getPlayer().hasPermission("husktowns.plotsale.buy")) {
                    event.getPlayer().sendMessage("You do not have permission to buy a plot!");
                    return;
                }

                int value;

                try {
                    value = Integer.parseInt(sign.getLine(2).replace(ChatColor.GOLD + "Price: $", ""));
                } catch (NumberFormatException e) {
                    event.getPlayer().sendMessage("Invalid plot value!");
                    return;
                }

                Location loc = event.getClickedBlock().getLocation();
                Position position = Position.at(loc.getX(), loc.getY(), loc.getZ(), getHuskTownsAPI().getWorld(loc.getWorld().getName()));

                TownClaim claim = getHuskTownsAPI().getClaimAt(position).orElse(null);

                if (claim == null) {
                    event.getPlayer().sendMessage("There is no claim here!");
                    return;
                }

                Player player = event.getPlayer();
                OnlineUser user = getHuskTownsAPI().getOnlineUser(player.getUniqueId());

                if (user != null) {
                    if(claim.claim().isPlotManager(user.getUuid()) || claim.claim().isPlotMember(user.getUuid())) {
                        event.getPlayer().sendMessage(ChatColor.RED + "You cannot buy a plot you already own!");
                        return;
                    }

                    if(getHuskTownsAPI().isOperationAllowed(user, OperationType.BLOCK_BREAK, position)) {
                        event.getPlayer().sendMessage(ChatColor.RED + "You cannot buy a plot in a town you have high privileges for!");
                        return;
                    }

                    if(claim.town().getMembers().size() >= claim.town().getMaxMembers(huskTowns)) {
                        event.getPlayer().sendMessage(ChatColor.RED + "The town is full! You cannot buy a plot here. Notify the town owner to increase the town size limit.");
                        return;
                    }

                    if (economy.getBalance(player) >= value) {
                        economy.withdrawPlayer(player, value);
                        // add the funds to the town
                        claim.town().setMoney(claim.town().getMoney().add(BigDecimal.valueOf(value)));
                        if(!claim.town().getMembers().containsKey(user.getUuid())) {
                            claim.town().addMember(user.getUuid(), huskTowns.getRoles().getDefaultRole());
                            player.sendMessage(ChatColor.GREEN + "You are now a member of " + ChatColor.BOLD + "" + ChatColor.BOLD +claim.town().getName());
                        }

                        for(UUID uuid : claim.claim().getPlotMembers()) {
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

        if (line1.equalsIgnoreCase("[plot sale]")) {
            event.setCancelled(true);

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

            OnlineUser user = getHuskTownsAPI().getOnlineUser(event.getPlayer().getUniqueId());
            Member userMembership = getHuskTownsAPI().getUserTown(user).orElse(null);
            Town userTown = userMembership == null ? null : userMembership.town();

            if (user != null || userTown != null) {
                if (userTown == claim.town() && getHuskTownsAPI().isPrivilegeAllowed(Privilege.INVITE, user)) {
                    // set the sign text
                    event.setLine(0, ChatColor.DARK_BLUE + "[Plot Sale]");
                    event.setLine(1, ChatColor.GREEN + "For Sale!");
                    event.setLine(2, ChatColor.GOLD + "Price: $" + value);
                    event.setLine(3, ChatColor.GREEN + "" + ChatColor.BOLD + "Click to buy!");

                    event.getPlayer().sendMessage(ChatColor.YELLOW + "Sign created! The funds from the sale will be added to the town's bank account.");
                    event.getPlayer().sendMessage(ChatColor.YELLOW + "Note: If this plot has any members, they will be removed when the plot is sold.");
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

    public HuskTownsAPI getHuskTownsAPI() {
        return HuskTownsAPI.getInstance();
    }

}

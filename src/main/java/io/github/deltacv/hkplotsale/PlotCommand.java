package io.github.deltacv.hkplotsale;

import net.william278.husktowns.api.HuskTownsAPI;
import net.william278.husktowns.claim.Claim;
import net.william278.husktowns.claim.Position;
import net.william278.husktowns.claim.TownClaim;
import net.william278.husktowns.town.Privilege;
import net.william278.husktowns.user.User;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.jetbrains.annotations.NotNull;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class PlotCommand implements CommandExecutor {

    HuskTownsPlotSale plugin;

    public PlotCommand(HuskTownsPlotSale plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String[] strings) {
        if (!(commandSender instanceof Player)) {
            commandSender.sendMessage("You must be a player to use this command.");
            return true;
        }

        Player player = (Player) commandSender;

        if (!player.hasPermission("huskplot.plotsale")) {
            player.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
            return true;
        }

        HuskTownsAPI api = HuskTownsAPI.getInstance();
        User user = api.getOnlineUser(player.getUniqueId());

        Location loc = player.getLocation();
        Position position = Position.at(loc.getX(), loc.getY(), loc.getZ(), api.getWorld(loc.getWorld().getName()));

        TownClaim claim = api.getClaimAt(position).orElse(null);

        if (strings.length == 0 || strings[0].equalsIgnoreCase("help")) {
            if (claim == null || claim.claim().getType() != Claim.Type.PLOT || (strings.length > 0 && strings[0].equalsIgnoreCase("help"))) {
                player.sendMessage(ChatColor.YELLOW + "/plot add " + ChatColor.RESET + "-" + ChatColor.DARK_GREEN +" Add a user to the plot you are standing on.");
                player.sendMessage(ChatColor.YELLOW + "/plot remove "+ ChatColor.RESET + "-" + ChatColor.DARK_GREEN +" Remove a user from the plot you are standing on.");
                player.sendMessage(ChatColor.YELLOW + "/plot buy "+ ChatColor.RESET + "-" + ChatColor.DARK_GREEN +" Buy the plot you are standing on.");
                player.sendMessage(ChatColor.YELLOW + "/plot sell " + ChatColor.RESET + "-" + ChatColor.DARK_GREEN +" Info on how to sell.");

                player.sendMessage("");
                player.sendMessage(ChatColor.ITALIC + "Stand on a plot to see more info.");
                return true;
            } else {
                Set<UUID> members = claim.claim().getPlotMembers();

                // find managers
                Set<UUID> managers = members.stream().filter(uuid -> claim.claim().isPlotManager(uuid)).collect(Collectors.toSet());
                // normal users
                Set<UUID> users = members.stream().filter(uuid -> claim.claim().isPlotMember(uuid) && !claim.claim().isPlotManager(uuid)).collect(Collectors.toSet());

                if (!managers.isEmpty()) {
                    player.sendMessage(ChatColor.GOLD + "Managers of this plot: ");
                    for (UUID uuid : managers) {
                        player.sendMessage(ChatColor.GREEN + " - " + Bukkit.getOfflinePlayer(uuid).getName());
                    }
                }
                if (!users.isEmpty()) {
                    player.sendMessage(ChatColor.GOLD + "Members of this plot: ");
                    for (UUID uuid : users) {
                        player.sendMessage(ChatColor.GREEN + " - " + Bukkit.getOfflinePlayer(uuid).getName());
                    }
                }

                HuskTownsPlotSale.PlotData data = plugin.getCachedPlotData(claim.claim().getChunk());

                if (data != null && data.forSale) {
                    Block block = player.getWorld().getBlockAt(data.saleSignX, data.saleSignY, data.saleSignZ);

                    if (block.getState() instanceof org.bukkit.block.Sign) {
                        org.bukkit.block.Sign sign = (org.bukkit.block.Sign) block.getState();
                        player.sendMessage(ChatColor.YELLOW + "" + ChatColor.UNDERLINE + "This plot is for sale !" + ChatColor.RESET + " " + sign.getLine(2) + ChatColor.YELLOW + " - /plot buy");
                    } else {
                        player.sendMessage(ChatColor.YELLOW + "" + ChatColor.UNDERLINE + "This plot is for sale ! But we can't find the price sign...");
                    }
                } else {
                    player.sendMessage(ChatColor.YELLOW + "" + ChatColor.UNDERLINE + "This plot is not for sale.");
                }
            }

            return true;
        } else if (strings.length == 1) {
            if (strings[0].equalsIgnoreCase("buy")) {
                if (claim == null || claim.claim().getType() != Claim.Type.PLOT) {
                    player.sendMessage(ChatColor.RED + "You are not standing on a plot !");
                    return true;
                }

                HuskTownsPlotSale.PlotData data = plugin.getCachedPlotData(claim.claim().getChunk());

                if (data == null || !data.forSale) {
                    player.sendMessage(ChatColor.RED + "This plot is not for sale.");
                    return true;
                }

                Block block = player.getWorld().getBlockAt(data.saleSignX, data.saleSignY, data.saleSignZ);
                if (!(block.getState() instanceof org.bukkit.block.Sign)) {
                    player.sendMessage(ChatColor.RED + "This plot is for sale, but we can't find the price sign.");
                    return true;
                }

                player.sendMessage(ChatColor.GREEN + "This plot is for sale. " + ((org.bukkit.block.Sign) block.getState()).getLine(2) + ".");
                player.sendMessage(ChatColor.YELLOW + "Type /plot buy confirm");

                return true;
            } else if (strings[0].equalsIgnoreCase("add")) {
                player.sendMessage(ChatColor.RED + "Usage: /plot add <player>");
                return true;
            } else if (strings[0].equalsIgnoreCase("remove")) {
                player.sendMessage(ChatColor.RED + "Usage: /plot remove <player>");
                return true;
            } else if (strings[0].equalsIgnoreCase("sell")) {
                player.sendMessage(ChatColor.YELLOW + "To sell a plot, place a sign within the plot:");
                player.sendMessage(ChatColor.GOLD + "Line 1: " + ChatColor.RESET + "[sale]");
                player.sendMessage(ChatColor.GOLD + "Line 2: " + ChatColor.RESET + "price#");
                return true;
            }
        } else if (strings.length == 2) {
            if(strings[0].equalsIgnoreCase("buy") && strings[1].equalsIgnoreCase("confirm")) {

                HuskTownsPlotSale.PlotData data = plugin.getCachedPlotData(claim.claim().getChunk());

                if (data != null && data.forSale) {
                    Block block = player.getWorld().getBlockAt(data.saleSignX, data.saleSignY, data.saleSignZ);
                    plugin.onPlayerInteract(new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, player.getInventory().getItemInMainHand(), block, BlockFace.SELF));
                    return true;
                } else {
                    player.sendMessage(ChatColor.RED + "This plot is not for sale.");
                    return true;
                }
            } else if (strings[0].equalsIgnoreCase("add")) {
                if (claim == null || claim.claim().getType() != Claim.Type.PLOT) {
                    player.sendMessage(ChatColor.RED + "You are not standing on a plot !");
                    return true;
                }

                // find managers
                Set<UUID> managers = claim.claim().getPlotMembers().stream().filter(uuid -> claim.claim().isPlotManager(uuid)).collect(Collectors.toSet());

                // get plot manager
                if(!player.hasPermission("husktowns.plotsale.bypass")) {
                    if(api.isPrivilegeAllowed(Privilege.INVITE, user)) {
                        if(!managers.isEmpty()) {
                            player.sendMessage(ChatColor.RED + "You are not allowed to add members to this plot.");
                            return true;
                        }

                    } else {
                        if (!claim.claim().isPlotManager(player.getUniqueId())) {
                            player.sendMessage(ChatColor.RED + "You are not a manager of this plot.");
                            return true;
                        }
                    }
                } else {
                    player.sendMessage(ChatColor.DARK_RED + "Bypassing permission check.");
                }

                OfflinePlayer offPlayer = Bukkit.getOfflinePlayer(strings[1]);
                if (!offPlayer.hasPlayedBefore()) {
                    player.sendMessage(ChatColor.RED + "Player not found.");
                    return true;
                }

                if(claim.claim().getPlotMembers().contains(offPlayer.getUniqueId())) {
                    player.sendMessage(ChatColor.RED + "Player is already a member of this plot.");
                    return true;
                }

                HuskTownsPlotSale.PlotData data = plugin.getCachedPlotData(claim.claim().getChunk());

                if(managers.isEmpty()) {
                    if(data == null) {
                        data = new HuskTownsPlotSale.PlotData();
                    }

                    data.firstOwner = offPlayer.getUniqueId();
                    player.sendMessage(ChatColor.GREEN + "Added " + offPlayer.getName() + " to the plot as the first owner.");

                    plugin.savePlotData(claim.claim().getChunk(), data);
                } else {
                    player.sendMessage(ChatColor.GREEN + "Added " + offPlayer.getName() + " to the plot.");
                }

                claim.claim().setPlotMember(offPlayer.getUniqueId(), managers.isEmpty());

                return true;
            } else if (strings[0].equalsIgnoreCase("remove")) {
                if (claim == null || claim.claim().getType() != Claim.Type.PLOT) {
                    player.sendMessage(ChatColor.RED + "You are not standing on a plot !");
                    return true;
                }

                // find managers
                Set<UUID> managers = claim.claim().getPlotMembers().stream().filter(uuid -> claim.claim().isPlotManager(uuid)).collect(Collectors.toSet());

                OfflinePlayer offPlayer = Bukkit.getOfflinePlayer(strings[1]);

                HuskTownsPlotSale.PlotData data = plugin.getCachedPlotData(claim.claim().getChunk());

                // get plot manager
                if(!player.hasPermission("husktowns.plotsale.bypass")) {
                    if(api.isPrivilegeAllowed(Privilege.INVITE, user)) {
                        if(!managers.isEmpty()) {
                            player.sendMessage(ChatColor.RED + "You are not allowed to add members to this plot.");
                            return true;
                        }

                    } else {
                        if (!claim.claim().isPlotManager(player.getUniqueId())) {
                            player.sendMessage(ChatColor.RED + "You are not a manager of this plot.");
                            return true;
                        }
                    }

                    if(offPlayer.getUniqueId().equals(player.getUniqueId())) {
                        player.sendMessage(ChatColor.RED + "You cannot remove yourself from the plot.");
                        return true;
                    }

                    if(data != null && offPlayer.getUniqueId().equals(data.firstOwner)) {
                        player.sendMessage(ChatColor.RED + "You cannot remove the owner of the plot.");
                        return true;
                    }
                } else {
                    player.sendMessage(ChatColor.DARK_RED + "Bypassing permission check.");
                }

                Set<UUID> members = claim.claim().getPlotMembers();

                if (!members.contains(offPlayer.getUniqueId())) {
                    player.sendMessage(ChatColor.RED + "Player is not a member of this plot.");
                    return true;
                }

                if(managers.contains(offPlayer.getUniqueId()) && managers.size() == 1) {
                    data.firstOwner = null;
                    plugin.savePlotData(claim.claim().getChunk(), data);
                }

                claim.claim().removePlotMember(offPlayer.getUniqueId());

                player.sendMessage(ChatColor.GREEN + "Removed " + offPlayer.getName() + " from the plot.");

                return true;
            }
        }

        player.sendMessage(ChatColor.RED + "Invalid usage. Use /plot help for help.");

        return true;
    }
}

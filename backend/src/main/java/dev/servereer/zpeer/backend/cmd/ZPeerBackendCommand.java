package dev.servereer.zpeer.backend.cmd;

import dev.servereer.zpeer.backend.ZPeerBackend;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;
import java.util.stream.Collectors;

public final class ZPeerBackendCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of("reload", "status");

    private final ZPeerBackend plugin;

    public ZPeerBackendCommand(ZPeerBackend plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase();
        switch (sub) {
            case "reload" -> handleReload(sender);
            case "status" -> handleStatus(sender);
            default -> sender.sendMessage(ChatColor.AQUA + "[zpeer] /zpeer <reload|status>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase();
            return SUBS.stream().filter(s -> s.startsWith(prefix))
                    .collect(Collectors.toList());
        }
        return List.of();
    }

    private void handleStatus(CommandSender sender) {
        int n = plugin.activeProxyCount();
        if (n == 0) {
            sender.sendMessage(ChatColor.GRAY + "[zpeer] no active proxy connections");
        } else {
            sender.sendMessage(ChatColor.AQUA + "[zpeer] active proxy connections: " + n);
        }
        for (String line : plugin.statusLines()) {
            sender.sendMessage(ChatColor.GRAY + "  · " + line);
        }
    }

    private void handleReload(CommandSender sender) {
        try {
            int newN = plugin.reloadAll();
            sender.sendMessage(ChatColor.GREEN + "[zpeer] reloaded. active proxies: " + newN);
        } catch (Exception e) {
            sender.sendMessage(ChatColor.RED + "[zpeer] reload failed: " + e.getMessage());
            plugin.getLogger().warning("[zpeer] reload error: " + e);
        }
    }
}

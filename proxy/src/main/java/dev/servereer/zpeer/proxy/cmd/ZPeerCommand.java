package dev.servereer.zpeer.proxy.cmd;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import dev.servereer.zpeer.proxy.ZPeerProxy;
import dev.servereer.zpeer.proxy.config.ProxyConfig;
import dev.servereer.zpeer.proxy.session.Session;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.Arrays;
import java.util.List;

public final class ZPeerCommand implements SimpleCommand {

    private static final List<String> SUBS = List.of("reload", "status", "help");

    private final ZPeerProxy plugin;

    public ZPeerCommand(ZPeerProxy plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource sender = invocation.source();
        String[] args = invocation.arguments();

        String sub = args.length == 0 ? "help" : args[0].toLowerCase();
        switch (sub) {
            case "reload" -> handleReload(sender);
            case "status" -> handleStatus(sender);
            default -> handleHelp(sender);
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase();
            return SUBS.stream().filter(s -> s.startsWith(prefix)).toList();
        }
        return List.of();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("zpeer.admin");
    }

    private void handleHelp(CommandSource sender) {
        sender.sendMessage(Component.text("[zpeer] /zpeer <reload|status>",
                NamedTextColor.AQUA));
    }

    private void handleStatus(CommandSource sender) {
        ProxyConfig c = plugin.config();
        if (c == null) {
            sender.sendMessage(Component.text("[zpeer] no config loaded",
                    NamedTextColor.RED));
            return;
        }
        sender.sendMessage(Component.text(String.format(
                "[zpeer] listen=%s:%d  tokens=%d  pool-target=%d",
                c.listenHost, c.listenPort, c.tokenToServer.size(), c.poolTarget),
                NamedTextColor.AQUA));
        int n = 0;
        for (Session s : plugin.sessions().all()) {
            sender.sendMessage(Component.text(
                    "  · " + s.serverName + "  (loopback :" + s.loopbackPort
                            + ", registered=" + (s.registeredServer != null) + ")",
                    NamedTextColor.GRAY));
            n++;
        }
        if (n == 0) {
            sender.sendMessage(Component.text("  · (no backends dialed in)",
                    NamedTextColor.DARK_GRAY));
        }
    }

    private void handleReload(CommandSource sender) {
        try {
            ProxyConfig oldCfg = plugin.config();
            ProxyConfig newCfg = ProxyConfig.loadOrCreate(plugin.dataDir());

            boolean addrChanged = oldCfg == null
                    || !newCfg.listenHost.equals(oldCfg.listenHost)
                    || newCfg.listenPort != oldCfg.listenPort;

            if (addrChanged) {
                String from = oldCfg == null ? "(unbound)"
                        : oldCfg.listenHost + ":" + oldCfg.listenPort;
                String to = newCfg.listenHost + ":" + newCfg.listenPort;
                plugin.logger().info("[zpeer] reload: address {} -> {}, rebinding", from, to);
                plugin.rebindListener(newCfg);
            }
            plugin.setConfig(newCfg);

            sender.sendMessage(Component.text(String.format(
                    "[zpeer] reloaded. listen=%s:%d  tokens=%d  pool-target=%d  (existing sessions kept)",
                    newCfg.listenHost, newCfg.listenPort,
                    newCfg.tokenToServer.size(), newCfg.poolTarget),
                    NamedTextColor.GREEN));
        } catch (Exception e) {
            sender.sendMessage(Component.text(
                    "[zpeer] reload failed: " + e.getMessage(), NamedTextColor.RED));
            plugin.logger().warn("[zpeer] reload error: " + Arrays.toString(e.getStackTrace()));
        }
    }
}

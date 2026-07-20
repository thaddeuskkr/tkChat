package dev.tkkr.tkchat.velocity.integration;

import com.velocitypowered.api.proxy.Player;
import dev.tkkr.tkchat.velocity.Permissions;
import dev.tkkr.tkchat.core.model.AccessDecision;
import dev.tkkr.tkchat.core.model.DenialReason;
import dev.tkkr.tkchat.core.model.SenderContext;
import dev.tkkr.tkchat.core.service.AccessController;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.context.MutableContextSet;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryMode;
import net.luckperms.api.query.QueryOptions;
import space.arim.libertybans.api.LibertyBans;
import space.arim.libertybans.api.NetworkAddress;
import space.arim.omnibus.OmnibusProvider;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class VelocityAccessController implements AccessController {
    private final LuckPerms luckPerms;
    private final LibertyBans libertyBans;
    private volatile boolean failClosed;

    public VelocityAccessController(boolean failClosed) {
        this.luckPerms = LuckPermsProvider.get();
        this.libertyBans = OmnibusProvider.getOmnibus().getRegistry()
                .getProvider(LibertyBans.class)
                .orElseThrow(() -> new IllegalStateException("LibertyBans API is unavailable"));
        this.failClosed = failClosed;
    }

    public void reconfigure(boolean failClosed) {
        this.failClosed = failClosed;
    }

    @Override
    public CompletionStage<AccessDecision> authorize(
            SenderContext sender,
            String permission,
            String bypassPermission,
            boolean containsLink
    ) {
        User user = luckPerms.getUserManager().getUser(sender.playerId());
        if (user == null) {
            return CompletableFuture.completedFuture(AccessDecision.deny(DenialReason.NOT_READY));
        }
        QueryOptions options = queryOptions(sender.serverId(), sender.backendContext());
        var permissionData = user.getCachedData().getPermissionData(options);
        boolean allowed = permission == null || permission.isBlank()
                || permissionData.checkPermission(permission).asBoolean()
                || (bypassPermission != null && !bypassPermission.isBlank()
                && permissionData.checkPermission(bypassPermission).asBoolean());
        if (!allowed) {
            return CompletableFuture.completedFuture(AccessDecision.deny(DenialReason.NO_PERMISSION));
        }
        if (containsLink
                && !permissionData.checkPermission(Permissions.BYPASS_LINKS).asBoolean()) {
            return CompletableFuture.completedFuture(AccessDecision.deny(DenialReason.LINKS_NOT_ALLOWED));
        }
        String prefix = user.getCachedData().getMetaData(options).getPrefix();
        String suffix = user.getCachedData().getMetaData(options).getSuffix();
        return libertyBans.getSelector()
                .getCachedMute(sender.playerId(), NetworkAddress.of(sender.address()))
                .toCompletableFuture()
                .handle((punishment, error) -> {
                    if (error != null) {
                        return failClosed
                                ? AccessDecision.deny(DenialReason.INTERNAL_ERROR)
                                : AccessDecision.allow(prefix, suffix);
                    }
                    return punishment.isPresent()
                            ? AccessDecision.deny(DenialReason.MUTED)
                            : AccessDecision.allow(prefix, suffix);
                });
    }

    public boolean hasPermission(Player player, String permission) {
        User user = luckPerms.getUserManager().getUser(player.getUniqueId());
        if (user == null) {
            return false;
        }
        String serverId = player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .orElse("unknown");
        return user.getCachedData().getPermissionData(queryOptions(serverId, java.util.Map.of()))
                .checkPermission(permission).asBoolean();
    }

    private static QueryOptions queryOptions(String serverId, java.util.Map<String, String> backendContexts) {
        MutableContextSet contexts = MutableContextSet.create();
        contexts.add("server", serverId);
        backendContexts.forEach(contexts::add);
        return QueryOptions.builder(QueryMode.CONTEXTUAL).context(contexts).build();
    }
}

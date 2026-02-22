package com.dadoirie.trueauth.server;

import com.dadoirie.trueauth.Trueauth;
import com.dadoirie.trueauth.config.TrueauthConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.List;

@EventBusSubscriber(modid = Trueauth.MODID)
public class SkinRefreshHandler {
    private static final int SUBTITLE_MAX_CHARS = 64;

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        var server = sp.getServer();
        if (server == null || !server.isDedicatedServer()) return;

        server.execute(() -> {
            var list = server.getPlayerList();
            list.broadcastAll(new ClientboundPlayerInfoRemovePacket(List.of(sp.getUUID())));
            list.broadcastAll(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(sp)));
        });

        var netConn = sp.connection.getConnection();
        var fallbackOpt = AuthState.consume(netConn);
        boolean useScreen = "screen".equalsIgnoreCase(TrueauthConfig.authStateReport());

        if (fallbackOpt.isPresent()) {
            if (TrueauthConfig.showOfflineLongMessage()) {
                String longMsg = TrueauthConfig.offlineFallbackMessage();
                if (longMsg == null || longMsg.isEmpty()) {
                    longMsg = "Notice: You are currently joining in offline mode. If you have a premium account, network issues may have prevented authentication. Please reconnect to retry.";
                }
                sp.sendSystemMessage(Component.literal(longMsg).withStyle(ChatFormatting.RED));
            }
            
            String playerName = sp.getGameProfile().getName();
            MutableComponent title;
            if (TrueauthRuntime.NAME_REGISTRY.isRegistered(playerName) 
                    && TrueauthRuntime.NAME_REGISTRY.isPremium(playerName) 
                    && TrueauthRuntime.NAME_REGISTRY.isSemiPremium(playerName)) {
                title = Component.literal(TrueauthConfig.semiPremiumTitle()).withStyle(ChatFormatting.YELLOW);
            } else {
                title = Component.literal(TrueauthConfig.offlineTitle()).withStyle(ChatFormatting.RED);
            }
            String reasonMessage = fallbackOpt.get();
            var subtitle = Component.literal(clamp(reasonMessage, SUBTITLE_MAX_CHARS)).withStyle(ChatFormatting.GRAY);

            if (useScreen) {
                sp.connection.send(new ClientboundSetTitlesAnimationPacket(10, 60, 10));
                sp.connection.send(new ClientboundSetTitleTextPacket(title));
                sp.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
            } else {
                sp.sendSystemMessage(title.append(Component.literal(" ").append(subtitle)));
            }
        } else {
            String titleText = TrueauthConfig.onlineTitle();
            var title = Component.literal(titleText).withStyle(ChatFormatting.GREEN);
            String shortSubtitle = TrueauthConfig.onlineShortSubtitle();
            var subtitle = Component.literal(clamp(shortSubtitle, SUBTITLE_MAX_CHARS)).withStyle(ChatFormatting.GRAY);

            if (useScreen) {
                sp.connection.send(new ClientboundSetTitlesAnimationPacket(10, 60, 10));
                sp.connection.send(new ClientboundSetTitleTextPacket(title));
                sp.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
            } else {
                sp.sendSystemMessage(title.append(Component.literal(" ").append(subtitle)));
            }
        }
    }

    private static String clamp(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(0, max - 1)) + "…";
    }
}
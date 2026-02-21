package com.dadoirie.trueauth.mixin.server;

import com.dadoirie.trueauth.config.TrueauthConfig;
import com.dadoirie.trueauth.server.NameRegistry;
import com.dadoirie.trueauth.server.TrueauthRuntime;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.commands.OpCommand;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.ServerOpListEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.util.UUID;

@Mixin(OpCommand.class)
public class OpCommandMixin {
    
    private static final SimpleCommandExceptionType ERROR_NOT_REGISTERED = new SimpleCommandExceptionType(
        Component.literal("No such Player on the server")
    );
    private static final SimpleCommandExceptionType ERROR_NOT_PREMIUM = new SimpleCommandExceptionType(
        Component.literal("Only premium players can be opped")
    );
    private static final SimpleCommandExceptionType ERROR_ALREADY_OP = new SimpleCommandExceptionType(Component.translatable("commands.op.failed"));
    
    /**
     * @author TrueAuth
     * @reason Replace op command to use NameRegistry for UUID lookup and add level/bypassesPlayerLimit arguments
     */
    @Overwrite
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("op")
                .requires(source -> source.hasPermission(3))
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(ctx -> trueauth$opPlayer(ctx, StringArgumentType.getString(ctx, "name"), 4, false))
                    .then(Commands.argument("level", IntegerArgumentType.integer(2, 4))
                        .executes(ctx -> trueauth$opPlayer(ctx, 
                            StringArgumentType.getString(ctx, "name"),
                            IntegerArgumentType.getInteger(ctx, "level"), false))
                        .then(Commands.argument("bypassesPlayerLimit", StringArgumentType.word())
                            .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(new String[]{"true", "false"}, builder))
                            .executes(ctx -> trueauth$opPlayer(ctx, 
                                StringArgumentType.getString(ctx, "name"),
                                IntegerArgumentType.getInteger(ctx, "level"),
                                StringArgumentType.getString(ctx, "bypassesPlayerLimit").equalsIgnoreCase("true"))
                            )
                        )
                    )
                )
        );
    }
    
    private static int trueauth$opPlayer(CommandContext<CommandSourceStack> ctx, String name, int level, boolean bypassesPlayerLimit) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        
        NameRegistry registry = TrueauthRuntime.NAME_REGISTRY;
        
        if (!registry.isRegistered(name)) {
            throw ERROR_NOT_REGISTERED.create();
        }
        
        if (TrueauthConfig.opPremiumOnly() && !registry.isPremium(name)) {
            throw ERROR_NOT_PREMIUM.create();
        }
        
        UUID uuid = registry.getUuid(name);
        GameProfile profile = new GameProfile(uuid, name);
        
        PlayerList playerList = source.getServer().getPlayerList();
        
        if (playerList.isOp(profile)) {
            throw ERROR_ALREADY_OP.create();
        }
        
        playerList.getOps().add(new ServerOpListEntry(profile, level, bypassesPlayerLimit));
        
        ServerPlayer onlinePlayer = playerList.getPlayer(uuid);
        if (onlinePlayer != null) {
            playerList.sendPlayerPermissionLevel(onlinePlayer);
        }
        
        source.sendSuccess(() -> Component.translatable("commands.op.success", name), true);
        
        return 1;
    }
}

package com.dadoirie.trueauth.command;

import com.dadoirie.trueauth.Trueauth;
import com.dadoirie.trueauth.config.TrueauthConfig;
import com.dadoirie.trueauth.server.NameRegistry;
import com.dadoirie.trueauth.server.TrueauthRuntime;
import com.dadoirie.trueauth.server.Whitelist;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;

import com.google.gson.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@EventBusSubscriber(modid = Trueauth.MODID)
public class TrueauthCommands {

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent e) {
        CommandDispatcher<CommandSourceStack> d = e.getDispatcher();
        d.register(Commands.literal("trueauth")
                .requires(src -> src.hasPermission(3))
                // Added: `/trueauth mojang status`
                .then(Commands.literal("config")
                        .then(Commands.literal("nomojang")
                                .then(Commands.literal("status")
                                        .executes(ctx -> cmdNomojangStatus(ctx.getSource()))
                                )
                                .then(Commands.literal("on")
                                        .executes(ctx -> cmdNomojangSet(ctx.getSource(), true))
                                )
                                .then(Commands.literal("off")
                                        .executes(ctx -> cmdNomojangSet(ctx.getSource(), false))
                                )
                                .then(Commands.literal("toggle")
                                        .executes(ctx -> cmdNomojangToggle(ctx.getSource()))
                                )
                        )
                )
                .then(Commands.literal("mojang")
                        .then(Commands.literal("status")
                                .executes(ctx -> mojangStatus(ctx.getSource()))
                        )
                )
                .then(Commands.literal("link")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> run(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name"),
                                        true, true, true, true, true)))
                        .then(Commands.literal("run")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(ctx -> run(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name"),
                                                false, true, true, true, true))))
                        .then(Commands.literal("dryrun")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(ctx -> run(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name"),
                                                true, true, true, true, true))))
                )
                .then(Commands.literal("reload")
                        .executes(ctx -> cmdConfigReload(ctx.getSource()))
                )
                // Whitelist commands - use string argument to preserve exact name capitalization
                .then(Commands.literal("whitelist")
                        .then(Commands.literal("add")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(ctx -> cmdWhitelistAdd(ctx.getSource(), StringArgumentType.getString(ctx, "name"), false))
                                        .then(Commands.literal("premium")
                                                .executes(ctx -> cmdWhitelistAdd(ctx.getSource(), StringArgumentType.getString(ctx, "name"), true))
                                        )
                                )
                        )
                        .then(Commands.literal("remove")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(ctx -> cmdWhitelistRemove(ctx.getSource(), StringArgumentType.getString(ctx, "name")))
                                )
                        )
                        .then(Commands.literal("list")
                                .executes(ctx -> cmdWhitelistList(ctx.getSource()))
                        )
                )
                // TODO: Rewrite to use NameRegistry.removeEntry() instead of PlayerPasswordStorage
                /* .then(Commands.literal("password")
                        .then(Commands.literal("remove")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .executes(ctx -> cmdPasswordRemove(ctx.getSource(), StringArgumentType.getString(ctx, "player")))))
                ) */
        );



    }

    // New method: reload configuration from disk at runtime and write values into `TrueauthConfig.COMMON`
    private static int cmdConfigReload(CommandSourceStack src) {
        try {
            Path cfgPath = FMLPaths.CONFIGDIR.get().resolve("trueauth-common.toml");
            CommentedFileConfig cfg = CommentedFileConfig.builder(cfgPath)
                    .sync() // Keep in sync with disk
                    .autosave()
                    .build();
            cfg.load();

            // Helper read function: first try `auth.xxx`, then try the version without the `auth` prefix (compatible with different definition locations)
            java.util.function.BiFunction<String, String, Object> getVal = (authKey, altKey) -> {
                if (cfg.contains(authKey)) return cfg.get(authKey);
                if (altKey != null && cfg.contains(altKey)) return cfg.get(altKey);
                return null;
            };

            // Boolean items
            Object v;
            v = getVal.apply("auth.nomojang.enabled", "nomojang.enabled");
            if (v instanceof Boolean) TrueauthConfig.COMMON.nomojangEnabled.set((Boolean) v);

            v = getVal.apply("auth.debug", "debug");
            if (v instanceof Boolean) TrueauthConfig.COMMON.debug.set((Boolean) v);

            v = getVal.apply("auth.recentIpGrace.enabled", "recentIpGrace.enabled");
            if (v instanceof Boolean) TrueauthConfig.COMMON.recentIpGraceEnabled.set((Boolean) v);

            v = getVal.apply("auth.allowOfflineForUnknownOnly", "allowOfflineForUnknownOnly");
            if (v instanceof Boolean) TrueauthConfig.COMMON.allowOfflineForUnknownOnly.set((Boolean) v);

            // Numeric items
            v = getVal.apply("auth.recentIpGrace.ttlSeconds", "recentIpGrace.ttlSeconds");
            if (v instanceof Number) TrueauthConfig.COMMON.recentIpGraceTtlSeconds.set(((Number) v).intValue());

            /// String items
            v = getVal.apply("auth.offlineFallbackMessage", "offlineFallbackMessage");
            if (v != null) TrueauthConfig.COMMON.offlineFallbackMessage.set(String.valueOf(v));

            v = getVal.apply("auth.offlineShortSubtitle", "offlineShortSubtitle");
            if (v != null) TrueauthConfig.COMMON.offlineShortSubtitle.set(String.valueOf(v));

            v = getVal.apply("auth.onlineShortSubtitle", "onlineShortSubtitle");
            if (v != null) TrueauthConfig.COMMON.onlineShortSubtitle.set(String.valueOf(v));

            // Send feedback
            src.sendSuccess(() -> Component.literal("[TrueAuth] Config reloaded from disk").withStyle(net.minecraft.ChatFormatting.GREEN), false);
            return 1;
        } catch (Exception ex) {
            src.sendFailure(Component.literal("[TrueAuth] Failed to reload config: " + ex.getMessage()).withStyle(net.minecraft.ChatFormatting.RED));
            return 0;
        }
    }

    private static int cmdNomojangStatus(CommandSourceStack src) {
        boolean enabled = TrueauthConfig.nomojangEnabled();
        if (enabled) {
            src.sendSuccess(() -> Component.literal("[TrueAuth] NoMojang: enabled").withStyle(net.minecraft.ChatFormatting.GREEN), false);
        } else {
            src.sendSuccess(() -> Component.literal("[TrueAuth] NoMojang: disabled").withStyle(net.minecraft.ChatFormatting.RED), false);
        }
        return 1;
    }

    private static int cmdNomojangSet(CommandSourceStack src, boolean value) {
        try {
            TrueauthConfig.COMMON.nomojangEnabled.set(value);
            src.sendSuccess(() -> Component.literal("[TrueAuth] NoMojang " + (value ? "enabled" : "disabled"))
                    .withStyle(value ? net.minecraft.ChatFormatting.GREEN : net.minecraft.ChatFormatting.RED), false);
            return 1;
        } catch (Throwable t) {
            src.sendFailure(Component.literal("[TrueAuth] Failed to set NoMojang: " + t.getMessage()).withStyle(net.minecraft.ChatFormatting.RED));
            return 0;
        }
    }

    private static int cmdNomojangToggle(CommandSourceStack src) {
        boolean current = TrueauthConfig.nomojangEnabled();
        return cmdNomojangSet(src, !current);
    }

    private static int mojangStatus(CommandSourceStack src) {
        try {
            String testUrl = TrueauthConfig.COMMON.mojangReverseProxy.get()+"/session/minecraft/hasJoined?username=Mojang&serverId=test";
            java.net.URL url = new java.net.URL(testUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.connect();

            int responseCode = conn.getResponseCode();
            if (responseCode == 200 || responseCode == 204 || responseCode == 403) {
                src.sendSuccess(() -> Component.literal("[TrueAuth] Mojang session server accessible, response code: " + responseCode)
                        .withStyle(net.minecraft.ChatFormatting.GREEN), false);
            } else {
                src.sendFailure(Component.literal("[TrueAuth] Mojang session server abnormal response, response code: " + responseCode)
                        .withStyle(net.minecraft.ChatFormatting.RED));
            }
            return 1;
        } catch (Exception e) {
            src.sendFailure(Component.literal("[TrueAuth] Cannot connect to Mojang session server: " + e.getMessage())
                    .withStyle(net.minecraft.ChatFormatting.RED));
            return 0;
        }
    }

    private static int run(CommandSourceStack src, String name,
                           boolean dryRun, boolean backup,
                           boolean mergeInv, boolean mergeEnder, boolean mergeStats) {
        MinecraftServer server = src.getServer();

        Optional<NameRegistry.Entry> reg = getEntry(name);
        if (reg.isEmpty()) {
            src.sendFailure(Component.literal("Premium record not found in registry for name: " + name));
            return 0;
        }
        UUID premium = reg.get().uuid;
        UUID offline = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));

        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        Path playerData = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
        Path adv = worldRoot.resolve("advancements");
        Path stats = worldRoot.resolve("stats");

        Path premDat = playerData.resolve(premium + ".dat");
        Path offDat = playerData.resolve(offline + ".dat");
        Path premAdv = adv.resolve(premium + ".json");
        Path offAdv = adv.resolve(offline + ".json");
        Path premStats = stats.resolve(premium + ".json");
        Path offStats = stats.resolve(offline + ".json");

        src.sendSuccess(() -> Component.literal(
                "[TrueAuth] link " + (dryRun ? "(dry-run)" : "(run)") + " name=" + name +
                        "\n premium=" + premium + "\n offline=" + offline +
                        "\n files:\n  " + offDat + " -> " + premDat +
                        "\n  " + offAdv + " -> " + premAdv +
                        "\n  " + offStats + " -> " + premStats
        ), false);

        if (dryRun) return 1;

        try {
            if (backup) {
                String ts = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
                Path backupDir = worldRoot.resolve("backups/trueauth/" + ts + "/" + name);
                Files.createDirectories(backupDir);
                copyIfExists(premDat, backupDir.resolve("premium.dat"));
                copyIfExists(offDat, backupDir.resolve("offline.dat"));
                copyIfExists(premAdv, backupDir.resolve("premium.adv.json"));
                copyIfExists(offAdv, backupDir.resolve("offline.adv.json"));
                copyIfExists(premStats, backupDir.resolve("premium.stats.json"));
                copyIfExists(offStats, backupDir.resolve("offline.stats.json"));
            }

            // ==== NBT merge implementation ====
            if (Files.exists(offDat)) {
                if (!Files.exists(premDat)) {
                    Files.move(offDat, premDat, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    // Merge NBT (inventory/ender chest)
                    mergePlayerDatNBT(premDat, offDat, mergeInv, mergeEnder);
                }
            }
            // ==== advancements merge implementation ====
            if (Files.exists(offAdv)) {
                if (!Files.exists(premAdv)) {
                    Files.move(offAdv, premAdv, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    mergeAdvancementsJson(premAdv, offAdv);
                }
            }
            // ==== stats merge implementation ====
            if (Files.exists(offStats)) {
                if (!Files.exists(premStats)) {
                    Files.move(offStats, premStats, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    mergeStatsJson(premStats, offStats);
                }
            }

            src.sendSuccess(() -> Component.literal("Done. Recommend player login with premium account to verify data."), false);
            return 1;
        } catch (Exception ex) {
            src.sendFailure(Component.literal("Failed: " + ex.getMessage()));
            ex.printStackTrace();
            return 0;
        }
    }

    private static Optional<NameRegistry.Entry> getEntry(String name) {
        // Only for command printing of `premium`; when other fields are not needed, it can be replaced with a direct `getUuid`
        try {
            var f = NameRegistry.class.getDeclaredField("map");
            f.setAccessible(true);
        } catch (Throwable ignored) {
        }
        // Check if registered first, then get UUID
        if (!TrueauthRuntime.NAME_REGISTRY.isRegistered(name)) {
            return Optional.empty();
        }
        NameRegistry.Entry e = new NameRegistry.Entry();
        e.uuid = TrueauthRuntime.NAME_REGISTRY.getUuid(name);
        return Optional.of(e);
    }

    private static void copyIfExists(Path from, Path to) throws Exception {
        if (Files.exists(from)) {
            Files.createDirectories(to.getParent());
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // --- NBT merge: inventory/ender chest ---
    private static void mergePlayerDatNBT(Path premDat, Path offDat, boolean mergeInv, boolean mergeEnder) throws Exception {
        CompoundTag prem, off;
        try (InputStream is = Files.newInputStream(premDat)) {
            prem = NbtIo.readCompressed(is, NbtAccounter.unlimitedHeap());
        }
        try (InputStream is = Files.newInputStream(offDat)) {
            off = NbtIo.readCompressed(is, NbtAccounter.unlimitedHeap());
        }

        boolean changed = false;
        if (mergeInv) {
            changed |= mergeItemListTag(prem, off, "Inventory");
        }
        if (mergeEnder) {
            changed |= mergeItemListTag(prem, off, "EnderItems");
        }

        if (changed) {
            try (OutputStream os = Files.newOutputStream(premDat, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                NbtIo.writeCompressed(prem, os);
            }
        }
    }

    // Merge rule: `premium` takes priority; items in `offline` that do not appear are appended at the end (original slot numbers are not overwritten)
    private static boolean mergeItemListTag(CompoundTag prem, CompoundTag off, String key) {
        if (!prem.contains(key) || !off.contains(key)) return false;
        ListTag premList = prem.getList(key, 10); // 10: CompoundTag
        ListTag offList = off.getList(key, 10);
        // Use the slot number as the primary key
        Set<Integer> premSlots = new HashSet<>();
        for (int i = 0; i < premList.size(); ++i) {
            CompoundTag tag = premList.getCompound(i);
            if (tag.contains("Slot")) premSlots.add((int) tag.getByte("Slot"));
        }
        boolean changed = false;
        for (int i = 0; i < offList.size(); ++i) {
            CompoundTag tag = offList.getCompound(i);
            if (tag.contains("Slot")) {
                int slot = tag.getByte("Slot");
                if (!premSlots.contains(slot)) {
                    premList.add(tag.copy());
                    changed = true;
                }
            }
        }
        if (changed) {
            prem.put(key, premList);
        }
        return changed;
    }

    // --- advancements merge: union ---
    private static void mergeAdvancementsJson(Path premAdv, Path offAdv) throws Exception {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        JsonObject prem, off;
        try (Reader r = Files.newBufferedReader(premAdv)) {
            prem = gson.fromJson(r, JsonObject.class);
        }
        try (Reader r = Files.newBufferedReader(offAdv)) {
            off = gson.fromJson(r, JsonObject.class);
        }
        boolean changed = false;
        for (String key : off.keySet()) {
            if (!prem.has(key)) {
                prem.add(key, off.get(key));
                changed = true;
            }
        }
        if (changed) {
            try (Writer w = Files.newBufferedWriter(premAdv, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING)) {
                gson.toJson(prem, w);
            }
        }
    }

    // A more robust implementation of mergeStatsJson that handles differences in JsonElement types and avoids ClassCastException
    private static void mergeStatsJson(Path premStats, Path offStats) throws Exception {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        JsonObject prem, off;
        try (Reader r = Files.newBufferedReader(premStats)) {
            prem = gson.fromJson(r, JsonObject.class);
        }
        try (Reader r = Files.newBufferedReader(offStats)) {
            off = gson.fromJson(r, JsonObject.class);
        }
        boolean changed = false;

        for (String cat : off.keySet()) {
            JsonElement offElem = off.get(cat);
            // If the category does not exist in `premium`, copy the entire element directly (regardless of type)
            if (!prem.has(cat)) {
                prem.add(cat, offElem);
                changed = true;
                continue;
            }

            JsonElement premElem = prem.get(cat);

            // Both sides are objects -> merge entry by entry (accumulate numeric values; keep `premium` for non-numeric ones)
            if (offElem.isJsonObject() && premElem.isJsonObject()) {
                JsonObject offCat = offElem.getAsJsonObject();
                JsonObject premCat = premElem.getAsJsonObject();
                for (String key : offCat.keySet()) {
                    JsonElement offVal = offCat.get(key);
                    if (!premCat.has(key)) {
                        premCat.add(key, offVal);
                        changed = true;
                    } else {
                        JsonElement premVal = premCat.get(key);
                        // Attempt to accumulate primitive numeric values
                        if (premVal.isJsonPrimitive() && offVal.isJsonPrimitive()) {
                            JsonPrimitive pPri = premVal.getAsJsonPrimitive();
                            JsonPrimitive oPri = offVal.getAsJsonPrimitive();
                            if (pPri.isNumber() && oPri.isNumber()) {
                                try {
                                    long a = pPri.getAsLong();
                                    long b = oPri.getAsLong();
                                    premCat.addProperty(key, a + b);
                                    changed = true;
                                } catch (Exception ignored) {
                                    // If it cannot be accumulated as a long, keep the original `premium` value
                                }
                            }
                        }
                        // For other types (arrays/objects/non-numeric primitives), prefer keeping `prem` and do not overwrite it
                    }
                }
                prem.add(cat, premCat);
            } else {
                // Types are inconsistent or neither is an object:
                // If both sides are primitives and are numbers, then try to add them together (e.g., for rare numeric statistics)
                if (premElem.isJsonPrimitive() && offElem.isJsonPrimitive()) {
                    JsonPrimitive pPri = premElem.getAsJsonPrimitive();
                    JsonPrimitive oPri = offElem.getAsJsonPrimitive();
                    if (pPri.isNumber() && oPri.isNumber()) {
                        try {
                            long a = pPri.getAsLong();
                            long b = oPri.getAsLong();
                            prem.addProperty(cat, a + b);
                            changed = true;
                        } catch (Exception ignored) {
                            // If it cannot be accumulated, keep `prem`
                        }
                    }
                }
                // In all other cases (types are inconsistent and `prem` already exists), keep `prem` and do not overwrite it
            }
        }

        if (changed) {
            try (Writer w = Files.newBufferedWriter(premStats, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING)) {
                gson.toJson(prem, w);
            }
        }

    }
    
    // TODO: Rewrite to use NameRegistry.removeEntry() instead of PlayerPasswordStorage
    // Password remove command - completely removes player from password storage
    /* private static int cmdPasswordRemove(CommandSourceStack src, String playerName) {
        if (!FMLEnvironment.dist.isDedicatedServer()) {
            src.sendFailure(Component.literal("[TrueAuth] This command is only available on dedicated servers"));
            return 0;
        }
        try {
            PlayerPasswordStorage storage = TrueauthRuntime.PASSWORD_STORAGE;
            if (storage.hasStoredPassword(playerName)) {
                storage.removePlayer(playerName);
                src.sendSuccess(() -> Component.literal("[TrueAuth] Successfully removed player from password storage: " + playerName), false);
                return 1;
            } else {
                src.sendFailure(Component.literal("[TrueAuth] Player " + playerName + " does not have a stored password"));
                return 0;
            }
        } catch (Exception ex) {
            src.sendFailure(Component.literal("[TrueAuth] Failed to remove player from password storage: " + playerName + ": " + ex.getMessage()));
            return 0;
        }
    } */
    
    // Whitelist commands - preserve exact name capitalization
    private static int cmdWhitelistAdd(CommandSourceStack src, String name, boolean premiumOnly) {
        Whitelist whitelist = Whitelist.getInstance();
        Whitelist.Entry existing = whitelist.getWhitelistEntry(name);
        if (existing != null) {
            src.sendFailure(Component.literal("[TrueAuth] Player " + existing.name + " is already whitelisted" + 
                    (existing.premiumOnly ? " (premium only)" : "")));
            return 0;
        }
        if (whitelist.add(name, premiumOnly)) {
            src.sendSuccess(() -> Component.literal("[TrueAuth] Added " + name + " to whitelist" + 
                    (premiumOnly ? " (premium only)" : "")), true);
            return 1;
        }
        src.sendFailure(Component.literal("[TrueAuth] Failed to add " + name + " to whitelist"));
        return 0;
    }
    
    private static int cmdWhitelistRemove(CommandSourceStack src, String name) {
        Whitelist whitelist = Whitelist.getInstance();
        Whitelist.Entry existing = whitelist.getWhitelistEntry(name);
        if (existing == null) {
            src.sendFailure(Component.literal("[TrueAuth] Player " + name + " is not whitelisted"));
            return 0;
        }
        if (whitelist.remove(name)) {
            src.sendSuccess(() -> Component.literal("[TrueAuth] Removed " + existing.name + " from whitelist"), true);
            return 1;
        }
        src.sendFailure(Component.literal("[TrueAuth] Failed to remove " + name + " from whitelist"));
        return 0;
    }
    
    private static int cmdWhitelistList(CommandSourceStack src) {
        List<Whitelist.Entry> entries = Whitelist.getInstance().getWhitelistedEntries();
        if (entries.isEmpty()) {
            src.sendSuccess(() -> Component.literal("[TrueAuth] Whitelist is empty"), false);
            return 0;
        }
        StringBuilder sb = new StringBuilder("[TrueAuth] Whitelist (" + entries.size() + " entries):\n");
        for (Whitelist.Entry entry : entries) {
            sb.append("  - ").append(entry.name);
            if (entry.premiumOnly) {
                sb.append(" (premium only)");
            }
            sb.append("\n");
        }
        final String list = sb.toString();
        src.sendSuccess(() -> Component.literal(list), false);
        return entries.size();
    }
}
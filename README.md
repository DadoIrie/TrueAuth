# TrueAuth

> **Alpha Release** - This mod is currently in alpha stage. Some features may be incomplete or have bugs. Reporting issues on GitHub is highly appreciated and helps achieve a stable full release.

A NeoForge mod that lets offline-mode servers verify premium accounts during login, without the player's access token ever leaving their client.

## What it does

When running a server in offline mode (`online-mode=false`), you normally lose the ability to verify if players actually own the Minecraft accounts they're claiming. TrueAuth fixes this by having the client perform Mojang's `joinServer` verification locally, then having the server check with Mojang's session servers.

If verification succeeds, the player gets their real premium UUID and skin. If it fails or they don't have a premium account, they can still join with an offline UUID after setting up a password.

**Both client and server need this mod installed.**

## For Players

**Important:** This mod requires both the server and client to have it installed. Installing it on your client alone won't do anything for servers without TrueAuth.

**Password prompts:** When you open the multiplayer screen, you will be prompted to set a server password (this is required to access multiplayer). This password is only used for servers running TrueAuth. If you connect to a server without TrueAuth, your stored password won't be used there.

If the server has TrueAuth installed:
- If you have a premium account, you'll get your proper skin and UUID automatically
- If you're on a shared computer, you can set a user password to lock multiplayer access with a password

## Features

### Premium Verification
- Client calls `joinServer` locally - access token never sent to server
- Server verifies with Mojang session servers
- Premium players get their official UUID and skin

### Password Authentication
- All players must set a server password when opening the multiplayer screen (required to access multiplayer)
- This password is used when Mojang verification fails or NoMojang mode is enabled
- Passwords are hashed client-side with SHA-256 before transmission
- Server stores only the hash, never plain text
- Per-server password storage on the client
- Optional user password for shared computers (locks access to multiplayer)

### Identity Protection
- Name Registry tracks which names have verified as premium
- Prevents offline players from using names already claimed by premium accounts
- Prevents premium players from taking names registered to offline players
- Data migration command to merge offline and premium player data

### IP Grace (Optional)
If a player's verification fails but they recently verified successfully from the same IP, they can still get their premium UUID. Useful for handling network hiccups. Should be used cautiously on shared networks.

### Semi-Premium Status
When a known premium player's Mojang verification fails but they authenticate successfully via password, they're marked as "semi-premium". They still get their premium UUID and cached skin, but the notification shows "Semi-Premium" instead of "Premium Mode" to indicate the player identity was confirmed via password rather than a fresh Mojang verification.

### NoMojang Mode
Option to skip Mojang session server verification entirely. Relies on IP grace for premium UUID assignment. Useful when Mojang servers are unreachable.

### Whitelist System
Built-in whitelist that reads from Minecraft's `whitelist.json` and `ops.json`. Can also add players directly via commands with optional "premium only" restriction.

### AuthMe Integration
Optionally integrates with the AuthMe mod to add a password configuration button to the account selection screen.

## Requirements

- Minecraft 1.21.1
- NeoForge 21.1.x
- Java 21
- Server must have `online-mode=false`
- Both client and server must have the mod installed

## Commands

All commands require OP level 3+.

```
/trueauth config nomojang status     - Check if NoMojang mode is enabled
/trueauth config nomojang on/off     - Enable/disable NoMojang mode
/trueauth config nomojang toggle     - Toggle NoMojang mode

/trueauth mojang status              - Test connection to Mojang session servers

/trueauth reload                     - Reload config from disk

/trueauth whitelist add <name>       - Add player to whitelist
/trueauth whitelist add <name> premium  - Add with premium-only restriction
/trueauth whitelist remove <name>    - Remove player from whitelist
/trueauth whitelist list             - List whitelisted players
```

### Link Command (Not Yet Implemented)
The link command for migrating offline player data to premium UUID is currently disabled. It needs to be rewritten to integrate with TrueAuth's password-based authentication system. The old implementation from the forked TrueUUID mod is not compatible with the current architecture.

## Configuration

Config file: `config/trueauth-common.toml`

```toml
[auth]
    # Message shown when joining in offline mode
    offlineFallbackMessage = "Note: You are entering the server in offline mode..."
    
    # Whether to show the long offline message
    showOfflineLongMessage = false
    
    # Notification messages (shown via screen overlay or chat depending on authStateReport)
    offlineTitle = "Offline Mode"
    onlineTitle = "Premium Mode"
    semiPremiumTitle = "Semi-Premium"
    
    # Subtitle variants for different scenarios
    offlineShortSubtitle = "Auth failed"
    offlineShortSubtitleNoMojang = "Disabled Mojang auth"
    offlineShortSubtitleIpGrace = "IP Grace verified"
    onlineShortSubtitle = "Premium verified"
    
    # How to notify players of auth state: "chat" or "screen"
    authStateReport = "chat"
    
    # Only allow offline fallback for names never verified as premium
    allowOfflineForUnknownOnly = true
    
    # IP Grace settings
    recentIpGrace.enabled = true
    recentIpGrace.ttlSeconds = 300    # 5 minutes, recommended 60-600
    
    # NoMojang mode - skip Mojang verification
    nomojang.enabled = false
    mojangReverseProxy = "https://sessionserver.mojang.com"
    
    # Whitelist
    whitelist.enabled = false
    
    # OP command replacement
    op.enabledTrueauthOpChanges = false
    op.premiumOnly = true    # Only premium players can be opped
    
    # Debug logging
    debug = false
```

## How it Works

1. During login (HELLO phase), server sends a custom login query with a nonce
2. Client receives the nonce and calls `joinServer(profile, token, nonce)` locally
3. Client sends back a response with the result and password hash
4. Server contacts Mojang's session server to verify the nonce
5. If verified:
   - Player's profile is replaced with premium UUID
   - Skin properties are injected
   - Player is notified with "Premium Mode" message
6. If not verified:
   - Player can join with offline UUID if they have a password set
   - Known premium players get their premium UUID and are marked as "semi-premium" (see Semi-Premium Status section)
   - Player is notified with "Offline Mode" or "Semi-Premium" message accordingly

## Compatibility

- **Forgified Fabric API**: **Recommended.** If present, uses Fabric networking API instead of mixin-based packet handling. The mixin-based networking can have compatibility issues with other mods, so installing Forgified Fabric API is strongly recommended for stability.
- **AuthMe**: Optional integration for password UI on account selection screen
- **Proxies (Bungee/Velocity)**: Not verified since I have no experience with proxies - will need to take a closer look at it

## Privacy

- Player access tokens never leave the client
- Server only receives a boolean acknowledgment from the client
- Passwords are hashed client-side before transmission
- Server stores only password hashes
- No data is sent to any external services other than Mojang's session servers

## Building

```bash
# Windows
gradlew.bat build

# macOS/Linux
./gradlew build
```

Output: `build/libs/trueauth-<version>-neoforge1.21.1.jar`

## License

GNU LGPL 3.0

## Credits

- Mojang authlib and session API.
- Sponge Mixin.
- ForgeGradle.

---
Based on [TrueAuth](https://github.com/YuWan-030/TrueUUID/tree/1.21) NeoForge 1.21.1 branch by [@YuWan-030](https://github.com/YuWan-030), licensed under LGPL‑3.0<br>
Forked mod maintained by: [@DadoIrie](https://github.com/DadoIrie)
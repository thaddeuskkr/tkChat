# tkChat

tkChat is a Velocity-led Minecraft network chat system for Paper and Fabric servers. Velocity is
the trust boundary: it checks LuckPerms and LibertyBans before routing global channels, server-local
chat, groups, and direct messages. MariaDB stores social state and player preferences. RabbitMQ is
available for multi-proxy fan-out, but is disabled by default for a single Velocity process.

This repository targets every Java Edition release from Minecraft 1.21 through 26.2. It produces:

- One Velocity 4.1 plugin (Java 25)
- Paper 1.21.x, 26.1.x, and 26.2 plugins
- Fabric 1.21.x and 26.1.x family mods, plus an exact-version Fabric 26.2 mod

## Architecture

1. A player sends chat or a tkChat command through Velocity.
2. Velocity evaluates the correct LuckPerms `server` context, link bypass, rate limit, and
   LibertyBans mute cache.
3. Every approved message, including server-local chat, is cancelled in the vanilla pipeline and
   rendered by Velocity as a server-authored message. This gives every channel the same client
   behavior and avoids reportability/signature indicators changing after a backend switch.
4. A single proxy delivers locally; multi-proxy deployments use RabbitMQ so each Velocity instance
   can deliver to its eligible local recipients.
5. Player input is always inserted as a literal Adventure component. MiniMessage is parsed only
   for administrator-controlled formats and LuckPerms metadata.

Messages are visually normal chat but are intentionally server-authored and cannot be reported
through Mojang's signed-chat reporting interface. Velocity still authenticates the sender,
evaluates permissions and mutes, and inserts player input only as literal text.

## Requirements

- Velocity 4.1 on Java 25+
- SignedVelocity on Velocity and every Paper/Fabric backend
- Velocity player-info forwarding configured for production. Fabric needs a compatible forwarding
  solution; SignedVelocity synchronizes chat decisions but does not forward player identities.
- LuckPerms and LibertyBans on Velocity
- MariaDB 10.6+ (enabled by default)
- RabbitMQ (optional; only needed when multiple Velocity processes must fan out chat)
- Java 21 for Minecraft 1.21.x and Java 25 for Minecraft 26.x

The Minecraft 1.21.x Fabric artifact accepts Fabric Loader 0.15.11 and newer. The matching Fabric
API may require a newer loader on later 1.21.x releases; for example, the tested 1.21.11 API requires
Loader 0.17.3. The Minecraft 26.1.x and 26.2 artifacts accept Fabric Loader 0.19.0 and newer. The
current SignedVelocity builds for Minecraft 26.x require Loader 0.19.3, so complete installations
using those builds retain that effective minimum. Newer compatible API and loader versions remain
valid.

LuckPerms and LibertyBans are not required on backend servers. The backend artifacts intentionally
contain no moderation or routing authority.

## Build

The Gradle wrapper provisions the required toolchains automatically:

```bash
./gradlew :core:test :velocity:shadowJar :paper-1.21:jar :paper-26.1:jar :paper-26.2:jar
```

Build a particular Fabric artifact:

```bash
./gradlew :fabric-1-21:remapJar
./gradlew :fabric-26-1:jar
./gradlew :fabric-26-2:jar
```

Build every release artifact with `./gradlew releaseArtifacts`. Release jars are written under the
plugin-version folder, such as `build/releases/0.6.1/`, and include the plugin version in each jar
name, such as `tkChat-Velocity-0.6.1.jar`.
The three Paper family jars share one implementation. The 1.21.x jar is compiled against Paper
1.21, the 26.1.x jar against Paper 26.1.1, and the 26.2 jar against Paper 26.2. Compiling against
the oldest published API in each family prevents accidental use of methods that are unavailable on
an earlier patch release. The test suite also compiles the shared bridge against every published
Paper 1.21 API from 1.21 through 1.21.11; Paper did not publish a 1.21.2 API/server build.
Fabric 1.21 through 1.21.11 share one remapped jar. All twelve former per-version builds produced
the same runtime class and method references; their only meaningful jar difference was the exact
Minecraft version in `fabric.mod.json`. The family jar lists all twelve supported versions there.
Fabric 26.1 through 26.1.2 likewise share one jar compiled against 26.1 and the lowest supported
Fabric API. The three former exact-version jars contained identical runtime classes, so the family
jar bounds its metadata to the three versions that were verified rather than claiming future 26.1
patches automatically.
Fabric 26.x tasks require Gradle itself to run on Java 25; use `JAVA_HOME` for a Java 25 installation
when invoking the complete matrix.

## Modrinth publishing

Publishing is automated by `.github/workflows/publish-modrinth.yml`. When a push to `main` changes
`projectVersion`, the workflow verifies the new version, builds and tests the complete matrix,
creates the matching GitHub tag and release (for example, `v0.6.1`), attaches all 7 jars, and then
publishes every Velocity, Paper, and Fabric artifact to Modrinth. No GitHub release needs to be
created manually.

The release notes are generated from the commit subjects since the previous version tag. Every
entry includes its short commit SHA linked to the commit, followed by a link to the full diff. The
first release includes the complete commit history because no earlier tag exists. The exact same
Markdown is used for the GitHub release and every Modrinth version entry. Modrinth entries use
platform-specific identifiers so each jar retains the correct loader, Minecraft version, and
dependency metadata.

Configure these values in the repository's `modrinth` GitHub environment before the first run:

- Secret `MODRINTH_TOKEN`: a Modrinth personal access token with `CREATE_VERSION` permission.
- Variable `MODRINTH_PROJECT_ID`: the Modrinth project ID or slug.

For a local publication, set `MODRINTH_TOKEN` and `MODRINTH_PROJECT_ID`, then run
`./gradlew publishModrinth --no-parallel --no-configuration-cache`.

`projectVersion` in `gradle.properties` is the release version source of truth. Bump it for runtime,
configuration, compatibility, or artifact changes; documentation and release-workflow-only changes
do not require a new plugin version. Gradle writes that value into Velocity's generated plugin
metadata, and `/tkchat` reads it from the metadata at runtime; no Java version constant needs to be
updated manually.

## Live integration verification

The gameplay and single-proxy transport path was exercised on July 20, 2026 using two authenticated Prism Launcher
clients, Velocity 4.1.0-SNAPSHOT build 9, two Fabric 26.2 backends, Fabric API 0.155.2+26.2,
FabricProxy-Lite 2.12.0, the local SignedVelocity 1.4.2-SNAPSHOT proxy/Fabric artifacts,
LibertyBans 1.2.0-M1-SNAPSHOT. RabbitMQ was disabled.

The controlled run verified global chat in both directions between different backends, local-chat
server isolation, direct messages and replies, group create/invite/accept/chat across backends,
and LibertyBans mute rejection. FabricProxy-Lite was
first run with its default configuration to generate `config/FabricProxy-Lite.toml`; both backends
then used Velocity modern forwarding with the same generated forwarding secret,
`hackOnlineMode = true`, and `hackMessageChain = true`. Both clients retained their forwarded
Mojang identities, switched backends without a public-key or message-chain rejection, and rendered
the local route only on the correct backend after the switch. Persistent records used the
forwarded Mojang UUIDs. All current channel deliveries are intentionally rendered as server-authored
messages. The MariaDB repository has separate live integration coverage for schema creation, complete
login snapshots, password and invitation rules, atomic disband cleanup, and concurrent membership
constraints.

## Installation

1. Put `tkChat-Velocity-<version>.jar` on Velocity.
2. Put the matching Paper-family, Fabric family, or exact-version Fabric 26.2 artifact on
   every backend.
3. Keep SignedVelocity installed on the proxy and all backends.
4. Start Velocity once to generate `plugins/tkchat/config.yml`.
5. Configure MariaDB in `mariadb`, preferably by supplying credentials through
   `TKCHAT_MARIADB_URL`, `TKCHAT_MARIADB_USERNAME`, and `TKCHAT_MARIADB_PASSWORD`.
6. Leave RabbitMQ disabled for one Velocity process, or configure a unique `instance-id` and
   RabbitMQ URI for a multi-proxy network.
7. Restart Velocity. The required InnoDB tables, keys, and indexes are created automatically.

Use a unique RabbitMQ `instance-id` per Velocity process. Each instance receives its own queue;
sharing a queue name would load-balance messages instead of broadcasting them.

## Permissions

tkChat denies by default. Grant the nodes appropriate for each group:

Permission names are fixed in code rather than configurable. LuckPerms normalizes nodes to
lowercase, so the documented form is `tkchat` even though the plugin name is styled `tkChat`.

| Pattern | Purpose |
|---|---|
| `tkchat.command.<command>` | Use a tkChat command |
| `tkchat.command.channel.others` | Change another online player's active channel |
| `tkchat.channel.<channel>.send` | Send to a configured channel |
| `tkchat.channel.<channel>.receive` | Receive a configured channel |
| `tkchat.format.<format>` | Use an allowed MiniMessage style in the player's own messages |
| `tkchat.bypass.ratelimit` | Ignore the chat rate limit |
| `tkchat.bypass.links` | Include clickable URLs |
| `tkchat.bypass.private_groups` | Join private groups without an invite or password |
| `tkchat.bypass.group_join_notifications` | Join groups without notifying their members |
| `tkchat.bypass.channel_restrictions` | Ignore channel and group send/receive restrictions |
| `tkchat.bypass.chat_clear` | Keep chat history when `/clearchat` is used |

Version 0.6.0 renames the former `tkchat.channels.<channel>.*` nodes to
`tkchat.channel.<channel>.*`; update existing LuckPerms grants when upgrading.

Command nodes are `channel`, `message`, `reply`, `me`, `group`, `groupchat`, `ignore`, `dmtoggle`,
`broadcast`, `clearchat`, `socialspy`, and `reload`. Aliases and `/tkchat` subcommands use their
canonical command's permission.

Player MiniMessage formatting permissions are:

- Decorations: `bold`, `italic`, `underlined`, `strikethrough`, and `obfuscated`.
- Named colors: `black`, `dark_blue`, `dark_green`, `dark_aqua`, `dark_red`, `dark_purple`,
  `gold`, `gray`, `dark_gray`, `blue`, `green`, `aqua`, `red`, `light_purple`, `yellow`, and
  `white`. MiniMessage's `grey` and `dark_grey` aliases use the corresponding `gray` permission.
- Other visual formats: `hex`, `gradient`, `transition`, `rainbow`, `pride`, `shadow`, `font`,
  `reset`, and `newline`.

For example, `<red>`, `<color:red>`, and `<c:red>` require `tkchat.format.red`; color arguments
inside gradients, transitions, and shadows also require their matching named-color or `hex`
permission. Standard decoration aliases such as `<b>`, `<i>`, `<em>`, `<u>`, `<st>`, and `<obf>`
use their canonical permission. LuckPerms administrators can grant every style with
`tkchat.format.*`.

Behavior/content tags are deliberately unavailable in player messages: click, hover, insertion,
selector, score, NBT, translation, keybind, sprite, and head tags remain visible as literal input.

Example:

```text
/lp group default permission set tkchat.command.channel true
/lp group default permission set tkchat.command.message true
/lp group default permission set tkchat.command.reply true
/lp group default permission set tkchat.command.me true
/lp group default permission set tkchat.command.group true
/lp group default permission set tkchat.command.groupchat true
/lp group default permission set tkchat.command.ignore true
/lp group default permission set tkchat.command.dmtoggle true
/lp group default permission set tkchat.channel.global.send true
/lp group default permission set tkchat.channel.global.receive true
/lp group default permission set tkchat.channel.local.send true
/lp group default permission set tkchat.channel.local.receive true
/lp group default permission set tkchat.channel.group.send true
/lp group default permission set tkchat.channel.group.receive true
/lp user tkkr permission set tkchat.format.* true
```

Configured channels live under `channels` in `plugins/tkchat/config.yml`. Each channel controls its
ID, command aliases, display name, network/server scope, and MiniMessage format. Its permission
nodes are derived from the ID. Grant `tkchat.bypass.channel_restrictions` and
`tkchat.bypass.private_groups` to administrators who should bypass locked channels and private
group access.

Command responses are MiniMessage templates in `plugins/tkchat/messages.yml`, which is generated
with sensible defaults on first startup. This includes usage and error messages, permission and
moderation denials, group notifications, and group action-button labels and hover text. Runtime
values such as player, group, and channel names are inserted as literal text, so they cannot inject
formatting into an administrator-defined response. The prefix prepended to every response remains
alongside the other presentation settings at `formats.response-prefix` in `config.yml`; its default
is a colored `tkChat » `, and setting it to an empty string disables it. When an upgrade adds a new
response, an existing `messages.yml` uses the bundled default for that missing key while retaining
all of its configured overrides.

The active backend name is explicitly added as LuckPerms' `server` context. World, dimension,
gamemode, region, and other backend-only contexts are not inferred by the proxy in this release.

## Commands

- `/tkchat` shows the running tkChat version and points to the help command.
- `/tkchat help` lists the usage of only the root commands the sender has permission to use;
  `/tkchat help <command>` shows that command's description, usage, standalone commands, and
  permission node.
- `/tkchat <command>` provides every full command under a stable plugin-owned root. It does not
  expose short command aliases as subcommands, but `/tkchat channel <channel> [player]` accepts
  configured channel aliases such as `g` and `l`.
- `/tkchat reload` reloads the Velocity configuration (`tkchat.command.reload`).
- `/channel [channel] [player]` (`/ch`); changing another player's channel requires
  `tkchat.command.channel.others`
- `/<channel> [message]` and any configured alias, such as `/g` or `/l`; an omitted message
  switches channel and a supplied message sends once without switching
- `/msg <player> <message>` (`/tell`, `/w`, `/message`)
- `/reply <message>` (`/r`)
- `/me <action>`; sends to the active channel and uses `formats.me`
- `/group` (show the current group's owner, visibility, members, and pending invitees)
- `/group create <name> [password]` (no password creates a public group; providing one creates a private group)
- `/group list` (list public groups and their owners; `tkchat.bypass.private_groups` also reveals
  private groups and their visibility)
- `/group join <name> [password]` (notifies all online members when the player joins)
- `/group invite <player>` (any member can invite; the invite includes the current members)
- `/group accept <name>` (invite messages include a clickable accept button and joining notifies
  all online members)
- `/group leave`
- `/group chat <message>`
- `/groupchat [message]` (`/gc`, `/pc`); an omitted message switches to the group channel
- `/ignore <player>` (`/block`)
- `/dmtoggle`
- `/broadcast <message>` (`/bc`)
- `/clearchat <channel>` (channel aliases such as `g` and `l` are accepted)
- `/socialspy [on|off]` (`/spy`)

Full root-command examples include `/tkchat channel global`, `/tkchat channel g`,
`/tkchat local [message]`, `/tkchat message <player> <message>`, `/tkchat me <action>`,
`/tkchat broadcast <message>`, and `/tkchat clearchat <channel>`. Standalone commands and their
aliases remain available when another plugin has not claimed them.

Group join notices are sent to the group's currently online members. Grant
`tkchat.bypass.group_join_notifications` for silent joins. A private-group join made through
`tkchat.bypass.private_groups` is also silent.

Reloading applies channels, channel command aliases, the default channel, chat limits and rate
limits, formats (including the response prefix), `messages.yml`, mentions, item links, clear-chat
settings, SignedVelocity enforcement, and the LibertyBans fail-closed setting. Players whose
selected channel was removed are moved to the new default channel. Changes to `instance-id`,
`mariadb`, or `rabbitmq` are validated but require a Velocity restart because they own long-lived
storage or transport connections; the command reports those sections after an otherwise successful
reload.

The proxy also intercepts `/minecraft:msg`, `/minecraft:tell`, `/minecraft:w`, and
`/minecraft:me`, preventing a namespaced vanilla bypass.

Group names are unique case-insensitively and match `[A-Za-z0-9_-]{1,32}`. A member's group appears
in `/channel` under its normalized name, so `/channel builders` switches normal chat into the
`Builders` group. Private-group passwords are stored only as salted PBKDF2-SHA256 hashes;
invitations bypass the password and expire after five minutes. MariaDB enforces unique normalized
group names and one group membership per player. Group creation, joining, leaving, disbanding,
invitation consumption, and affected active-channel repairs are transactional. tkChat also records
each player's latest username during login, allowing group rosters to retain names when players are
offline without relying on LuckPerms' user cache; offline roster entries are marked explicitly.

## Chat features

- Broadcasts and chat clearing fan out through the network transport. Clearing a global channel
  clears chat for every player on the network; clearing a server-scoped channel only clears chat
  for players on the command sender's current backend. Because Minecraft uses one chat history,
  clearing a channel also removes the recipient's visible messages from other channels.
- Ignores apply to channel, group, and direct chat. Staff mutes remain LibertyBans' responsibility;
  tkChat uses LibertyBans' cached mute lookup for every routed player message.
- `/me` actions follow the sender's active static or group channel, so they inherit that route's
  scope, permissions, ignores, moderation, rate limit, mentions, item links, and URL handling. The
  `formats.me` MiniMessage template accepts `<prefix>`, `<name>`, `<suffix>`, `<target>` (also
  `<channel>`), and `<message>`.
- Case-insensitive `@Username` mentions can highlight the recipient's name and play a configurable
  sound. Mention styling and sound settings live under `mentions`.
- `<item>` and `[item]` link the sender's main-hand item. The Velocity plugin asks the Paper or
  Fabric bridge for its identifier, amount, and display name, then renders a hoverable
  item component. Placeholders, visible format, and timeout are configurable under `item-links`.
- Social spy is a per-session toggle that shows eligible staff channel, group, and direct messages
  they would not normally receive.
- Join and leave announcements are published after the initial backend connection succeeds.
  `formats.join` and `formats.leave` reach only players on that backend; `formats.global-join` and
  `formats.global-leave` are network-wide. All four accept MiniMessage plus `<name>` and `<server>`,
  and an empty format disables that announcement. When local and global formats are both enabled,
  the local format replaces the global one on the player's backend so nobody sees a duplicate. The
  joining player does not receive their own join message. Local leave/join messages also fire for
  the old/new backend during a server switch, while global messages only fire when entering or
  leaving the proxy. The Paper and Fabric bridges suppress the corresponding vanilla messages.
- Channel, group, action, direct-message, broadcast, clear, social-spy, join/leave, mention, and
  item-link presentation remain customizable with MiniMessage formats in the Velocity config.
  Backend configuration stays limited to backend-local concerns.

## Upgrade compatibility

Existing 0.3.x `config.yml` files do not need migration: omitted additive settings such as
`formats.me` receive their in-code default, and tkChat does not rewrite the file. Existing
`messages.yml` overrides remain authoritative while newly introduced response keys fall back to
their bundled defaults. `/me` uses a marker inside the existing serialized message envelope rather
than adding a new network message type, so mixed 0.3.x proxies can deserialize it during a rolling
upgrade; an older proxy degrades gracefully by showing it with the channel's ordinary format.

Discord integration, runtime-created custom channels, multi-language messages, and proximity chat
are intentionally outside the current scope.

## Failure behavior

- LibertyBans is fail-closed by default: a failed mute lookup rejects the message.
- MariaDB startup failure prevents tkChat from registering chat listeners unless
  `mariadb.fallback-to-memory` is explicitly enabled.
- Login waits for one complete transactional social-state snapshot. If that read fails, chat and
  state-changing chat commands fail closed for that player while background retries run; broadcasts
  and chat clearing still work. Disconnect/reconnect generations prevent a late read from restoring
  an old session.
- RabbitMQ may fall back to delivery inside the current Velocity process. Disable
  `rabbitmq.fallback-to-local` when a multi-proxy network should fail closed instead.
- Live RabbitMQ messages expire after 60 seconds to avoid replaying stale chat after downtime.

## Multi-proxy note

Global channels and already-addressed group messages fan out across multiple Velocity instances.
Name lookup for `/msg` and `/group invite` currently searches the current Velocity process, which
matches the single-proxy topology this project was designed for. A shared presence directory is the
remaining requirement before those two commands can target a player connected to another proxy.

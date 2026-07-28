# tkChat

tkChat is a Velocity-led chat system for Paper, Purpur, and Fabric networks. It provides global and
server-local chat, groups, direct messages, configurable formatting, LuckPerms permissions, and
LibertyBans mute enforcement from one proxy plugin.

## Features

- Configurable global and server-local channels, plus one-off sends without changing channel
- Persistent public/private groups, group invitations, direct messages, replies, ignores, and social spy
- MiniMessage formats, LuckPerms prefixes/suffixes, clickable links, mentions, item links, and coordinates
- Network broadcasts, channel-aware chat clearing, `/me`, and configurable player notifications
- MariaDB-backed social state and optional RabbitMQ fan-out for multi-proxy networks

Messages are rendered as server-authored chat. They look like normal chat but cannot be reported through
Mojang's signed-chat reporting interface.

## Requirements

- Velocity 4.1 running Java 25 or newer
- SignedVelocity on Velocity and every backend
- LuckPerms and LibertyBans on Velocity; neither is required on the backends
- MariaDB 10.6 or newer by default
- Correct Velocity player-info forwarding for production networks
- RabbitMQ only when chat must fan out across multiple Velocity processes

Fabric backends also need Fabric API and FabricProxy-Lite. SignedVelocity synchronizes chat decisions;
it does not forward player identities, so configure FabricProxy-Lite with the same modern-forwarding
secret as Velocity.

| Backend | tkChat artifact | Java | Additional requirement |
| --- | --- | --- | --- |
| Paper/Purpur 1.21-1.21.11 | `tkChat-Paper-1.21.x-<version>.jar` | 21+ | Paper-compatible server |
| Paper/Purpur 26.1.1-26.1.2 | `tkChat-Paper-26.1.x-<version>.jar` | 25+ | There was no upstream Paper 26.1 build |
| Paper/Purpur 26.2 | `tkChat-Paper-26.2-<version>.jar` | 25+ | Paper-compatible server |
| Fabric 1.21-1.21.11 | `tkChat-Fabric-1.21.x-<version>.jar` | 21+ | Fabric Loader 0.15.11+ and a matching Fabric API |
| Fabric 26.1-26.1.2 | `tkChat-Fabric-26.1.x-<version>.jar` | 25+ | Fabric Loader 0.19.0+ and a matching Fabric API |
| Fabric 26.2 | `tkChat-Fabric-26.2-<version>.jar` | 25+ | Fabric Loader 0.19.0+ and a matching Fabric API |

Some SignedVelocity 26.x builds require Fabric Loader 0.19.3, which then becomes the effective minimum.

## Installation

1. Download the release from Modrinth or [GitHub Releases](https://github.com/thaddeuskkr/tkChat/releases).
2. Put `tkChat-Velocity-<version>.jar` and SignedVelocity in Velocity's `plugins` directory.
3. Put the matching tkChat backend artifact and SignedVelocity on every Paper/Purpur or Fabric backend.
4. On Fabric, also install Fabric API and FabricProxy-Lite and configure modern forwarding.
5. Install LuckPerms and LibertyBans on Velocity.
6. Start Velocity once to generate `plugins/tkchat/config.yml` and `plugins/tkchat/messages.yml`.
7. Configure the `mariadb` section. Credentials can instead be supplied with
   `TKCHAT_MARIADB_URL`, `TKCHAT_MARIADB_USERNAME`, and `TKCHAT_MARIADB_PASSWORD`.
8. Restart Velocity. tkChat creates its InnoDB tables, keys, and indexes automatically.

RabbitMQ is disabled by default and is unnecessary for a single Velocity process. For multiple proxies,
enable it, set `TKCHAT_RABBITMQ_URI` or `rabbitmq.uri`, and give every proxy a unique `instance-id`.
Sharing an instance ID causes messages to be load-balanced instead of delivered to every proxy.

`/tkchat reload` reloads channels, aliases, formats, messages, chat limits, mentions, item links,
coordinates, notifications, and moderation settings. Changes to `instance-id`, `mariadb`, or `rabbitmq`
require a Velocity restart.

## Commands

Every command is also available below the stable `/tkchat` root. For example, `/msg Steve hello`
can be run as `/tkchat message Steve hello`. Configured short channel aliases such as `/g` remain
standalone commands and are not `/tkchat` subcommands.

| Command and arguments | Aliases | Purpose |
| --- | --- | --- |
| `/tkchat` | - | Show the running version |
| `/tkchat help [command]` | - | Show commands available to the sender or detailed command help |
| `/tkchat reload` | - | Reload the Velocity configuration |
| `/channel [channel] [player]` | `/ch` | View or change an active channel; `player` targets another online player |
| `/<channel> [message]` | Configured per channel, such as `/g` and `/l` | Switch channel, or send once when `message` is supplied |
| `/msg <player> <message>` | `/tell`, `/w`, `/message` | Send a direct message |
| `/reply <message>` | `/r` | Reply to the last direct-message conversation |
| `/me <action>` | - | Send an action to the active channel |
| `/group` | `/party` | Show the current group, members, owner, visibility, and pending invitations |
| `/groupchat [message]` | `/gc`, `/pc` | Switch to group chat, or send once when `message` is supplied |
| `/ignore <player>` | `/block` | Toggle ignoring a player |
| `/dmtoggle` | - | Toggle incoming direct messages |
| `/broadcast <message>` | `/bc` | Broadcast across the network |
| `/clearchat <channel>` | - | Clear chat for a channel; channel aliases are accepted |
| `/socialspy [on\|off]` | `/spy` | Toggle social spy or choose its state explicitly |

### Group commands

- `/group create <name> [password]` creates a public group without a password or a private group with one.
- `/group list` lists public groups. `tkchat.bypass.private_groups` also reveals private groups.
- `/group join <name> [password]` joins a group and notifies its online members.
- `/group invite <player>` invites an online player; any member can invite.
- `/group accept <name>` accepts an unexpired invitation without requiring the password.
- `/group leave` leaves the current group.
- `/group chat <message>` sends one group message.

Group names are case-insensitively unique and must match `[A-Za-z0-9_-]{1,32}`. Invitations expire
after five minutes. A member can also select their group by name with `/channel <group>`.

The proxy blocks `/minecraft:msg`, `/minecraft:tell`, `/minecraft:w`, and `/minecraft:me` so the
namespaced vanilla commands cannot bypass tkChat moderation.

## Permissions

tkChat denies access by default. Permission names are fixed, lowercase LuckPerms nodes.

### Commands and channels

| Permission | Purpose |
| --- | --- |
| `tkchat.command.<command>` | Use a command; canonical names are listed below |
| `tkchat.command.channel.others` | Change another online player's active channel |
| `tkchat.channel.<channel>.send` | Send to a configured channel |
| `tkchat.channel.<channel>.receive` | Receive a configured channel |

Canonical command names are `channel`, `message`, `reply`, `me`, `group`, `groupchat`, `ignore`,
`dmtoggle`, `broadcast`, `clearchat`, `socialspy`, and `reload`. Aliases and `/tkchat` subcommands use
the canonical command permission.

Channel permissions are derived from the configured channel ID. For example, the default global
channel uses `tkchat.channel.global.send` and `tkchat.channel.global.receive`.

### Bypasses

| Permission | Purpose |
| --- | --- |
| `tkchat.bypass.ratelimit` | Ignore the chat rate limit |
| `tkchat.bypass.links` | Make URLs clickable |
| `tkchat.bypass.private_groups` | View and join private groups without an invitation or password |
| `tkchat.bypass.group_join_notifications` | Join groups without notifying members |
| `tkchat.bypass.global_player_notifications` | Always receive network join/leave and server-switch notices |
| `tkchat.bypass.channel_restrictions` | Ignore channel and group send/receive restrictions |
| `tkchat.bypass.chat_clear` | Keep chat history when `/clearchat` is used |

### Player formatting

`tkchat.format.<format>` allows a MiniMessage style in the player's own messages:

- Decorations: `bold`, `italic`, `underlined`, `strikethrough`, `obfuscated`
- Named colors: `black`, `dark_blue`, `dark_green`, `dark_aqua`, `dark_red`, `dark_purple`, `gold`,
  `gray`, `dark_gray`, `blue`, `green`, `aqua`, `red`, `light_purple`, `yellow`, `white`
- Other visual formats: `hex`, `gradient`, `transition`, `rainbow`, `pride`, `shadow`, `font`,
  `reset`, `newline`

`grey` and `dark_grey` use the corresponding `gray` permissions. Tag aliases such as `<b>`, `<i>`,
`<em>`, `<u>`, `<st>`, and `<obf>` use their canonical permission. Grant all player styles with
`tkchat.format.*`.

For example, `<red>`, `<color:red>`, and `<c:red>` require `tkchat.format.red`. Colors used inside
gradients, transitions, and shadows also require their named-color permission or `tkchat.format.hex`.

Player messages cannot use behavior/content tags such as click, hover, insertion, selector, score,
NBT, translation, keybind, sprite, or head. Disallowed tags are displayed literally.

Example starter permissions:

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
```

When upgrading from versions before 0.6.0, replace `tkchat.channels.<channel>.*` grants with
`tkchat.channel.<channel>.*`.

LuckPerms checks include the active backend as the `server` context. Backend-only contexts such as
world, dimension, gamemode, and region are not inferred by the proxy.

## Formatting and configuration

The main settings are in `plugins/tkchat/config.yml`. Command responses are MiniMessage templates in
`plugins/tkchat/messages.yml`. Missing settings and response keys use the bundled defaults without
overwriting existing files.

Configured channels live under `channels` and define an `id`, aliases, display name, `GLOBAL` or
`SERVER` scope, and MiniMessage format. Channel, group, direct-message, `/me`, broadcast, chat-clear,
social-spy, and player-notification formats are under `formats`.

| Format | Placeholders |
| --- | --- |
| Channel, group, and `/me` | `<prefix>`, `<name>`, `<user>`, `<suffix>`, `<target>`/`<channel>`, `<server>`, `<message>` |
| Direct incoming/outgoing | `<name>`, `<target>`, `<message>` |
| Broadcast | `<message>` |
| Chat clear | `<name>`, `<target>` |
| Social spy | `<name>`, `<target>`, `<message>` |
| Local/global join and leave | `<name>`, `<server>` |
| Server switch | `<user>`/`<name>`, `<old_server>`, `<new_server>` |
| Item-link format | `<amount>`, `<item_name>` |
| Coordinate format | `<x>`, `<y>`, `<z>`, `<world>`, `<server>` |

Administrator-controlled formats and LuckPerms prefixes/suffixes may use MiniMessage. Player input,
names, group names, and other runtime values are inserted literally and cannot inject formatting.
Set `formats.response-prefix` to an empty string to remove the prefix from command responses.

### Mentions, items, and coordinates

- `@Username` mentions are case-insensitive, work across routed channels and broadcasts, and can
  highlight the recipient and play a configurable sound. Configure them under `mentions`.
- `<item>` and `[item]` show the sender's main-hand item with hover details. Configure placeholders,
  display format, and response timeout under `item-links`.
- `<coords>` and `[coords]` insert the sender's block coordinates. Configure placeholders, display
  format, and response timeout under `coordinates`.

Item and coordinate placeholders apply to channel, group, direct, `/me`, and broadcast messages.

### Player notifications

`notifications.local-join`, `local-leave`, `global-join`, and `global-leave` explicitly enable or
disable their corresponding formats. Local notices reach the affected backend; global notices reach
the network when a player enters or leaves the proxy. If both are enabled, ordinary viewers on the
affected backend see only the local notice.

Viewers with `tkchat.bypass.global_player_notifications` receive the global notice even when its
toggle is disabled, and receive it instead of the local notice on the affected backend. They also
receive one `formats.server-switch` summary for server changes. Ordinary viewers never receive the
switch summary; if local notices are enabled, they see the usual leave on the old server and join on
the new server.

## Multi-proxy limitation

RabbitMQ fans out global and already-addressed group messages. `/msg` recipient lookup and
`/group invite` currently search only the sender's Velocity process, so they cannot target a player
connected through another proxy without a shared presence directory.

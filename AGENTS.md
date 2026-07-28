# Repository guidance for coding agents

This file contains maintainer and implementation guidance. Keep `README.md` focused on installation,
requirements, commands, permissions, configuration, and player-visible behavior.

## Start with the repository

- Read the relevant implementation, tests, generated resource templates, and shared build files before
  proposing or making a change.
- Treat `velocity/src/main/resources/config.yml` and `messages.yml` as the documented default behavior.
- Treat `Permissions.java`, `CommandRegistrar.java`, and the platform build scripts as the source of truth
  for permissions, commands, artifacts, and platform compatibility.
- Preserve unrelated working-tree changes. Use the smallest ownership-aware change that solves the issue.

## Module layout

- `core`: platform-neutral models, routing, policy, groups, rate limiting, and repository contracts.
- `velocity`: authority, command registration, configuration, state loading, formatting, delivery,
  LuckPerms/LibertyBans integration, MariaDB, and RabbitMQ.
- `paper-platform`: shared thin Paper/Purpur backend bridge.
- `fabric-platform`: shared thin Fabric bridge, with `mc_1_21` and `modern` version-family source sets.
- `paper-targets` and `fabric-targets`: lightweight target projects using the shared platform builds.

## Architecture invariants

- Velocity is the trust boundary. It owns validation, authorization, mutes, rate limits, routing,
  presentation, social state, and transport. Do not move independent authority to a backend bridge.
- Backends only intercept native chat, coordinate with SignedVelocity, suppress duplicate vanilla output,
  and return trusted backend-local data such as held items and coordinates.
- All approved messages, including local chat, are rendered as server-authored Adventure components.
  Do not reintroduce mixed signed/local rendering without an explicit design change and end-to-end tests.
- Never parse player-controlled text, player names, group names, or other runtime values as MiniMessage.
  Insert them as literal/unparsed or component placeholders. Only administrator-controlled templates and
  trusted LuckPerms metadata may supply formatting.
- Velocity awaiting events and storage/moderation calls must remain asynchronous. Do not block the proxy
  event thread; resume through continuations/completion stages.
- Channel, group, direct, action, and broadcast messages are logged once on their originating Velocity
  process. RabbitMQ consumers must not duplicate that console log. Lifecycle notices and chat-clear
  control messages are intentionally excluded.

## Session and wire safety

- A stale disconnect from a replaced same-UUID connection must not clean up the new session. Before any
  disconnect cleanup, confirm that `proxy.getPlayer(uuid)` is the same `Player` instance as the event.
- Guard asynchronous login/state results with the current connection generation. A late result must not
  restore or remove another session.
- `ApprovedMessage` and related records are transport contracts. Preserve mixed-version deserialization
  where practical and run serialization tests for changes.
- Be careful when adding JavaBean-style `is...()` accessors to transported/configuration models: Jackson
  may expose them as new serialized properties.

## Configuration compatibility

- Existing `config.yml` and `messages.yml` files are user-owned. Do not rewrite them during upgrades.
- Additive configuration must have safe in-code defaults. Missing response keys fall back to bundled
  `messages.yml` values while configured overrides remain authoritative.
- Use explicit booleans for feature enablement. Do not treat an empty format as an enable/disable toggle.
- Preserve the lifecycle-notification migration for old configs that predate `notifications`.
- Reloadable settings may change at runtime; `instance-id`, MariaDB, and RabbitMQ own long-lived resources
  and require a restart. Keep reload transactional so invalid replacements leave the old runtime active.
- Permission names are fixed lowercase nodes. Channel permissions derive from channel IDs.

## Player-notification behavior

- Global join/leave means entering or leaving the proxy. Local join/leave means entering or leaving a
  backend, including ordinary views of a server switch.
- When local and global are both enabled, ordinary viewers on the affected backend get the local notice,
  not a duplicate global notice.
- `tkchat.bypass.global_player_notifications` always grants the global join/leave view even when its toggle
  is false. On the affected backend it replaces, rather than supplements, the local notice.
- On server switches, permitted viewers receive one `formats.server-switch` message. Ordinary viewers must
  never receive it and continue to receive only enabled local leave/join messages.

## Platform and artifact matrix

Gradle must run on Java 25 for the complete build. Java toolchains compile the 1.21 targets for Java 21.

| Target | Compiled against | Advertised Minecraft versions | Artifact task |
| --- | --- | --- | --- |
| Velocity | Velocity 4.1 | Proxy | `:velocity:shadowJar` |
| Paper 1.21.x | oldest Paper 1.21 API | 1.21-1.21.11 | `:paper-1.21:jar` |
| Paper 26.1.x | Paper 26.1.1 | 26.1.1-26.1.2 | `:paper-26.1:jar` |
| Paper 26.2 | Paper 26.2 | 26.2 | `:paper-26.2:jar` |
| Fabric 1.21.x | Minecraft 1.21 family source set | 1.21-1.21.11 | `:fabric-1-21:remapJar` |
| Fabric 26.1.x | Minecraft 26.1 modern source set | 26.1-26.1.2 | `:fabric-26-1:jar` |
| Fabric 26.2 | Minecraft 26.2 modern source set | 26.2 | `:fabric-26-2:jar` |

Paper did not publish an exact 26.1 server/API artifact, so never advertise the Paper 26.1.x jar for
exact 26.1. Compile shared family bridges against the oldest supported API and retain compatibility
checks that prevent accidental use of newer methods.

Generated metadata is part of compatibility verification:

- Check `paper-plugin.yml` for the correct plugin and API version.
- Check `fabric.mod.json` for Minecraft bounds, Java, Loader, Fabric API, and server-only environment.
- Inspect release jars, not only Gradle properties, after compatibility or version changes.

## Building and verification

Use the wrapper. Useful focused commands are:

```bash
./gradlew :core:test :velocity:test
./gradlew :velocity:shadowJar :paper-1.21:jar :paper-26.1:jar :paper-26.2:jar
./gradlew :fabric-1-21:remapJar :fabric-26-1:jar :fabric-26-2:jar
```

Before handing off a release-affecting change, run:

```bash
./gradlew check releaseArtifacts --no-daemon
```

`releaseArtifacts` must place exactly seven deployable jars in `build/releases/<version>/`. Exclude
development and sources jars. For platform-sensitive work, also inspect generated metadata and perform
a realistic backend/proxy startup or gameplay smoke test when feasible; compilation alone is insufficient.

Add focused regression coverage for the changed path and its normal counterpart. In particular, preserve
tests for duplicate-login lifecycle ownership, wire serialization, channel recipient filtering, notification
deduplication/bypass behavior, placeholder transport, and duplicate backend chat suppression.

MariaDB integration tests require an available database and are separate from ordinary unit tests. Do not
embed deployment or database credentials in source, tests, logs, or documentation.

## Integration verification

For chat-routing or backend changes, the useful smoke-test topology is one Velocity proxy, two backends,
and two authenticated clients connected to different backends. Verify at least:

- global delivery in both directions and local-channel isolation;
- server switches, lifecycle-notification deduplication, and the permitted switch summary;
- direct messages/replies, group create/invite/accept/chat, ignores, and LibertyBans mute rejection;
- item and coordinate placeholders on each changed backend family; and
- that Paper/Fabric suppresses the original backend output without client chat-validation errors.

For Fabric, generate FabricProxy-Lite's config first, then use Velocity modern forwarding with the same
secret. The previously working test topology required `hackOnlineMode = true` and
`hackMessageChain = true`. Use forwarded Mojang UUIDs when checking persistent state. RabbitMQ should stay
disabled for a single-proxy smoke test unless transport fan-out is the feature under test.

## Versions and publishing

- `projectVersion` in `gradle.properties` is authoritative. Generated Velocity, Paper, and Fabric metadata
  must all contain it.
- Bump the version for runtime, configuration, compatibility, or artifact changes. Documentation-only and
  release-workflow-only changes do not require a plugin version bump.
- `.github/workflows/publish-modrinth.yml` builds and tests the complete matrix, verifies the version, creates
  the GitHub tag/release, and publishes platform-specific Modrinth entries.
- Release changelogs come from commit subjects since the prior version tag and are shared by GitHub and
  Modrinth.
- `MODRINTH_TOKEN` and `MODRINTH_PROJECT_ID` are supplied by the GitHub `modrinth` environment. Never commit
  them. Local publication uses `./gradlew publishModrinth --no-parallel --no-configuration-cache`.
- Keep Modrinth game versions and loaders exact. Do not use a family label to claim an upstream server build
  that was never released.
- Keep the Modrinth project environment set to client unsupported and server required, displayed by
  Modrinth as “Dedicated servers only.”

## Failure behavior to preserve

- LibertyBans is fail-closed by default. A failed mute lookup rejects chat.
- MariaDB startup failure prevents listener registration unless `mariadb.fallback-to-memory` is enabled.
- Failed login-state loading keeps player chat and state-changing chat commands closed while retries run;
  broadcasts and chat clearing remain available.
- RabbitMQ can fall back to local delivery. When `fallback-to-local` is false, transport failure must remain
  fail-closed for multi-proxy consistency.
- Reject expired queued or transported messages instead of replaying stale chat after an outage.
- Multi-proxy `/msg` and `/group invite` name lookup remains local to one Velocity process until a shared
  presence directory is implemented.

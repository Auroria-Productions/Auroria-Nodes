# Auroria Nodes

Auroria Nodes is Auroria Productions’ fork of Phonon’s Minecraft town and nation plugin, maintained by FwMilzz and Ginjer. Players claim territories, collect local resources, negotiate alliances and fight wars. Staff can referee one-life key-point battles, manage claims and configure how towns earn and spend their income.

[Download the plugin JAR](https://github.com/Auroria-Productions/Auroria-Nodes/releases/download/v1.21-26.3-core-dev.5.11/milzzynodes.jar) · [Download the source kit](https://github.com/Auroria-Productions/Auroria-Nodes/releases/download/v1.21-26.3-core-dev.5.11/Auroria-Nodes-dev.5.11-Source.zip) · [Release notes](https://github.com/Auroria-Productions/Auroria-Nodes/releases/tag/v1.21-26.3-core-dev.5.11)

## What the core includes

| System | In game |
| --- | --- |
| Towns and nations | Town membership, leadership, plots, claims, protected chests, outposts, capitals and ports. |
| Diplomacy | Alliances, truces, vassals, tributaries, independence revolts and peace terms. |
| War | Flag wars, skirmishes, fortifications, territory raids, rallies and staff-run key-point wars. In a key-point battle, death eliminates the fighter for that battle and applies war sickness on respawn. |
| Resources and trade | Resource-bearing territories, town income inventories, mining towers, contracts, refineries, a market, national trade routes, banking, loans and infrastructure upkeep. |
| Player activities | Bounties, supply drops, duels, skills, an event calendar, points of interest, titles and K/D leaderboards. |
| Administration | Permissions, per-town PvP switches, staff chat monitoring, claim and resource management, financial auditing and JSON storage migration. |

## Requirements and installation

1. Use a Paper server and the Java runtime required by that server release. The plugin declares Bukkit API `1.21`. We verified the release JAR on Paper **1.21.11 build 132** and **26.3 build 256** with Java 25. Folia is marked unsupported in the descriptor.
2. Install **Vault** and place `milzzynodes.jar` in `plugins/`. Use a Vault-compatible economy provider for features that withdraw from or credit Vault balances. The JAR bundles the Kotlin standard library.
3. For an upgrade, stop the server, back up `plugins/milzzynodes/` and replace the prior core JAR. Keep a single copy of the core installed. For a new territory map, supply the server’s Nodes `world.json` in the core data folder.
4. Start the server. Run `nodesadmin consolecheck` and `nodesadmin storage status` in the console, then try `/territory` in game on your map.

The public plugin name is `milzzynodes`. Its descriptor also provides `nodes` for integrations. The packaged configuration and the dependency and permission declarations are available below.

[Default config.yml](docs/defaults/config.yml) · [Plugin descriptor and permissions](docs/defaults/plugin.yml)

## Command index

The following 51 names are declared in this build. Select a command for syntax, arguments, aliases and access details. The administration guide lists the full staff command tree.

[Full command reference](docs/COMMANDS.md) · [All /nodesadmin subcommands](docs/ADMIN-COMMANDS.md)

### Towns, nations and travel

| Command | Aliases | What it does |
| --- | --- | --- |
| [`/town`](docs/COMMANDS.md#town) | `/t` | Found a town, recruit residents, assign land and manage its income. |
| [`/nation`](docs/COMMANDS.md#nation) | `/n` | Unite towns, choose a capital, make subject agreements and set taxes. |
| [`/vassal`](docs/COMMANDS.md#vassal) | `/vassals` | Start a revolt against your nation’s overlord. |
| [`/plot`](docs/COMMANDS.md#plot) | `/p` | Select blocks, create plots, transfer ownership and set access rules. |
| [`/port`](docs/COMMANDS.md#port) | — | Travel by port, apply for a port and change access or fees. |
| [`/player`](docs/COMMANDS.md#player) | `/p` | Look up a resident. |
| [`/territory`](docs/COMMANDS.md#territory) | — | Inspect the territory underfoot or by ID. |
| [`/nodes`](docs/COMMANDS.md#nodes) | `/nd` | Look up resources, territories, towns, nations, players and war information. |
| [`/noptions`](docs/COMMANDS.md#noptions) | — | Set minimap orientation, chat preference and battle notifications. |
| [`/map`](docs/COMMANDS.md#map) | — | Open the discoveries menu and display an earned exploration title. |

### Diplomacy and war

| Command | Aliases | What it does |
| --- | --- | --- |
| [`/ally`](docs/COMMANDS.md#ally) | — | Offer or accept an alliance. |
| [`/unally`](docs/COMMANDS.md#unally) | — | End an alliance. |
| [`/war`](docs/COMMANDS.md#war) | — | Declare war or join a participating side. |
| [`/peace`](docs/COMMANDS.md#peace) | — | Offer peace or negotiate surrender. |
| [`/truce`](docs/COMMANDS.md#truce) | — | View current truces and their remaining time. |
| [`/warzone`](docs/COMMANDS.md#warzone) | — | Inspect a warzone or open its administration menu. |
| [`/captime`](docs/COMMANDS.md#captime) | — | Show a node’s chunk capture time. |
| [`/captimes`](docs/COMMANDS.md#captimes) | — | Use the same capture-time lookup as /captime. |
| [`/keywar`](docs/COMMANDS.md#keywar) | `/kpwar`, `/keypointwar` | Propose, join, referee and conclude one-life key-point battles. |
| [`/surrender`](docs/COMMANDS.md#surrender) | — | Move your town into another nation through surrender. |
| [`/raid`](docs/COMMANDS.md#raid) | — | Start, inspect or cancel a territory raid. |

### Income, industry and trade

| Command | Aliases | What it does |
| --- | --- | --- |
| [`/neincome`](docs/COMMANDS.md#neincome) | — | Inspect the sources of a town’s resource income. |
| [`/nemarket`](docs/COMMANDS.md#nemarket) | — | Trade town-market listings, set sanctions and collect sale proceeds. |
| [`/refinery`](docs/COMMANDS.md#refinery) | `/rf`, `/refineries` | Inspect recipes and operate the refinery assigned to a territory. |
| [`/miningtower`](docs/COMMANDS.md#miningtower) | — | Submit a town mining tower and review submissions. |
| [`/miningtowerdebug`](docs/COMMANDS.md#miningtowerdebug) | — | Inspect your town’s approved tower, tier and applied bonuses. |
| [`/resourcetower`](docs/COMMANDS.md#resourcetower) | `/mt` | List, place or remove resource-producing towers. |
| [`/economy`](docs/COMMANDS.md#economy) | `/ne` | Manage accounts, loans, trade routes, infrastructure and the exchange. |
| [`/economyadmin`](docs/COMMANDS.md#economyadmin) | `/nea` | Issue funds or stock, review loans and register infrastructure. |
| [`/minebuff`](docs/COMMANDS.md#minebuff) | — | Apply or clear a global or town-specific mining bonus. |
| [`/minebufftown`](docs/COMMANDS.md#minebufftown) | — | Apply or clear a bonus for one named town. |
| [`/netowncontrol`](docs/COMMANDS.md#netowncontrol) | — | Inspect or toggle availability of selected town commands. |

### Chat and community

| Command | Aliases | What it does |
| --- | --- | --- |
| [`/globalchat`](docs/COMMANDS.md#globalchat) | `/gc` | Select global chat or mute its feed. |
| [`/townchat`](docs/COMMANDS.md#townchat) | `/tc` | Select or leave town chat. |
| [`/nationchat`](docs/COMMANDS.md#nationchat) | `/nc` | Select or leave nation chat. |
| [`/allychat`](docs/COMMANDS.md#allychat) | `/ac` | Select or leave allied chat. |
| [`/localchat`](docs/COMMANDS.md#localchat) | `/lc` | Select or leave proximity chat. |
| [`/rpchat`](docs/COMMANDS.md#rpchat) | `/rp` | Toggle nearby roleplay chat. |
| [`/language`](docs/COMMANDS.md#language) | `/lang` | Set your resident language. |
| [`/report`](docs/COMMANDS.md#report) | — | Send a report to staff. |
| [`/calendar`](docs/COMMANDS.md#calendar) | — | Open the event calendar or read the upcoming schedule. |
| [`/skills`](docs/COMMANDS.md#skills) | — | View skill levels and skill leaderboards. |
| [`/duel`](docs/COMMANDS.md#duel) | — | Challenge a player, pick a kit and wager, or spectate a duel. |

### Rewards and statistics

| Command | Aliases | What it does |
| --- | --- | --- |
| [`/bounty`](docs/COMMANDS.md#bounty) | `/bounties` | Fund a bounty, inspect rewards and collect queued payouts. |
| [`/killshop`](docs/COMMANDS.md#killshop) | — | Spend kill points and manage the item shop. |
| [`/supplydrop`](docs/COMMANDS.md#supplydrop) | `/sd` | Track drops and manage spawn points and weighted loot. |
| [`/nekdr`](docs/COMMANDS.md#nekdr) | `/kdr` | Look up personal kill/death statistics or the top list. |
| [`/stats`](docs/COMMANDS.md#stats) | `/kdr` | Show a player’s kill/death record. |
| [`/leaderboard`](docs/COMMANDS.md#leaderboard) | `/lb` | Rank players, towns, nations and war performance. |

### Server administration

| Command | Aliases | What it does |
| --- | --- | --- |
| [`/nodesadmin`](docs/ADMIN-COMMANDS.md) | `/nda` | Administer towns, territory, diplomacy, events, storage and server operations. |
| [`/neadmin`](docs/COMMANDS.md#neadmin) | — | Legacy extension-reload entry. In dev.5.11 it only prints its usage. |

**Shared aliases.** Two commands declare `/p` (player and plot), and two declare `/kdr` (nekdr and stats). Use `/player`, `/plot`, `/nekdr` or `/stats` to choose explicitly. The release declares `/neadmin reload`, but invoking it returns the usage line. Core reload controls are under `/nodesadmin reload`.

## Configuration and data

The active core configuration is `plugins/milzzynodes/config.yml`. The shipped file groups related settings under labelled sections. Set `war.keyPointWars.enabled` for key-point battles and `war.keyPointWars.warSicknessSeconds` for respawn sickness. Refinery types and recipes use `refinery-1182.yml`. The world topology is stored in `world.json` and settlements in `towns.json`. The `data/` directory holds feature-specific JSON files.

[Browse config.yml](docs/defaults/config.yml) · [Browse the refinery defaults](core/refinery-1182.yml)

## Source and verification

The release includes the original verified plugin JAR and a source ZIP with the active Java changes, the MainNodes and Ginjer source trees, the supplied core base, build dependencies and tests. Run `bash build.sh` from the checkout or extracted source kit.

[Build instructions and verification](docs/BUILDING.md) · [MainNodes source](reference/main-nodes) · [Ginjer source](reference/ginjer-nodes) · [License](LICENSE.md)

# Nodes administration commands

[Repository overview](../README.md) · [All 51 core commands](COMMANDS.md)

Use the full command name when installing alongside other plugins. Angle brackets mark required values, square brackets mark optional values, and a vertical bar separates choices. In the server console, omit the leading slash.

Use `/nodesadmin` or `/nda`. The entry permission is `nodes.helper`. Staff roles and individual actions have additional checks. Many actions support `nodes.admin.<section>` or a narrower action-specific permission. For one-life battles, staff use the separate `/keywar` root with `nodes.keywar.staff`.

[Permission declarations](defaults/plugin.yml) · [Key-point war commands](COMMANDS.md#keywar)

## Server operations

| Command | What it does |
| --- | --- |
| `/nodesadmin help` | Show the actions available to this sender. |
| `/nodesadmin integrations` | Inspect detected integrations. |
| `/nodesadmin reload [config\|managers\|resources\|territory\|tab]` | Reload the selected part of the core. |
| `/nodesadmin reload territory <*\|id...>` | Reload every territory or a chosen set. |
| `/nodesadmin save [sync]` | Save the current world. Use sync to wait for the native save. |
| `/nodesadmin load` | Load the saved world. |
| `/nodesadmin runincome` | Run one town income distribution. |
| `/nodesadmin clearincome` | Clear stored income for every town. |
| `/nodesadmin disable <market\|miningtower\|miningoutminer> [on\|off]` | Inspect or toggle the subsystem’s disabled flag. |
| `/nodesadmin storage status` | Inspect active JSON files and feature directories. |
| `/nodesadmin storage migrate` | Run the feature JSON storage migration. |
| `/nodesadmin consolecheck` | Check the console command bridge. |
| `/nodesadmin playersonline` | Report online player information. |
| `/nodesadmin holograms <stats\|refresh\|respawn\|cleanup>` | Inspect or repair player nametag holograms. |
| `/nodesadmin debug <resource\|chunk\|territory\|resident\|town\|nation> <name/id> <field>` | Inspect a native object field. The value is written to the server log. |
| `/nodesadmin core info` | Show the core account audit. |
| `/nodesadmin core audit` | Inspect the core financial audit. |
| `/nodesadmin core resolve <id> <applied\|not-applied>` | Resolve an interrupted payment once its disposition is known. |
| `/nodesadmin core region <nation> <minX> <minZ> <maxX> <maxZ>` | Set a nation’s permitted rectangular region using block coordinates. |
| `/nodesadmin core inactive` | List residents flagged inactive by the core. |
| `/nodesadmin core relay <message...>` | Send through the configured third-party relay. |

## Residents and staff tools

| Command | What it does |
| --- | --- |
| `/nodesadmin resident towncooldown <player> <milliseconds>` | Set the resident’s town creation cooldown. |
| `/nodesadmin pvp <town> <on\|off>` | Set the town’s PvP switch. |
| `/nodesadmin inactive [days]` | List residents whose last login predates the threshold. |
| `/nodesadmin socialspy [local\|town\|nation\|ally]` | Toggle monitoring for staff in game. |
| `/nodesadmin socialspy <online-player> [local\|town\|nation\|ally\|global]` | Console form controlling monitoring for a player. |
| `/nodesadmin stats reset <player>` | Reset an individual’s kill and death record. |
| `/nodesadmin stats reset all confirm` | Reset everyone’s kill and death records. |
| `/nodesadmin announce <message...>` | Broadcast a staff announcement. |
| `/nodesadmin plot list` | List plots with their town and centre. |
| `/nodesadmin plot delete <name>` | Remove a named plot. |
| `/nodesadmin leaderlaunch <on\|off\|status>` | Change or inspect Leader Launch access. |
| `/nodesadmin leaderlaunch <add\|remove> <players...>` | Change the Leader Launch access list. |
| `/nodesadmin ban <town\|nation> <name>` | Ban residents of the named group. |
| `/nodesadmin unban <player\|town> <name>` | Unban one resident or a town’s residents. |
| `/nodesadmin checkban [town] <name>` | List banned residents in a town. |
| `/nodesadmin miningboost global <duration> [tier]` | Apply a server-wide mining boost. |
| `/nodesadmin miningboost player <name> <duration> [tier]` | Apply a mining boost to one player. |
| `/nodesadmin miningboost clear global` | Clear the global boost. |
| `/nodesadmin miningboost clear player <name>` | Clear a player’s boost. |

## Town management

| Command | What it does |
| --- | --- |
| `/nodesadmin town create <name> <territory-ids...>` | Create a town from the listed territories. |
| `/nodesadmin town delete <name>` | Delete a town. |
| `/nodesadmin town addplayer <town> <players...>` | Add residents to a town. |
| `/nodesadmin town addallplayer <town>` | Add residents who currently have no town. |
| `/nodesadmin town removeplayer <town> <players...>` | Remove residents. |
| `/nodesadmin town addterritory <town> <ids...>` | Assign territories to a town. |
| `/nodesadmin town removeterritory <town> <ids...>` | Unclaim territories. |
| `/nodesadmin town captureterritory <town> <ids...>` | Record captured territories. |
| `/nodesadmin town releaseterritory <ids...>` | Release territories from occupation. |
| `/nodesadmin town claimsbonus <town> <number>` | Set the bonus claim allowance. |
| `/nodesadmin town claimsannex <town> <number>` | Set the annex claim adjustment. |
| `/nodesadmin town claimspenalty <town> <number>` | Set the claim penalty. |
| `/nodesadmin town setspawn <town>` | Move the spawn to your current location. |
| `/nodesadmin town spawn <town>` | Teleport to the town’s spawn. |
| `/nodesadmin town addofficer <town> <player>` | Appoint an officer. |
| `/nodesadmin town removeofficer <town> <player>` | Remove an officer. |
| `/nodesadmin town addgeneral <town> <player>` | Appoint a general. |
| `/nodesadmin town removegeneral <town> <player>` | Remove a general. |
| `/nodesadmin town leader <town> <player>` | Assign the leader. |
| `/nodesadmin town removeleader <town>` | Clear the leader. |
| `/nodesadmin town open <town>` | Toggle open membership. |
| `/nodesadmin town income <town>` | Open the income inventory. |
| `/nodesadmin town incomeadd <town>` | Add the held stack to income. |
| `/nodesadmin town incomeremove <town> [material]` | Remove the specified material or clear the inventory when omitted. |
| `/nodesadmin town sethome <town> <territory-id>` | Set a new home territory. |
| `/nodesadmin town sethomecooldown <town> <milliseconds>` | Set the home-move cooldown. |
| `/nodesadmin town addoutpost <town> <name> <territory-id>` | Add an outpost. |
| `/nodesadmin town removeoutpost <town> <name>` | Delete an outpost. |
| `/nodesadmin town addoutpostcredits <town> <amount>` | Adjust outpost credits. |
| `/nodesadmin town color <town> <r> <g> <b>` | Set the map colour. |
| `/nodesadmin town rename <town> <new-name>` | Rename a town. |
| `/nodesadmin town defaulttownspawns <town>` | Restore the town’s default spawn locations. |
| `/nodesadmin town fixspawns` | Repair town spawns. |

## Nations and diplomacy

| Command | What it does |
| --- | --- |
| `/nodesadmin nation create <name> <towns...>` | Create a nation from member towns. |
| `/nodesadmin nation delete <name>` | Disband the nation. |
| `/nodesadmin nation addtown <nation> <towns...>` | Add towns to a nation. |
| `/nodesadmin nation removetown <nation> <towns...>` | Remove towns. |
| `/nodesadmin nation capital <nation> <town>` | Change the national capital. |
| `/nodesadmin nation color <nation> <r> <g> <b>` | Set the national map colour. |
| `/nodesadmin nation rename <nation> <new-name>` | Rename a nation. |
| `/nodesadmin enemy <name1> <name2>` | Set the two parties as enemies. |
| `/nodesadmin peace <name1> <name2>` | Make peace between the two parties. |
| `/nodesadmin peaceall` | Set peace between all towns. |
| `/nodesadmin ally <name1> <name2>` | Set an alliance. |
| `/nodesadmin allyremove <name1> <name2>` | End an alliance. |
| `/nodesadmin truce <name1> <name2>` | Establish a truce. |
| `/nodesadmin truceremove <name1> <name2>` | Remove a truce. |
| `/nodesadmin treaty <name1> <name2> <add\|remove> occupy <side> <territory-id>` | Edit an occupation term. Sides are 0 and 1. |
| `/nodesadmin treaty <name1> <name2> <add\|remove> item <side> <material> <count>` | Edit an item term. |

## Territories, war and rallies

| Command | What it does |
| --- | --- |
| `/nodesadmin territory tp <id>` | Teleport to the territory. |
| `/nodesadmin territory addresource <id> <resources...>` | Assign resource nodes. |
| `/nodesadmin territory removeresource <id> <resources...>` | Remove assigned resources. |
| `/nodesadmin territory addtype <id> <types...>` | Attach territory types. |
| `/nodesadmin territory removetype <id> <types...>` | Remove territory types. |
| `/nodesadmin resource <add\|remove> <id> <resources...>` | Shortcut for resource assignment. Also accepts ressource. |
| `/nodesadmin war` | Show the native war state. |
| `/nodesadmin war enable [duration]` | Start full flag war. |
| `/nodesadmin war skirmish [duration]` | Start a border skirmish. |
| `/nodesadmin war disable` | Stop native war. |
| `/nodesadmin war <whitelist\|blacklist\|annex> list` | Inspect the chosen access list. |
| `/nodesadmin war <whitelist\|blacklist\|annex> <add\|remove> <town>` | Edit a list. Removal also accepts del and delete. |
| `/nodesadmin wardeclaration [on\|off]` | Inspect or toggle declarations. The legacy spelling wardeclartion also works. |
| `/nodesadmin warzone [name]` | Manage warzones or inspect a named zone. |
| `/nodesadmin attacktimedebug [chunkX chunkZ]` | Show the factors used to compute a chunk attack time. |
| `/nodesadmin rallycap <nation> <n\|-1>` | Set the nation’s rally cap. Use -1 to clear its override. |
| `/nodesadmin rallycap <enable\|disable>` | Toggle the global cap. |
| `/nodesadmin rallycap size [number]` | Inspect or set the global cap size. |
| `/nodesadmin territory addcore <id\|town>` | Mark a core territory. |
| `/nodesadmin territory removecore <id\|town>` | Clear a core territory. |

## Ports, outposts and mining outposts

| Command | What it does |
| --- | --- |
| `/nodesadmin port list` | List known ports. |
| `/nodesadmin port requests [id approve\|deny]` | Review applications or decide one. Approvals use the in-game tier menu. |
| `/nodesadmin port tpn <id>` | Teleport to an applicant’s port location. |
| `/nodesadmin port accept <id>` | Accept an application. |
| `/nodesadmin port deny <id>` | Reject an application. |
| `/nodesadmin port tier <id> <tier1\|tier2\|tier3>` | Set a pending port’s tier. |
| `/nodesadmin port tier set <port> <tier1\|tier2\|tier3>` | Change an existing port’s tier. |
| `/nodesadmin port create <name> <x> <z> [groups] [public]` | Create a port. |
| `/nodesadmin port delete <name>` | Delete a port. |
| `/nodesadmin port addgroup <port> <group>` | Add a port to a group. |
| `/nodesadmin port removegroup <port> <group>` | Remove it from a group. |
| `/nodesadmin port group <add\|remove> <name>` | Create or delete a port group. |
| `/nodesadmin portgroup create <name>` | Create a port group. |
| `/nodesadmin portgroup delete <name>` | Delete a port group. |
| `/nodesadmin outpost list` | List pending outposts. |
| `/nodesadmin outpost tpn <id>` | Teleport to the application location. |
| `/nodesadmin outpost accept <id>` | Accept the application. |
| `/nodesadmin outpost deny <id>` | Reject the application. |
| `/nodesadmin outpost tier <id> <tier1\|tier2\|tier3>` | Assign the requested outpost’s tier. |
| `/nodesadmin miningoutpost place [name...]` | Place a mining outpost at your position. |
| `/nodesadmin miningoutpost remove` | Remove the nearby outpost. |
| `/nodesadmin miningoutpost removeindex <index>` | Remove an indexed outpost. |
| `/nodesadmin miningoutpost list` | List outposts and their indexes. |
| `/nodesadmin miningoutpost resources` | List output materials. |
| `/nodesadmin miningoutpost setresource <material> <amount>` | Configure output. |
| `/nodesadmin miningoutpost tp <index>` | Teleport to an outpost. |
| `/nodesadmin miningoutpost regenholos` | Regenerate outpost holograms. |

The `miningoutminer` spelling runs the same staff commands as `miningoutpost`.

## Calendar events and announcements

| Command | What it does |
| --- | --- |
| `/nodesadmin calendar add <date\|daily\|weekly:FRIDAY\|monthly:15> [HH:mm] <type> <title...>` | Schedule an event. |
| `/nodesadmin calendar edit <id> title\|desc\|date\|time\|type\|action <value...>` | Change an event. |
| `/nodesadmin calendar remove <id>` | Delete an event. |
| `/nodesadmin calendar show <id>` | Show one event. |
| `/nodesadmin calendar list [all]` | List upcoming (or all) events. |
| `/nodesadmin calendar announce [hours=N] <text...>` | Post an announcement. Use hours=0 to keep it visible until staff remove it. |
| `/nodesadmin calendar announcements` | List announcements. |
| `/nodesadmin calendar unannounce <number>` | Remove an announcement. |

## Points of interest

| Command | What it does |
| --- | --- |
| `/nodesadmin poi add <type> [radius=N] <name...>` | Place a village, ruin, cave, shrine, shipwreck, dungeon or camp at your position. Discovery opens after a short delay. |
| `/nodesadmin poi list` | List places. |
| `/nodesadmin poi show <id>` | Show one place. |
| `/nodesadmin poi remove <id>` | Delete a place. |
| `/nodesadmin poi tp <id>` | Teleport to a place. |
| `/nodesadmin poi move <id>` | Move a place to your position. |
| `/nodesadmin poi name\|desc\|type\|radius <id> <value...>` | Edit a place. |
| `/nodesadmin poi activate <id>` | Let players find a new place right away. |
| `/nodesadmin poi reward <id> money <amount> \| item <MATERIAL> [amount] \| hand \| title <text\|none> \| clearitems` | Set the discovery reward (hand = the exact item you hold, custom items included). |
| `/nodesadmin poi reset <player> [id]` | Clear a player's discoveries. |

## Resident skills

| Command | What it does |
| --- | --- |
| `/nodesadmin skills show <player>` | Show a player's skill levels. |
| `/nodesadmin skills set <player> <skill> <level>` | Set a skill level. |
| `/nodesadmin skills addxp <player> <skill> <xp>` | Give skill xp. |
| `/nodesadmin skills reset <player>` | Reset all skills of a player. |

## Duel arenas and kits

| Command | What it does |
| --- | --- |
| `/nodesadmin duel arena add <name>` | Create an arena. |
| `/nodesadmin duel arena spawn1\|spawn2\|spectate <name>` | Set that spot to where you stand. |
| `/nodesadmin duel arena list \| tp <name> \| remove <name>` | Manage arenas. |
| `/nodesadmin duel kit save <name>` | Save your inventory, armor and offhand as a kit. |
| `/nodesadmin duel kit list \| remove <name>` | Manage kits. |
| `/nodesadmin duel end <player>` | End a duel as a draw (wagers refunded). |
| `/nodesadmin duel resetstats <player>` | Clear a player's duel record. |

## Other staff commands

Several independently registered commands have their own administration permissions. Their staff actions and arguments are in the core guide.

[/keywar](COMMANDS.md#keywar) · [/economyadmin](COMMANDS.md#economyadmin) · [/minebuff](COMMANDS.md#minebuff) · [/minebufftown](COMMANDS.md#minebufftown) · [/miningtower](COMMANDS.md#miningtower) · [/miningtowerdebug](COMMANDS.md#miningtowerdebug) · [/resourcetower](COMMANDS.md#resourcetower) · [/refinery](COMMANDS.md#refinery) · [/netowncontrol](COMMANDS.md#netowncontrol) · [/bounty](COMMANDS.md#bounty) · [/killshop](COMMANDS.md#killshop) · [/supplydrop](COMMANDS.md#supplydrop) · [/warzone](COMMANDS.md#warzone) · [/neadmin](COMMANDS.md#neadmin)

The older `/nodesadmin dynmap status` spelling returned “Invalid command” on both tested servers. The core does not register `/nodesweep` in its descriptor.

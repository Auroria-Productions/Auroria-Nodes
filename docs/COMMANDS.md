# Core command reference

[Repository overview](../README.md) · [All staff subcommands](ADMIN-COMMANDS.md)

Use the full command name when installing alongside other plugins. Angle brackets mark required values, square brackets mark optional values, and a vertical bar separates choices. In the server console, omit the leading slash.

The command index follows the dev.5.11 JAR. Some actions require town or nation leadership, an enabled feature or a player standing in the relevant territory. Inventory, selection and location actions run in game. The permissions below are the command entry permissions from the release descriptor. Handlers can impose additional checks.

## Towns, nations and travel

### town

Found a town, recruit residents, assign land and manage its income.

**Aliases** `/t`  
**Entry permission** `nodes.command.town`

| Command | What it does |
| --- | --- |
| `/town [help]` | Show town information or help. |
| `/town create <name>` | Found a town in the territory you are standing in. Also accepts new. |
| `/town delete` | Disband your town. Also accepts disband. |
| `/town demote <player>` | Remove officer rank. |
| `/town leader <player>` | Transfer town leadership. |
| `/town general <promote\|demote> <player>` | Set a resident’s general rank. |
| `/town general list` | List your generals. |
| `/town apply <town>` | Apply for membership. Also accepts join. |
| `/town invite <player>` | Invite a player. |
| `/town accept [player]` | Accept an invitation or a resident’s application. |
| `/town deny [player]` | Decline an invitation or application. Also accepts reject. |
| `/town leave` | Leave your town. |
| `/town kick <player>` | Remove a resident. |
| `/town spawn [outpost]` | Travel to your town or an outpost. |
| `/town setspawn` | Record your current location as the town spawn. |
| `/town list` | List towns. |
| `/town info [town]` | Inspect a town. |
| `/town online [town]` | List online residents. |
| `/town onlinetop` | Rank towns by online population. |
| `/town color <r> <g> <b>` | Set the town’s map colour. Use random to pick one. |
| `/town claim` | Claim the territory underfoot. |
| `/town unclaim` | Release the territory underfoot. |
| `/town transfer <territoryId> <town>` | Transfer a territory to another town. |
| `/town capital` | Move the town’s home territory. |
| `/town annex` | Annex an occupied territory under the configured war rules. |
| `/town income` | Open town income storage. |
| `/town prefix [player] <text\|remove>` | Set or clear a resident’s prefix. |
| `/town suffix [player] <text\|remove>` | Set or clear a resident’s suffix. |
| `/town rename <name>` | Rename the town. |
| `/town map` | View the world map in game. |
| `/town minimap` | Toggle the sidebar map. |
| `/town permissions <type> <group> <allow\|deny>` | Set town access rules. Also accepts perms. |
| `/town protect [show]` | Toggle chest protection mode or display protected chests. |
| `/town trust <player>` | Add a trusted resident. |
| `/town untrust <player>` | Remove trust. |
| `/town outpost apply <name>` | Request an outpost in the current territory. |
| `/town outpost list` | List the town’s outposts. |
| `/town outpost setspawn` | Record the outpost spawn. |
| `/town market [arguments...]` | Open the town market or use the market actions listed under nemarket. |
| `/town pointshop` | Open the town points shop. |
| `/town contracts` | Open the contracts menu. Also accepts contract. |
| `/town fly` | Toggle flight within your town, subject to permission. |
| `/town plot <action> [arguments...]` | Use the plot commands listed under plot. |
| `/town leaderlaunch` | Show the staff-managed Leader Launch rules. |
| `/town announce <message>` | Send a title announcement to town members. |
| `/town point <n>` | Set a rally beam for town members. Also accepts rally. |
| `/town pointdisable` | Toggle whether you see town rally markers. |
| `/town pvp [on\|off]` | Inspect or set the town PvP switch. |
| `/town supply` | Inspect the town’s supply-line status. |
| `/town fortify [upgrade\|info\|status]` | Upgrade or inspect the current territory’s fortification. |
| `/town fortify treasury` | Inspect fortification material reserves. |
| `/town fortify deposit [amount]` | Deposit the held material. |
| `/town fortify withdraw <material> <amount>` | Withdraw materials from the reserve. |
| `/town rp <message>` | Send a local roleplay message. |
| `/town waypoint` | Request a waypoint through the configured integration. |
| `/town bank` | Inspect the town treasury. |
| `/town deposit <amount>` | Deposit into the town treasury. |
| `/town withdraw <amount>` | Withdraw from the treasury as town leadership. |
| `/town plotbank <plot> [deposit\|withdraw <amount>]` | View or use a plot account. |
| `/town levy <amount>` | Bill each resident the specified amount. |
| `/town bills [pay <id>]` | List outstanding bills or pay a bill. |
| `/town plotupkeep <plot> <amount>` | Set upkeep for a plot. |
| `/town promote <player>` | Appoint a town officer. |
| `/town officer <player>` | Toggle officer status for a resident. |

### nation

Unite towns, choose a capital, make subject agreements and set taxes.

**Aliases** `/n`  
**Entry permission** `nodes.command.nation`

| Command | What it does |
| --- | --- |
| `/nation delete` | Delete your nation. |
| `/nation leave` | Leave your nation. |
| `/nation accept` | Accept a nation invite. |
| `/nation deny` | Deny a nation invite. |
| `/nation list` | List all nations. |
| `/nation info [nation]` | View nation details. |
| `/nation online [nation]` | View online players. |
| `/nation spawn [town]` | Teleport to a town in your nation. |
| `/nation announce <text>` | Broadcast title to nation members. |
| `/nation [help]` | Show your nation or list commands. |
| `/nation onlinetop` | Rank nations by online population. |
| `/nation subjects` | Show your nation’s overlord, vassals, tributaries and rates. |
| `/nation vassal <offer\|accept\|decline\|release\|break> <nation> [resourcePercent] [economyPercent]` | Negotiate or end a vassal agreement. The rates apply when offering terms. |
| `/nation tributary <offer\|accept\|decline\|release\|break> <nation> [resourcePercent] [economyPercent]` | Negotiate or end a tributary agreement. |
| `/nation bank` | Inspect the national treasury. |
| `/nation deposit <amount>` | Deposit into the treasury. |
| `/nation withdraw <amount>` | Withdraw as national leadership. |
| `/nation tax <resources\|economy> <percent>` | Set a national tax rate. |
| `/nation create <name>` | Create a nation (town leader only). |
| `/nation invite <town>` | Invite a town to your nation. |
| `/nation capital <town>` | Set the nation capital. |
| `/nation color <r> <g> <b>` | Set nation color on map. |
| `/nation rename <name>` | Rename your nation. |

The command also accepts `new` for `create`, `disband` for `delete`, `reject` for `deny`, and `annonce` for `announce`.

### vassal

Start a revolt against your nation’s overlord.

**Aliases** `/vassals`  
**Entry permission** `nodes.command.nation`

| Command | What it does |
| --- | --- |
| `/vassal revolt` | Seek independence from your overlord under the configured cooldown. |

### plot

Select blocks, create plots, transfer ownership and set access rules.

**Aliases** `/p`  
**Entry permission** `nodes.command.plot`

| Command | What it does |
| --- | --- |
| `/plot select` | toggle selection mode, then left/right click blocks. |
| `/plot create <name>` | create plot. |
| `/plot delete <name>` | delete plot. |
| `/plot owner <plot> <player>` | set owner. |
| `/plot permissions <plot> <type> <group\|player> <allow\|deny>` | Set a plot access rule. Also accepts permission. |
| `/plot list player <name>` | list player plots. |
| `/plot list town\|all` | list town/all plots. |
| `/plot near` | show nearby plot. |
| `/plot info <name>` | plot details. |
| `/plot show` | show plot borders with particles for 5 seconds. |
| `/plot help` | Show the available actions. |

The town form is `/town plot …`. Types include build, destroy, interact, chests, items and all. A rule may target a role or a specific player.

### port

Travel by port, apply for a port and change access or fees.

**Aliases** —  
**Entry permission** `nodes.command.port`

| Command | What it does |
| --- | --- |
| `/port help` | List port commands. |
| `/port list` | List ports. |
| `/port info <name>` | Inspect a port. |
| `/port warp <destination>` | Travel to a port. |
| `/port apply <name>` | Submit a port application. Also accepts request. |
| `/port allow <ally\|neutral\|enemy> <true\|false>` | Set access for that relation. |
| `/port fee <ally\|neutral\|enemy> <amount>` | Set a port fee for that relation. |

### player

Look up a resident.

**Aliases** `/p`  
**Entry permission** `nodes.command.player`

| Command | What it does |
| --- | --- |
| `/player [name]` | Inspect yourself or a named resident. |

### territory

Inspect the territory underfoot or by ID.

**Aliases** —  
**Entry permission** `nodes.command.territory`

| Command | What it does |
| --- | --- |
| `/territory [id]` | Inspect your current territory or look one up by ID. |

Coordinates in territory files are chunk coordinates. The in-game lookup takes a territory ID.

### nodes

Look up resources, territories, towns, nations, players and war information.

**Aliases** `/nd`  
**Entry permission** `nodes.command.nodes`

| Command | What it does |
| --- | --- |
| `/nodes [help]` | Show the general information commands. |
| `/nodes resource [name]` | List resource nodes or inspect one. |
| `/nodes territory [id]` | Inspect a territory. |
| `/nodes town [name]` | Inspect a town or list towns. Also accepts towns. |
| `/nodes nation [name]` | Inspect a nation or list nations. Also accepts nations. |
| `/nodes player [name]` | Look up a resident. Also accepts players. |
| `/nodes war` | Inspect the war state. |
| `/nodes miningoutposts` | Show mining outpost waypoints. |
| `/nodes options [arguments...]` | Use the preferences menu and settings under noptions. |

### noptions

Set minimap orientation, chat preference and battle notifications.

**Aliases** —  
**Entry permission** `nodes.command.options`

| Command | What it does |
| --- | --- |
| `/noptions [help]` | Show your minimap and chat settings. |
| `/noptions orientation <north\|forward>` | Choose minimap orientation. |
| `/noptions chat <global\|town\|nation\|ally\|local>` | Set your default chat channel. |
| `/noptions attackmessages <all\|town\|nation\|ally\|warzone\|captures>` | Filter attack notices. |
| `/noptions capturemessages <title\|chat>` | Choose how capture notices appear. |

### map

Open the discoveries menu and display an earned exploration title.

**Aliases** —  
**Entry permission** `nodes.command.map`

| Command | What it does |
| --- | --- |
| `/map` | Places you have discovered and what is left to find. |
| `/map title <name>` | Show an earned title in chat. |
| `/map title none` | Remove your title. |

## Diplomacy and war

### ally

Offer or accept an alliance.

**Aliases** —  
**Entry permission** `nodes.command.ally`

| Command | What it does |
| --- | --- |
| `/ally <town\|nation>` | Offer or accept an alliance with the named town or nation. |
| `/ally help` | Show the available actions. |

### unally

End an alliance.

**Aliases** —  
**Entry permission** `nodes.command.unally`

| Command | What it does |
| --- | --- |
| `/unally <town\|nation>` | Break the alliance with that town or nation. |
| `/unally help` | Show the available actions. |

### war

Declare war or join a participating side.

**Aliases** —  
**Entry permission** `nodes.command.war`

| Command | What it does |
| --- | --- |
| `/war <town\|nation>` | Declare war subject to server diplomacy rules. |
| `/war join <war-id> <attackers\|defenders>` | Join an existing war on one side. |
| `/war help` | Show the available actions. |

The active war mode determines which declaration and reinforcement rules apply. Staff start one-life key-point battles through `/keywar`.

### peace

Offer peace or negotiate surrender.

**Aliases** —  
**Entry permission** `nodes.command.peace`

| Command | What it does |
| --- | --- |
| `/peace <town\|nation>` | Offer or accept peace. |
| `/peace surrender <enemy> [conquer\|plunder]` | Surrender to an enemy under the configured settlement rules. |
| `/peace help` | Show the available actions. |

### truce

View current truces and their remaining time.

**Aliases** —  
**Entry permission** `nodes.command.truce`

| Command | What it does |
| --- | --- |
| `/truce` | See active truces and their remaining duration. |

### warzone

Inspect a warzone or open its administration menu.

**Aliases** —  
**Entry permission** `nodes.command.warzone`

| Command | What it does |
| --- | --- |
| `/warzone [name]` | List warzones or inspect one. In-game administrators can open the management menu. |
| `/warzone edit [name]` | Open the warzone management interface in game. |

### captime

Show a node’s chunk capture time.

**Aliases** —  
**Entry permission** `nodes.command.captime`

| Command | What it does |
| --- | --- |
| `/captime [nodeId]` | Show the time to capture a node’s chunk. |

### captimes

Use the same capture-time lookup as /captime.

**Aliases** —  
**Entry permission** `nodes.command.captimes`

| Command | What it does |
| --- | --- |
| `/captimes [nodeId]` | Use the same lookup as captime. |

### keywar

Propose, join, referee and conclude one-life key-point battles.

**Aliases** `/kpwar`, `/keypointwar`  
**Entry permission** `nodes.command.keywar`

| Command | What it does |
| --- | --- |
| `/keywar help` | List battle commands. |
| `/keywar create <war> <attackerNation> <defenderNation>` | Staff create a war between two Nodes nations. |
| `/keywar point <war> <name> [radius]` | Propose a key point at your position as an eligible leader. |
| `/keywar enlist <war> <point>` | Enlist for that battle. Also accepts join. |
| `/keywar leave <war> <point>` | Leave setup or abandon an active battle. |
| `/keywar roster <war> <point> <add\|remove> <player> [attackers\|defenders]` | Staff edit the setup roster. |
| `/keywar start <war> <point>` | Staff start the battle. |
| `/keywar status [war]` | List wars or inspect a war’s points. Also accepts list. |
| `/keywar resolve <war> <point> <attackers\|defenders\|draw>` | Staff record the result. Also accepts finish. |
| `/keywar ceasefire <war> <point>` | Register one side’s agreement to stop fighting. |
| `/keywar terms <war> <point> <agreement...>` | Staff record a negotiated peace settlement. |

Both `war.enabled` and `war.keyPointWars.enabled` must be enabled. A fallen participant respawns with war sickness and stays eliminated from that battle. The default sickness duration is 600 seconds. Staff record the result and peace terms. Land and town ownership stay under staff control.

### surrender

Move your town into another nation through surrender.

**Aliases** —  
**Entry permission** `nodes.command.surrender`

| Command | What it does |
| --- | --- |
| `/surrender <nation>` | Start the town’s surrender to a named nation. |
| `/surrender confirm` | Confirm the pending surrender. |
| `/surrender cancel` | Withdraw the request. |

### raid

Start, inspect or cancel a territory raid.

**Aliases** —  
**Entry permission** `nodes.command.raid`

| Command | What it does |
| --- | --- |
| `/raid <town> <territoryId>` | Start a raid (officer/leader). |
| `/raid status` | List active raids. |
| `/raid cancel` | Cancel your raid during prep. |
| `/raid help` | Show the available actions. |

## Income, industry and trade

### neincome

Inspect the sources of a town’s resource income.

**Aliases** —  
**Entry permission** `nodes.command.town`

| Command | What it does |
| --- | --- |
| `/neincome info` | Display a breakdown of your town’s income. Running the bare command opens the same breakdown. |

### nemarket

Trade town-market listings, set sanctions and collect sale proceeds.

**Aliases** —  
**Entry permission** `nodes.command.town`

| Command | What it does |
| --- | --- |
| `/nemarket` | Browse the market in your current territory. |
| `/nemarket add <price> <currency> [qty]` | List held item. |
| `/nemarket info` | Your town's market stats. |
| `/nemarket tax <set\|remove\|info> [rate]` | Manage tax (leaders). |
| `/nemarket sanction <town> <block\|price> [mult]` | Sanction a town (leaders). |
| `/nemarket unsanction <town>` | Remove sanction (leaders). |
| `/nemarket sanctions` | List active sanctions. |
| `/nemarket gdp` | GDP leaderboard. |
| `/nemarket listings` | View your active listings. |
| `/nemarket cancel <#>` | Cancel a listing by number. |
| `/nemarket mailbox [check\|redeem]` | Collect offline sale payments. |
| `/nemarket help` | Show the available actions. |

The `sell` spelling also lists the held item for sale, using the same arguments as `add`.

### refinery

Inspect recipes and operate the refinery assigned to a territory.

**Aliases** `/rf`, `/refineries`  
**Entry permission** `nodes.command.refinery`

| Command | What it does |
| --- | --- |
| `/refinery` | Open the refinery assigned to the territory underfoot. |
| `/refinery help` | Show active refinery commands. |
| `/refinery recipe [name]` | List recipe names or inspect a recipe. |
| `/refinery type [name]` | List refinery types or inspect one. |
| `/refinery reload <config\|world>` | Staff reload recipe configuration or territory definitions. |
| `/refinery save` | Staff save refinery state. |

This release runs the territory-based Phonon refinery. Assign refinery types through world and resource definitions. Operators can inspect types and recipes before reloading them.

### miningtower

Submit a town mining tower and review submissions.

**Aliases** —  
**Entry permission** `nodesextensions.miningtower.submit`

| Command | What it does |
| --- | --- |
| `/miningtower [help]` | Show the submission and review commands. |
| `/miningtower submit` | Submit a town tower for rating. |
| `/miningtower info` | View your town’s tower information. |
| `/miningtower pending` | Staff review submissions. |
| `/miningtower delete <town>` | Staff remove the town’s tower. |
| `/miningtower reload` | Staff reload the tower configuration. |
| `/miningtower cooldown <remove\|check> <town>` | Clear or inspect the submission cooldown. |
| `/miningtower cooldown add <town> <hours>` | Set the cooldown. |

This command manages town submissions and ratings. Its submission and rating permissions are separate.

### miningtowerdebug

Inspect your town’s approved tower, tier and applied bonuses.

**Aliases** —  
**Entry permission** `nodesextensions.miningtower.rate`

| Command | What it does |
| --- | --- |
| `/miningtowerdebug` | Inspect your town’s approved tower, tier and bonus in game. |

### resourcetower

List, place or remove resource-producing towers.

**Aliases** `/mt`  
**Entry permission** `nodes.command.miningtower`

| Command | What it does |
| --- | --- |
| `/resourcetower list` | List mining towers. |
| `/resourcetower create <id> <cooldown> <item> <amount> [item amount ...]` | Create a tower at your feet (cooldown e.g. 30m, 3h). |
| `/resourcetower remove <id>` | Remove a tower. |
| `/resourcetower help` | Show the available actions. |

The placement action also accepts `place`. Removal accepts `delete`. Some legacy help still prints the spelling `/miningtower` for these actions. Use `/resourcetower` or `/mt` for resource-producing towers.

### economy

Manage accounts, loans, trade routes, infrastructure and the exchange.

**Aliases** `/ne`  
**Entry permission** `nodes.command.economy`

| Command | What it does |
| --- | --- |
| `/economy balance <account>` | Balance and your wallet. |
| `/economy deposit <account> <amount>` | Wallet to account. |
| `/economy withdraw <account> <amount>` | Account to wallet. |
| `/economy pay <account> <player> <amount>` | Pay a player's bank. |
| `/economy history <account>` | Latest 20 records. |
| `/economy business create <name>` | Open a business account. |
| `/economy menu` | Open the economy menu. |
| `/economy prices` | What the server buys. |
| `/economy sell hand\|all\|town` | Sell items to the server (town: stored income, to the treasury). |
| `/economy top [players\|nations\|towns]` | Richest lists. |
| `/economy stock` | Your nation's trade resources. |
| `/economy` | Open the economy menu. |
| `/economy market mine` | Show your listings. |
| `/economy market sell <price>` | Offer the held stack for sale. |
| `/economy market buy <id>` | Buy a listing. |
| `/economy market cancel <id>` | Withdraw a listing. |
| `/economy shop list [page]` | Show the exchange catalogue. |
| `/economy shop <buy\|sell> <material> <amount>` | Trade with the server exchange. |
| `/economy route list` | View trade routes. |
| `/economy route offer <buyer-nation> <material> <quantity> <total-price> <interval-minutes>` | Propose a recurring export. |
| `/economy route accept <id>` | Accept an incoming trade offer. |
| `/economy route cancel <id>` | End a route or withdraw the offer. |
| `/economy loan request <account> <amount>` | Apply to borrow funds. |
| `/economy loan list <account>` | List loans for the account. |
| `/economy loan repay <account> <loan-id> <amount>` | Repay debt. |
| `/economy infra list` | See national infrastructure and upkeep. |
| `/economy infra pay <id>` | Pay infrastructure arrears. |
| `/economy market list [page]` | Show market listings. |
| `/economy help` | Show the available actions. |

Use `player`, `town`, `nation` or `business-<name>` where an account is requested. The account and operation determine who can spend from it.

### economyadmin

Issue funds or stock, review loans and register infrastructure.

**Aliases** `/nea`  
**Entry permission** `nodes.economy.admin`

| Command | What it does |
| --- | --- |
| `/economyadmin issue <player\|reserve> <amount>` | Create currency. |
| `/economyadmin vote <player> [service]` | Pay the vote reward (for vote plugins that run commands). |
| `/economyadmin stock <nation> <material> <quantity>` | Issue trade resources. |
| `/economyadmin loan approve\|reject <id>` | Decide a loan request. |
| `/economyadmin infra <nation> <railway\|port\|road\|power> <units> <name>` | Register infrastructure. |
| `/economyadmin exchange reset` | Put every exchange item back at its target stock. |
| `/economyadmin retire <name>` | Retire infrastructure once its arrears are paid. |
| `/economyadmin` | Open the administration menu in game. |
| `/economyadmin help` | Show the available actions. |

### minebuff

Apply or clear a global or town-specific mining bonus.

**Aliases** —  
**Entry permission** `nodes.admin`

| Command | What it does |
| --- | --- |
| `/minebuff [rates\|amount] <multiplier> <time>` | Apply a global bonus to mining rates or amounts. |
| `/minebuff clear` | Clear the global buff. |
| `/minebuff town <town> [rates\|amount] <multiplier> <time>` | Apply a bonus in the named town. |
| `/minebuff town <town> clear` | Clear that town’s bonus. |

### minebufftown

Apply or clear a bonus for one named town.

**Aliases** —  
**Entry permission** `nodes.admin`

| Command | What it does |
| --- | --- |
| `/minebufftown <town> [rates\|amount] <multiplier> <time>` | Apply a buff to a town. |
| `/minebufftown <town> clear` | Clear the town’s buff. |

### netowncontrol

Inspect or toggle availability of selected town commands.

**Aliases** —  
**Entry permission** `nodesextensions.admin`

| Command | What it does |
| --- | --- |
| `/netowncontrol status` | Inspect command availability. |
| `/netowncontrol <leave\|spawn\|capital\|disband> [enable\|disable]` | Toggle the town action or set its state. |

## Chat and community

### globalchat

Select global chat or mute its feed.

**Aliases** `/gc`  
**Entry permission** `nodes.command.chat.global`

| Command | What it does |
| --- | --- |
| `/globalchat [join\|leave\|mute\|unmute]` | Select global chat or change whether you receive it. |

### townchat

Select or leave town chat.

**Aliases** `/tc`  
**Entry permission** `nodes.command.chat.town`

| Command | What it does |
| --- | --- |
| `/townchat [leave]` | Select or leave town chat. |

### nationchat

Select or leave nation chat.

**Aliases** `/nc`  
**Entry permission** `nodes.command.chat.nation`

| Command | What it does |
| --- | --- |
| `/nationchat [leave]` | Select or leave nation chat. |

### allychat

Select or leave allied chat.

**Aliases** `/ac`  
**Entry permission** `nodes.command.chat.ally`

| Command | What it does |
| --- | --- |
| `/allychat [leave]` | Select or leave allied chat. |

### localchat

Select or leave proximity chat.

**Aliases** `/lc`  
**Entry permission** `nodes.command.chat.local`

| Command | What it does |
| --- | --- |
| `/localchat [leave]` | Select or leave local chat. |

### rpchat

Toggle nearby roleplay chat.

**Aliases** `/rp`  
**Entry permission** `nodes.command.chat.rp`

| Command | What it does |
| --- | --- |
| `/rpchat` | Toggle nearby roleplay chat. |

### language

Set your resident language.

**Aliases** `/lang`  
**Entry permission** `nodes.command.language`

| Command | What it does |
| --- | --- |
| `/language <en\|de\|el\|tr>` | Select a language. This build displays el as the Spanish option. |

### report

Send a report to staff.

**Aliases** —  
**Entry permission** `nodes.command.report`

| Command | What it does |
| --- | --- |
| `/report <message>` | Submit a report to staff. |

### calendar

Open the event calendar or read the upcoming schedule.

**Aliases** —  
**Entry permission** `nodes.command.calendar`

| Command | What it does |
| --- | --- |
| `/calendar` | Open the server calendar. |
| `/calendar today` | Today's events and announcements. |
| `/calendar upcoming` | Events in the next 7 days. |
| `/calendar week` | Read the upcoming week. Also accepts upcoming. |
| `/calendar help` | Show the available actions. |

### skills

View skill levels and skill leaderboards.

**Aliases** —  
**Entry permission** `nodes.command.skills`

| Command | What it does |
| --- | --- |
| `/skills` | Open your skills menu (mining, farming, woodcutting, hunting). |
| `/skills top <skill>` | The ten highest players in a skill. |
| `/skills help` | Show the available actions. |

### duel

Challenge a player, pick a kit and wager, or spectate a duel.

**Aliases** —  
**Entry permission** `nodes.command.duel`

| Command | What it does |
| --- | --- |
| `/duel <player> [kit\|own] [wager]` | Challenge a player (own = your own gear). |
| `/duel accept [player]` | Accept a challenge. |
| `/duel deny` | Decline a challenge. |
| `/duel cancel` | Withdraw your challenge. |
| `/duel spectate <player>` | Watch a duel. |
| `/duel leave` | Stop watching, or forfeit your own duel. |
| `/duel top` | Highest rated duelists. |
| `/duel stats [player]` | Rating and win/loss record. |
| `/duel kits` | List kits. |
| `/duel watch <player>` | Spectate a player’s duel. Also accepts spectate. |
| `/duel help` | Show the available actions. |

## Rewards and statistics

### bounty

Fund a bounty, inspect rewards and collect queued payouts.

**Aliases** `/bounties`  
**Entry permission** `nodes.command.bounty`

| Command | What it does |
| --- | --- |
| `/bounty list [page]` | Players with ore bounties. |
| `/bounty info <player> [page]` | Rewards, funders and reasons. |
| `/bounty add <player> <ore> <amount> [reason]` | Fund a bounty from your inventory. |
| `/bounty additem <player> [reason]` | Fund with the stack in your hand. |
| `/bounty addmoney <player> <amount> [reason]` | Fund with money from your wallet. |
| `/bounty addreward <player> <package> [reason]` | Fund a preset package. |
| `/bounty materials` | Allowed reward ores. |
| `/bounty rewards` | Preset packages. |
| `/bounty collect` | Receive rewards waiting for inventory space. |
| `/bounty wallet` | Rewards waiting for you. |
| `/bounty status` | Staff see bounty-system status. |
| `/bounty refund <contribution-id>` | Staff reverse a contribution. |
| `/bounty return <delivery-id>` | Staff return a queued delivery. |
| `/bounty audit [page]` | Staff list contributions and queued deliveries. |
| `/bounty help` | Show the available actions. |

Funding and hunting use separate permissions. Money-backed bounties also depend on the economy settings.

### killshop

Spend kill points and manage the item shop.

**Aliases** —  
**Entry permission** `nodes.command.killshop`

| Command | What it does |
| --- | --- |
| `/killshop` | Open the shop. |
| `/killshop points` | Show your points. |
| `/killshop enable` | Enable killshop (awards points). |
| `/killshop disable` | Disable killshop. |
| `/killshop add <cost> <id>` | Add held item to shop. |
| `/killshop remove <id>` | Remove shop entry. |
| `/killshop cost <id> <cost>` | Change cost. |
| `/killshop list` | List entries. |
| `/killshop help` | Show the available actions. |

Balance also accepts `point`, `bal` and `balance`. Removal also accepts `delete`, and price changes accept `setcost`.

### supplydrop

Track drops and manage spawn points and weighted loot.

**Aliases** `/sd`  
**Entry permission** `nodes.command.supplydrop`

| Command | What it does |
| --- | --- |
| `/supplydrop status` | Active drop or time until the next one. |
| `/supplydrop addpoint <id>` | Add a drop point at your feet. |
| `/supplydrop removepoint <id>` | Remove a drop point. |
| `/supplydrop points` | List drop points. |
| `/supplydrop loot add <id> <weight>` | Add held item to the loot table. |
| `/supplydrop loot remove <id>` | Remove a loot entry. |
| `/supplydrop loot list` | List loot entries. |
| `/supplydrop spawn [point]` | Start a drop now. |
| `/supplydrop cancel` | Call off the active drop. |
| `/supplydrop help` | Show the available actions. |

The removal command also accepts `delpoint`. Staff need `nodes.supplydrop.admin` to edit points and loot.

### nekdr

Look up personal kill/death statistics or the top list.

**Aliases** `/kdr`  
**Entry permission** `nodes.command.nekdr`

| Command | What it does |
| --- | --- |
| `/nekdr [player\|top]` | View a record or the K/D leaderboard. |

### stats

Show a player’s kill/death record.

**Aliases** `/kdr`  
**Entry permission** Checks run in the handler.

| Command | What it does |
| --- | --- |
| `/stats [player]` | View your own or a named player’s K/D record. |

### leaderboard

Rank players, towns, nations and war performance.

**Aliases** `/lb`  
**Entry permission** Checks run in the handler.

| Command | What it does |
| --- | --- |
| `/leaderboard <kills\|deaths\|kdr\|towns\|nations\|war>` | Rank individual players, towns, nations or war performance. |

Singular `town` and `nation` also work. War rankings require an active tracked war or skirmish.

## Server administration

### nodesadmin

Administer towns, territory, diplomacy, events, storage and server operations.

**Aliases** `/nda`  
**Entry permission** `nodes.helper`

| Command | What it does |
| --- | --- |
| `/nodesadmin [help]` | Show staff commands available to the sender. The complete staff syntax is in the administration guide. |

See [all /nodesadmin subcommands](ADMIN-COMMANDS.md) for syntax, modes and permissions.

### neadmin

Legacy extension-reload entry. In dev.5.11 it only prints its usage.

**Aliases** —  
**Entry permission** `nodesextensions.admin`

| Command | What it does |
| --- | --- |
| `/neadmin reload` | The descriptor advertises this legacy command. A console invocation on both tested Paper versions returned the usage line. Use nodesadmin reload for the active core reload controls. |

The core currently binds its administration handlers to `/nodesadmin` and `/nda`.

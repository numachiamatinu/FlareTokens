<div align="center">

# FlareTokens

**The coin of your server (or of your whole network): in-game store, shops made with drag and drop, cheques, mob drops and PlaceholderAPI.**

![Minecraft](https://img.shields.io/badge/Minecraft-1.8.8%20→%2026.x-8e2de2)
![Platforms](https://img.shields.io/badge/Spigot%20|%20Paper%20|%20Purpur-supported-b57bff)
![Java](https://img.shields.io/badge/Java-8%2B-orange)
![License](https://img.shields.io/badge/license-MIT-green)

</div>

---

## Features

- **Tokens balance** for every player, with first-join tokens, `/tokens pay` (also to offline players), a top menu
  and the **history** of every token got and spent (`/tokens history`)
- **In-game store**: as many menus as you want in `shops/` (a hub with categories, ranks, kits, keys...),
  linked with `[open] <menu>` and `[back]`. Products with a **price**, items and commands to give,
  **confirm menu**, **limit per player** (once, per day, week or month), **stock** for the whole server and permission
- **Drag and drop editor** (`/tokens editor`): move items, add glass and buttons, link menus, and sell the item
  in your hand with exactly its enchantments and name; price, limit, stock... from a menu, no YAML needed
- **`/tokens settings`**: every option in one menu, plus what normal players can use
- **Timed sales**: `/tokens sale 20 2h` lowers every price by 20% for 2 hours (also on one shop only)
- **Network coin**: with MySQL every server of a BungeeCord / Velocity network has the same balances, updated
  within about a second, the same sales and the same stock. Web stores can give tokens by name or UUID
- **Action language**: `[buy(tokens)] 100`, `[CONSOLE]`, `[MESSAGE]`, `[TITLE]`, `[ACTIONBAR]`, `[BOSSBAR]`,
  `[SOUND]`, `[EFFECT]`, `[tokens_give]`, `[money_give]`, `[OPEN_MENU]`, `[SWITCH_SERVER]`, `[DELAY]`,
  `if / do / else / end` blocks and checks (`[has_tokens]`, `[has_permission]`, `[expression]`...)
- **Cheques**: `/tokens withdraw <amount>` turns tokens into an item, right click to redeem (sneak = whole stack)
- **Mob drops** (`drops.yml`): tokens for kills with chance, random amounts, permission multipliers,
  spawner and world filters
- **Storage**: YAML, SQLite or MySQL/MariaDB. With MySQL several servers share the same balances
- **Hex colors, gradients, rainbow, click & hover** in every text (MiniMessage tags and `&` codes),
  on 1.8–1.15 hex becomes the nearest color
- **PlaceholderAPI** placeholders and a small **API** for other plugins
- **`/tokens about`**: version (green = latest, red = outdated), server, update notifier, plugin hooks and every
  permission in one place, plus a message to admins when a new version is out
- **Safe updates**: new options and messages are added to your files, your edits are never touched

## Compatibility

| | |
|---|---|
| Server | Spigot, Paper, Purpur and other forks |
| Versions | 1.8.8 → 26.x (one jar for all) |
| Java | 8 or newer (the plugin; 26.x servers themselves need Java 25) |
| Optional | PlaceholderAPI, Vault + an economy plugin (for `[buy(money)]`), LuckPerms (ranks in the top), ItemsAdder, ExecutableItems |

## Installation

1. Put `FlareTokens-<version>.jar` in `plugins/`
2. Start the server: all files are created automatically
3. Missing PlaceholderAPI is downloaded and started by itself (`auto-install` in `config.yml`)

### Updating from FlareTokens 3.x

Just replace the jar. On the first start:

- your old files are saved in `plugins/FlareTokens/backup-3.x/`
- storage, MySQL, first-join tokens, pay and cheque settings are moved to the new `config.yml`
- your shops are moved from `shop/` to `shops/` (same format, they work as they are)
- **balances are not touched**: same `data/data.db`, same MySQL table, same `data/data.yml`
- old cheques can still be redeemed, the old placeholders and permissions keep working

Messages moved to `lang/en.yml`: if you edited the old `locale/en.yml`, copy your texts from the backup.

## Commands

| Command | Permission | |
|---|---|---|
| `/tokens` | `flaretokens.command.balance` | your balance |
| `/tokens balance [player]` | `.balance` / `.balance.others` | see a balance |
| `/tokens pay <player> <amount>` | `flaretokens.command.pay` | send tokens |
| `/tokens withdraw <amount\|all>` | `flaretokens.command.withdraw` | tokens into a cheque |
| `/tokens menu` | `flaretokens.command.menu` | balance, shop, top and history in one menu |
| `/tokens shop [shop] [player]` | `flaretokens.command.shop` / `.shop.others` | open a shop |
| `/tokens top` | `flaretokens.command.top` | richest players (menu; `top chat` or console: chat) |
| `/tokens history [player] [page]` | `flaretokens.command.history` / `.history.others` | tokens got and spent |
| `/tokens give <player\|*> <amount> [-s]` | `flaretokens.command.give` | give tokens |
| `/tokens remove <player\|*> <amount> [-s]` | `flaretokens.command.remove` | remove tokens |
| `/tokens set <player> <amount> [-s]` | `flaretokens.command.set` | set a balance |
| `/tokens sale <percent> <time> [shop]` / `sale stop` | `flaretokens.command.sale` | timed sale (time: 30m, 2h, 1d) |
| `/tokens settings` | `flaretokens.command.settings` | every option in a menu |
| `/tokens editor [menu]` | `flaretokens.command.editor` | edit menus and shops in game |
| `/tokens migrate <yaml\|sqlite\|mysql>` | `flaretokens.command.migrate` | copy all balances to another storage |
| `/tokens reload` | `flaretokens.command.reload` | reload the files |
| `/tokens about` | `flaretokens.command.version` | version, update check, server, hooks and permissions (also `/tokens version`) |

Aliases: `/flaretokens`, `/token`, `/ftokens`, `/ftoken`, `/ft`, `/tmtokens`, `/coins`. `-s` = the player gets no message.
Amounts accept `1k`, `2.5m`. `<player>` can also be a UUID (web stores). `flaretokens.admin` gives every permission,
`flaretokens.player` every player command, `flaretokens.redeem` redeems cheques.
`player-access` in `config.yml` (or `/tokens settings`) turns player features off for everyone but admins.

## Menus and shops

Every file in `shops/` is a menu: `/tokens shop <name>`, its `open-commands`, or `[open] <name>` from another menu.
The menus of the plugin (`/tokens menu`, top, history, confirm) are in `menus/`. All of them work the same way:

```yaml
title: "<gradient:#8e2de2:#c471f5>Ranks</gradient>"
rows: 3
open-commands: [ranks]
items:
  vip:
    slots: "11"
    material: EMERALD
    name: "&aVIP"
    price: 1500                 # tokens
    confirm: true               # confirm menu first
    limit: 1                    # once per player (limit-reset: daily / weekly / monthly)
    hide-if: ["[has_permission] group.vip"]
    commands: ["lp user {player} parent add vip"]
  back:
    slots: "22"
    material: ARROW
    name: "&c← Back"
    actions: ["[back]"]
decorations:
  - slots: "0-10,12-21,23-26"
    material: PURPLE_STAINED_GLASS_PANE
    name: " "
```

Decorations are drawn under the items, so a border never covers a button. Every option is explained at the top
of the example files (`shops/main.yml` is a hub, `ranks.yml` and `items.yml` show products).
In game: `/tokens editor <name>` — drag and drop, close to save; **shift + right click** an empty slot for the
palette (glass, back / close buttons, links to other menus, sell the item in your hand), **shift + right click**
an item to show it to admins only, **shift + left click** an item for its price, limit, stock, permission and commands.

## Network

FlareTokens goes on every game server of the network (Spigot, Paper...), not on the BungeeCord / Velocity proxy.
Put the same MySQL database in `storage` on every server: balances, history, purchase limits, stock and sales
are shared. A change made on one server shows on the others within `network.sync-seconds` (1 by default),
and the player gets the usual "You received ..." message (`network.notify-changes`). Sales reach every server
within a few seconds.
Give `network.server-name` a name on each server to see it in the history. Web stores (Tebex, CraftingStore...)
can run `tokens give {username} 1000` or `tokens give {uuid} 1000`: it works with the player offline or on
another server.

## Placeholders

| Placeholder | |
|---|---|
| `%flaretokens_tokens%` | balance (`%flaretokens_get_tokens%` from 3.x works too) |
| `%flaretokens_tokens_formatted%` | 1.2k, 3.4M… |
| `%flaretokens_tokens_commas%` | 1,234,567 |
| `%flaretokens_top_<n>_name%` | name at position n |
| `%flaretokens_top_<n>_tokens%` | balance at position n (also `_formatted`, `_commas`) |
| `%flaretokens_position%` | position of the player in the top |
| `%flaretokens_sale%` / `%flaretokens_sale_time%` | discount of the timed sale (0 = none) / time left |

Inside the plugin files: `{player}` `{tokens}` `{tokens_formatted}` `{tokens_commas}` `{missing:500}`.

**Ranks in the top:** in the top menu (`menus/top.yml`) and in `/tokens top chat` the PlaceholderAPI placeholders are
of the player of each line, so a rank goes before the name with `"&#b57bff#{position} %luckperms_prefix%&f{name}"`.
With LuckPerms it works for offline players too.

## API

```java
long tokens = FlareTokensAPI.getTokens(player);           // online player
FlareTokensAPI.giveTokens(player, 100);
if (FlareTokensAPI.takeTokens(player, 50)) { /* bought */ }
FlareTokensAPI.fetchTokens(uuid).thenAccept(t -> { });     // anyone, also offline
```

`TokensChangeEvent` is called before the balance of an online player changes (cancellable).

## Anonymous statistics

This plugin sends anonymous usage statistics to TMDevelopment every 30 minutes:
plugin version, Minecraft version, server software, Java version, CPU core count,
online player count, online/offline mode and a random server id generated on your server.
The country of the server is derived from its IP address (only the country code is kept).
Each plugin can also send a few anonymous settings of its own
(FlareTokens: storage type, number of shops and whether drops are on).
No player names or UUIDs, no IP addresses (they are never stored), no chat, world or config data.
Data of a server is deleted 30 days after its last report; only anonymous totals are kept.
We use it to know which versions to support.
Turn it off for FlareTokens with `stats: false` in `config.yml`, or for all TMDevelopment plugins
in `plugins/TMDStats/config.yml` (`enabled: false`).

FlareTokens also checks GitHub for new versions (at startup, when an admin joins, at most every 6 hours,
and with `/tokens about`). It only reads the latest release: nothing is sent.
Turn it off with `update-check: false` in `config.yml`.

## Building

```
mvn package
```

The jar is in `target/`.

## Credits

Originally created by **Maiky** (FlaresDevelopment). Rebuilt and continued by **TMDevelopment**.

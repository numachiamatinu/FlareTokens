# Changelog — FlareTokens

## 4.0.0
FlareTokens is back: rebuilt from scratch, faster, and now the coin of your whole server or network,
with an in-game store you build with drag and drop.
Updating from 3.x is automatic: just replace the jar, your balances, shops and cheques keep working.

### Compatibility
- One jar for Spigot, Paper, Purpur and forks from **1.8.8 to 26.x**, Java 8 or newer.
  Newer features (boss bars, hex colors, title timings) are used only where the server supports them.

### Updating from 3.x
- Your old files are saved in `backup-3.x/`, the settings move to the new `config.yml`
  and your shops move to `shops/` (same format, they work as they are).
- **Balances are not touched**: same SQLite file, MySQL table and YAML file.
- Cheques made with 3.x can still be redeemed. The 3.x placeholders and permissions keep working.
- Messages are now in `lang/en.yml`: if you edited the old `locale/en.yml`, copy your texts from the backup.

### New
- **In-game store**: as many menus as you want in `shops/` (a hub with categories, ranks, kits, keys...),
  linked with `[open] <menu>` and `[back]`. New example files: `main.yml` (hub), `ranks.yml`, `items.yml`.
- **Products**: `price`, `give` (items), `commands`, `confirm` (confirm menu), `limit` per player
  (once, per day, week or month), `stock` for the whole server, `permission`, `show-if` / `hide-if`.
  The price, "you need X more tokens", "sold out" lines are added by themselves (texts in each shop file).
- **Drag and drop editor**: `/tokens editor`. Move items, add glass panes and buttons, link menus, create new menus
  and sell the item in your hand with exactly its enchantments and name. Price, limit, stock, permission and commands
  from a menu, no YAML needed. Same editor as the other Flare plugins.
- **`/tokens settings`**: every option in one menu, plus **player access** (what normal players can use).
- **`/tokens menu`** (balance, shop, top, history), a **top menu** and a **history menu** with pages. Their layouts are
  in `menus/` and can be edited like the shops.
- **History**: every change of balance is saved (who, how much, why, on which server, when): `/tokens history [player]`.
  Kept 90 days by default (`history.keep-days`).
- **Timed sales**: `/tokens sale 20 2h [shop]` lowers the prices by 20% for 2 hours, with an announcement;
  `%flaretokens_sale%` and `%flaretokens_sale_time%` for scoreboards.
- **Network coin**: with MySQL, balances changed on another server show up within about a second, and history,
  limits, stock and sales are shared by every server. `tokens give <uuid> <amount>` for web stores.
- Menu files use the same format as the other Flare plugins: `title`, `rows`, `open-commands`, `open-permission`,
  `slots: "0-8,45-53"`, `material: head:{player}` / `texture:<value>`, `admin-only`, and `decorations` drawn
  under the items (a border can't cover a button any more).
- Hex colors, gradients, rainbow, click and hover in every message (MiniMessage tags and `&` codes).
  The 3.x `<GRADIENT:#a-#b>` syntax still works.
- Clear files with short explanations: `config.yml`, `lang/en.yml`, `shops/`, `menus/`, `drops.yml`.
  New options and messages are added to your files by themselves in future updates.
- `/tokens top`, `/tokens migrate <yaml|sqlite|mysql>`, `/tokens withdraw all`, amounts like `2.5k`.
- `/tokens version`: plugin, server and Java version, and whether a new FlareTokens is out.
  Admins also get a clickable message when they join (turn it off with `update-check: false`).
- New aliases: `/token`, `/ftokens`, `/ftoken` (the 3.x ones keep working).
- Sneak + right click redeems a whole stack of cheques.
- **Mob drops** (`drops.yml`): tokens for kills with chance, random amounts, permission multipliers,
  spawner and world filters (off by default).
- 3.x shop options still work, plus `priority`, `view_requirements`, separate `[LEFT]` / `[RIGHT]` actions,
  live items (`update: true`), `{missing:<price>}` placeholder and a click cooldown against double purchases.
- New actions: `[open]`, `[back]`, `[BROADCAST]`, `[REFRESH]`, `[DELAY]`, short `[TITLE]`, `[SOUND]`, `[EFFECT]`
  and `[BOSSBAR]` forms.
- New checks: `[has_tokens] 100`, `[has_money] 100`, `[has_permission] node`, `[expression] a >= b`
  (the 3.x `check(...)` forms still work).
- PlaceholderAPI: `%flaretokens_tokens%`, `_tokens_formatted`, `_tokens_commas`, `top_<n>_name`, `top_<n>_tokens`,
  `position`, `sale`, `sale_time`. They also work for offline players (holograms, leaderboards).
- Developer API: `FlareTokensAPI` and the cancellable `TokensChangeEvent`.
- Every balance change is written in `logs/transactions.log`.
- Missing PlaceholderAPI is downloaded and started automatically.

### Improved
- Balances of online players are kept in memory and saved in the background: the server never waits for the database.
- Placeholders of offline players no longer freeze the server: the balance is read in the background.
- Several servers can share one MySQL database: changes are atomic, so no token is lost or created twice.
- Balances go up to 9 quintillion (3.x stopped at 2.1 billion).
- Cheques can't be redeemed in creative mode (permission `flaretokens.redeem.creative` to allow it).

### Fixed
- `[tokens_give]`, `[tokens_set]` and `[tokens_remove]` did nothing.
- `drops.yml` did nothing.
- "Pay to offline players: false" also blocked payments to online players.
- `/tokens balance <player>` showed the wrong message.
- The help showed `pay [amount] [player]`: both orders are accepted now.

### Removed
- `check(javascript,...)`: Java no longer includes a JavaScript engine. Use `check(expression;a;op;b)`.
- `[op(...)]`: unsafe. Use `[CONSOLE]` with `{player}`.

### Anonymous statistics
- bStats is replaced by TMDStats. Turn it off with `stats: false` in `config.yml`,
  or for every TMDevelopment plugin in `plugins/TMDStats/config.yml`.
- The update check only reads the latest release from GitHub: nothing is sent.

# My Market (Fabric 1.21.1)

Money is a plain number (not an item). Every item has a price; buying costs 2x the sell price.
Prices follow the scale of donut.auction (netherite ingot = 4.7M, netherite block = 41.8M, elytra = 369M).

## Features
- Shop (`/shop`): scroll the list with the **mouse wheel** (or the up/down arrow buttons), click an item to buy.
  - Left click = chosen amount (1 / 16 / 64), right click = 1, shift+click = a full stack
  - **Search**: click the sign, type a name in the box, click the paper. Or `/shop <name>`
  - Rarity filter
- Money HUD in the top-right corner, always visible, small. Numbers: 1-999, 1K, 1.5M, 2.25B, T, Qa, Qi
- Everything is in English

## Commands
- /sell   : sell window (click an item in your inventory to sell it)
- /shop [search] : buy window (also /buy)
- /money  : show your money (also /balance)
- /price  : price of the item in your hand
- /money give|take <player> <amount> : admin only

## Install
Put the jar (plus Fabric API for 1.21.1) in `mods`.
- Server only: everything works with vanilla clients, except the mouse wheel and the money HUD.
- Also put it on the client (singleplayer already does) to get the mouse wheel scrolling and the HUD.

## Build
1. JDK 21. 2. `./gradlew build`  3. jar: `build/libs/mymarket-1.0.0.jar` (not the "-sources" one)

## Change prices
Edit `config/mymarket_prices_v2.json` (created on first run). These are SELL prices:
{ "minecraft:diamond": 20000, "minecraft:dirt": 0 }
0 = cannot be sold or bought. Restart the world to apply.
(The old `mymarket_prices.json` is no longer read.)

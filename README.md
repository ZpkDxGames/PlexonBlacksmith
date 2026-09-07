# PlexonBlacksmith 1.4.0

PlexonBlacksmith provides a direct, plugin-owned `/blacksmith` inventory for repair and enchanting on Paper 26.2.

## 1.4.0

This release preserves the production 1.3.0 GUI and pricing behavior while adding:

- optional PlexonCore 1.x module registration (`blacksmith`)
- standalone compatibility when PlexonCore is absent
- Bukkit `ServicesManager` public API
- post-commit repair and enchant events
- per-session transaction/reentrancy guards
- Vault rollback/refund support for failures before output commit
- explicit double-click collection protection
- session nonce/IDs and safe disconnect/shutdown return handling
- `/blacksmith diagnostics` and `/blacksmith reload`
- Java 25 / Paper 26.2 Maven build and tag-driven release CI

## Player workflow

### Repair

Place one damaged item directly into **Repair Input**. The exact item stays visible, the repaired copy and exact Vault quote appear, and the item is repaired only when the result is clicked with an empty cursor.

### Enchant

Place the target item and an enchanted book directly into the Enchant inputs. Compatible enchantment upgrades are previewed, the exact price is shown, and the book is consumed only after successful payment and output commit.

Inputs remain cached while switching pages. Closing the GUI returns every unused cached input; full inventories fall back to dropping the item naturally at the player's location.

## Requirements

- Paper 26.2
- Java 25
- Vault plus a Vault-compatible economy provider for paid transactions
- PlexonCore 1.0.0+ API 1.x is optional

## Commands

- `/blacksmith` — open the forge
- `/blacksmith diagnostics` — admin diagnostics
- `/blacksmith reload` — safely close active sessions and refresh economy/Core health

## Core modes

- Compatible PlexonCore present: `CORE`
- PlexonCore absent/disabled/incompatible: `STANDALONE`

Gameplay remains owned by PlexonBlacksmith in both modes.

## Upgrade from 1.3.0

Stop the server, replace `PlexonBlacksmith-1.3.0.jar` with `PlexonBlacksmith-1.4.0.jar`, keep the existing plugin folder, then start the server. Do not restore any old GUIPlus Blacksmith menu/alias.

# PlexonBlacksmith 2.0 Configuration

Configuration lives in `plugins/PlexonBlacksmith/config.yml`.

## Features

- `features.combine`: enables/disables Combine.
- `features.enchant`: enables/disables Enchant.

Disabled workstation pages enter a LOCKED state and cannot accept or consume items.

## Confirmation

- `confirmation.timeout-seconds`: time allowed for the second confirmation click. Valid range: 2–30 seconds.
- `confirmation.expensive-threshold`: Repair quotes at or above this value require confirmation.
- `confirmation.combine-always`: requires confirmation before donor consumption.
- `confirmation.enchant-always`: requires confirmation before enchanted-book consumption.

Any input/page change invalidates the armed confirmation.

## Pricing

`pricing.repair.full.*` defines the full-repair value by material tier. The charged amount is proportional to missing durability, subject to `pricing.repair.minimum`.

`pricing.combine.base` is the fixed combine fee. `pricing.combine.durability-bonus-percent` controls the bounded vanilla-style durability bonus. `pricing.combine.repair-value-multiplier` prices the durability actually restored.

`pricing.enchant.base` and `pricing.enchant.rates.*` preserve the 1.4 pricing model while making the rates configurable.

Out-of-range/non-finite numeric settings fall back to safe defaults and are reported by `/blacksmith diagnostics`.

## Reload

Stable 2.0 uses a fail-closed reload boundary.

`/blacksmith reload` first parses the candidate `config.yml` without changing the running plugin. If the file is missing or malformed, reload is rejected and the accepted runtime plus every active exact-ItemStack custody session remain unchanged.

Only after the candidate YAML is parseable does the existing reload path run: active workstation sessions are closed, unused inputs are returned, configuration is reloaded and the current Vault provider is re-resolved. This prevents both malformed configuration fallback and live sessions changing price/ownership rules halfway through a transaction.

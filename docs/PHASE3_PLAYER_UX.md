# PlexonBlacksmith Phase 3 — Player UX Product Layer

## Scope

This Phase 3 branch changes the player experience only. `PlexonBlacksmithBridge` remains the sole Phase 2 authority for exact quotes, item custody, validation, Vault withdrawal/refund, Repair, Combine, Enchant, transaction completion, events, and close/quit/disable recovery.

The Phase 3 main class extends that authority and adds a player-facing navigation/presentation layer. It does not implement another transaction engine.

## Frozen base

- Phase 2 branch: `phase2/2.0.0-premium-workstation`
- Exact Phase 2 candidate: `475f1e8005fd6ed5f9118be7385473a69a574b05`
- Published candidate: `v2.0.0-rc.1`
- Phase 3 branch: `phase3/player-ux-overhaul`

## Old player journey

```text
/blacksmith
  -> Repair page immediately
     -> Previous / Next changes workstation mode
     -> result preview slot is also the operation action
     -> Guide sends chat messages
     -> generic WAITING / LOCKED state card
```

The Phase 2 implementation is safe, but players have to infer that the three pages are separate services and that the preview item doubles as the state-changing control.

## New player journey

```text
/blacksmith
  -> BLACKSMITH HOME
     -> REPAIR
        -> input
        -> exact result preview
        -> quote/status
        -> explicit REPAIR ITEM action
     -> COMBINE
        -> primary + donor
        -> compatibility/result preview
        -> quote/status
        -> explicit COMBINE ITEMS action
     -> ENCHANT
        -> target + enchanted book
        -> compatible exact result preview
        -> quote/status
        -> explicit APPLY ENCHANTMENTS action
     -> GUIDE
```

Home and Guide are non-transactional. Workstation result slots are preview-only on the Phase 3 player surface; all state-changing work is submitted through the fixed primary-action slot.

## 54-slot workstation convention

The Phase 2 input/output positions are preserved so cached item custody and exact result construction do not move underneath the player.

Bottom row:

- 45 — Repair mode
- 46 — Combine mode
- 47 — Enchant mode
- 48 — Blacksmith Home
- 49 — Primary action
- 50 — Guide
- 51 — Status / Quote
- 52 — Close
- 53 — reserved/filler

There is no fake Previous/Next pagination because the workstation has no paged result list.

## Repair presentation

Repair keeps the accepted Phase 2 input and transaction implementation. The UX projects:

- exact input item;
- exact repaired result preview;
- quote from `PlexonBlacksmithAPI.quoteRepair`;
- one safe Vault balance snapshot where available;
- human states such as `WAITING FOR ITEM`, `READY TO REPAIR`, `ALREADY FULLY REPAIRED`, `NOT REPAIRABLE`, `INSUFFICIENT FUNDS`, and `ECONOMY UNAVAILABLE`;
- fixed primary action in slot 49.

The result preview cannot be used as the commit action. Inserting or inspecting an item never charges money.

## Combine presentation

Combine preserves Phase 2's material/custom-identity normalization and donor-consumption rules. The UX shows:

- primary item;
- donor item;
- exact result preview when the backend quote is combinable;
- authoritative cost;
- balance where available;
- missing-input and compatibility states in player language.

Same-type/custom identity authority stays in `buildCombineResult` / the public quote API. The GUI does not implement arbitrary item fusion.

Existing confirmation semantics remain intact because the donor is destructive input. The first primary click may arm the accepted Phase 2 confirmation; the second accepted click performs the existing synchronous transaction.

## Enchant presentation

Enchant is still target + enchanted book. It is not converted into an enchanting-table replacement.

The UX projects `quoteEnchant` and shows only a compatible result when the Phase 2 backend can actually apply at least one higher-level, non-conflicting enchantment. Exact target metadata is preserved by the existing result builder.

Existing book-consumption confirmation remains intact.

## Quote and economy authority

The presentation layer never independently calculates service prices. It reads the existing public quote objects:

```text
authoritative cached inputs
    -> existing Phase 2 quote/service
    -> Phase 3 presentation
    -> exact result preview + cost + status
    -> explicit action
    -> existing Phase 2 transaction
```

Vault remains the economy boundary. Phase 3 uses reflection only for a read-only `getBalance` snapshot because PlexonBlacksmith deliberately does not compile against or register an economy provider. No withdraw/deposit/refund method exists in the Phase 3 class.

## Exact result preview

Preview items are cloned from the authoritative result snapshot. Phase 3 may append presentation lore to the clone only. It does not reconstruct the item from material/name/lore, and never mutates the committed result source.

The preview slot is intercepted before the Phase 2 result action can run. Shift-click and other clicks on the preview therefore cannot grant the result.

## Input custody

The Phase 2 session remains the only item-custody owner. Mode switching uses the existing in-session mode transitions, so cached inputs stay exact and safe. Returning to Blacksmith Home closes the Phase 2 workstation first, allowing its existing close handler to return every unused cached input before the Home inventory opens.

Quit, reload, disable and server shutdown continue using the existing Phase 2 return path. Phase 3 adds no world-drop or alternate recovery logic.

## Duplicate click and stale presentation

Phase 2's synchronous `transactionActive` guard and current-input rebuild remain authoritative.

Phase 3 adds only a defensive one-submit set around the explicit primary control. It also stores a presentation token containing the active Phase 2 session ID, page, input/result hash, price and confirmation state. Before submitting, the current public session snapshot must still match the rendered token. A mismatch refreshes the quote instead of submitting stale presentation state.

This guard does not replace the transaction/session authority.

## Performance

Phase 3 introduces no repeating task, inventory tick refresh, per-slot Vault poll, database/file I/O, or serialization pass. Presentation refresh happens only after meaningful input, drag, mode, guide, Back, confirmation or transaction actions.

Balance is read at most once for a valid priced presentation refresh. Static controls are small bounded 54-slot renders.

## Configuration compatibility

`config.yml` is unchanged. Existing:

- feature flags;
- confirmation policy;
- repair pricing;
- combine pricing;
- enchant pricing

remain authoritative. No administrator migration is required.

The production GUIPlus `blacksmith` alias is not touched by this source branch; cutover remains a later deployment task.

## Runtime validation matrix

1. `/blacksmith` opens Home.
2. Repair empty state.
3. Repair damaged vanilla item.
4. Repair custom/PDC item.
5. Repair full-durability item.
6. Insufficient funds.
7. Successful Repair charge/result.
8. Rapid duplicate Repair click.
9. Combine compatible pair.
10. Combine incompatible pair.
11. Custom identity mismatch.
12. Successful Combine.
13. Enchant supported item/book.
14. Incompatible enchantment.
15. Successful Enchant.
16. Back/Close returns cached inputs.
17. Disconnect with cached input.
18. Reload with active session.
19. Plugin/server restart behavior.
20. Verify exact PDC/components after each operation.
21. Verify Vault/Theosis balance correctness.
22. Attempt preview extraction with shift/double/hotbar/drag interactions.
23. Repeated Home/workstation/Guide navigation under Spark/MSPT observation.
24. Verify GUIPlus alias cutover only during deployment.
25. Integrated >=30-minute soak.

Runtime certification is not implied by source CI and remains pending until this matrix is executed on PlexonCraft.

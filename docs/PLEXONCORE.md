# PlexonCore Integration

PlexonBlacksmith 1.4.0 supports PlexonCore API `>=1.0 <2.0` as an optional/provided dependency.

Module identity:

- ID: `blacksmith`
- Display: `PlexonBlacksmith`
- Integration: `PLEXON_BLACKSMITH`

Published capabilities include repair, enchanting, public API/events, economy transactions, GUI input caching and custom item preservation.

The Core bridge is isolated behind a reflection-gated factory so PlexonBlacksmith can start without PlexonCore classes at runtime.

Core present and compatible results in `CORE` mode. Missing, disabled, incompatible or unresolvable Core falls back to `STANDALONE`; repair/enchant gameplay remains owned by PlexonBlacksmith.

PlexonCore is never shaded into the Blacksmith JAR.

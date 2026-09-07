# PlexonBlacksmith 1.3.0

This build owns the complete `/blacksmith` runtime inventory. It no longer modifies GUIPlus-owned slots.

Why: GUIPlus redraws scene item definitions after runtime edits, so cached player items and dynamic result/price icons can be overwritten by the configured default icons. A mutable service inventory therefore cannot reliably share slot ownership with GUIPlus.

GUIPlus can remain installed for the rest of the server. Remove only the old Blacksmith GUIPlus YAML/alias.

# Custom group membership foundation

The fork uses upstream `custom_category_members` (fork Room schema 48, upstream baseline 47). A membership is scoped by
`profileId`, `mediaType`, `contextKey`, and `itemId`; custom groups can share members and combine
sources. Provider category IDs and source identities remain unchanged.

`CustomCategoryDao.appendItem` and `appendItems` append absent members. Repeating Copy preserves
the original membership row and position. Bulk input is deduplicated in first-occurrence order;
existing destination members retain their order. Queries and inserts use batches of 500 inside
one Room transaction. `appendItems` returns the actual number added. Concurrent appends serialize
position allocation; position overflow rolls back the transaction, including earlier batches.

`insertAll` retains replacement behavior for existing restore/relink/order callers. The new
append path uses conflict-ignore inserts. Schema 48 adds `addedAt` (last addition time) to memberships; the 47→48 migration preserves
all existing rows, positions, indexes, and profile cascades and assigns legacy rows zero. New
appends record an event time after the profile’s observed membership deletions; removing a
member records a time after its addition. This preserves local causal order for same-millisecond
actions and an already observed peer clock ahead of ours. Idempotent Copy does not change time.

User-data export, source-resync snapshots, and pending identity relinking carry `addedAt` in the
existing optional JSON `at` field. Backup format version 24 and custom UUID/source/profile remapping
remain compatible. Older membership records without `at` read as zero. An explicit RESTORE clears
local deletion markers before applying those records, as upstream already does. MERGE refuses
records older than a local deletion; incoming stale deletions cannot erase a newer re-addition.
Restoring an older live record retains the existing position policy without rolling back its
newer local addition time. This uses the existing wall-clock merge model: equal-time deletions
win, and clock skew between devices remains a limitation. Old Core versions cannot provide the
new timestamp comparison; both sync peers should use this fork before relying on re-add ordering.

Copy only adds destination membership. Upstream Move also suppresses a provider origin via
`SectionCustomizations.movedFromOrigin`, or removes a custom-origin membership through
`UserDataWriter` (to retain local-sync deletion markers). Global hiding is the separate
`hiddenItems` map. The TV's existing “keep in original” option selects Copy semantics.

This DAO primitive does not validate custom group definitions, profile access, source permissions,
or resolve stale caller-supplied item IDs. A domain-facing management service must do so before
exposing these operations to a browser. Provider Move still crosses Room and DataStore; durable
bulk Move coordination and recovery remain separate work. This change does not claim atomicity
across those stores or implement the complete group-management milestone.

`CustomCategoryMembershipTest` has 16 passing cases against production `BundledSQLiteDriver`, covering idempotent
Copy, several custom groups, provider preservation, profile/source/media-type scope, resync row-ID changes,
disappearance/return, backup membership restore, overlapping concurrent bulk copies, 50k members,
and rollback at position overflow. It also covers scoped deletion markers, stale snapshots and
deletions, explicit legacy restore, and addition-time preservation across resync/backup.
`OwnTVDatabaseMigrationTest` verifies populated schema 47 upgrades and old migration paths. Run on a disposable test target, never a populated TV.

Validation: Core and Player Core unit tests pass (914 + 252); Core device suite passes all 97
cases, including the 16 migration cases. Core/Player Core lint passes with zero errors.

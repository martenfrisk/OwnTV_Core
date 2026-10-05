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

The DAO remains an internal primitive. `GroupService` is the shared mutation boundary for TV and
future management clients; it validates current custom definitions, profile/source/type scope,
Kids restrictions, item identities and source membership. Optional expected revisions detect
conflicting clients. TV Move/Copy/Remove/global Hide and item-list range Hide/Unhide use it.
Move from All/search/recent derives each item's actual provider origin. Ordinary Move preserves
favorites; only the explicit legacy Favorites-origin TV adapter removes them.

`CustomCategoryMembershipTest` has 16 passing cases against production `BundledSQLiteDriver`, covering idempotent
Copy, several custom groups, provider preservation, profile/source/media-type scope, resync row-ID changes,
disappearance/return, backup membership restore, overlapping concurrent bulk copies, 50k members,
and rollback at position overflow. It also covers scoped deletion markers, stale snapshots and
deletions, explicit legacy restore, and addition-time preservation across resync/backup.
`OwnTVDatabaseMigrationTest` verifies populated schema 47 upgrades and old migration paths. Run on a disposable test target, never a populated TV.

Baseline validation at the membership-foundation commit: 914 + 252 unit cases and 97 Core
device cases passed, including 16 migration cases; lint had zero errors.


## Durable commands and catalog coordination

`GroupEdit` accepts a `GroupScope`, action, item IDs and optional destination/origin/revision.
The service deduplicates selections in first-occurrence order, validates the entire selection in
500-row credential-free projections, then saves private AtomicFile chunks and a manifest before
changing canonical data. New destination positions and addition/removal times are part of that
intent. Copy preserves existing membership positions/times. Invalid late batches publish nothing.

The journal at `filesDir/group-operations` bridges Room and DataStore. It contains identity and
ordering metadata, never stream URLs/passwords. Startup and the next command recover it.
Completed chunks also replay after a restart: their catalog row IDs can change or be reused.
Recovery first detaches matching old rows across all chunks, then reattaches every desired
membership through the existing remote-ID/name resolver. This preserves earlier destination
members and handles ID swaps across batch boundaries. Exact saved position/time guards protect
newer rows; unresolved identities go through the existing pending-user-data mechanism. Custom
membership and explicit Favorites-origin removals retain stable deletion markers through
UserDataWriter. Missing/corrupt journal files retain the command and fail closed.

Group mutations serialize. SourceRepository refresh/update/delete and Stalker background backfill
hold per-source catalog locks; unrelated imports remain parallel. Backup export/import, profile
changes and TV group-definition creation/deletion finish accepted commands before reading or
changing their scope. The journal is recovery metadata, not canonical backup data; schema 48 and
backup format 24 remain unchanged. Catalog revisions persist across restart and advance after
changes, including partial failed/cancelled imports. Screen cancellation remains possible during
planning, while published commands finish before releasing their locks.

This service does not complete the full group milestone: durable definition deletion, complete
CRUD/duplicate/merge/split, bulk naming/reordering/reset, remaining TV editor work and the remote
management API still require implementation and acceptance. It is also not a catalog/Guide browse,
playback, low-memory or representative-hardware performance claim.


`GroupServiceTest` adds 26 device cases against the real stores. The complete Core device suite
now passes 123 cases, including the original 16 membership and 16 migration cases. Core/Player
Core JVM counts remain 914/252; TV JVM/device counts remain 177/19. Fault-injection repeats are
excluded from these unique counts. The 50,000-item cases contain actual catalog rows and verify
Copy ordering and provider Move's independent suppression; they are domain-operation tests,
not full browse/playback performance acceptance.

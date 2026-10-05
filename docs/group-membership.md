# Custom group membership foundation

The fork uses upstream `custom_category_members` (fork Room schema 49, upstream baseline 47). A membership is scoped by
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
changing their scope. The journal is recovery metadata, not canonical backup data. Membership commands retain backup
format 24; the definition-deletion follow-up below adds schema 49. Catalog revisions persist across restart and advance after
changes, including partial failed/cancelled imports. Screen cancellation remains possible during
planning, while published commands finish before releasing their locks.

The original service checkpoint did not complete the full group milestone. Definition edits are
implemented below; duplicate/merge/split composition, remaining TV editor work and the remote
management API still require implementation and acceptance. It is also not a catalog/Guide browse,
playback, low-memory or representative-hardware performance claim.


`GroupServiceTest` adds 26 device cases against the real stores. The complete Core device suite
now passes 123 cases, including the original 16 membership and 16 migration cases. Core/Player
Core JVM counts remain 914/252; TV JVM/device counts remain 177/19. Fault-injection repeats are
excluded from these unique counts. The 50,000-item cases contain actual catalog rows and verify
Copy ordering and provider Move's independent suppression; they are domain-operation tests,
not full browse/playback performance acceptance.


## Durable definition edits and backup deletion

`GroupDefinitionEdit` routes Create, Rename (including bulk display overrides), Delete, Hide,
Unhide, Reset and Reorder through the journal. Names trim and validate before mutation; a null
rename clears the display override. Provider identities remain unchanged. Scope validation and
expected catalog revisions match membership commands. Filtered reordering replaces selected
slots in the full order, retaining every other source's placement. Reset clears only the selected
groups' hide/name/order overrides and provider suppression; custom memberships remain intact.

Definition deletion always restores provider origins for former members, preserving the existing
TV policy even if another custom group contains an item. It preserves global item hiding, favorites
and other custom memberships. Before clearing membership identities, it writes canonical `group`
and `member` deletion facts, removes the definition and its pins and restores provider keys. Room
membership/order and pending records are then removed. Schema 49's additive `groupAppliedAt`
column marks cleanup completion for the exact deletion event. A newer deletion cannot be marked
complete by an older cleanup. Startup also completes incoming intents without a private journal.

Merge refuses a retired UUID's stale definitions, membership and group-specific order even while
catalog rows are missing; it discards refused pending records. Received member facts can restore
provider keys after their Room rows were already removed. Local groups absent from an offline
peer survive section merging with their local group pins; incoming metadata wins for shared UUIDs.
Explicit Restore keeps upstream's deletion-marker reset and can restore the original UUID.
Organizational facts survive the watch/favorite tombstone cap, including edits over 10,000 members.
Pending resolution/relink/deletion snapshots serialize, preserving concurrently appended records.

Customize-only backups now carry custom memberships together with definitions and deletion facts.
Manual Reorder continues accepting memberships for existing callers/files. Deleted-only backup
payloads are discoverable. Canonical profile IDs remap normally; group UUIDs and source sentinel
-1 stay unchanged. Backup format stays 24, and old readers cannot enforce the new group deletion
policy. This is the existing identity/resolver system, not a parallel fingerprint.

The TV's existing definition actions now use this shared boundary and localized validation errors.
A D-pad reorder carries the profile/media/source scope captured when the editor opens. Bulk rename
captures selected rows, accepted names and persistence callbacks before queued writes run;
obsolete previews cannot reopen or replace a dismissed/new editor. The provider/catalog and
playback paths remain separate from these user-data edits.

Duplicate/merge/split composition, remaining item/group actions and their TV/browser editors still
belong to the full-plan milestone. Automated persistence tests do not establish browse/Guide,
playback, hardware or D-pad golden acceptance.


Definition checkpoint verification: Core/Player JVM suites pass 918/252 cases, including four
queued-editor regression cases. Core device suite passes 139 cases: 41 GroupService, 16 membership,
17 migration and existing subsystem cases. Recovery tests interrupt definition publication and each
cleanup boundary; backup tests use actual Customize-only and deletion-only files, including profile
remapping, stale Merge and explicit Restore. The 10,001-member deletion retains every removal fact
past the watch-history cap. Missing identities in retired groups are refused instead of left pending.
Lint remains zero errors/eight existing Core warnings and zero Player warnings; no new group findings.

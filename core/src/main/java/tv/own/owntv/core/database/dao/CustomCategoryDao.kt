package tv.own.owntv.core.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.CustomCategoryMemberEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.model.MediaType

// Three scope parameters plus a bounded ID set stay below older SQLite's 999 bind limit.
private const val MEMBERSHIP_APPEND_CHUNK = 500

/**
 * Membership rows of the user's custom combined categories (issue #87). Each row pins one content
 * item to a [position] inside a custom category, identified by its stable DataStore key
 * (`"custom:<uuid>"`, `CustomizeKeys.CUSTOM_PREFIX`) as [CustomCategoryMemberEntity.contextKey].
 * The table is modeled on `content_order`: membership defines the SET, while the user's manual
 * reorder WITHIN a custom category still rides the existing `content_order` rows (same contextKey —
 * the browse queries below LEFT JOIN both). See [CustomCategoryMemberEntity].
 */
@Dao
interface CustomCategoryDao {

    // --- write side (mirrors ContentOrderDao) ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<CustomCategoryMemberEntity>)

    /** Copy never replaces an existing membership; restore/reorder still use [insertAll]. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAbsentMembers(rows: List<CustomCategoryMemberEntity>): List<Long>

    @Query("SELECT itemId FROM custom_category_members WHERE profileId = :profileId AND mediaType = :type AND contextKey = :contextKey AND itemId IN (:itemIds)")
    suspend fun existingItemIds(profileId: Long, type: MediaType, contextKey: String, itemIds: List<Long>): List<Long>

    @Query("SELECT * FROM custom_category_members WHERE profileId = :profileId AND mediaType = :type AND contextKey = :contextKey AND itemId IN (:itemIds)")
    suspend fun membersForIds(profileId: Long, type: MediaType, contextKey: String, itemIds: List<Long>): List<CustomCategoryMemberEntity>

    /** Recovery detaches only the exact old row; it never deletes a newer membership on a reused ID. */
    @Query("DELETE FROM custom_category_members WHERE profileId = :profileId AND mediaType = :type AND contextKey = :contextKey AND itemId = :itemId AND position = :position AND addedAt = :addedAt")
    suspend fun detachJournalRow(profileId: Long, type: MediaType, contextKey: String, itemId: Long, position: Int, addedAt: Long)

    @Query("DELETE FROM custom_category_members WHERE profileId = :profileId AND mediaType = :type AND contextKey = :contextKey")
    suspend fun clearContext(profileId: Long, type: MediaType, contextKey: String)

    @Query("SELECT * FROM custom_category_members WHERE profileId = :profileId AND mediaType = :type AND contextKey = :contextKey AND id > :afterId ORDER BY id LIMIT :limit")
    suspend fun membersAfter(profileId: Long, type: MediaType, contextKey: String, afterId: Long, limit: Int): List<CustomCategoryMemberEntity>

    @Query("SELECT COALESCE(MAX(addedAt), 0) FROM custom_category_members WHERE profileId = :profileId AND mediaType = :type AND contextKey IN (:keys)")
    suspend fun latestMemberTime(profileId: Long, type: MediaType, keys: List<String>): Long

    /** Context keys that actually have membership rows for this profile/section. */
    @Query("SELECT DISTINCT contextKey FROM custom_category_members WHERE profileId = :profileId AND mediaType = :type")
    fun observeContextKeys(profileId: Long, type: MediaType): Flow<List<String>>

    /** Replaces a context's entire membership in one transaction (commit point of a Move). */
    @Transaction
    suspend fun replaceContext(profileId: Long, type: MediaType, contextKey: String, rows: List<CustomCategoryMemberEntity>) {
        clearContext(profileId, type, contextKey)
        if (rows.isNotEmpty()) insertAll(rows)
    }

    /** Highest existing position for a context — append-point for "Move to…". */
    @Query("SELECT COALESCE(MAX(position), -1) FROM custom_category_members WHERE profileId = :profileId AND mediaType = :type AND contextKey = :contextKey")
    suspend fun maxPosition(profileId: Long, type: MediaType, contextKey: String): Int

    /** Appends only if absent. Repeating Copy preserves the row ID and its existing position. */
    @Transaction
    suspend fun appendItem(profileId: Long, type: MediaType, contextKey: String, itemId: Long) {
        appendItems(profileId, type, contextKey, listOf(itemId))
    }

    /**
     * Bulk Copy's membership primitive. Keeps existing members and their ordering, appends missing
     * items in first-selected order, and returns how many were added. One transaction serializes the
     * max-position read and every batch, so concurrent appends cannot claim the same positions.
     * Queries/inserts are chunked; no full destination catalog is loaded. Provider data, favorites,
     * suppression and global hiding are unchanged — Move coordinates its origin separately.
     */
    @Transaction
    suspend fun appendItems(profileId: Long, type: MediaType, contextKey: String, itemIds: List<Long>): Int {
        if (itemIds.isEmpty()) return 0
        var nextPosition = maxPosition(profileId, type, contextKey).toLong() + 1
        var added = 0
        val deletedAt = latestDeletion(profileId)
        check(deletedAt < Long.MAX_VALUE)
        val addedAt = maxOf(System.currentTimeMillis(), deletedAt + 1)
        for (batch in itemIds.distinct().chunked(MEMBERSHIP_APPEND_CHUNK)) {
            val existing = existingItemIds(profileId, type, contextKey, batch).toHashSet()
            val missing = batch.filterNot { it in existing }
            if (missing.isEmpty()) continue
            // Fail the whole transaction rather than wrapping to negative positions.
            check(nextPosition + missing.size - 1 <= Int.MAX_VALUE)
            val rows = missing.map { itemId ->
                CustomCategoryMemberEntity(
                    profileId = profileId, mediaType = type, contextKey = contextKey,
                    itemId = itemId, position = (nextPosition++).toInt(),
                    addedAt = addedAt,
                )
            }
            added += insertAbsentMembers(rows).count { it != -1L }
        }
        return added
    }

    /** Does this row already exist? The dry run before a sync counts what is genuinely new. */
    @Query("SELECT EXISTS(SELECT 1 FROM custom_category_members WHERE profileId = :profileId AND mediaType = :type AND contextKey = :contextKey AND itemId = :itemId)")
    suspend fun exists(profileId: Long, type: MediaType, contextKey: String, itemId: Long): Boolean

    /** Removes ONE membership row — "Move to…" away from a custom-category origin without keeping it. */
    /** The custom categories [itemId] belongs to — a channel kept in one stays visible when its own
     *  provider folder is hidden. */
    @Query("SELECT DISTINCT contextKey FROM custom_category_members WHERE profileId = :profileId AND mediaType = :type AND itemId = :itemId")
    suspend fun contextsOf(profileId: Long, type: MediaType, itemId: Long): List<String>

    @Query("DELETE FROM custom_category_members WHERE profileId = :profileId AND mediaType = :type AND contextKey = :contextKey AND itemId = :itemId")
    suspend fun deleteItem(profileId: Long, type: MediaType, contextKey: String, itemId: Long)

    @Query("SELECT addedAt FROM custom_category_members WHERE profileId = :profileId AND mediaType = :type AND contextKey = :contextKey AND itemId = :itemId")
    suspend fun addedAt(profileId: Long, type: MediaType, contextKey: String, itemId: Long): Long?

    @Query("DELETE FROM custom_category_members WHERE profileId = :profileId AND mediaType = :type AND contextKey = :contextKey AND itemId = :itemId AND addedAt <= :at")
    suspend fun deleteIfOlderThan(profileId: Long, type: MediaType, contextKey: String, itemId: Long, at: Long): Int

    @Query("SELECT COALESCE(MAX(deletedAt), 0) FROM user_data_tombstones WHERE profileId = :profileId AND kind = 'member'")
    suspend fun latestDeletion(profileId: Long): Long

    /**
     * Resolvable member counts for the active sources — the "Move to…" dialog's per-row badges.
     * A re-sync temporarily leaves stale membership ids until relinking finishes, and a profile may
     * disable/remove a source. Neither kind of row is visible in the category, so neither may inflate
     * the badge.
     */
    @Query(
        "SELECT contextKey, COUNT(*) AS count FROM custom_category_members " +
            "WHERE profileId = :profileId AND mediaType = :type AND contextKey IN (:contextKeys) AND (" +
            "(:type = 'LIVE' AND itemId IN (SELECT id FROM channels WHERE sourceId IN (:sourceIds))) OR " +
            "(:type = 'MOVIE' AND itemId IN (SELECT id FROM movies WHERE sourceId IN (:sourceIds))) OR " +
            "(:type = 'SERIES' AND itemId IN (SELECT id FROM series WHERE sourceId IN (:sourceIds)))" +
            ") GROUP BY contextKey",
    )
    fun observeCountsByContexts(
        profileId: Long,
        type: MediaType,
        contextKeys: List<String>,
        sourceIds: List<Long>,
    ): Flow<List<CustomCategoryCount>>

    /** Everything, for Backup & Restore / re-sync snapshotting. */
    @Query("SELECT * FROM custom_category_members")
    suspend fun getAllOnce(): List<CustomCategoryMemberEntity>

    /** Stable customization keys for every live member of one custom category. Used before delete
     *  to restore items that had been moved out of their provider folder. */
    @Query(
        "SELECT CAST(c.sourceId AS TEXT) || ':' || COALESCE(c.remoteId, c.name) " +
            "FROM custom_category_members m INNER JOIN channels c ON m.mediaType = 'LIVE' AND m.itemId = c.id " +
            "WHERE m.profileId = :profileId AND m.mediaType = 'LIVE' AND m.contextKey = :contextKey " +
            "UNION ALL " +
            "SELECT CAST(mv.sourceId AS TEXT) || ':' || COALESCE(mv.remoteId, mv.name) " +
            "FROM custom_category_members m INNER JOIN movies mv ON m.mediaType = 'MOVIE' AND m.itemId = mv.id " +
            "WHERE m.profileId = :profileId AND m.mediaType = 'MOVIE' AND m.contextKey = :contextKey " +
            "UNION ALL " +
            "SELECT CAST(s.sourceId AS TEXT) || ':' || COALESCE(s.remoteId, s.name) " +
            "FROM custom_category_members m INNER JOIN series s ON m.mediaType = 'SERIES' AND m.itemId = s.id " +
            "WHERE m.profileId = :profileId AND m.mediaType = 'SERIES' AND m.contextKey = :contextKey",
    )
    suspend fun stableItemKeys(profileId: Long, contextKey: String): List<String>

    /**
     * Membership rows tied to one source, joined to stable content keys — the per-source re-sync
     * snapshot. Mirrors [ContentOrderDao.exportRowsForSource]: without this, a resync renumbered
     * the content ids and every Move the user had made was silently lost.
     */
    @Query(
        "SELECT m.profileId AS profileId, m.mediaType AS mediaType, m.itemId AS itemId, " +
            "m.contextKey AS contextKey, m.position AS position, m.addedAt AS addedAt, " +
            "COALESCE(c.sourceId, mv.sourceId, s.sourceId) AS sourceId, " +
            "COALESCE(c.remoteId, mv.remoteId, s.remoteId) AS remoteId, " +
            "COALESCE(c.name, mv.name, s.name) AS name " +
            "FROM custom_category_members m " +
            "LEFT JOIN channels c ON m.mediaType = 'LIVE' AND m.itemId = c.id " +
            "LEFT JOIN movies mv ON m.mediaType = 'MOVIE' AND m.itemId = mv.id " +
            "LEFT JOIN series s ON m.mediaType = 'SERIES' AND m.itemId = s.id " +
            "WHERE c.sourceId = :sourceId OR mv.sourceId = :sourceId OR s.sourceId = :sourceId",
    )
    suspend fun exportRowsForSource(sourceId: Long): List<CustomCategoryMemberExportRow>

    /** Snapshot-scoped orphan drop, mirroring FavoriteDao.purgeSnapshotOrphan. */
    @Query(
        "DELETE FROM custom_category_members WHERE profileId = :profileId AND mediaType = :type AND itemId = :itemId AND (" +
            "(:type = 'LIVE'   AND itemId NOT IN (SELECT id FROM channels)) OR " +
            "(:type = 'MOVIE'  AND itemId NOT IN (SELECT id FROM movies))   OR " +
            "(:type = 'SERIES' AND itemId NOT IN (SELECT id FROM series))" +
            ")",
    )
    suspend fun purgeSnapshotOrphan(profileId: Long, type: MediaType, itemId: Long)

    /**
     * Drops membership rows whose content row no longer exists — content is clear-then-insert on
     * every sync, so an item's itemId goes stale. Called after a re-sync's relink
     * (UserDataResolver), mirroring FavoriteDao.purgeOrphans.
     */
    @Query(
        "DELETE FROM custom_category_members WHERE " +
            "(mediaType = 'LIVE'   AND itemId NOT IN (SELECT id FROM channels)) OR " +
            "(mediaType = 'MOVIE'  AND itemId NOT IN (SELECT id FROM movies))   OR " +
            "(mediaType = 'SERIES' AND itemId NOT IN (SELECT id FROM series))",
    )
    suspend fun purgeOrphans()

    // --- browse side: paging / count / search (mirrors the ChannelDao manual-order joins) ---

    /**
     * Paged content of a custom category in rail order. Membership defines the SET (INNER JOIN);
     * the user's manual reorder within the category rides content_order with the same contextKey
     * (LEFT JOIN, matching [ContentOrderDao]'s position semantics); items without an explicit
     * order row fall back to membership position (insertion order).
     */
    @Query(
        "SELECT c.* FROM channels c " +
            "INNER JOIN custom_category_members m ON m.itemId = c.id AND m.profileId = :profileId AND m.mediaType = 'LIVE' AND m.contextKey = :contextKey " +
            "LEFT JOIN content_order o ON o.itemId = c.id AND o.profileId = :profileId AND o.mediaType = 'LIVE' AND o.contextKey = :contextKey " +
            "WHERE c.sourceId IN (:sourceIds) " +
            "ORDER BY (CASE WHEN o.position IS NULL THEN 1 ELSE 0 END), o.position, m.position, c.sortOrder, c.name",
    )
    fun pagingChannels(profileId: Long, contextKey: String, sourceIds: List<Long>): PagingSource<Int, ChannelEntity>

    /** A–Z variant of [pagingChannels]: a manually placed channel keeps its saved position, the rest
     *  sort by name instead of falling back to membership/provider order — the same "manual order
     *  wins, the rest goes A–Z" convention the folder list itself uses (see `alphaRest`). */
    @Query(
        "SELECT c.* FROM channels c " +
            "INNER JOIN custom_category_members m ON m.itemId = c.id AND m.profileId = :profileId AND m.mediaType = 'LIVE' AND m.contextKey = :contextKey " +
            "LEFT JOIN content_order o ON o.itemId = c.id AND o.profileId = :profileId AND o.mediaType = 'LIVE' AND o.contextKey = :contextKey " +
            "WHERE c.sourceId IN (:sourceIds) " +
            "ORDER BY (CASE WHEN o.position IS NULL THEN 1 ELSE 0 END), o.position, c.name",
    )
    fun pagingChannelsAlpha(profileId: Long, contextKey: String, sourceIds: List<Long>): PagingSource<Int, ChannelEntity>

    @Query(
        "SELECT mv.* FROM movies mv " +
            "INNER JOIN custom_category_members m ON m.itemId = mv.id AND m.profileId = :profileId AND m.mediaType = 'MOVIE' AND m.contextKey = :contextKey " +
            "LEFT JOIN content_order o ON o.itemId = mv.id AND o.profileId = :profileId AND o.mediaType = 'MOVIE' AND o.contextKey = :contextKey " +
            "WHERE mv.sourceId IN (:sourceIds) " +
            "ORDER BY (CASE WHEN o.position IS NULL THEN 1 ELSE 0 END), o.position, m.position, mv.sortOrder, mv.name",
    )
    fun pagingMovies(profileId: Long, contextKey: String, sourceIds: List<Long>): PagingSource<Int, MovieEntity>

    /** A–Z variant of [pagingMovies] — see [pagingChannelsAlpha]. */
    @Query(
        "SELECT mv.* FROM movies mv " +
            "INNER JOIN custom_category_members m ON m.itemId = mv.id AND m.profileId = :profileId AND m.mediaType = 'MOVIE' AND m.contextKey = :contextKey " +
            "LEFT JOIN content_order o ON o.itemId = mv.id AND o.profileId = :profileId AND o.mediaType = 'MOVIE' AND o.contextKey = :contextKey " +
            "WHERE mv.sourceId IN (:sourceIds) " +
            "ORDER BY (CASE WHEN o.position IS NULL THEN 1 ELSE 0 END), o.position, mv.name",
    )
    fun pagingMoviesAlpha(profileId: Long, contextKey: String, sourceIds: List<Long>): PagingSource<Int, MovieEntity>

    @Query(
        "SELECT s.* FROM series s " +
            "INNER JOIN custom_category_members m ON m.itemId = s.id AND m.profileId = :profileId AND m.mediaType = 'SERIES' AND m.contextKey = :contextKey " +
            "LEFT JOIN content_order o ON o.itemId = s.id AND o.profileId = :profileId AND o.mediaType = 'SERIES' AND o.contextKey = :contextKey " +
            "WHERE s.sourceId IN (:sourceIds) " +
            "ORDER BY (CASE WHEN o.position IS NULL THEN 1 ELSE 0 END), o.position, m.position, s.sortOrder, s.name",
    )
    fun pagingSeries(profileId: Long, contextKey: String, sourceIds: List<Long>): PagingSource<Int, SeriesEntity>

    /** A–Z variant of [pagingSeries] — see [pagingChannelsAlpha]. */
    @Query(
        "SELECT s.* FROM series s " +
            "INNER JOIN custom_category_members m ON m.itemId = s.id AND m.profileId = :profileId AND m.mediaType = 'SERIES' AND m.contextKey = :contextKey " +
            "LEFT JOIN content_order o ON o.itemId = s.id AND o.profileId = :profileId AND o.mediaType = 'SERIES' AND o.contextKey = :contextKey " +
            "WHERE s.sourceId IN (:sourceIds) " +
            "ORDER BY (CASE WHEN o.position IS NULL THEN 1 ELSE 0 END), o.position, s.name",
    )
    fun pagingSeriesAlpha(profileId: Long, contextKey: String, sourceIds: List<Long>): PagingSource<Int, SeriesEntity>

    /** Live count of a custom category's in-scope items — the rail count badge. */
    @Query(
        "SELECT COUNT(*) FROM custom_category_members m " +
            "LEFT JOIN channels c ON m.mediaType = 'LIVE' AND m.itemId = c.id " +
            "LEFT JOIN movies mv ON m.mediaType = 'MOVIE' AND m.itemId = mv.id " +
            "LEFT JOIN series s ON m.mediaType = 'SERIES' AND m.itemId = s.id " +
            "WHERE m.profileId = :profileId AND m.mediaType = :type AND m.contextKey = :contextKey " +
            "AND COALESCE(c.sourceId, mv.sourceId, s.sourceId) IN (:sourceIds)",
    )
    fun countMembers(profileId: Long, type: MediaType, contextKey: String, sourceIds: List<Long>): Flow<Int>

    /** Content ids in one custom category. The Guide already holds its bounded channel window in
     *  memory, so this small projection lets it apply a custom-category filter without another
     *  content-table load. */
    @Query("SELECT itemId FROM custom_category_members WHERE profileId = :profileId AND mediaType = :type AND contextKey = :contextKey")
    suspend fun itemIds(profileId: Long, type: MediaType, contextKey: String): List<Long>

    /** Bounded snapshot of a custom category in rail order — the Move session's in-memory reorder. */
    @Query(
        "SELECT c.* FROM channels c " +
            "INNER JOIN custom_category_members m ON m.itemId = c.id AND m.profileId = :profileId AND m.mediaType = 'LIVE' AND m.contextKey = :contextKey " +
            "LEFT JOIN content_order o ON o.itemId = c.id AND o.profileId = :profileId AND o.mediaType = 'LIVE' AND o.contextKey = :contextKey " +
            "WHERE c.sourceId IN (:sourceIds) " +
            "ORDER BY (CASE WHEN o.position IS NULL THEN 1 ELSE 0 END), o.position, m.position, c.sortOrder, c.name LIMIT :limit",
    )
    suspend fun snapshotChannels(profileId: Long, contextKey: String, sourceIds: List<Long>, limit: Int): List<ChannelEntity>

    @Query(
        "SELECT mv.* FROM movies mv " +
            "INNER JOIN custom_category_members m ON m.itemId = mv.id AND m.profileId = :profileId AND m.mediaType = 'MOVIE' AND m.contextKey = :contextKey " +
            "LEFT JOIN content_order o ON o.itemId = mv.id AND o.profileId = :profileId AND o.mediaType = 'MOVIE' AND o.contextKey = :contextKey " +
            "WHERE mv.sourceId IN (:sourceIds) " +
            "ORDER BY (CASE WHEN o.position IS NULL THEN 1 ELSE 0 END), o.position, m.position, mv.sortOrder, mv.name LIMIT :limit",
    )
    suspend fun snapshotMovies(profileId: Long, contextKey: String, sourceIds: List<Long>, limit: Int): List<MovieEntity>

    @Query(
        "SELECT s.* FROM series s " +
            "INNER JOIN custom_category_members m ON m.itemId = s.id AND m.profileId = :profileId AND m.mediaType = 'SERIES' AND m.contextKey = :contextKey " +
            "LEFT JOIN content_order o ON o.itemId = s.id AND o.profileId = :profileId AND o.mediaType = 'SERIES' AND o.contextKey = :contextKey " +
            "WHERE s.sourceId IN (:sourceIds) " +
            "ORDER BY (CASE WHEN o.position IS NULL THEN 1 ELSE 0 END), o.position, m.position, s.sortOrder, s.name LIMIT :limit",
    )
    suspend fun snapshotSeries(profileId: Long, contextKey: String, sourceIds: List<Long>, limit: Int): List<SeriesEntity>

    /** In-category search (mirrors ChannelDao.searchInCategory), for custom categories. */
    @Query(
        "SELECT c.* FROM channels c " +
            "INNER JOIN custom_category_members m ON m.itemId = c.id AND m.profileId = :profileId AND m.mediaType = 'LIVE' AND m.contextKey = :contextKey " +
            "WHERE c.sourceId IN (:sourceIds) AND c.name LIKE '%' || :query || '%' " +
            "ORDER BY m.position, c.sortOrder, c.name",
    )
    fun searchChannels(query: String, profileId: Long, contextKey: String, sourceIds: List<Long>): PagingSource<Int, ChannelEntity>

    @Query(
        "SELECT mv.* FROM movies mv " +
            "INNER JOIN custom_category_members m ON m.itemId = mv.id AND m.profileId = :profileId AND m.mediaType = 'MOVIE' AND m.contextKey = :contextKey " +
            "WHERE mv.sourceId IN (:sourceIds) AND mv.name LIKE '%' || :query || '%' " +
            "ORDER BY m.position, mv.sortOrder, mv.name",
    )
    fun searchMovies(query: String, profileId: Long, contextKey: String, sourceIds: List<Long>): PagingSource<Int, MovieEntity>

    @Query(
        "SELECT s.* FROM series s " +
            "INNER JOIN custom_category_members m ON m.itemId = s.id AND m.profileId = :profileId AND m.mediaType = 'SERIES' AND m.contextKey = :contextKey " +
            "WHERE s.sourceId IN (:sourceIds) AND s.name LIKE '%' || :query || '%' " +
            "ORDER BY m.position, s.sortOrder, s.name",
    )
    fun searchSeries(query: String, profileId: Long, contextKey: String, sourceIds: List<Long>): PagingSource<Int, SeriesEntity>
}

/**
 * One membership row joined to its content row's stable identity, for the per-source re-sync
 * snapshot ([CustomCategoryDao.exportRowsForSource]). Mirrors [ContentOrderExportRow]; the shape is
 * identical because the table is modeled on content_order.
 */
/** One custom category's member count, for the Move to… dialog badges. */
data class CustomCategoryCount(val contextKey: String, val count: Int)

data class CustomCategoryMemberExportRow(
    val profileId: Long,
    val mediaType: MediaType,
    val itemId: Long,
    val contextKey: String,
    val position: Int,
    val addedAt: Long,
    val sourceId: Long,
    val remoteId: String?,
    val name: String?,
)

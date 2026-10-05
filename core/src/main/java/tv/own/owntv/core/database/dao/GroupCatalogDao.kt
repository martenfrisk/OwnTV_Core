package tv.own.owntv.core.database.dao

import androidx.room.Embedded
import androidx.room.Dao
import androidx.room.Query
import tv.own.owntv.core.database.entity.CategoryEntity
import tv.own.owntv.core.model.MediaType

/** Credential-free, bounded projections for the shared group domain and management API. */
@Dao
interface GroupCatalogDao {
    @Query("SELECT i.id, i.sourceId, i.categoryId, i.remoteId, i.name, CAST(c.sourceId AS TEXT) || ':' || COALESCE(c.remoteId, c.name) AS providerKey FROM channels i LEFT JOIN categories c ON c.id = i.categoryId WHERE i.id IN (:ids)")
    suspend fun channels(ids: List<Long>): List<GroupCatalogItem>

    @Query("SELECT i.id, i.sourceId, i.categoryId, i.remoteId, i.name, CAST(c.sourceId AS TEXT) || ':' || COALESCE(c.remoteId, c.name) AS providerKey FROM movies i LEFT JOIN categories c ON c.id = i.categoryId WHERE i.id IN (:ids)")
    suspend fun movies(ids: List<Long>): List<GroupCatalogItem>

    @Query("SELECT i.id, i.sourceId, i.categoryId, i.remoteId, i.name, CAST(c.sourceId AS TEXT) || ':' || COALESCE(c.remoteId, c.name) AS providerKey FROM series i LEFT JOIN categories c ON c.id = i.categoryId WHERE i.id IN (:ids)")
    suspend fun series(ids: List<Long>): List<GroupCatalogItem>

    /** Keyset pages preserve manual/member/provider order without returning playback credentials. */
    @Query("SELECT * FROM (SELECT i.id, i.sourceId, i.categoryId, i.remoteId, i.name, CAST(c.sourceId AS TEXT) || ':' || COALESCE(c.remoteId, c.name) AS providerKey, CASE WHEN o.position IS NULL THEN 1 ELSE 0 END AS cursorBucket, COALESCE(o.position, 0) AS cursorOrder, 0 AS cursorMember, i.sortOrder AS cursorSort FROM channels i LEFT JOIN categories c ON c.id = i.categoryId LEFT JOIN content_order o ON o.itemId = i.id AND o.profileId = :profileId AND o.mediaType = :type AND o.contextKey = :key WHERE i.categoryId = :categoryId) WHERE (cursorBucket, cursorOrder, cursorMember, cursorSort, name, id) > (:bucket, :order, :member, :sort, :name, :id) ORDER BY cursorBucket, cursorOrder, cursorMember, cursorSort, name, id LIMIT :limit")
    suspend fun providerChannels(profileId: Long, type: MediaType, key: String, categoryId: Long, bucket: Int, order: Long, member: Long, sort: Long, name: String, id: Long, limit: Int): List<GroupCatalogPageItem>

    @Query("SELECT * FROM (SELECT i.id, i.sourceId, i.categoryId, i.remoteId, i.name, CAST(c.sourceId AS TEXT) || ':' || COALESCE(c.remoteId, c.name) AS providerKey, CASE WHEN o.position IS NULL THEN 1 ELSE 0 END AS cursorBucket, COALESCE(o.position, 0) AS cursorOrder, m.position AS cursorMember, i.sortOrder AS cursorSort FROM channels i LEFT JOIN categories c ON c.id = i.categoryId INNER JOIN custom_category_members m ON m.itemId = i.id AND m.profileId = :profileId AND m.mediaType = :type AND m.contextKey = :key LEFT JOIN content_order o ON o.itemId = i.id AND o.profileId = :profileId AND o.mediaType = :type AND o.contextKey = :key WHERE i.sourceId IN (SELECT sourceId FROM profile_source WHERE profileId = :profileId)) WHERE (cursorBucket, cursorOrder, cursorMember, cursorSort, name, id) > (:bucket, :order, :member, :sort, :name, :id) ORDER BY cursorBucket, cursorOrder, cursorMember, cursorSort, name, id LIMIT :limit")
    suspend fun customChannels(profileId: Long, type: MediaType, key: String, bucket: Int, order: Long, member: Long, sort: Long, name: String, id: Long, limit: Int): List<GroupCatalogPageItem>

    @Query("SELECT * FROM (SELECT i.id, i.sourceId, i.categoryId, i.remoteId, i.name, CAST(c.sourceId AS TEXT) || ':' || COALESCE(c.remoteId, c.name) AS providerKey, CASE WHEN o.position IS NULL THEN 1 ELSE 0 END AS cursorBucket, COALESCE(o.position, 0) AS cursorOrder, 0 AS cursorMember, i.sortOrder AS cursorSort FROM movies i LEFT JOIN categories c ON c.id = i.categoryId LEFT JOIN content_order o ON o.itemId = i.id AND o.profileId = :profileId AND o.mediaType = :type AND o.contextKey = :key WHERE i.categoryId = :categoryId) WHERE (cursorBucket, cursorOrder, cursorMember, cursorSort, name, id) > (:bucket, :order, :member, :sort, :name, :id) ORDER BY cursorBucket, cursorOrder, cursorMember, cursorSort, name, id LIMIT :limit")
    suspend fun providerMovies(profileId: Long, type: MediaType, key: String, categoryId: Long, bucket: Int, order: Long, member: Long, sort: Long, name: String, id: Long, limit: Int): List<GroupCatalogPageItem>

    @Query("SELECT * FROM (SELECT i.id, i.sourceId, i.categoryId, i.remoteId, i.name, CAST(c.sourceId AS TEXT) || ':' || COALESCE(c.remoteId, c.name) AS providerKey, CASE WHEN o.position IS NULL THEN 1 ELSE 0 END AS cursorBucket, COALESCE(o.position, 0) AS cursorOrder, m.position AS cursorMember, i.sortOrder AS cursorSort FROM movies i LEFT JOIN categories c ON c.id = i.categoryId INNER JOIN custom_category_members m ON m.itemId = i.id AND m.profileId = :profileId AND m.mediaType = :type AND m.contextKey = :key LEFT JOIN content_order o ON o.itemId = i.id AND o.profileId = :profileId AND o.mediaType = :type AND o.contextKey = :key WHERE i.sourceId IN (SELECT sourceId FROM profile_source WHERE profileId = :profileId)) WHERE (cursorBucket, cursorOrder, cursorMember, cursorSort, name, id) > (:bucket, :order, :member, :sort, :name, :id) ORDER BY cursorBucket, cursorOrder, cursorMember, cursorSort, name, id LIMIT :limit")
    suspend fun customMovies(profileId: Long, type: MediaType, key: String, bucket: Int, order: Long, member: Long, sort: Long, name: String, id: Long, limit: Int): List<GroupCatalogPageItem>

    @Query("SELECT * FROM (SELECT i.id, i.sourceId, i.categoryId, i.remoteId, i.name, CAST(c.sourceId AS TEXT) || ':' || COALESCE(c.remoteId, c.name) AS providerKey, CASE WHEN o.position IS NULL THEN 1 ELSE 0 END AS cursorBucket, COALESCE(o.position, 0) AS cursorOrder, 0 AS cursorMember, i.sortOrder AS cursorSort FROM series i LEFT JOIN categories c ON c.id = i.categoryId LEFT JOIN content_order o ON o.itemId = i.id AND o.profileId = :profileId AND o.mediaType = :type AND o.contextKey = :key WHERE i.categoryId = :categoryId) WHERE (cursorBucket, cursorOrder, cursorMember, cursorSort, name, id) > (:bucket, :order, :member, :sort, :name, :id) ORDER BY cursorBucket, cursorOrder, cursorMember, cursorSort, name, id LIMIT :limit")
    suspend fun providerSeries(profileId: Long, type: MediaType, key: String, categoryId: Long, bucket: Int, order: Long, member: Long, sort: Long, name: String, id: Long, limit: Int): List<GroupCatalogPageItem>

    @Query("SELECT * FROM (SELECT i.id, i.sourceId, i.categoryId, i.remoteId, i.name, CAST(c.sourceId AS TEXT) || ':' || COALESCE(c.remoteId, c.name) AS providerKey, CASE WHEN o.position IS NULL THEN 1 ELSE 0 END AS cursorBucket, COALESCE(o.position, 0) AS cursorOrder, m.position AS cursorMember, i.sortOrder AS cursorSort FROM series i LEFT JOIN categories c ON c.id = i.categoryId INNER JOIN custom_category_members m ON m.itemId = i.id AND m.profileId = :profileId AND m.mediaType = :type AND m.contextKey = :key LEFT JOIN content_order o ON o.itemId = i.id AND o.profileId = :profileId AND o.mediaType = :type AND o.contextKey = :key WHERE i.sourceId IN (SELECT sourceId FROM profile_source WHERE profileId = :profileId)) WHERE (cursorBucket, cursorOrder, cursorMember, cursorSort, name, id) > (:bucket, :order, :member, :sort, :name, :id) ORDER BY cursorBucket, cursorOrder, cursorMember, cursorSort, name, id LIMIT :limit")
    suspend fun customSeries(profileId: Long, type: MediaType, key: String, bucket: Int, order: Long, member: Long, sort: Long, name: String, id: Long, limit: Int): List<GroupCatalogPageItem>

    @Query("SELECT * FROM categories WHERE mediaType = :type AND CAST(sourceId AS TEXT) || ':' || COALESCE(remoteId, name) = :key LIMIT 2")
    suspend fun providerGroup(type: MediaType, key: String): List<CategoryEntity>
}

data class GroupCatalogItem(
    val id: Long,
    val sourceId: Long,
    val categoryId: Long?,
    val remoteId: String?,
    val name: String,
    val providerKey: String?,
)


/** Cursor values are non-null so row-value keyset comparisons have no nullable boundary. */
data class GroupCatalogPageItem(
    @Embedded val item: GroupCatalogItem,
    val cursorBucket: Int,
    val cursorOrder: Long,
    val cursorMember: Long,
    val cursorSort: Long,
)

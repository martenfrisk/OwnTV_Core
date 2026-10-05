package tv.own.owntv.core.database.dao

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

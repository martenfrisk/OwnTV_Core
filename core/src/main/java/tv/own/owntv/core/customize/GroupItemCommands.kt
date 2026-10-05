package tv.own.owntv.core.customize

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.core.backup.GroupUserDataRemoval
import tv.own.owntv.core.backup.UserDataResolver
import tv.own.owntv.core.backup.UserDataWriter
import tv.own.owntv.core.content.AdultCategoryClassifier
import tv.own.owntv.core.database.OwnTVDatabase
import tv.own.owntv.core.database.dao.GroupCatalogItem
import tv.own.owntv.core.database.entity.FavoriteEntity
import tv.own.owntv.core.database.transaction
import tv.own.owntv.core.model.MediaType

/** Validates the complete item selection before publishing one replayable, credential-free intent. */
internal class GroupItemCommands(
    private val db: OwnTVDatabase,
    private val customize: CustomizationStore,
    private val userData: UserDataResolver,
    private val writer: UserDataWriter,
    private val journal: GroupOperationJournal,
    private val afterStep: suspend (GroupMutationStage, Int) -> Unit,
    private val resolveRows: suspend (MediaType, JSONArray) -> LinkedHashMap<JSONObject, Long?>,
) {
    data class Plan(val manifest: JSONObject, val selected: Int, val changed: Boolean)

    suspend fun prepare(id: String, edit: GroupItemEdit, sources: Set<Long>): Plan {
        val pid = edit.scope.profileId
        val type = edit.scope.mediaType
        val profile = db.profileDao().getById(pid) ?: throw GroupEditException(GroupError.INVALID_PROFILE)
        val ids = edit.itemIds.distinct()
        requireDomain(ids.all { it > 0 }, GroupError.INVALID_ITEM)
        requireDomain(if (edit.action == GroupItemAction.RENAME) edit.names.keys == ids.toSet() else edit.names.isEmpty(), GroupError.INVALID_ITEM)
        requireDomain(edit.itemKeys.isEmpty() || edit.itemKeys.keys == ids.toSet(), GroupError.INVALID_ITEM)
        val cust = customize.observe(pid, type).first()
        val pending = userData.pendingItemRecords(pid, type, setOf("fav", "order")).groupBy { userData.groupItemKey(it) }
        val latest = if (edit.action == GroupItemAction.RENAME) 0L else
            db.tombstoneDao().latestDeletion(pid, if (edit.action == GroupItemAction.RESET) "order" else "fav")
        requireDomain(latest < Long.MAX_VALUE, GroupError.POSITION_OVERFLOW)
        var chunks = 0
        var changed = false
        val adult = mutableMapOf<Long, Boolean>()
        for (batch in ids.chunked(GROUP_BATCH_SIZE)) {
            currentCoroutineContext().ensureActive()
            val items = load(type, batch).associateBy { it.id }
            requireDomain(items.size == batch.size, GroupError.INVALID_ITEM)
            val favorites = if (edit.action in setOf(GroupItemAction.FAVORITE, GroupItemAction.UNFAVORITE))
                db.favoriteDao().recordsForIds(pid, type, batch).associateBy { it.itemId } else emptyMap()
            val orders = if (edit.action == GroupItemAction.RESET) db.contentOrderDao().recordsForIds(pid, type, batch).groupBy { it.itemId } else emptyMap()
            val rows = JSONArray()
            for (itemId in batch) {
                val item = items.getValue(itemId)
                requireDomain(item.sourceId in sources, GroupError.INVALID_SOURCE)
                if (profile.isKids && item.categoryId != null) {
                    val prohibited = adult[item.categoryId] ?: AdultCategoryClassifier.isAdult(db.categoryDao().getById(item.categoryId)?.name).also { adult[item.categoryId] = it }
                    requireDomain(!prohibited, GroupError.INVALID_ITEM)
                }
                val identity = identity(type, item)
                val key = userData.groupItemKey(identity)
                requireDomain(edit.itemKeys.isEmpty() || edit.itemKeys[itemId] == key, GroupError.INVALID_ITEM)
                val row = JSONObject(identity.toString()).put("oid", item.id).put("key", key)
                val favorite = favorites[itemId]
                favorite?.let { row.put("oldFavAt", it.addedAt) }
                when (edit.action) {
                    GroupItemAction.RENAME -> {
                        val name = edit.names.getValue(itemId)?.trim()?.takeIf { it.isNotEmpty() }
                        requireDomain(name == null || name.length <= 240, GroupError.INVALID_NAME)
                        row.put("displayName", name ?: JSONObject.NULL)
                        changed = changed || cust.itemNames[key] != name
                    }
                    GroupItemAction.FAVORITE, GroupItemAction.UNFAVORITE -> {
                        val pendingAt = pending[key].orEmpty().filter { it.getString("kind") == "fav" }.maxOfOrNull { it.optLong("at", 0) } ?: 0L
                        val latestChoice = maxOf(latest, favorite?.addedAt ?: 0L, pendingAt)
                        requireDomain(latestChoice < Long.MAX_VALUE, GroupError.POSITION_OVERFLOW)
                        row.put("at", if (edit.action == GroupItemAction.FAVORITE && favorite != null) favorite.addedAt else maxOf(System.currentTimeMillis(), latestChoice + 1))
                        changed = changed || edit.action == GroupItemAction.UNFAVORITE || favorite == null
                    }
                    GroupItemAction.RESET -> {
                        val stored = orders[itemId].orEmpty().map { JSONObject().put("ctx", it.contextKey).put("pos", it.position).put("at", it.modifiedAt) }
                        val absent = pending[key].orEmpty().filter { it.getString("kind") == "order" }
                            .map { JSONObject().put("ctx", it.getString("ctx")).put("pos", it.getInt("pos")).put("at", it.optLong("at", 0)) }
                        val allOrders = stored + absent
                        val latestChoice = maxOf(latest, allOrders.maxOfOrNull { it.getLong("at") } ?: 0L)
                        requireDomain(latestChoice < Long.MAX_VALUE, GroupError.POSITION_OVERFLOW)
                        row.put("orders", JSONArray(allOrders)).put("at", maxOf(System.currentTimeMillis(), latestChoice + 1))
                        changed = changed || key in cust.itemNames || key in cust.hiddenItems || key in cust.movedFromOrigin || allOrders.isNotEmpty()
                    }
                }
                rows.put(row)
            }
            currentCoroutineContext().ensureActive()
            journal.writeChunk(id, chunks++, rows)
        }
        val manifest = JSONObject().put("domain", "items").put("id", id).put("profile", pid).put("type", type.name)
            .put("action", edit.action.name).put("sources", JSONArray(sources.toList())).put("chunks", chunks).put("total", ids.size).put("next", 0)
        return Plan(manifest, ids.size, changed)
    }

    suspend fun replay(pending: JSONObject, recovering: Boolean, commit: suspend (Int, Int) -> Unit) {
        val id = pending.getString("id")
        val pid = pending.getLong("profile")
        val type = MediaType.valueOf(pending.getString("type"))
        requireDomain(type != MediaType.EPISODE, GroupError.INVALID_TYPE)
        requireDomain(db.profileDao().getById(pid) != null, GroupError.INVALID_PROFILE)
        val action = GroupItemAction.valueOf(pending.getString("action"))
        val chunks = pending.getInt("chunks")
        // All old IDs are detached before any current IDs attach; swaps may span several chunks.
        if (recovering) for (index in 0 until chunks) {
            val resolved = resolveRows(type, journal.readChunk(id, index))
            db.transaction {
                for ((row, itemId) in resolved) {
                    val old = row.getLong("oid")
                    if (old == itemId) continue
                    if (action in setOf(GroupItemAction.FAVORITE, GroupItemAction.UNFAVORITE) && row.has("oldFavAt")) {
                        db.favoriteDao().detachJournalRow(pid, type, old, row.getLong("oldFavAt"))
                    }
                    if (action == GroupItemAction.FAVORITE) db.favoriteDao().detachJournalRow(pid, type, old, row.getLong("at"))
                    if (action == GroupItemAction.RESET) {
                        val orders = row.getJSONArray("orders")
                        for (o in 0 until orders.length()) {
                            val order = orders.getJSONObject(o)
                            db.contentOrderDao().detachJournalRow(pid, type, order.getString("ctx"), old, order.getInt("pos"), order.getLong("at"))
                        }
                    }
                }
            }
        }
        var completed = 0
        for (index in 0 until chunks) {
            val resolved = resolveRows(type, journal.readChunk(id, index))
            val missing = JSONArray()
            db.transaction {
                when (action) {
                    GroupItemAction.FAVORITE -> for ((row, itemId) in resolved) {
                        if (itemId == null) missing.put(identityRecord(row, pid).put("kind", "fav").put("at", row.getLong("at")))
                        else db.favoriteDao().add(FavoriteEntity(profileId = pid, mediaType = type, itemId = itemId, addedAt = row.getLong("at")))
                    }
                    GroupItemAction.UNFAVORITE -> writer.removeGroupFavorites(pid, type, resolved.map { (row, itemId) -> GroupUserDataRemoval(row, itemId, row.getLong("at")) })
                    GroupItemAction.RESET -> for ((row, itemId) in resolved) {
                        val orders = row.getJSONArray("orders")
                        for (o in 0 until orders.length()) {
                            val key = orders.getJSONObject(o).getString("ctx")
                            userData.rememberOrderRemoval(pid, row, key, row.getLong("at"))
                            itemId?.let { db.contentOrderDao().deleteIfOlderThan(pid, type, key, it, row.getLong("at")) }
                        }
                    }
                    GroupItemAction.RENAME -> Unit
                }
            }
            if (missing.length() > 0) userData.importAll(missing)
            afterStep(GroupMutationStage.ITEM_DATA_WRITTEN, index)
            if (action == GroupItemAction.RENAME || action == GroupItemAction.RESET) customize.update(pid, type) { current ->
                val keys = resolved.keys.map { it.getString("key") }.toSet()
                if (action == GroupItemAction.RESET) current.copy(itemNames = current.itemNames - keys,
                    hiddenItems = current.hiddenItems - keys, movedFromOrigin = current.movedFromOrigin - keys)
                else {
                    val names = current.itemNames.toMutableMap()
                    for (row in resolved.keys) {
                        val key = row.getString("key")
                        if (row.isNull("displayName")) names.remove(key) else names[key] = row.getString("displayName")
                    }
                    current.copy(itemNames = names)
                }
            }
            afterStep(GroupMutationStage.ITEM_CUSTOMIZATION_WRITTEN, index)
            completed += resolved.size
            commit(index + 1, completed)
            afterStep(GroupMutationStage.CHUNK_COMMITTED, index)
        }
        if (action == GroupItemAction.UNFAVORITE || action == GroupItemAction.RESET) userData.resolvePending()
    }

    private fun identity(type: MediaType, item: GroupCatalogItem): JSONObject = JSONObject().put("t", type.name)
        .put("src", item.sourceId).putOpt("rid", item.remoteId).put("name", item.name)
    private fun identityRecord(row: JSONObject, pid: Long): JSONObject = JSONObject().put("p", pid).put("t", row.getString("t"))
        .put("src", row.getLong("src")).putOpt("rid", if (row.isNull("rid")) null else row.getString("rid")).put("name", row.getString("name"))
    private suspend fun load(type: MediaType, ids: List<Long>): List<GroupCatalogItem> = when (type) {
        MediaType.LIVE -> db.groupCatalogDao().channels(ids)
        MediaType.MOVIE -> db.groupCatalogDao().movies(ids)
        MediaType.SERIES -> db.groupCatalogDao().series(ids)
        MediaType.EPISODE -> throw GroupEditException(GroupError.INVALID_TYPE)
    }
    private fun requireDomain(condition: Boolean, error: GroupError) { if (!condition) throw GroupEditException(error) }
}

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
import tv.own.owntv.core.database.dao.GroupCatalogPageItem
import tv.own.owntv.core.database.entity.CustomCategoryMemberEntity
import tv.own.owntv.core.database.transaction
import tv.own.owntv.core.model.MediaType

/** Builds/replays a single duplicate/merge/split intent under GroupService's catalog coordination. */
internal class GroupCompositionCommands(
    private val db: OwnTVDatabase,
    private val customize: CustomizationStore,
    private val userData: UserDataResolver,
    private val writer: UserDataWriter,
    private val journal: GroupOperationJournal,
    private val afterStep: suspend (GroupMutationStage, Int) -> Unit,
    private val resolveRows: suspend (MediaType, JSONArray) -> LinkedHashMap<JSONObject, Long?>,
) {
    data class Plan(val manifest: JSONObject, val destinations: List<String>, val selected: Int, val added: Int, val changed: Boolean)
    private data class DestinationMember(val position: Int, val at: Long)
    private data class PendingMember(val identity: JSONObject, val bucket: Int, val order: Long, val member: Long) {
        fun precedes(row: GroupCatalogPageItem): Boolean = when {
            bucket != row.cursorBucket -> bucket < row.cursorBucket
            order != row.cursorOrder -> order < row.cursorOrder
            else -> member <= row.cursorMember
        }
    }

    suspend fun sources(scope: GroupScope): Set<Long> {
        requireDomain(scope.mediaType != MediaType.EPISODE, GroupError.INVALID_TYPE)
        requireDomain(db.profileDao().getById(scope.profileId) != null, GroupError.INVALID_PROFILE)
        val linked = db.sourceDao().sourceIdsForProfile(scope.profileId).toSet()
        val selected = scope.sourceIds ?: linked
        requireDomain(selected.all { it in linked }, GroupError.INVALID_SOURCE)
        return selected
    }

    suspend fun prepare(id: String, edit: GroupCompositionEdit, sources: Set<Long>): Plan {
        val pid = edit.scope.profileId
        val type = edit.scope.mediaType
        val profile = db.profileDao().getById(pid) ?: throw GroupEditException(GroupError.INVALID_PROFILE)
        requireDomain(edit.action in setOf(GroupAction.COPY, GroupAction.MOVE), GroupError.INVALID_GROUP)
        requireDomain(edit.parts.isNotEmpty(), GroupError.INVALID_GROUP)
        val cust = customize.observe(pid, type).first()
        val customIds = cust.customCategories.filter { !profile.isKids || !AdultCategoryClassifier.isAdult(it.name) }.map { it.id }.toSet()
        val definitions = mutableListOf<CustomCategory>()
        val destinations = edit.parts.map { part ->
            val target = part.destination
            requireDomain((target.groupId == null) != (target.name == null), GroupError.INVALID_TARGET)
            requireDomain(part.selections.isNotEmpty(), GroupError.INVALID_ORIGIN)
            if (target.groupId != null) {
                requireDomain(target.groupId in customIds, GroupError.INVALID_TARGET)
                target.groupId
            } else {
                val name = target.name!!.trim()
                requireDomain(name.isNotBlank() && name.length <= 120, GroupError.INVALID_NAME)
                requireDomain(!profile.isKids || !AdultCategoryClassifier.isAdult(name), GroupError.INVALID_NAME)
                CustomCategory("${CustomizeKeys.CUSTOM_PREFIX}${java.util.UUID.randomUUID()}", name).also { definitions += it }.id
            }
        }
        var chunk = 0
        for (batch in definitions.chunked(GROUP_BATCH_SIZE)) {
            currentCoroutineContext().ensureActive()
            journal.writeChunk(id, chunk++, JSONArray(batch.map { JSONObject().put("key", it.id).put("name", it.name) }))
        }
        val definitionChunks = chunk
        val pending = userData.pendingGroupRecords(pid, type)
        val pendingMembers = pending.filter { it.optString("kind") == "member" }.groupBy { it.getString("ctx") }
        val pendingIndexes = pendingMembers.mapValues { (_, rows) -> rows.associateBy { identityKey(it) } }
        val pendingOrders = pending.filter { it.optString("kind") == "order" }
            .groupBy { it.getString("ctx") }.mapValues { (_, rows) -> rows.associateBy { identityKey(it) } }
        val lastDeletion = db.customCategoryDao().latestDeletion(pid)
        requireDomain(lastDeletion < Long.MAX_VALUE, GroupError.POSITION_OVERFLOW)
        val at = maxOf(System.currentTimeMillis(), lastDeletion + 1)
        val positions = mutableMapOf<String, Long>()
        val planned = mutableMapOf<Pair<String, String>, DestinationMember>()
        val movedTargets = mutableMapOf<String, String>()
        val selectedKeys = mutableSetOf<String>()
        val seen = mutableSetOf<Triple<String, String, String>>()
        val adult = mutableMapOf<Long, Boolean>()
        var rows = JSONArray()
        var total = definitions.size
        var added = 0
        var removed = false
        var targetBatch = emptyMap<Long, CustomCategoryMemberEntity>()
        var originBatch = emptyMap<Long, CustomCategoryMemberEntity>()

        suspend fun prepareBatch(target: String, origin: String, ids: List<Long>) {
            targetBatch = db.customCategoryDao().membersForIds(pid, type, target, ids).associateBy { it.itemId }
            originBatch = if (origin in customIds) db.customCategoryDao().membersForIds(pid, type, origin, ids).associateBy { it.itemId } else emptyMap()
        }

        suspend fun flush() {
            if (rows.length() == 0) return
            currentCoroutineContext().ensureActive()
            journal.writeChunk(id, chunk++, rows)
            rows = JSONArray()
        }
        suspend fun accept(target: String, origin: String, identity: JSONObject, item: GroupCatalogItem?, wholeGroup: Boolean) {
            val src = identity.getLong("src")
            if (wholeGroup && src !in sources) return
            requireDomain(src in sources, GroupError.INVALID_SOURCE)
            val key = CustomizeKeys.item(src, identity.optString("rid").takeIf { it.isNotEmpty() }, identity.getString("name"))
            if (wholeGroup && origin !in customIds && cust.movedFromOrigin[key] == origin) return
            if (profile.isKids && item?.categoryId != null) {
                val prohibited = adult[item.categoryId] ?: AdultCategoryClassifier.isAdult(db.categoryDao().getById(item.categoryId)?.name).also { adult[item.categoryId] = it }
                if (wholeGroup && prohibited) return
                requireDomain(!prohibited, GroupError.INVALID_ITEM)
            }
            requireDomain(!profile.isKids || item != null, GroupError.INVALID_ITEM)
            if (!seen.add(Triple(target, origin, key))) return
            if (edit.action == GroupAction.MOVE) {
                requireDomain(movedTargets[key]?.let { it == target } != false, GroupError.INVALID_ITEM)
                movedTargets[key] = target
            }
            selectedKeys += key
            val old = item?.let { targetBatch[it.id] }
            val oldPending = pendingIndexes[target]?.get(key)
            val allocation = planned.getOrPut(target to key) {
                if (old != null) DestinationMember(old.position, old.addedAt)
                else if (oldPending != null) DestinationMember(oldPending.getInt("pos"), oldPending.optLong("at", 0))
                else {
                    val next = positions.getOrPut(target) {
                        maxOf(db.customCategoryDao().maxPosition(pid, type, target).toLong(),
                            pendingMembers[target].orEmpty().maxOfOrNull { it.getLong("pos") } ?: -1L) + 1
                    }
                    requireDomain(next <= Int.MAX_VALUE, GroupError.POSITION_OVERFLOW)
                    positions[target] = next + 1
                    added++
                    DestinationMember(next.toInt(), at)
                }
            }
            val originRow = item?.let { originBatch[it.id] }
            val originAt = originRow?.addedAt ?: identity.optLong("at", 0)
            requireDomain(edit.action != GroupAction.MOVE || originAt < Long.MAX_VALUE, GroupError.POSITION_OVERFLOW)
            val record = JSONObject(identity.toString()).put("oid", item?.id ?: -1L).put("key", key)
                .put("target", target).put("origin", origin).put("customOrigin", origin in customIds)
                .put("pos", allocation.position).put("at", allocation.at).put("deleteAt", maxOf(at, if (originAt < Long.MAX_VALUE) originAt + 1 else at))
            originRow?.let { record.put("originPos", it.position).put("originAt", it.addedAt) }
            if (edit.action == GroupAction.MOVE && origin != target) removed = true
            requireDomain(total < Int.MAX_VALUE, GroupError.POSITION_OVERFLOW)
            total++
            rows.put(record)
            if (rows.length() == GROUP_BATCH_SIZE) flush()
        }

        for ((partIndex, part) in edit.parts.withIndex()) {
            val target = destinations[partIndex]
            for (selection in part.selections) {
                val origin = selection.groupId
                val provider = if (origin in customIds) null else db.groupCatalogDao().providerGroup(type, origin).singleOrNull()
                requireDomain(origin in customIds || provider != null, GroupError.INVALID_ORIGIN)
                if (provider != null) {
                    requireDomain(provider.sourceId in sources, GroupError.INVALID_SOURCE)
                    requireDomain(!profile.isKids || !AdultCategoryClassifier.isAdult(provider.name), GroupError.INVALID_ORIGIN)
                }
                val ids = selection.itemIds?.distinct()
                if (ids != null) {
                    requireDomain(ids.all { it > 0 }, GroupError.INVALID_ITEM)
                    for (batch in ids.chunked(GROUP_BATCH_SIZE)) {
                        currentCoroutineContext().ensureActive()
                        val items = load(type, batch).associateBy { it.id }
                        requireDomain(items.size == batch.size, GroupError.INVALID_ITEM)
                        val members = if (origin in customIds) db.customCategoryDao().existingItemIds(pid, type, origin, batch).toSet() else emptySet()
                        prepareBatch(target, origin, batch)
                        for (itemId in batch) {
                            val item = items.getValue(itemId)
                            requireDomain(if (provider != null) item.categoryId == provider.id else itemId in members, GroupError.INVALID_ORIGIN)
                            accept(target, origin, identity(type, item), item, false)
                        }
                    }
                } else {
                    var cursor: GroupCatalogPageItem? = null
                    // Merge absent identities into their saved slots while keeping catalog pages bounded.
                    // Pending records for an already present member must not occupy a second slot.
                    val missing = mutableListOf<PendingMember>()
                    for (batch in pendingIndexes[origin].orEmpty().values.toList().chunked(GROUP_BATCH_SIZE)) {
                        currentCoroutineContext().ensureActive()
                        val resolved = userData.currentItemIds(type, batch)
                        val present = db.customCategoryDao().existingItemIds(pid, type, origin, resolved.filterNotNull()).toSet()
                        for ((identity, currentId) in batch.zip(resolved)) if (currentId !in present) {
                            val order = pendingOrders[origin]?.get(identityKey(identity))?.getLong("pos")
                            missing += PendingMember(identity, if (order == null) 1 else 0, order ?: 0L, identity.getLong("pos"))
                        }
                    }
                    missing.sortWith(compareBy<PendingMember> { it.bucket }.thenBy { it.order }.thenBy { it.member })
                    var nextMissing = 0
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val page = page(pid, type, origin, provider?.id, cursor)
                        if (page.isEmpty()) break
                        prepareBatch(target, origin, page.map { it.item.id })
                        for (row in page) {
                            while (nextMissing < missing.size && missing[nextMissing].precedes(row)) {
                                accept(target, origin, missing[nextMissing++].identity, null, true)
                            }
                            val item = row.item
                            accept(target, origin, identity(type, item), item, true)
                        }
                        cursor = page.last()
                    }
                    while (nextMissing < missing.size) {
                        accept(target, origin, missing[nextMissing++].identity, null, true)
                    }
                }
            }
        }
        flush()
        val manifest = JSONObject().put("domain", "composition").put("id", id).put("profile", pid).put("type", type.name)
            .put("action", edit.action.name).put("sources", JSONArray(sources.toList())).put("chunks", chunk)
            .put("definitions", definitionChunks).put("total", total).put("next", 0)
        return Plan(manifest, destinations, selectedKeys.size, added, definitions.isNotEmpty() || added > 0 || removed)
    }

    suspend fun replay(pending: JSONObject, recovering: Boolean, commit: suspend (Int, Int) -> Unit) {
        val id = pending.getString("id")
        val pid = pending.getLong("profile")
        val type = MediaType.valueOf(pending.getString("type"))
        requireDomain(type != MediaType.EPISODE, GroupError.INVALID_TYPE)
        requireDomain(db.profileDao().getById(pid) != null, GroupError.INVALID_PROFILE)
        val move = pending.getString("action") == GroupAction.MOVE.name
        val definitions = pending.getInt("definitions")
        val chunks = pending.getInt("chunks")
        if (recovering) for (index in definitions until chunks) {
            val resolved = resolveRows(type, journal.readChunk(id, index))
            db.transaction {
                for ((row, itemId) in resolved) {
                    val old = row.getLong("oid")
                    if (old == itemId) continue
                    db.customCategoryDao().detachJournalRow(pid, type, row.getString("target"), old, row.getInt("pos"), row.getLong("at"))
                    if (row.has("originPos")) db.customCategoryDao().detachJournalRow(pid, type, row.getString("origin"), old, row.getInt("originPos"), row.getLong("originAt"))
                }
            }
        }
        var completed = 0
        for (index in 0 until chunks) {
            val rows = journal.readChunk(id, index)
            if (index < definitions) {
                customize.update(pid, type) { current -> current.copy(customCategories = current.customCategories +
                    (0 until rows.length()).map { rows.getJSONObject(it) }.filterNot { row -> current.customCategories.any { it.id == row.getString("key") } }
                        .map { CustomCategory(it.getString("key"), it.getString("name")) }) }
                afterStep(GroupMutationStage.GROUP_DEFINITION_WRITTEN, index)
            } else {
                val resolved = resolveRows(type, rows)
                val missing = JSONArray()
                db.transaction {
                    val additions = resolved.mapNotNull { (row, itemId) ->
                        val target = row.getString("target")
                        if (itemId == null) {
                            missing.put(JSONObject(row.toString()).put("p", pid).put("kind", "member").put("ctx", target)); null
                        } else CustomCategoryMemberEntity(profileId = pid, mediaType = type, contextKey = target,
                            itemId = itemId, position = row.getInt("pos"), addedAt = row.getLong("at"))
                    }
                    db.customCategoryDao().insertAbsentMembers(additions)
                    if (move) resolved.entries.filter { it.key.getBoolean("customOrigin") && it.key.getString("origin") != it.key.getString("target") }
                        .groupBy { it.key.getString("origin") }.forEach { (origin, entries) ->
                            writer.removeCustomCategoryMembers(pid, type, origin, entries.map { (row, itemId) ->
                                GroupUserDataRemoval(row, itemId, row.getLong("deleteAt")) })
                        }
                }
                if (missing.length() > 0) userData.importAll(missing)
                afterStep(GroupMutationStage.MEMBERSHIP_WRITTEN, index)
                if (move) customize.update(pid, type) { current -> current.copy(movedFromOrigin = current.movedFromOrigin +
                    resolved.keys.filter { !it.getBoolean("customOrigin") }.associate { it.getString("key") to it.getString("origin") }) }
                afterStep(GroupMutationStage.ORIGIN_WRITTEN, index)
            }
            completed += rows.length()
            commit(index + 1, completed)
            afterStep(GroupMutationStage.CHUNK_COMMITTED, index)
        }
        if (move) userData.resolvePending()
    }

    private suspend fun page(pid: Long, type: MediaType, key: String, categoryId: Long?, cursor: GroupCatalogPageItem?): List<GroupCatalogPageItem> {
        val dao = db.groupCatalogDao()
        val bucket = cursor?.cursorBucket ?: -1
        val order = cursor?.cursorOrder ?: 0L
        val member = cursor?.cursorMember ?: 0L
        val sort = cursor?.cursorSort ?: 0L
        val name = cursor?.item?.name ?: ""
        val id = cursor?.item?.id ?: 0L
        return when (type) {
            MediaType.LIVE -> if (categoryId == null) dao.customChannels(pid, type, key, bucket, order, member, sort, name, id, GROUP_BATCH_SIZE)
                else dao.providerChannels(pid, type, key, categoryId, bucket, order, member, sort, name, id, GROUP_BATCH_SIZE)
            MediaType.MOVIE -> if (categoryId == null) dao.customMovies(pid, type, key, bucket, order, member, sort, name, id, GROUP_BATCH_SIZE)
                else dao.providerMovies(pid, type, key, categoryId, bucket, order, member, sort, name, id, GROUP_BATCH_SIZE)
            MediaType.SERIES -> if (categoryId == null) dao.customSeries(pid, type, key, bucket, order, member, sort, name, id, GROUP_BATCH_SIZE)
                else dao.providerSeries(pid, type, key, categoryId, bucket, order, member, sort, name, id, GROUP_BATCH_SIZE)
            MediaType.EPISODE -> throw GroupEditException(GroupError.INVALID_TYPE)
        }
    }
    private suspend fun load(type: MediaType, ids: List<Long>): List<GroupCatalogItem> = when (type) {
        MediaType.LIVE -> db.groupCatalogDao().channels(ids)
        MediaType.MOVIE -> db.groupCatalogDao().movies(ids)
        MediaType.SERIES -> db.groupCatalogDao().series(ids)
        MediaType.EPISODE -> throw GroupEditException(GroupError.INVALID_TYPE)
    }
    private fun identity(type: MediaType, item: GroupCatalogItem): JSONObject = JSONObject().put("t", type.name)
        .put("src", item.sourceId).putOpt("rid", item.remoteId).put("name", item.name)
    private fun identityKey(row: JSONObject): String = CustomizeKeys.item(row.getLong("src"),
        row.optString("rid").takeIf { it.isNotEmpty() }, row.getString("name"))
    private fun requireDomain(condition: Boolean, error: GroupError) { if (!condition) throw GroupEditException(error) }
}

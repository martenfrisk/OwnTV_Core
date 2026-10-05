package tv.own.owntv.core.customize

import android.content.Context
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.core.backup.UserDataResolver
import tv.own.owntv.core.backup.UserDataWriter
import tv.own.owntv.core.backup.GroupUserDataRemoval
import tv.own.owntv.core.content.AdultCategoryClassifier
import tv.own.owntv.core.database.OwnTVDatabase
import tv.own.owntv.core.database.dao.GroupCatalogItem
import tv.own.owntv.core.database.entity.ContentOrderEntity
import tv.own.owntv.core.database.entity.CustomCategoryMemberEntity
import tv.own.owntv.core.database.transaction
import tv.own.owntv.core.model.MediaType

/**
 * One validated group mutation path for TV and management clients. Provider rows are immutable.
 * Durable commands bridge Room and DataStore, and replay resolves the existing stable identity
 * rather than trusting row IDs after restart. No playlist URL or credential enters the journal.
 *
 * Lock order: group mutation → ordered source locks → journal state. A source import takes only
 * its source lock and journal-state lock after initial recovery, retaining parallel imports.
 */
class GroupService(
    context: Context,
    private val db: OwnTVDatabase,
    private val customize: CustomizationStore,
    private val userData: UserDataResolver,
    private val writer: UserDataWriter,
    journalDirectory: File = File(context.filesDir, "group-operations"),
    private val afterStep: suspend (GroupMutationStage, Int) -> Unit = { _, _ -> },
) {
    private val journal = GroupOperationJournal(journalDirectory)
    private val mutations = Mutex()
    private val stateMutex = Mutex()
    private val sourceLocks = ConcurrentHashMap<Long, Mutex>()
    private val _progress = MutableStateFlow(GroupOperationProgress())
    val progress = _progress.asStateFlow()

    fun acknowledgeFailure() {
        val current = _progress.value
        _progress.compareAndSet(current, current.copy(failure = null))
    }

    internal fun startRecovery() {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { recover() }.onFailure {
                _progress.value = GroupOperationProgress(revision = _progress.value.revision, failure = GroupError.JOURNAL_UNAVAILABLE)
            }
        }
    }

    suspend fun revision(): Long = withContext(Dispatchers.IO) {
        stateMutex.withLock { journal.readState().getLong("revision") }
    }

    suspend fun recover() = withContext(Dispatchers.IO) { mutations.withLock { recoverLocked() } }

    /** Sources recover first, then hold only their own catalog lock during network/import work. */
    suspend fun <T> withCatalogSource(sourceId: Long, block: suspend () -> T): T {
        recover()
        return withSources(listOf(sourceId)) {
            try { block() } finally { withContext(NonCancellable) { advanceRevision() } }
        }
    }

    /** Backup, restore, profile/source membership changes and definition deletion use this gate. */
    suspend fun <T> withStableCatalog(changed: Boolean = false, block: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            mutations.withLock {
                recoverLocked()
                withSources(db.sourceDao().allSourceIds()) {
                    try { block() } finally {
                        if (changed) withContext(NonCancellable) { advanceRevision() }
                    }
                }
            }
        }

    /** TV callers use a Result; expected validation failures cannot crash a view-model coroutine. */
    suspend fun editFromTv(edit: GroupEdit): Result<GroupEditResult> = try {
        Result.success(this.edit(edit))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }

    suspend fun edit(edit: GroupEdit): GroupEditResult = try {
        performEdit(edit)
    } catch (cancelled: CancellationException) {
        _progress.value = GroupOperationProgress(revision = _progress.value.revision)
        throw cancelled
    } catch (failure: Exception) {
        _progress.value = GroupOperationProgress(revision = _progress.value.revision,
            failure = (failure as? GroupEditException)?.code ?: GroupError.JOURNAL_UNAVAILABLE)
        throw failure
    }

    private suspend fun performEdit(edit: GroupEdit): GroupEditResult = withContext(Dispatchers.IO) {
        mutations.withLock {
            recoverLocked()
            val scope = edit.scope
            checkDomain(scope.mediaType != MediaType.EPISODE, GroupError.INVALID_TYPE)
            val profile = db.profileDao().getById(scope.profileId)
                ?: throw GroupEditException(GroupError.INVALID_PROFILE)
            val linked = db.sourceDao().sourceIdsForProfile(scope.profileId).toSet()
            val sources = scope.sourceIds ?: linked
            checkDomain(sources.all { it in linked }, GroupError.INVALID_SOURCE)
            withSources(sources.toList()) {
                val revision = revision()
                checkDomain(edit.expectedRevision == null || edit.expectedRevision == revision, GroupError.REVISION_CONFLICT)
                val cust = customize.observe(scope.profileId, scope.mediaType).first()
                val adding = edit.action == GroupAction.COPY || edit.action == GroupAction.MOVE
                if (adding) checkDomain(cust.customCategories.any { it.id == edit.target }, GroupError.INVALID_TARGET)
                val customOrigin = edit.origin?.let { key -> cust.customCategories.any { it.id == key } } == true
                val favoritesOrigin = edit.removeFromFavorites && edit.origin == ContentOrderEntity.FAV_CONTEXT
                checkDomain(!edit.removeFromFavorites || (edit.action == GroupAction.MOVE && favoritesOrigin), GroupError.INVALID_ORIGIN)
                val inferProvider = edit.action == GroupAction.MOVE && edit.origin == null
                val needsOrigin = edit.action in setOf(GroupAction.MOVE, GroupAction.REMOVE, GroupAction.SUPPRESS, GroupAction.RESTORE_ORIGIN)
                val provider = if (needsOrigin && !inferProvider && !customOrigin && !favoritesOrigin) {
                    edit.origin?.let { db.groupCatalogDao().providerGroup(scope.mediaType, it).singleOrNull() }
                } else null
                if (needsOrigin && !inferProvider) {
                    checkDomain(customOrigin || favoritesOrigin || provider != null, GroupError.INVALID_ORIGIN)
                    checkDomain(edit.action != GroupAction.REMOVE || customOrigin, GroupError.INVALID_ORIGIN)
                    checkDomain(edit.action !in setOf(GroupAction.SUPPRESS, GroupAction.RESTORE_ORIGIN) || provider != null, GroupError.INVALID_ORIGIN)
                    if (provider != null) checkDomain(provider.sourceId in sources, GroupError.INVALID_SOURCE)
                }
                val ids = edit.itemIds.distinct()
                checkDomain(ids.all { it > 0 }, GroupError.INVALID_ITEM)
                val id = UUID.randomUUID().toString()
                _progress.value = GroupOperationProgress(id, total = ids.size, revision = revision)
                val pending = JSONObject()
                    .put("id", id).put("profile", scope.profileId).put("type", scope.mediaType.name)
                    .put("action", edit.action.name).putOpt("target", edit.target).putOpt("origin", edit.origin)
                    .put("customOrigin", customOrigin).put("favoritesOrigin", favoritesOrigin)
                    .put("restoreProvider", edit.restoreProviderOnRemove).put("total", ids.size)
                    .put("sources", JSONArray(sources.toList())).put("next", 0)
                var position = if (adding) db.customCategoryDao().maxPosition(scope.profileId, scope.mediaType, edit.target!!).toLong() + 1 else 0
                val deletedAt = db.customCategoryDao().latestDeletion(scope.profileId)
                checkDomain(deletedAt < Long.MAX_VALUE, GroupError.POSITION_OVERFLOW)
                val addedAt = maxOf(System.currentTimeMillis(), deletedAt + 1)
                var added = 0
                var chunks = 0
                val adultGroups = mutableMapOf<Long, Boolean>()
                try {
                    for (batch in ids.chunked(GROUP_BATCH_SIZE)) {
                        currentCoroutineContext().ensureActive()
                        val items = load(scope.mediaType, batch).associateBy { it.id }
                        checkDomain(items.size == batch.size, GroupError.INVALID_ITEM)
                        val targetRows = if (adding) db.customCategoryDao().membersForIds(scope.profileId, scope.mediaType, edit.target!!, batch).associateBy { it.itemId } else emptyMap()
                        val originRows = if (customOrigin) db.customCategoryDao().membersForIds(scope.profileId, scope.mediaType, edit.origin, batch).associateBy { it.itemId } else emptyMap()
                        val favorites = if (favoritesOrigin) db.favoriteDao().recordsForIds(scope.profileId, scope.mediaType, batch).associateBy { it.itemId } else emptyMap()
                        val rows = JSONArray()
                        for (itemId in batch) {
                            val item = items.getValue(itemId)
                            checkDomain(item.sourceId in sources, GroupError.INVALID_SOURCE)
                            if (profile.isKids && item.categoryId != null) {
                                val adult = adultGroups[item.categoryId] ?: AdultCategoryClassifier.isAdult(db.categoryDao().getById(item.categoryId)?.name).also { adultGroups[item.categoryId] = it }
                                checkDomain(!adult, GroupError.INVALID_ITEM)
                            }
                            if (needsOrigin && !inferProvider) {
                                if (customOrigin) checkDomain(itemId in originRows, GroupError.INVALID_ORIGIN)
                                if (provider != null) checkDomain(item.categoryId == provider.id, GroupError.INVALID_ORIGIN)
                                if (favoritesOrigin) checkDomain(itemId in favorites, GroupError.INVALID_ORIGIN)
                            }
                            val add = adding && itemId !in targetRows
                            if (add) checkDomain(position <= Int.MAX_VALUE, GroupError.POSITION_OVERFLOW)
                            val originalTime = originRows[itemId]?.addedAt ?: favorites[itemId]?.addedAt ?: 0
                            checkDomain(originalTime < Long.MAX_VALUE, GroupError.POSITION_OVERFLOW)
                            val row = JSONObject().put("t", scope.mediaType.name).put("src", item.sourceId)
                                .putOpt("rid", item.remoteId).put("name", item.name).put("oid", item.id)
                                .put("key", CustomizeKeys.item(item.sourceId, item.remoteId, item.name))
                                .put("add", add).put("at", addedAt).put("deleteAt", maxOf(addedAt, originalTime + 1))
                            row.putOpt("origin", if (inferProvider) item.providerKey else edit.origin)
                            if (adding) {
                                val previous = targetRows[itemId]
                                row.put("pos", if (add) position++ else previous!!.position.toLong())
                                row.put("at", previous?.addedAt ?: addedAt)
                                if (add) added++
                            }
                            originRows[itemId]?.let { previous -> row.put("originPos", previous.position).put("originAt", previous.addedAt) }
                            favorites[itemId]?.let { previous -> row.put("favoriteAt", previous.addedAt) }
                            rows.put(row)
                        }
                        journal.writeChunk(id, chunks++, rows)
                    }
                    if (ids.isEmpty() || (edit.action == GroupAction.COPY && added == 0) ||
                        (edit.action == GroupAction.MOVE && edit.origin == edit.target)) {
                        journal.removeOperation(id)
                        _progress.value = GroupOperationProgress(revision = revision)
                        return@withSources GroupEditResult(id, ids.size, 0, revision)
                    }
                    pending.put("chunks", chunks).put("added", added)
                    stateMutex.withLock {
                        val state = journal.readState()
                        checkDomain(edit.expectedRevision == null || edit.expectedRevision == state.getLong("revision"), GroupError.REVISION_CONFLICT)
                        state.put("pending", pending)
                        journal.writeState(state)
                    }
                } catch (failure: Throwable) {
                    // Only unpublished plans may be discarded. A visible command must remain replayable.
                    val published = stateMutex.withLock { journal.readState().optJSONObject("pending")?.optString("id") == id }
                    if (!published) journal.removeOperation(id)
                    throw failure
                }
                // Once the command is durable, leaving a TV screen cannot interrupt its consistency.
                withContext(NonCancellable) { replay(pending, recovering = false) }
            }
        }
    }

    private suspend fun recoverLocked() {
        val pending = stateMutex.withLock { journal.readState().optJSONObject("pending") }
        if (pending != null) {
            val sources = pending.getJSONArray("sources")
            withSources((0 until sources.length()).map { sources.getLong(it) }) {
                withContext(NonCancellable) { replay(pending) }
            }
        }
        val revision = stateMutex.withLock {
            journal.removeOrphans(null)
            journal.readState().getLong("revision")
        }
        _progress.update { it.copy(revision = revision) }
    }

    private suspend fun replay(pending: JSONObject, recovering: Boolean = true): GroupEditResult {
        val id = pending.getString("id")
        val profileId = pending.getLong("profile")
        val type = MediaType.valueOf(pending.getString("type"))
        val action = GroupAction.valueOf(pending.getString("action"))
        val target = pending.optString("target")
        val origin = pending.optString("origin")
        val total = pending.getInt("total")
        val chunks = pending.getInt("chunks")
        checkDomain(db.profileDao().getById(profileId) != null, GroupError.INVALID_PROFILE)
        if (recovering) {
            // Detach all stale IDs before inserting any replacements: ID swaps may cross chunks.
            // Every detachment is conditional on the saved position/time, protecting newer rows.
            for (index in 0 until chunks) {
                val resolved = resolveRows(type, journal.readChunk(id, index))
                db.transaction {
                    for ((record, itemId) in resolved) {
                        val oldId = record.getLong("oid")
                        if (oldId == itemId) continue
                        if (record.has("pos")) db.customCategoryDao().detachJournalRow(profileId, type, target,
                            oldId, record.getInt("pos"), record.getLong("at"))
                        if (record.has("originPos")) db.customCategoryDao().detachJournalRow(profileId, type, origin,
                            oldId, record.getInt("originPos"), record.getLong("originAt"))
                        if (record.has("favoriteAt")) db.favoriteDao().detachJournalRow(profileId, type, oldId, record.getLong("favoriteAt"))
                    }
                }
            }
        }
        // Completed chunks also replay after restart: their row IDs may have changed since commit.
        for (index in 0 until chunks) {
            val resolved = resolveRows(type, journal.readChunk(id, index))
            val missing = JSONArray()
            db.transaction {
                val additions = resolved.mapNotNull { (record, itemId) ->
                    if (!record.has("pos")) null
                    else if (itemId == null) {
                        missing.put(JSONObject(record.toString()).put("p", profileId).put("kind", "member").put("ctx", target))
                        null
                    } else CustomCategoryMemberEntity(
                        profileId = profileId, mediaType = type, contextKey = target, itemId = itemId,
                        position = record.getInt("pos"), addedAt = record.getLong("at"),
                    )
                }
                if (additions.isNotEmpty()) db.customCategoryDao().insertAbsentMembers(additions)
                if ((action == GroupAction.MOVE || action == GroupAction.REMOVE) && pending.getBoolean("customOrigin")) {
                    writer.removeCustomCategoryMembers(profileId, type, origin,
                        resolved.map { (record, itemId) -> GroupUserDataRemoval(record, itemId, record.getLong("deleteAt")) })
                }
                if (action == GroupAction.MOVE && pending.getBoolean("favoritesOrigin")) {
                    writer.removeGroupFavorites(profileId, type, resolved.map { (record, itemId) ->
                        GroupUserDataRemoval(record, itemId, record.getLong("deleteAt"))
                    })
                }
            }
            if (missing.length() > 0) userData.importAll(missing)
            afterStep(GroupMutationStage.MEMBERSHIP_WRITTEN, index)
            val keys = resolved.keys.associate { it.getString("key") to it.getString("name") }
            when (action) {
                GroupAction.HIDE, GroupAction.UNHIDE -> customize.setItemsHidden(profileId, type, keys, action == GroupAction.HIDE)
                GroupAction.SUPPRESS -> customize.update(profileId, type) { it.copy(movedFromOrigin = it.movedFromOrigin + keys.mapValues { origin }) }
                GroupAction.RESTORE_ORIGIN -> customize.update(profileId, type) {
                    it.copy(movedFromOrigin = it.movedFromOrigin.filterNot { (key, group) -> key in keys && group == origin })
                }
                GroupAction.MOVE -> if (!pending.getBoolean("customOrigin") && !pending.getBoolean("favoritesOrigin")) {
                    customize.update(profileId, type) { it.copy(movedFromOrigin = it.movedFromOrigin + resolved.keys.filter { it.has("origin") }.associate { it.getString("key") to it.getString("origin") }) }
                }
                GroupAction.REMOVE -> if (pending.getBoolean("restoreProvider")) {
                    customize.update(profileId, type) { it.copy(movedFromOrigin = it.movedFromOrigin - keys.keys) }
                }
                GroupAction.COPY -> Unit
            }
            afterStep(GroupMutationStage.ORIGIN_WRITTEN, index)
            val revision = stateMutex.withLock {
                val state = journal.readState()
                state.getJSONObject("pending").put("next", index + 1)
                journal.writeState(state)
                state.getLong("revision")
            }
            _progress.value = GroupOperationProgress(id, minOf((index + 1) * GROUP_BATCH_SIZE, total), total, revision)
            afterStep(GroupMutationStage.CHUNK_COMMITTED, index)
        }
        val revision = stateMutex.withLock {
            val state = journal.readState()
            state.remove("pending")
            state.put("revision", nextRevision(state.getLong("revision")))
            journal.writeState(state)
            state.getLong("revision")
        }
        journal.removeOperation(id)
        _progress.value = GroupOperationProgress(revision = revision)
        return GroupEditResult(id, total, pending.getInt("added"), revision)
    }

    private suspend fun resolveRows(type: MediaType, rows: JSONArray): LinkedHashMap<JSONObject, Long?> {
        val records = (0 until rows.length()).map { rows.getJSONObject(it) }
        val current = load(type, records.map { it.getLong("oid") }).associateBy { it.id }
        val resolved = linkedMapOf<JSONObject, Long?>()
        val changed = mutableListOf<JSONObject>()
        for (record in records) {
            val old = current[record.getLong("oid")]
            val sameIdentity = old != null && old.sourceId == record.getLong("src") &&
                if (record.has("rid")) old.remoteId == record.getString("rid") else old.remoteId == null && old.name == record.getString("name")
            resolved[record] = if (sameIdentity) old.id else null
            if (!sameIdentity) changed += record
        }
        if (changed.isNotEmpty()) changed.zip(userData.currentItemIds(type, changed)).forEach { (record, id) -> resolved[record] = id }
        return resolved
    }

    private suspend fun load(type: MediaType, ids: List<Long>): List<GroupCatalogItem> = when (type) {
        MediaType.LIVE -> db.groupCatalogDao().channels(ids)
        MediaType.MOVIE -> db.groupCatalogDao().movies(ids)
        MediaType.SERIES -> db.groupCatalogDao().series(ids)
        MediaType.EPISODE -> throw GroupEditException(GroupError.INVALID_TYPE)
    }

    private suspend fun advanceRevision() {
        val revision = stateMutex.withLock {
            val state = journal.readState()
            state.put("revision", nextRevision(state.getLong("revision")))
            journal.writeState(state)
            state.getLong("revision")
        }
        _progress.update { it.copy(revision = revision) }
    }

    private suspend fun <T> withSources(ids: List<Long>, block: suspend () -> T): T {
        val held = mutableListOf<Mutex>()
        try {
            for (id in ids.distinct().sorted()) {
                val mutex = sourceLocks.getOrPut(id) { Mutex() }
                mutex.lock()
                held += mutex
            }
            return block()
        } finally { held.asReversed().forEach { it.unlock() } }
    }

    private fun nextRevision(revision: Long): Long {
        checkDomain(revision < Long.MAX_VALUE, GroupError.REVISION_CONFLICT)
        return revision + 1
    }

    private fun checkDomain(condition: Boolean, error: GroupError) {
        if (!condition) throw GroupEditException(error)
    }
}

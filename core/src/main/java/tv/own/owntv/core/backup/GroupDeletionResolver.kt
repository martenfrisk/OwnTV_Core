package tv.own.owntv.core.backup

import android.content.Context
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.customize.GroupEditException
import tv.own.owntv.core.customize.GroupError
import tv.own.owntv.core.customize.GroupMutationStage
import tv.own.owntv.core.database.OwnTVDatabase
import tv.own.owntv.core.database.dao.GroupCatalogItem
import tv.own.owntv.core.database.transaction
import tv.own.owntv.core.model.MediaType

/** A deletion fact doubles as a durable cross-store cleanup intent; it is never a provider edit. */
internal class GroupDeletionResolver(
    private val context: Context,
    private val db: OwnTVDatabase,
    private val userData: UserDataResolver,
) {
    private val customize = CustomizationStore(context)

    fun identity(type: MediaType, key: String): JSONObject = JSONObject().put("t", type.name).put("src", -1).put("ctx", key)

    suspend fun isDeleted(profileId: Long, type: MediaType, key: String): Boolean =
        db.tombstoneDao().deletedAt(profileId, "group", userData.canonicalIdentity(identity(type, key))) != null

    suspend fun record(profileId: Long, type: MediaType, key: String, at: Long) {
        db.tombstoneDao().record(profileId, "group", userData.canonicalIdentity(identity(type, key)), at)
    }

    /** Called under catalog coordination. Already-applied facts cost only the pending-marker query. */
    suspend fun reconcile(afterStep: suspend (GroupMutationStage, Int) -> Unit = { _, _ -> }) {
        var completed = 0
        while (true) {
            val pending = db.tombstoneDao().pendingGroupDeletions(500)
            if (pending.isEmpty()) break
            for (marker in pending) {
                val record = JSONObject(marker.identity)
                val type = MediaType.valueOf(record.getString("t"))
                val key = record.getString("ctx")
                if (type == MediaType.EPISODE || !CustomizeKeys.isCustom(key)) throw GroupEditException(GroupError.INVALID_GROUP)
                val restoreKeys = linkedSetOf<String>()
                // A received member tombstone may already have removed the Room row in this payload.
                // Its stable identity still supplies the provider-origin key during group cleanup.
                var afterDeletion = 0L
                while (true) {
                    val facts = db.tombstoneDao().memberDeletionsAfter(marker.profileId, afterDeletion, 500)
                    if (facts.isEmpty()) break
                    for (fact in facts) {
                        if (fact.deletedAt < marker.deletedAt) continue
                        val identity = JSONObject(fact.identity)
                        if (identity.optString("t") != type.name || identity.optString("ctx") != key) continue
                        restoreKeys += CustomizeKeys.item(identity.getLong("src"),
                            identity.optString("rid").takeIf { it.isNotEmpty() }, identity.getString("name"))
                    }
                    afterDeletion = facts.last().id
                }
                var afterId = 0L
                while (true) {
                    val members = db.customCategoryDao().membersAfter(marker.profileId, type, key, afterId, 500)
                    if (members.isEmpty()) break
                    val catalog = load(type, members.map { it.itemId }).associateBy { it.id }
                    db.transaction {
                        for (member in members) {
                            val item = catalog[member.itemId] ?: continue
                            restoreKeys += CustomizeKeys.item(item.sourceId, item.remoteId, item.name)
                            if (member.addedAt == Long.MAX_VALUE) throw GroupEditException(GroupError.POSITION_OVERFLOW)
                            val identity = JSONObject().put("t", type.name).put("src", item.sourceId)
                                .putOpt("rid", item.remoteId).put("name", item.name)
                            userData.rememberGroupRemoval(marker.profileId, identity, key, maxOf(marker.deletedAt, member.addedAt + 1))
                        }
                    }
                    afterId = members.last().id
                }
                // Missing catalog items still have stable pending identities and provider-hide keys.
                val pendingRows = context.pendingStore.data.first()[PENDING_KEY]?.let { JSONArray(it) } ?: JSONArray()
                db.transaction {
                    for (i in 0 until pendingRows.length()) {
                        val entry = pendingRows.getJSONObject(i)
                        if (!belongs(entry, marker.profileId, type, key) || entry.optString("kind") != "member") continue
                        val at = entry.optLong("at", 0)
                        if (at == Long.MAX_VALUE) throw GroupEditException(GroupError.POSITION_OVERFLOW)
                        restoreKeys += CustomizeKeys.item(entry.getLong("src"), entry.optString("rid").takeIf { it.isNotEmpty() }, entry.getString("name"))
                        userData.rememberGroupRemoval(marker.profileId, entry, key, maxOf(marker.deletedAt, at + 1))
                    }
                }
                // Definition first: a crash never clears the only identities needed to restore origins.
                customize.deleteCustomCategory(marker.profileId, type, key, restoreKeys)
                afterStep(GroupMutationStage.GROUP_DEFINITION_WRITTEN, completed)
                db.transaction {
                    db.customCategoryDao().clearContext(marker.profileId, type, key)
                    db.contentOrderDao().clearContext(marker.profileId, type, key)
                }
                context.pendingStore.edit { prefs ->
                    val latest = prefs[PENDING_KEY]?.let { JSONArray(it) } ?: JSONArray()
                    val remaining = JSONArray()
                    for (i in 0 until latest.length()) {
                        val entry = latest.getJSONObject(i)
                        if (!belongs(entry, marker.profileId, type, key)) remaining.put(entry)
                    }
                    if (remaining.length() == 0) prefs.remove(PENDING_KEY) else prefs[PENDING_KEY] = remaining.toString()
                }
                db.tombstoneDao().markGroupApplied(marker.profileId, marker.identity, marker.deletedAt)
                afterStep(GroupMutationStage.GROUP_CLEANUP_COMPLETE, completed++)
            }
        }
    }

    private fun belongs(record: JSONObject, profileId: Long, type: MediaType, key: String): Boolean =
        record.optLong("p", -1) == profileId && record.optString("t") == type.name && record.optString("ctx") == key &&
            record.optString("kind") in setOf("member", "order")

    private suspend fun load(type: MediaType, ids: List<Long>): List<GroupCatalogItem> = when (type) {
        MediaType.LIVE -> db.groupCatalogDao().channels(ids)
        MediaType.MOVIE -> db.groupCatalogDao().movies(ids)
        MediaType.SERIES -> db.groupCatalogDao().series(ids)
        MediaType.EPISODE -> throw GroupEditException(GroupError.INVALID_TYPE)
    }
}

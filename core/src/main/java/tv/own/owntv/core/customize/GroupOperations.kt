package tv.own.owntv.core.customize

import tv.own.owntv.core.model.MediaType

/** Source filters restrict a profile's linked playlists; they never grant extra access. */
data class GroupScope(val profileId: Long, val mediaType: MediaType, val sourceIds: Set<Long>? = null)

enum class GroupAction { COPY, MOVE, REMOVE, SUPPRESS, RESTORE_ORIGIN, HIDE, UNHIDE }

data class GroupEdit(
    val scope: GroupScope,
    val action: GroupAction,
    val itemIds: List<Long>,
    val target: String? = null,
    /** A null Move origin suppresses each item’s actual provider group (All/search views). */
    val origin: String? = null,
    val expectedRevision: Long? = null,
    /** Only the existing TV Favorites-origin adapter sets this. General Move keeps favorites. */
    val removeFromFavorites: Boolean = false,
    /** Explicit compatibility policy; TV's existing Remove action restores the provider origin. */
    val restoreProviderOnRemove: Boolean = false,
)

data class GroupEditResult(val operationId: String, val selected: Int, val added: Int, val revision: Long)

enum class GroupError {
    INVALID_PROFILE, INVALID_TYPE, INVALID_SOURCE, INVALID_ITEM, INVALID_TARGET, INVALID_ORIGIN,
    REVISION_CONFLICT, POSITION_OVERFLOW, JOURNAL_UNAVAILABLE,
}

class GroupEditException(val code: GroupError) : IllegalArgumentException(code.name)

data class GroupOperationProgress(
    val operationId: String? = null,
    val completed: Int = 0,
    val total: Int = 0,
    val revision: Long = 0,
    val failure: GroupError? = null,
)

/** Failure-injection boundary used by real-store recovery tests. No callback is installed in production. */
enum class GroupMutationStage { MEMBERSHIP_WRITTEN, ORIGIN_WRITTEN, CHUNK_COMMITTED }

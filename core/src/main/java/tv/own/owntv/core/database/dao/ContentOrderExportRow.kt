package tv.own.owntv.core.database.dao

import tv.own.owntv.core.model.MediaType

/**
 * One manual-order row joined to its content row's stable identity, for the per-source re-sync
 * snapshot ([ContentOrderDao.exportRowsForSource]). Separate from [UserDataExportRow] because order
 * rows carry a context key + position instead of a timestamp, and never cover episodes.
 */
data class ContentOrderExportRow(
    val profileId: Long,
    val mediaType: MediaType,
    val itemId: Long,
    val contextKey: String,
    val position: Int,
    val modifiedAt: Long,
    val sourceId: Long,
    val remoteId: String?,
    val name: String?,
)

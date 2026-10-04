package tv.own.owntv.core.customize

import androidx.datastore.preferences.core.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tv.own.owntv.core.backup.PENDING_KEY
import tv.own.owntv.core.backup.UserDataResolver
import tv.own.owntv.core.backup.UserDataWriter
import tv.own.owntv.core.backup.pendingStore
import tv.own.owntv.core.database.OwnTVDatabase
import tv.own.owntv.core.database.entity.CategoryEntity
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.CustomCategoryMemberEntity
import tv.own.owntv.core.database.entity.ProfileEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.database.ownTVTestDatabase
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.model.SourceType

/** Real Room/driver tests: membership must survive the same resync/backup identity path as favorites. */
@RunWith(AndroidJUnit4::class)
class CustomCategoryMembershipTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: OwnTVDatabase
    private lateinit var resolver: UserDataResolver
    private var profileId = 0L
    private var otherProfileId = 0L
    private var sourceId = 0L
    private var otherSourceId = 0L
    private var providerCategoryId = 0L

    @Before
    fun setUp(): Unit = runBlocking {
        db = ownTVTestDatabase()
        profileId = db.profileDao().insert(ProfileEntity(name = "Primary", avatarColor = 0))
        otherProfileId = db.profileDao().insert(ProfileEntity(name = "Other", avatarColor = 0))
        sourceId = db.sourceDao().insert(SourceEntity(name = "A", type = SourceType.M3U, url = "https://a.test/list"))
        otherSourceId = db.sourceDao().insert(SourceEntity(name = "B", type = SourceType.M3U, url = "https://b.test/list"))
        providerCategoryId = db.categoryDao().insertAll(
            listOf(CategoryEntity(sourceId = sourceId, mediaType = MediaType.LIVE, name = "News", remoteId = "news")),
        ).single()
        resolver = UserDataResolver(
            context, db.channelDao(), db.movieDao(), db.seriesDao(), db.profileDao(),
            db.favoriteDao(), db.historyDao(), db.progressDao(), db.contentOrderDao(),
            db.customCategoryDao(), db.seriesSortOrderDao(), db.tombstoneDao(), db,
        )
        context.pendingStore.edit { it.remove(PENDING_KEY) }
    }

    @After
    fun tearDown() = runBlocking {
        context.pendingStore.edit { it.remove(PENDING_KEY) }
        db.close()
    }

    private suspend fun channel(id: Long, source: Long = sourceId, remoteId: String = "channel-$id") {
        db.channelDao().insertAll(listOf(ChannelEntity(
            id = id, sourceId = source, categoryId = providerCategoryId.takeIf { source == sourceId },
            name = "Channel $id", remoteId = remoteId, streamUrl = "https://stream.test/$id",
        )))
    }

    @Test
    fun copyKeepsProviderIdentityAndAllowsSeveralCustomGroups() = runBlocking {
        channel(1)
        val dao = db.customCategoryDao()
        dao.appendItem(profileId, MediaType.LIVE, "custom:a", 1)
        dao.appendItem(profileId, MediaType.LIVE, "custom:b", 1)

        assertEquals(setOf("custom:a", "custom:b"), dao.contextsOf(profileId, MediaType.LIVE, 1).toSet())
        val original = db.channelDao().getById(1)!!
        assertEquals(sourceId, original.sourceId)
        assertEquals(providerCategoryId, original.categoryId)
        assertEquals("channel-1", original.remoteId)
    }

    @Test
    fun repeatingCopyDoesNotMoveAnExistingMemberToTheTail() = runBlocking {
        channel(1)
        channel(2)
        val dao = db.customCategoryDao()
        dao.appendItem(profileId, MediaType.LIVE, "custom:a", 1)
        dao.appendItem(profileId, MediaType.LIVE, "custom:a", 2)
        val before = dao.getAllOnce().associateBy { it.itemId }
        dao.appendItem(profileId, MediaType.LIVE, "custom:a", 1)

        assertEquals(before, dao.getAllOnce().associateBy { it.itemId })
        assertEquals(listOf(1L, 2L), dao.snapshotChannels(profileId, "custom:a", listOf(sourceId), 10).map { it.id })
    }

    @Test
    fun membershipsAreIsolatedByProfileAndFilteredBySource() = runBlocking {
        channel(1)
        channel(2, otherSourceId)
        val dao = db.customCategoryDao()
        dao.appendItem(profileId, MediaType.LIVE, "custom:a", 1)
        dao.appendItem(profileId, MediaType.LIVE, "custom:a", 2)
        dao.appendItem(otherProfileId, MediaType.LIVE, "custom:a", 2)

        assertEquals(listOf(1L), dao.snapshotChannels(profileId, "custom:a", listOf(sourceId), 10).map { it.id })
        assertEquals(listOf(2L), dao.snapshotChannels(otherProfileId, "custom:a", listOf(sourceId, otherSourceId), 10).map { it.id })
        dao.deleteItem(profileId, MediaType.LIVE, "custom:a", 2)
        assertTrue(dao.exists(otherProfileId, MediaType.LIVE, "custom:a", 2))
    }

    @Test
    fun removingOneCustomMembershipLeavesOtherGroupsAndProviderData() = runBlocking {
        channel(1)
        val dao = db.customCategoryDao()
        dao.appendItem(profileId, MediaType.LIVE, "custom:a", 1)
        dao.appendItem(profileId, MediaType.LIVE, "custom:b", 1)
        dao.deleteItem(profileId, MediaType.LIVE, "custom:a", 1)

        assertFalse(dao.exists(profileId, MediaType.LIVE, "custom:a", 1))
        assertTrue(dao.exists(profileId, MediaType.LIVE, "custom:b", 1))
        assertEquals(providerCategoryId, db.channelDao().getById(1)!!.categoryId)
    }

    @Test
    fun resyncRelinksAllCustomMembershipsToTheNewRowId() = runBlocking {
        channel(1, remoteId = "stable")
        val dao = db.customCategoryDao()
        dao.appendItem(profileId, MediaType.LIVE, "custom:a", 1)
        dao.appendItem(profileId, MediaType.LIVE, "custom:b", 1)
        val snapshot = resolver.exportForSource(sourceId, setOf("member"))
        val times = dao.getAllOnce().associate { it.contextKey to it.addedAt }

        db.channelDao().clearSource(sourceId)
        channel(100, remoteId = "stable")
        resolver.relinkAfterSync(snapshot)

        assertEquals(setOf("custom:a", "custom:b"), dao.contextsOf(profileId, MediaType.LIVE, 100).toSet())
        assertEquals(setOf(100L), dao.getAllOnce().map { it.itemId }.toSet())
        assertEquals(times, dao.getAllOnce().associate { it.contextKey to it.addedAt })
    }

    @Test
    fun temporarilyMissingMemberRecoversWhenItsStableIdentityReturns() = runBlocking {
        channel(1, remoteId = "stable")
        val dao = db.customCategoryDao()
        dao.appendItem(profileId, MediaType.LIVE, "custom:a", 1)
        val snapshot = resolver.exportForSource(sourceId, setOf("member"))
        val addedAt = dao.getAllOnce().single().addedAt
        db.channelDao().clearSource(sourceId)
        resolver.relinkAfterSync(snapshot)
        assertTrue(dao.getAllOnce().isEmpty())

        channel(100, remoteId = "stable")
        resolver.resolvePending()
        assertTrue(dao.exists(profileId, MediaType.LIVE, "custom:a", 100))
        assertEquals(0, dao.getAllOnce().single().position)
        assertEquals(addedAt, dao.getAllOnce().single().addedAt)
    }

    @Test
    fun backupMembershipRecordsRestoreMultipleGroupsWithoutProviderReassignment() = runBlocking {
        channel(1, remoteId = "stable")
        val dao = db.customCategoryDao()
        dao.appendItem(profileId, MediaType.LIVE, "custom:a", 1)
        dao.appendItem(profileId, MediaType.LIVE, "custom:b", 1)
        val backup = resolver.exportAll(setOf("member"))
        val times = dao.getAllOnce().associate { it.contextKey to it.addedAt }
        dao.clearContext(profileId, MediaType.LIVE, "custom:a")
        dao.clearContext(profileId, MediaType.LIVE, "custom:b")
        resolver.importAll(backup)

        assertEquals(setOf("custom:a", "custom:b"), dao.contextsOf(profileId, MediaType.LIVE, 1).toSet())
        assertEquals(sourceId, db.channelDao().getById(1)!!.sourceId)
        assertEquals(providerCategoryId, db.channelDao().getById(1)!!.categoryId)
        assertEquals(times, dao.getAllOnce().associate { it.contextKey to it.addedAt })
    }

    @Test
    fun bulkCopyAddsOnlyMissingItemsInFirstSelectedOrder() = runBlocking {
        val dao = db.customCategoryDao()
        dao.appendItems(profileId, MediaType.LIVE, "custom:a", listOf(2, 1))
        val original = dao.getAllOnce().associateBy { it.itemId }
        assertEquals(2, dao.appendItems(profileId, MediaType.LIVE, "custom:a", listOf(1, 3, 3, 2, 4)))
        assertEquals(0, dao.appendItems(profileId, MediaType.LIVE, "custom:a", listOf(4, 2, 1, 3)))
        assertEquals(0, dao.appendItems(profileId, MediaType.LIVE, "custom:a", emptyList()))
        val rows = dao.getAllOnce().sortedBy { it.position }
        assertEquals(listOf(2L, 1L, 3L, 4L), rows.map { it.itemId })
        original.forEach { (id, row) -> assertEquals(row, rows.single { it.itemId == id }) }
    }

    @Test
    fun fiftyThousandMembersCrossQueryChunksWithoutDuplicatesOrOrderLoss() = runBlocking {
        val dao = db.customCategoryDao()
        val ids = (1L..50_000L).toList()
        assertEquals(50_000, dao.appendItems(profileId, MediaType.LIVE, "custom:large", ids + ids))
        assertEquals(0, dao.appendItems(profileId, MediaType.LIVE, "custom:large", ids.reversed()))
        val rows = dao.getAllOnce().sortedBy { it.position }
        assertEquals(ids, rows.map { it.itemId })
        assertEquals((0 until 50_000).toList(), rows.map { it.position })
    }

    @Test
    fun concurrentBulkCopiesShareNoPositionsAndDoNotReplaceOverlappingMembers() = runBlocking {
        val dao = db.customCategoryDao()
        val counts = (0 until 4).map { worker ->
            async(Dispatchers.IO) {
                val first = worker * 250L + 1
                dao.appendItems(profileId, MediaType.LIVE, "custom:concurrent", (first..first + 499).toList())
            }
        }.awaitAll()
        val rows = dao.getAllOnce()
        assertEquals(1_250, counts.sum())
        assertEquals((1L..1_250L).toSet(), rows.map { it.itemId }.toSet())
        assertEquals((0 until 1_250).toSet(), rows.map { it.position }.toSet())
    }

    @Test
    fun positionOverflowRollsBackEarlierChunksOfTheBulkCopy() = runBlocking {
        val dao = db.customCategoryDao()
        val original = CustomCategoryMemberEntity(
            profileId = profileId, mediaType = MediaType.LIVE, contextKey = "custom:full",
            itemId = 1, position = Int.MAX_VALUE - 500,
        )
        dao.insertAll(listOf(original))
        val before = dao.getAllOnce()
        var refused = false
        try {
            dao.appendItems(profileId, MediaType.LIVE, "custom:full", (2L..502L).toList())
        } catch (_: IllegalStateException) {
            refused = true
        }
        assertTrue(refused)
        assertEquals(before, dao.getAllOnce())
    }

    @Test
    fun bulkCopyScopesDuplicateItemIdsByMediaType() = runBlocking {
        val dao = db.customCategoryDao()
        val types = listOf(MediaType.LIVE, MediaType.MOVIE, MediaType.SERIES)
        types.forEach { type ->
            assertEquals(2, dao.appendItems(profileId, type, "custom:shared", listOf(1, 2)))
            assertEquals(0, dao.appendItems(profileId, type, "custom:shared", listOf(2, 1)))
        }
        dao.deleteItem(profileId, MediaType.LIVE, "custom:shared", 1)
        assertTrue(dao.exists(profileId, MediaType.MOVIE, "custom:shared", 1))
        assertTrue(dao.exists(profileId, MediaType.SERIES, "custom:shared", 1))
        assertEquals(5, dao.getAllOnce().size)
    }

    @Test
    fun userRemovalTombstonePreventsSyncFromRevivingOnlyTheDeletedMembership() = runBlocking {
        channel(1, remoteId = "stable")
        val dao = db.customCategoryDao()
        dao.appendItem(profileId, MediaType.LIVE, "custom:a", 1)
        dao.appendItem(profileId, MediaType.LIVE, "custom:b", 1)
        val beforeRemoval = resolver.exportAll(setOf("member"))
        val writer = UserDataWriter(
            db, db.favoriteDao(), db.historyDao(), db.progressDao(), dao, resolver,
        )
        writer.removeCustomCategoryMember(profileId, MediaType.LIVE, "custom:a", 1)

        assertEquals(1, resolver.exportTombstones(setOf("member")).length())
        assertEquals(1, resolver.importAll(beforeRemoval))
        assertFalse(dao.exists(profileId, MediaType.LIVE, "custom:a", 1))
        assertTrue(dao.exists(profileId, MediaType.LIVE, "custom:b", 1))
        assertEquals(providerCategoryId, db.channelDao().getById(1)!!.categoryId)
    }

    @Test
    fun staleDeletionAndStaleMembershipCannotUndoANewerReaddition() = runBlocking {
        channel(1, remoteId = "stable")
        val dao = db.customCategoryDao()
        val old = CustomCategoryMemberEntity(
            profileId = profileId, mediaType = MediaType.LIVE, contextKey = "custom:a",
            itemId = 1, position = 0, addedAt = 100,
        )
        dao.insertAll(listOf(old))
        val oldAddition = resolver.exportAll(setOf("member"))
        resolver.recordDeletion(profileId, "member", MediaType.LIVE, 1, "custom:a", at = 200)
        val oldDeletion = resolver.exportTombstones(setOf("member"))
        dao.deleteItem(profileId, MediaType.LIVE, "custom:a", 1)
        dao.insertAll(listOf(old.copy(addedAt = 300)))

        assertFalse(resolver.wouldRemove(profileId, oldDeletion.getJSONObject(0)))
        assertEquals(0, resolver.applyTombstones(oldDeletion))
        assertEquals(1, resolver.importAll(oldAddition))
        assertEquals(300L, dao.getAllOnce().single().addedAt)
        resolver.clearDeletionsFor(listOf(profileId))
        assertEquals(0, resolver.importAll(oldAddition))
        assertEquals(300L, dao.getAllOnce().single().addedAt)
        assertEquals(300L, resolver.exportAll(setOf("member")).getJSONObject(0).getLong("at"))
    }

    @Test
    fun legacyMembershipIsRefusedAfterDeletionButExplicitRestoreStillWorks() = runBlocking {
        channel(1, remoteId = "stable")
        val dao = db.customCategoryDao()
        dao.appendItem(profileId, MediaType.LIVE, "custom:a", 1)
        val legacy = resolver.exportAll(setOf("member"))
        legacy.getJSONObject(0).remove("at")
        val writer = UserDataWriter(
            db, db.favoriteDao(), db.historyDao(), db.progressDao(), dao, resolver,
        )
        writer.removeCustomCategoryMember(profileId, MediaType.LIVE, "custom:a", 1)

        assertFalse(resolver.wouldAdd(profileId, "member", legacy.getJSONObject(0)))
        assertEquals(1, resolver.importAll(legacy))
        assertFalse(dao.exists(profileId, MediaType.LIVE, "custom:a", 1))
        resolver.clearDeletionsFor(listOf(profileId)) // BackupManager's explicit RESTORE policy.
        assertEquals(0, resolver.importAll(legacy))
        assertTrue(dao.exists(profileId, MediaType.LIVE, "custom:a", 1))
        assertEquals(0L, dao.getAllOnce().single().addedAt)
    }

    @Test
    fun readdingImmediatelyAfterRemovalOutranksTheRecordedDeletion() = runBlocking {
        channel(1, remoteId = "stable")
        val dao = db.customCategoryDao()
        dao.appendItem(profileId, MediaType.LIVE, "custom:a", 1)
        // An already observed deletion can come from a peer whose clock is ahead of this device.
        resolver.recordDeletion(
            profileId, "member", MediaType.LIVE, 1, "custom:a",
            at = System.currentTimeMillis() + 60_000,
        )
        val oldDeletion = resolver.exportTombstones(setOf("member"))
        dao.deleteItem(profileId, MediaType.LIVE, "custom:a", 1)
        dao.appendItem(profileId, MediaType.LIVE, "custom:a", 1)

        val addition = resolver.exportAll(setOf("member"))
        assertTrue(addition.getJSONObject(0).getLong("at") > oldDeletion.getJSONObject(0).getLong("at"))
        assertEquals(0, resolver.applyTombstones(oldDeletion))
        assertEquals(0, resolver.importAll(addition))
        assertTrue(dao.exists(profileId, MediaType.LIVE, "custom:a", 1))
        val writer = UserDataWriter(
            db, db.favoriteDao(), db.historyDao(), db.progressDao(), dao, resolver,
        )
        writer.removeCustomCategoryMember(profileId, MediaType.LIVE, "custom:a", 1)
        assertEquals(1, resolver.importAll(addition))
    }
}

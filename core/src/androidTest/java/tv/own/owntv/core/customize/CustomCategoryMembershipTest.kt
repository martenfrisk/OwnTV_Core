package tv.own.owntv.core.customize

import androidx.datastore.preferences.core.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tv.own.owntv.core.backup.PENDING_KEY
import tv.own.owntv.core.backup.UserDataResolver
import tv.own.owntv.core.backup.pendingStore
import tv.own.owntv.core.database.OwnTVDatabase
import tv.own.owntv.core.database.entity.CategoryEntity
import tv.own.owntv.core.database.entity.ChannelEntity
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

        db.channelDao().clearSource(sourceId)
        channel(100, remoteId = "stable")
        resolver.relinkAfterSync(snapshot)

        assertEquals(setOf("custom:a", "custom:b"), dao.contextsOf(profileId, MediaType.LIVE, 100).toSet())
        assertEquals(setOf(100L), dao.getAllOnce().map { it.itemId }.toSet())
    }

    @Test
    fun temporarilyMissingMemberRecoversWhenItsStableIdentityReturns() = runBlocking {
        channel(1, remoteId = "stable")
        val dao = db.customCategoryDao()
        dao.appendItem(profileId, MediaType.LIVE, "custom:a", 1)
        val snapshot = resolver.exportForSource(sourceId, setOf("member"))
        db.channelDao().clearSource(sourceId)
        resolver.relinkAfterSync(snapshot)
        assertTrue(dao.getAllOnce().isEmpty())

        channel(100, remoteId = "stable")
        resolver.resolvePending()
        assertTrue(dao.exists(profileId, MediaType.LIVE, "custom:a", 100))
        assertEquals(0, dao.getAllOnce().single().position)
    }

    @Test
    fun backupMembershipRecordsRestoreMultipleGroupsWithoutProviderReassignment() = runBlocking {
        channel(1, remoteId = "stable")
        val dao = db.customCategoryDao()
        dao.appendItem(profileId, MediaType.LIVE, "custom:a", 1)
        dao.appendItem(profileId, MediaType.LIVE, "custom:b", 1)
        val backup = resolver.exportAll(setOf("member"))
        dao.clearContext(profileId, MediaType.LIVE, "custom:a")
        dao.clearContext(profileId, MediaType.LIVE, "custom:b")
        resolver.importAll(backup)

        assertEquals(setOf("custom:a", "custom:b"), dao.contextsOf(profileId, MediaType.LIVE, 1).toSet())
        assertEquals(sourceId, db.channelDao().getById(1)!!.sourceId)
        assertEquals(providerCategoryId, db.channelDao().getById(1)!!.categoryId)
    }
}

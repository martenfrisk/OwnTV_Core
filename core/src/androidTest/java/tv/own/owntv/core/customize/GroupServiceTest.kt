package tv.own.owntv.core.customize

import androidx.datastore.preferences.core.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tv.own.owntv.core.backup.*
import tv.own.owntv.core.database.OwnTVDatabase
import tv.own.owntv.core.database.entity.*
import tv.own.owntv.core.database.ownTVTestDatabase
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.model.SourceType

/** Real Room + DataStore + AtomicFile contracts, including restart between the two stores. */
@RunWith(AndroidJUnit4::class)
class GroupServiceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: OwnTVDatabase
    private lateinit var store: CustomizationStore
    private lateinit var resolver: UserDataResolver
    private lateinit var writer: UserDataWriter
    private lateinit var directory: File
    private lateinit var service: GroupService
    private var profile = 0L
    private var otherProfile = 0L
    private var source = 0L
    private var otherSource = 0L
    private var category = 0L
    private lateinit var origin: String
    private lateinit var a: String
    private lateinit var b: String
    private lateinit var c: String

    @Before fun setUp(): Unit = runBlocking {
        db = ownTVTestDatabase()
        store = CustomizationStore(context)
        profile = db.profileDao().insert(ProfileEntity(name = "Primary", avatarColor = 0))
        otherProfile = db.profileDao().insert(ProfileEntity(name = "Other", avatarColor = 0))
        for (pid in listOf(profile, otherProfile)) for (type in listOf(MediaType.LIVE, MediaType.MOVIE, MediaType.SERIES)) {
            store.update(pid, type) { SectionCustomizations() }
        }
        source = db.sourceDao().insert(SourceEntity(name = "A", type = SourceType.M3U, url = "https://a.test/list"))
        otherSource = db.sourceDao().insert(SourceEntity(name = "B", type = SourceType.M3U, url = "https://b.test/list"))
        db.sourceDao().link(ProfileSourceCrossRef(profile, source))
        db.sourceDao().link(ProfileSourceCrossRef(profile, otherSource))
        db.sourceDao().link(ProfileSourceCrossRef(otherProfile, source))
        category = db.categoryDao().insertAll(listOf(CategoryEntity(sourceId = source, mediaType = MediaType.LIVE, name = "News", remoteId = "news"))).single()
        origin = "$source:news"
        a = store.createCustomCategory(profile, MediaType.LIVE, "A").id
        b = store.createCustomCategory(profile, MediaType.LIVE, "B").id
        c = store.createCustomCategory(profile, MediaType.LIVE, "C").id
        resolver = UserDataResolver(context, db.channelDao(), db.movieDao(), db.seriesDao(), db.profileDao(), db.favoriteDao(),
            db.historyDao(), db.progressDao(), db.contentOrderDao(), db.customCategoryDao(), db.seriesSortOrderDao(), db.tombstoneDao(), db)
        writer = UserDataWriter(db, db.favoriteDao(), db.historyDao(), db.progressDao(), db.customCategoryDao(), resolver)
        context.pendingStore.edit { it.remove(PENDING_KEY) }
        directory = File(context.cacheDir, "group-test-${UUID.randomUUID()}")
        service = newService()
        channels(1..3)
    }

    @After fun tearDown() = runBlocking {
        context.pendingStore.edit { it.remove(PENDING_KEY) }
        for (pid in listOf(profile, otherProfile)) for (type in listOf(MediaType.LIVE, MediaType.MOVIE, MediaType.SERIES)) {
            store.update(pid, type) { SectionCustomizations() }
        }
        directory.deleteRecursively()
        db.close()
    }

    private fun newService(hook: suspend (GroupMutationStage, Int) -> Unit = { _, _ -> }) =
        GroupService(context, db, store, resolver, writer, directory, hook)
    private suspend fun channels(ids: IntRange, sid: Long = source, remote: String? = null) {
        for (batch in ids.toList().chunked(500)) db.channelDao().insertAll(batch.map {
            ChannelEntity(id = it.toLong(), sourceId = sid, categoryId = category.takeIf { sid == source },
                name = "Channel $it", remoteId = remote ?: "channel-$it", streamUrl = "https://stream.test/$it?password=never-journaled")
        })
    }
    private fun edit(action: GroupAction, ids: List<Long> = listOf(1), target: String? = a, from: String? = null, rev: Long? = null) =
        GroupEdit(GroupScope(profile, MediaType.LIVE), action, ids, target, from, rev)
    private suspend fun custom() = store.observe(profile, MediaType.LIVE).first()
    private suspend fun rejected(code: GroupError, command: GroupEdit) {
        try { service.edit(command); fail("Expected $code") } catch (failure: GroupEditException) { assertEquals(code, failure.code) }
    }

    @Test fun copyIsOrderedIdempotentAndKeepsProviderFavoritesAndOtherGroups() = runBlocking {
        db.favoriteDao().add(FavoriteEntity(profileId = profile, mediaType = MediaType.LIVE, itemId = 1))
        assertEquals(3, service.edit(edit(GroupAction.COPY, listOf(3, 1, 2, 3))).added)
        val before = db.customCategoryDao().getAllOnce()
        val revision = service.revision()
        assertEquals(0, service.edit(edit(GroupAction.COPY, listOf(1, 3))).added)
        assertEquals(before, db.customCategoryDao().getAllOnce())
        assertEquals(revision, service.revision())
        service.edit(edit(GroupAction.COPY, target = b))
        assertEquals(listOf(3L, 1L, 2L), db.customCategoryDao().snapshotChannels(profile, a, listOf(source), 10).map { it.id })
        assertEquals(setOf(a, b), db.customCategoryDao().contextsOf(profile, MediaType.LIVE, 1).toSet())
        assertEquals(category, db.channelDao().getById(1)!!.categoryId)
        assertTrue(db.favoriteDao().exists(profile, MediaType.LIVE, 1))
        assertTrue(custom().movedFromOrigin.isEmpty())
    }

    @Test fun providerMoveHideAndRestoreHaveIndependentScopes() = runBlocking {
        service.edit(edit(GroupAction.MOVE, from = origin))
        val key = CustomizeKeys.item(source, "channel-1", "Channel 1")
        assertEquals(origin, custom().movedFromOrigin[key])
        service.edit(edit(GroupAction.HIDE))
        assertTrue(key in custom().hiddenItems)
        assertEquals(origin, custom().movedFromOrigin[key])
        service.edit(edit(GroupAction.RESTORE_ORIGIN, from = origin))
        assertFalse(key in custom().movedFromOrigin)
        assertTrue(key in custom().hiddenItems)
        assertTrue(db.customCategoryDao().exists(profile, MediaType.LIVE, a, 1))
        service.edit(edit(GroupAction.UNHIDE))
        assertTrue(custom().hiddenItems.isEmpty())
        assertTrue(store.observe(otherProfile, MediaType.LIVE).first().isEmpty)
        assertEquals(category, db.channelDao().getById(1)!!.categoryId)
    }

    @Test fun customMoveRemovesOnlyItsOriginAndRecordsStableScopedDeletion() = runBlocking {
        for (group in listOf(a, c)) service.edit(edit(GroupAction.COPY, target = group))
        db.favoriteDao().add(FavoriteEntity(profileId = profile, mediaType = MediaType.LIVE, itemId = 1))
        service.edit(edit(GroupAction.MOVE, target = b, from = a))
        assertEquals(setOf(b, c), db.customCategoryDao().contextsOf(profile, MediaType.LIVE, 1).toSet())
        assertTrue(db.favoriteDao().exists(profile, MediaType.LIVE, 1))
        val deletions = resolver.exportTombstones(setOf("member")).let { rows -> (0 until rows.length()).map { rows.getJSONObject(it) } }
        assertEquals(1, deletions.size)
        assertEquals(a, deletions.single().getString("ctx"))
    }

    @Test fun sameGroupMoveIsNoOpAndLegacyRemoveRestoresOnlyProviderSuppression() = runBlocking {
        service.edit(edit(GroupAction.MOVE, from = origin))
        val before = db.customCategoryDao().getAllOnce()
        val rev = service.revision()
        service.edit(edit(GroupAction.MOVE, from = a))
        assertEquals(rev, service.revision())
        assertEquals(before, db.customCategoryDao().getAllOnce())
        service.edit(edit(GroupAction.HIDE))
        service.edit(edit(GroupAction.REMOVE, from = a).copy(restoreProviderOnRemove = true))
        assertTrue(custom().movedFromOrigin.isEmpty())
        assertFalse(custom().hiddenItems.isEmpty())
        assertFalse(db.customCategoryDao().exists(profile, MediaType.LIVE, a, 1))
    }

    @Test fun invalidScopeDestinationAndOriginFailBeforeMutation() = runBlocking {
        val initial = service.revision()
        rejected(GroupError.INVALID_PROFILE, edit(GroupAction.COPY).copy(scope = GroupScope(999, MediaType.LIVE)))
        rejected(GroupError.INVALID_TYPE, edit(GroupAction.COPY).copy(scope = GroupScope(profile, MediaType.EPISODE)))
        rejected(GroupError.INVALID_SOURCE, edit(GroupAction.COPY).copy(scope = GroupScope(otherProfile, MediaType.LIVE, setOf(otherSource))))
        rejected(GroupError.INVALID_TARGET, edit(GroupAction.COPY, target = "custom:missing"))
        rejected(GroupError.INVALID_ORIGIN, edit(GroupAction.MOVE, from = b))
        rejected(GroupError.INVALID_ORIGIN, edit(GroupAction.MOVE, from = "all"))
        rejected(GroupError.INVALID_ITEM, edit(GroupAction.COPY, listOf(999)))
        rejected(GroupError.INVALID_SOURCE, edit(GroupAction.COPY).copy(scope = GroupScope(profile, MediaType.LIVE, setOf(otherSource))))
        assertTrue(db.customCategoryDao().getAllOnce().isEmpty())
        assertEquals(initial, service.revision())
    }

    @Test fun invalidLateBatchLeavesNoPublishedPlanOrPartialRows() = runBlocking {
        channels(4..501)
        rejected(GroupError.INVALID_ITEM, edit(GroupAction.COPY, (1L..501L).toList() + 99999))
        assertTrue(db.customCategoryDao().getAllOnce().isEmpty())
        assertEquals(0L, service.revision())
        assertFalse(GroupOperationJournal(directory).readState().has("pending"))
        assertTrue(directory.listFiles().orEmpty().none { it.isDirectory })
    }

    @Test fun concurrentCommandsWithSameRevisionPermitOnlyOneWriter() = runBlocking {
        val results = coroutineScope { listOf(1L, 2L).map { item -> async { runCatching { service.edit(edit(GroupAction.COPY, listOf(item), rev = 0)) } } }.map { it.await() } }
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(GroupError.REVISION_CONFLICT, (results.single { it.isFailure }.exceptionOrNull() as GroupEditException).code)
        assertEquals(1L, service.revision())
        assertEquals(1, db.customCategoryDao().getAllOnce().size)
    }

    @Test fun restartAfterRoomCommitFinishesProviderMoveWithoutDuplicatingRows() = runBlocking {
        val failing = newService { stage, _ -> if (stage == GroupMutationStage.MEMBERSHIP_WRITTEN) error("Simulated process death") }
        assertTrue(runCatching { failing.edit(edit(GroupAction.MOVE, from = origin)) }.isFailure)
        val before = db.customCategoryDao().getAllOnce().single()
        assertTrue(custom().movedFromOrigin.isEmpty())
        newService().recover()
        assertEquals(before, db.customCategoryDao().getAllOnce().single())
        assertEquals(origin, custom().movedFromOrigin[CustomizeKeys.item(source, "channel-1", "Channel 1")])
        assertEquals(1L, service.revision())
        assertFalse(GroupOperationJournal(directory).readState().has("pending"))
    }

    @Test fun restartAfterDataStoreCommitIsIdempotent() = runBlocking {
        val failing = newService { stage, _ -> if (stage == GroupMutationStage.ORIGIN_WRITTEN) error("Simulated process death") }
        assertTrue(runCatching { failing.edit(edit(GroupAction.MOVE, from = origin)) }.isFailure)
        val before = db.customCategoryDao().getAllOnce()
        val marks = custom().movedFromOrigin
        newService().recover()
        assertEquals(before, db.customCategoryDao().getAllOnce())
        assertEquals(marks, custom().movedFromOrigin)
        assertEquals(1L, service.revision())
    }

    @Test fun middleChunkRestartPreservesOrderAndCompletesOnce() = runBlocking {
        channels(4..1001)
        val failing = newService { stage, index -> if (stage == GroupMutationStage.CHUNK_COMMITTED && index == 0) error("Simulated process death") }
        val ids = (1L..1001L).toList().reversed()
        assertTrue(runCatching { failing.edit(edit(GroupAction.COPY, ids)) }.isFailure)
        assertEquals(500, db.customCategoryDao().getAllOnce().size)
        newService().recover()
        assertEquals(ids, db.customCategoryDao().snapshotChannels(profile, a, listOf(source), 2000).map { it.id })
        assertEquals(1L, service.revision())
        assertTrue(directory.listFiles().orEmpty().none { it.isDirectory })
    }

    @Test fun recoveryUsesRemoteIdentityAfterCatalogRowIdsChange() = runBlocking {
        val failing = newService { stage, _ -> if (stage == GroupMutationStage.MEMBERSHIP_WRITTEN) error("Simulated process death") }
        assertTrue(runCatching { failing.edit(edit(GroupAction.MOVE, from = origin)) }.isFailure)
        db.channelDao().clearSource(source)
        channels(100..100, remote = "channel-1")
        newService().recover()
        assertTrue(db.customCategoryDao().exists(profile, MediaType.LIVE, a, 100))
        assertEquals(origin, custom().movedFromOrigin[CustomizeKeys.item(source, "channel-1", "Channel 1")])
        assertEquals(category, db.channelDao().getById(100)!!.categoryId)
    }

    @Test fun missingIdentityWaitsForLaterCatalogReappearance() = runBlocking {
        val failing = newService { stage, _ -> if (stage == GroupMutationStage.MEMBERSHIP_WRITTEN) error("Simulated process death") }
        assertTrue(runCatching { failing.edit(edit(GroupAction.COPY)) }.isFailure)
        db.channelDao().clearSource(source)
        newService().recover()
        channels(100..100, remote = "channel-1")
        resolver.resolvePending()
        assertTrue(db.customCategoryDao().exists(profile, MediaType.LIVE, a, 100))
    }

    @Test fun sourceImportsRemainParallelAndGroupEditWaitsForItsSource() = runBlocking {
        withTimeout(30_000) {
            coroutineScope {
                val enteredA = CompletableDeferred<Unit>(); val enteredB = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
                val jobA = async { service.withCatalogSource(source) { enteredA.complete(Unit); release.await() } }
                enteredA.await()
                val jobB = async { service.withCatalogSource(otherSource) { enteredB.complete(Unit); release.await() } }
                enteredB.await()
                val editJob = async { service.edit(edit(GroupAction.COPY).copy(scope = GroupScope(profile, MediaType.LIVE, setOf(source)))) }
                assertFalse(editJob.isCompleted)
                release.complete(Unit)
                jobA.await(); jobB.await(); editJob.await()
                assertEquals(3L, service.revision())
            }
        }
    }

    @Test fun stableCatalogGateRecoversBeforeExportingAndPreservesNullableReturn() = runBlocking {
        val failing = newService { stage, _ -> if (stage == GroupMutationStage.MEMBERSHIP_WRITTEN) error("Simulated process death") }
        assertTrue(runCatching { failing.edit(edit(GroupAction.MOVE, from = origin)) }.isFailure)
        var calls = 0
        val result: String? = newService().withStableCatalog { calls++; assertFalse(custom().movedFromOrigin.isEmpty()); null }
        assertNull(result)
        assertEquals(1, calls)
    }

    @Test fun journalNeverContainsStreamsOrCredentialsAndRejectsMissingChunk() = runBlocking {
        val failing = newService { stage, _ -> if (stage == GroupMutationStage.MEMBERSHIP_WRITTEN) error("Simulated process death") }
        assertTrue(runCatching { failing.edit(edit(GroupAction.MOVE, from = origin)) }.isFailure)
        directory.walkTopDown().filter { it.isFile }.forEach { file ->
            assertFalse(file.readText().contains("stream.test")); assertFalse(file.readText().contains("password"))
        }
        directory.walkTopDown().single { it.name == "0.json" }.delete()
        assertTrue(runCatching { newService().recover() }.isFailure)
        assertTrue(GroupOperationJournal(directory).readState().has("pending"))
        assertTrue(custom().movedFromOrigin.isEmpty())
    }

    @Test fun fiftyThousandActualCatalogRowsCopyInSelectionOrder() = runBlocking {
        channels(4..50_000)
        val ids = (1L..50_000L).toList().reversed()
        assertEquals(50_000, service.edit(edit(GroupAction.COPY, ids)).added)
        val members = db.customCategoryDao().getAllOnce().sortedBy { it.position }
        assertEquals(ids, members.map { it.itemId })
        assertEquals((0 until 50_000).toList(), members.map { it.position })
        assertEquals(1L, service.revision())
        assertEquals(0, service.progress.value.total)
        assertTrue(directory.listFiles().orEmpty().none { it.isDirectory })
    }
    @Test fun allViewMoveUsesActualProviderGroupsAndRetainsUncategorizedItems() = runBlocking {
        val otherCategory = db.categoryDao().insertAll(listOf(CategoryEntity(sourceId = otherSource, mediaType = MediaType.LIVE, name = "Sports", remoteId = "sports"))).single()
        db.channelDao().insertAll(listOf(
            ChannelEntity(id = 4, sourceId = otherSource, categoryId = otherCategory, name = "Sports", remoteId = "sports-1", streamUrl = "https://stream.test/4"),
            ChannelEntity(id = 5, sourceId = source, name = "Ungrouped", remoteId = "ungrouped", streamUrl = "https://stream.test/5"),
        ))
        db.favoriteDao().add(FavoriteEntity(profileId = profile, mediaType = MediaType.LIVE, itemId = 1))
        service.edit(edit(GroupAction.MOVE, listOf(1, 4, 5)))
        assertEquals(mapOf(CustomizeKeys.item(source, "channel-1", "Channel 1") to origin,
            CustomizeKeys.item(otherSource, "sports-1", "Sports") to "$otherSource:sports"), custom().movedFromOrigin)
        assertTrue(custom().hiddenItems.isEmpty())
        assertTrue(db.favoriteDao().exists(profile, MediaType.LIVE, 1))
        assertEquals(3, db.customCategoryDao().getAllOnce().size)
    }

    @Test fun movieSeriesAndLiveIdsNeverCrossMediaBoundaries() = runBlocking {
        db.movieDao().insertAll(listOf(MovieEntity(id = 1, sourceId = source, name = "Movie", remoteId = "movie", streamUrl = "https://stream.test/movie")))
        db.seriesDao().insertSeries(listOf(SeriesEntity(id = 1, sourceId = source, name = "Series", remoteId = "series")))
        for (type in listOf(MediaType.MOVIE, MediaType.SERIES)) {
            val target = store.createCustomCategory(profile, type, type.name).id
            service.edit(edit(GroupAction.COPY, target = target).copy(scope = GroupScope(profile, type)))
            assertTrue(db.customCategoryDao().exists(profile, type, target, 1))
            assertFalse(db.customCategoryDao().exists(profile, MediaType.LIVE, target, 1))
        }
        assertTrue(custom().movedFromOrigin.isEmpty())
        assertEquals(category, db.channelDao().getById(1)!!.categoryId)
    }

    @Test fun kidsProfileRejectsAdultCategoryEvenThroughAllView() = runBlocking {
        val adult = db.categoryDao().insertAll(listOf(CategoryEntity(sourceId = source, mediaType = MediaType.LIVE, name = "XXX Adult", remoteId = "adult"))).single()
        db.channelDao().insertAll(listOf(ChannelEntity(id = 4, sourceId = source, categoryId = adult, name = "Adult", remoteId = "adult", streamUrl = "https://stream.test/adult")))
        db.profileDao().update(db.profileDao().getById(profile)!!.copy(isKids = true))
        rejected(GroupError.INVALID_ITEM, edit(GroupAction.MOVE, listOf(1, 4)))
        assertTrue(db.customCategoryDao().getAllOnce().isEmpty())
        assertTrue(custom().movedFromOrigin.isEmpty())
    }

    @Test fun cancellingTvScreenAfterPublicationStillCompletesBothStores() = runBlocking {
        withTimeout(30_000) {
            coroutineScope {
                val committed = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
                val operation = newService { stage, _ -> if (stage == GroupMutationStage.MEMBERSHIP_WRITTEN) { committed.complete(Unit); release.await() } }
                val job = async { operation.editFromTv(edit(GroupAction.MOVE, from = origin)) }
                committed.await()
                job.cancel()
                release.complete(Unit)
                job.join()
                assertTrue(db.customCategoryDao().exists(profile, MediaType.LIVE, a, 1))
                assertFalse(custom().movedFromOrigin.isEmpty())
                assertFalse(GroupOperationJournal(directory).readState().has("pending"))
                assertEquals(1L, service.revision())
            }
        }
    }

    @Test fun invalidTvSelectionReportsFailureAndClearsBusyIndicator() = runBlocking {
        assertTrue(service.editFromTv(edit(GroupAction.COPY, listOf(1, 999))).isFailure)
        assertEquals(GroupError.INVALID_ITEM, service.progress.value.failure)
        assertNull(service.progress.value.operationId)
        service.acknowledgeFailure()
        assertNull(service.progress.value.failure)
        assertEquals(0L, service.revision())
    }

    @Test fun fiftyThousandProviderMovesRetainCatalogAndDoNotGloballyHide() = runBlocking {
        channels(4..50_000)
        val started = android.os.SystemClock.elapsedRealtime()
        service.edit(edit(GroupAction.MOVE, (1L..50_000L).toList(), from = origin))
        android.util.Log.i("GroupServiceTest", "50000 provider moves ms=${android.os.SystemClock.elapsedRealtime() - started}")
        assertEquals(50_000, custom().movedFromOrigin.size)
        assertTrue(custom().hiddenItems.isEmpty())
        assertEquals(50_000, db.customCategoryDao().getAllOnce().size)
        assertEquals(category, db.channelDao().getById(50_000)!!.categoryId)
        assertEquals(1L, service.revision())
    }

    @Test fun recoveredMembershipNeverAttachesToAReusedUnrelatedRowId() = runBlocking {
        val failing = newService { stage, _ -> if (stage == GroupMutationStage.MEMBERSHIP_WRITTEN) error("Simulated process death") }
        assertTrue(runCatching { failing.edit(edit(GroupAction.MOVE, from = origin)) }.isFailure)
        db.channelDao().clearSource(source)
        channels(1..1, remote = "unrelated")
        channels(100..100, remote = "channel-1")
        newService().recover()
        assertFalse(db.customCategoryDao().exists(profile, MediaType.LIVE, a, 1))
        assertTrue(db.customCategoryDao().exists(profile, MediaType.LIVE, a, 100))
    }

    @Test fun recoveryRelinksExistingTargetMembersInAPartialCopy() = runBlocking {
        service.edit(edit(GroupAction.COPY))
        val oldMember = db.customCategoryDao().getAllOnce().single()
        val failing = newService { stage, _ -> if (stage == GroupMutationStage.MEMBERSHIP_WRITTEN) error("Simulated process death") }
        assertTrue(runCatching { failing.edit(edit(GroupAction.COPY, listOf(1, 2))) }.isFailure)
        db.channelDao().clearSource(source)
        channels(1..1, remote = "unrelated")
        channels(100..100, remote = "channel-1")
        channels(101..101, remote = "channel-2")
        newService().recover()
        assertEquals(listOf(100L, 101L), db.customCategoryDao().getAllOnce().sortedBy { it.position }.map { it.itemId })
        assertEquals(oldMember.addedAt, db.customCategoryDao().addedAt(profile, MediaType.LIVE, a, 100))
    }

    @Test fun completedChunksRecoverCorrectlyWhenRowIdSwapsCrossChunkBoundaries() = runBlocking {
        channels(4..501)
        val failing = newService { stage, index -> if (stage == GroupMutationStage.CHUNK_COMMITTED && index == 0) error("Simulated process death") }
        assertTrue(runCatching { failing.edit(edit(GroupAction.COPY, (1L..501L).toList())) }.isFailure)
        db.channelDao().clearSource(source)
        // Every ID is reused for another identity; the first/last swap crosses the chunk boundary.
        db.channelDao().insertAll((1..501).map { id ->
            val original = if (id == 1) 501 else if (id == 501) 1 else id
            ChannelEntity(id = id.toLong(), sourceId = source, categoryId = category, name = "Channel $original",
                remoteId = "channel-$original", streamUrl = "https://stream.test/$original")
        })
        newService().recover()
        val expected = listOf(501L) + (2L..500L).toList() + 1L
        assertEquals(expected, db.customCategoryDao().getAllOnce().sortedBy { it.position }.map { it.itemId })
        assertEquals(1L, service.revision())
    }

    @Test fun legacyFavoritesMoveRecoversItsRemainingBatchThroughStableIdentity() = runBlocking {
        channels(4..501)
        for (id in 1L..501L) db.favoriteDao().add(FavoriteEntity(profileId = profile, mediaType = MediaType.LIVE, itemId = id))
        val failing = newService { stage, index -> if (stage == GroupMutationStage.CHUNK_COMMITTED && index == 0) error("Simulated process death") }
        val command = edit(GroupAction.MOVE, (1L..501L).toList(), from = ContentOrderEntity.FAV_CONTEXT).copy(removeFromFavorites = true)
        assertTrue(runCatching { failing.edit(command) }.isFailure)
        assertTrue(db.favoriteDao().exists(profile, MediaType.LIVE, 501))
        db.channelDao().clearSource(source)
        channels(501..501, remote = "unrelated")
        db.channelDao().insertAll((1..501).map { original -> ChannelEntity(id = 1000L + original, sourceId = source,
            categoryId = category, name = "Channel $original", remoteId = "channel-$original", streamUrl = "https://stream.test/$original") })
        newService().recover()
        assertTrue(db.favoriteDao().getAllOnce().isEmpty())
        assertEquals(501, resolver.exportTombstones(setOf("fav")).length())
        assertEquals((1001L..1501L).toList(), db.customCategoryDao().getAllOnce().sortedBy { it.position }.map { it.itemId })
        assertTrue(custom().movedFromOrigin.isEmpty())
    }

}

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
import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.core.database.transaction
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

    private fun definition(action: GroupDefinitionAction, keys: List<String> = listOf(a), name: String? = null) =
        GroupDefinitionEdit(GroupScope(profile, MediaType.LIVE), action, keys, name)

    @Test fun definitionsCreateRenameHideAndResetRemainProfileAndTypeScoped() = runBlocking {
        val created = service.editGroups(definition(GroupDefinitionAction.CREATE, emptyList(), "  Weekend  ")).groupIds.single()
        assertEquals("Weekend", custom().customCategories.single { it.id == created }.name)
        service.editGroups(definition(GroupDefinitionAction.RENAME, listOf(created), "Holiday"))
        service.editGroups(definition(GroupDefinitionAction.HIDE, listOf(created, origin)))
        service.edit(edit(GroupAction.MOVE, from = origin))
        service.editGroups(definition(GroupDefinitionAction.RESET, listOf(origin)))
        assertTrue(origin !in custom().hiddenCategories)
        assertTrue(created in custom().hiddenCategories)
        assertEquals("Holiday", custom().categoryNames[created])
        assertTrue(custom().movedFromOrigin.isEmpty())
        assertEquals("News", db.categoryDao().getById(category)!!.name)
        service.editGroups(definition(GroupDefinitionAction.RENAME, listOf(created)))
        assertTrue(created !in custom().categoryNames)
        assertTrue(store.observe(otherProfile, MediaType.LIVE).first().isEmpty)
        assertTrue(store.observe(profile, MediaType.MOVIE).first().isEmpty)
    }

    @Test fun bulkRenameValidatesTheEntireSelectionBeforeWriting() = runBlocking {
        service.editGroups(definition(GroupDefinitionAction.RENAME, listOf(a, b)).copy(names = mapOf(a to "One", b to "Two")))
        val before = custom()
        val revision = service.revision()
        val invalid = listOf(
            definition(GroupDefinitionAction.CREATE, emptyList(), "  ") to GroupError.INVALID_NAME,
            definition(GroupDefinitionAction.RENAME, listOf(a, b)).copy(names = mapOf(a to "OK", b to "x".repeat(121))) to GroupError.INVALID_NAME,
            definition(GroupDefinitionAction.HIDE, listOf(a, "custom:missing")) to GroupError.INVALID_GROUP,
            definition(GroupDefinitionAction.DELETE, listOf(origin)) to GroupError.INVALID_GROUP,
            definition(GroupDefinitionAction.HIDE).copy(scope = GroupScope(otherProfile, MediaType.LIVE)) to GroupError.INVALID_GROUP,
            definition(GroupDefinitionAction.HIDE).copy(scope = GroupScope(profile, MediaType.EPISODE)) to GroupError.INVALID_TYPE,
            definition(GroupDefinitionAction.HIDE).copy(scope = GroupScope(profile, MediaType.LIVE, setOf(999))) to GroupError.INVALID_SOURCE,
            definition(GroupDefinitionAction.HIDE).copy(expectedRevision = revision - 1) to GroupError.REVISION_CONFLICT,
        )
        for ((command, expected) in invalid) {
            try { service.editGroups(command); fail("Expected $expected") }
            catch (failure: GroupEditException) { assertEquals(expected, failure.code) }
        }
        assertEquals(before, custom()); assertEquals(revision, service.revision())
        service.editGroups(definition(GroupDefinitionAction.RENAME, listOf(a, b)).copy(names = mapOf(a to null, b to null)))
        assertTrue(custom().categoryNames.isEmpty())
    }

    @Test fun filteredReorderKeepsOtherPlaylistsAndUnselectedGroupSlots() = runBlocking {
        val other = db.categoryDao().insertAll(listOf(CategoryEntity(sourceId = otherSource, mediaType = MediaType.LIVE, name = "Other", remoteId = "other"))).single()
        val otherKey = "$otherSource:other"
        store.setCategoryOrder(profile, MediaType.LIVE, listOf(a, otherKey, origin, b, c))
        service.editGroups(definition(GroupDefinitionAction.REORDER, listOf(b, a)).copy(scope = GroupScope(profile, MediaType.LIVE, setOf(source))))
        assertEquals(listOf(b, otherKey, origin, a, c), custom().categoryOrder)
        assertEquals("Other", db.categoryDao().getById(other)!!.name)
        try {
            service.editGroups(definition(GroupDefinitionAction.HIDE, listOf(otherKey)).copy(scope = GroupScope(profile, MediaType.LIVE, setOf(source))))
            fail("Out-of-filter group accepted")
        } catch (failure: GroupEditException) { assertEquals(GroupError.INVALID_GROUP, failure.code) }
    }

    @Test fun deleteRestoresProviderOriginsAndKeepsGlobalHidesFavoritesAndOtherMemberships() = runBlocking {
        service.edit(edit(GroupAction.MOVE, listOf(1, 2), from = origin))
        service.edit(edit(GroupAction.COPY, target = b))
        service.edit(edit(GroupAction.HIDE))
        db.favoriteDao().add(FavoriteEntity(profileId = profile, mediaType = MediaType.LIVE, itemId = 1))
        store.setCategoryHidden(profile, MediaType.LIVE, a, true)
        store.renameCategory(profile, MediaType.LIVE, a, "Removed")
        store.setCategoryOrder(profile, MediaType.LIVE, listOf(a, b))
        db.contentOrderDao().insertAll(listOf(ContentOrderEntity(profileId = profile, mediaType = MediaType.LIVE, contextKey = a, itemId = 1, position = 9)))
        val result = service.editGroups(definition(GroupDefinitionAction.DELETE))
        assertTrue(custom().customCategories.none { it.id == a })
        assertTrue(a !in custom().categoryNames && a !in custom().hiddenCategories && a !in custom().categoryOrder)
        assertTrue(custom().movedFromOrigin.isEmpty())
        assertFalse(custom().hiddenItems.isEmpty())
        assertEquals(setOf(b), db.customCategoryDao().contextsOf(profile, MediaType.LIVE, 1).toSet())
        assertFalse(db.customCategoryDao().exists(profile, MediaType.LIVE, a, 2))
        assertTrue(db.favoriteDao().exists(profile, MediaType.LIVE, 1))
        assertEquals(category, db.channelDao().getById(1)!!.categoryId)
        assertEquals(1, resolver.exportTombstones(setOf("group")).length())
        assertEquals(2, resolver.exportTombstones(setOf("member")).length())
        assertFalse(db.tombstoneDao().hasPendingGroupDeletions())
        assertEquals(result.revision, service.revision())
        assertNull(context.pendingStore.data.first()[PENDING_KEY])
    }

    @Test fun definitionDeletionRecoversAtEveryCrossStoreBoundary() = runBlocking {
        for (stage in listOf(GroupMutationStage.GROUP_TOMBSTONE_RECORDED, GroupMutationStage.GROUP_DEFINITION_WRITTEN,
            GroupMutationStage.GROUP_CLEANUP_COMPLETE, GroupMutationStage.CHUNK_COMMITTED)) {
            val group = service.editGroups(definition(GroupDefinitionAction.CREATE, emptyList(), "Recover")).groupIds.single()
            service.edit(edit(GroupAction.MOVE, target = group, from = origin))
            val before = service.revision()
            val interrupted = newService { step, _ -> if (step == stage) error("Simulated process death") }
            assertTrue(interrupted.editGroupsFromTv(definition(GroupDefinitionAction.DELETE, listOf(group))).isFailure)
            service = newService(); service.recover(); service.recover()
            assertEquals(before + 1, service.revision())
            assertTrue(custom().customCategories.none { it.id == group })
            assertFalse(db.customCategoryDao().exists(profile, MediaType.LIVE, group, 1))
            assertTrue(custom().movedFromOrigin.isEmpty())
            assertFalse(db.tombstoneDao().hasPendingGroupDeletions())
            assertTrue(directory.listFiles()!!.all { it.name == "state.json" })
        }
    }

    @Test fun createdDefinitionReplaysWithTheSameIdAndExactlyOneRevision() = runBlocking {
        val before = service.revision()
        val interrupted = newService { stage, _ -> if (stage == GroupMutationStage.GROUP_DEFINITION_WRITTEN) error("Simulated restart") }
        assertTrue(interrupted.editGroupsFromTv(definition(GroupDefinitionAction.CREATE, emptyList(), "Once")).isFailure)
        val saved = custom().customCategories.single { it.name == "Once" }.id
        service = newService(); service.recover(); service.recover()
        assertEquals(listOf(saved), custom().customCategories.filter { it.name == "Once" }.map { it.id })
        assertEquals(before + 1, service.revision())
    }

    @Test fun staleBackupCannotResurrectDeletedDefinitionsMembersOrProviderSuppression() = runBlocking {
        service.edit(edit(GroupAction.MOVE, from = origin))
        val staleCustomization = store.exportAll()
        val staleMembers = resolver.exportAll(setOf("member"))
        val record = staleMembers.getJSONObject(0)
        record.put("at", Long.MAX_VALUE - 1) // A deleted UUID is retired, even with a newer membership clock.
        service.editGroups(definition(GroupDefinitionAction.DELETE))
        store.mergeAll(resolver.filterDeletedGroupCustomizations(staleCustomization), mergeCustomGroups = true)
        assertFalse(resolver.wouldAdd(profile, "member", record))
        assertEquals(1, resolver.importAll(staleMembers))
        assertTrue(custom().customCategories.none { it.id == a })
        assertTrue(custom().movedFromOrigin.isEmpty())
        assertTrue(db.customCategoryDao().getAllOnce().isEmpty())
        assertNull(context.pendingStore.data.first()[PENDING_KEY])
        // An explicit restore replaces local deletion history, unlike Merge.
        resolver.clearDeletionsFor(listOf(profile))
        store.mergeAll(staleCustomization)
        assertEquals(0, resolver.importAll(staleMembers))
        assertTrue(custom().customCategories.any { it.id == a })
        assertTrue(db.customCategoryDao().exists(profile, MediaType.LIVE, a, 1))
    }

    @Test fun incomingGroupDeletionRestoresOriginsEvenWhenMemberFactsArriveFirst() = runBlocking {
        service.edit(edit(GroupAction.MOVE, from = origin))
        val at = System.currentTimeMillis() + 1000
        val member = resolver.exportAll(setOf("member")).getJSONObject(0).put("at", at)
        val group = JSONObject().put("p", profile).put("t", "LIVE").put("src", -1).put("ctx", a).put("kind", "group").put("at", at)
        resolver.applyTombstones(JSONArray().put(member).put(group))
        assertTrue(custom().customCategories.none { it.id == a })
        assertTrue(custom().movedFromOrigin.isEmpty())
        assertTrue(db.customCategoryDao().getAllOnce().isEmpty())
        assertFalse(db.tombstoneDao().hasPendingGroupDeletions())
    }

    @Test fun importedDeletionIntentRecoversWithoutAServiceJournal() = runBlocking {
        service.edit(edit(GroupAction.MOVE, from = origin))
        resolver.recordGroupDeletion(profile, MediaType.LIVE, a, System.currentTimeMillis() + 1000)
        val before = service.revision()
        service = newService(); service.recover()
        assertEquals(before + 1, service.revision())
        assertTrue(custom().customCategories.none { it.id == a })
        assertTrue(custom().movedFromOrigin.isEmpty())
        service.recover(); assertEquals(before + 1, service.revision())
    }

    @Test fun groupMergeKeepsNewLocalGroupsMissingFromAnOfflinePeer() = runBlocking {
        val stale = store.exportAll()
        val group = service.editGroups(definition(GroupDefinitionAction.CREATE, emptyList(), "Local only")).groupIds.single()
        service.editGroups(definition(GroupDefinitionAction.RENAME, listOf(group), "Local name"))
        service.editGroups(definition(GroupDefinitionAction.HIDE, listOf(group)))
        store.mergeAll(stale, mergeCustomGroups = true)
        assertTrue(custom().customCategories.any { it.id == group })
        assertEquals("Local name", custom().categoryNames[group])
        assertTrue(group in custom().hiddenCategories)
    }

    @Test fun deletingLargeGroupRetainsEveryMembershipFactBeyondTheWatchHistoryCap() = runBlocking {
        channels(4..10001)
        service.edit(edit(GroupAction.MOVE, (1L..10001L).toList(), from = origin))
        service.editGroups(definition(GroupDefinitionAction.DELETE))
        db.tombstoneDao().prune(10_000)
        assertEquals(10001, resolver.exportTombstones(setOf("member")).length())
        assertEquals(1, resolver.exportTombstones(setOf("group")).length())
        assertTrue(custom().movedFromOrigin.isEmpty())
        assertTrue(db.customCategoryDao().getAllOnce().isEmpty())
        assertEquals(10001, db.channelDao().countForSourceOnce(source))
    }

    private fun backups(): BackupManager = BackupManager(
        db.profileDao(), db.sourceDao(), tv.own.owntv.core.settings.SettingsRepository(context, tv.own.owntv.core.i18n.LocaleStore.from(context)),
        store, resolver, tv.own.owntv.core.epg.EpgSourceStore(context),
        tv.own.owntv.core.player.ForceMpvStore(context, db.playbackQuirkDao()),
        tv.own.owntv.core.player.VodEngineStore(context, db.playbackQuirkDao()), db,
        tv.own.owntv.core.metadata.MetadataOverrideStore(context), db.metadataDao(),
        tv.own.owntv.core.subtitles.OpenSubtitlesAuthStore(context), File(directory, "backgrounds"), File(directory, "subtitles"),
        tv.own.owntv.core.profile.ProfileAvatarStore(context), service,
    )

    @Test fun customizeOnlyBackupCarriesMembersAndPreservesDeletionAcrossMergeButAllowsExplicitRestore() = runBlocking {
        service.edit(edit(GroupAction.MOVE, from = origin))
        val manager = backups()
        val folder = File(context.cacheDir, "backup-groups-${UUID.randomUUID()}")
        try {
            val file = File(manager.export(folder, setOf(BackupManager.Section.CUSTOMIZE), profileIds = setOf(profile), recordAsBackup = false).getOrThrow())
            val data = JSONObject(BackupContainer.open(file, null).json)
            assertEquals(1, data.getJSONArray("userData").length())
            assertEquals("member", data.getJSONArray("userData").getJSONObject(0).getString("kind"))
            service.editGroups(definition(GroupDefinitionAction.DELETE))
            manager.import(file, setOf(BackupManager.Section.CUSTOMIZE), mode = BackupManager.ImportMode.MERGE).getOrThrow()
            assertTrue(custom().customCategories.none { it.id == a })
            assertTrue(custom().movedFromOrigin.isEmpty())
            assertTrue(db.customCategoryDao().getAllOnce().isEmpty())
            manager.import(file, setOf(BackupManager.Section.CUSTOMIZE), mode = BackupManager.ImportMode.RESTORE).getOrThrow()
            assertTrue(custom().customCategories.any { it.id == a })
            assertTrue(db.customCategoryDao().exists(profile, MediaType.LIVE, a, 1))
            assertTrue(db.tombstoneDao().groupDeletions().isEmpty())
        } finally { folder.deleteRecursively() }
    }

    @Test fun deletionOnlyBackupIsDiscoverableAndCleansDefinitionAfterProfileIdRemapping() = runBlocking {
        service.edit(edit(GroupAction.MOVE, from = origin))
        val at = System.currentTimeMillis() + 1000
        val file = File(context.cacheDir, "group-deleted-${UUID.randomUUID()}.json")
        try {
            val incomingProfile = 900L
            val group = JSONObject().put("p", incomingProfile).put("t", "LIVE").put("src", -1).put("ctx", a).put("kind", "group").put("at", at)
            val root = JSONObject().put("version", 24).put("profiles", JSONArray().put(JSONObject().put("id", incomingProfile).put("name", "Primary").put("avatarColor", 0).put("createdAt", 1)))
                .put("tombstones", JSONArray().put(group))
            file.writeText(root.toString())
            val manager = backups()
            assertTrue(BackupManager.Section.CUSTOMIZE in manager.sectionsIn(file).getOrThrow().sections)
            manager.import(file, setOf(BackupManager.Section.CUSTOMIZE), mode = BackupManager.ImportMode.MERGE).getOrThrow()
            assertTrue(custom().customCategories.none { it.id == a })
            assertTrue(custom().movedFromOrigin.isEmpty())
            assertTrue(db.customCategoryDao().getAllOnce().isEmpty())
            val marker = db.tombstoneDao().groupDeletions().single()
            assertEquals(profile, marker.profileId)
            assertEquals(-1L, JSONObject(marker.identity).getLong("src"))
        } finally { file.delete() }
    }

    @Test fun unrepresentableMemberTimeFailsBeforePublishingDeletion() = runBlocking {
        db.customCategoryDao().insertAll(listOf(CustomCategoryMemberEntity(profileId = profile, mediaType = MediaType.LIVE,
            contextKey = a, itemId = 1, position = 0, addedAt = Long.MAX_VALUE)))
        val before = service.revision()
        try { service.editGroups(definition(GroupDefinitionAction.DELETE)); fail("Overflow accepted") }
        catch (failure: GroupEditException) { assertEquals(GroupError.POSITION_OVERFLOW, failure.code) }
        assertEquals(before, service.revision())
        assertTrue(custom().customCategories.any { it.id == a })
        assertTrue(db.tombstoneDao().groupDeletions().isEmpty())
        service.recover(); assertEquals(before, service.revision())
    }

    @Test fun deletedGroupDropsMissingCatalogMembersAndOrdersInsteadOfKeepingThemPending() = runBlocking {
        service.editGroups(definition(GroupDefinitionAction.DELETE))
        val missing = JSONObject().put("p", profile).put("t", "LIVE").put("src", source).put("rid", "not-loaded")
            .put("name", "Missing").put("ctx", a).put("pos", 0).put("at", Long.MAX_VALUE - 1)
        val records = JSONArray().put(JSONObject(missing.toString()).put("kind", "member"))
            .put(JSONObject(missing.toString()).put("kind", "order"))
        assertEquals(2, resolver.importAll(records))
        assertNull(context.pendingStore.data.first()[PENDING_KEY])
        assertFalse(resolver.wouldAdd(profile, "member", records.getJSONObject(0)))
        assertFalse(resolver.wouldAdd(profile, "order", records.getJSONObject(1)))
    }

    private fun composition(action: GroupAction, target: GroupDestination, vararg selections: GroupSelection) =
        GroupCompositionEdit(GroupScope(profile, MediaType.LIVE), action, listOf(GroupCompositionPart(target, selections.toList())))

    @Test fun duplicatePreservesStoredOrderAndProviderVisibilityWithoutMutatingFavorites() = runBlocking {
        service.edit(edit(GroupAction.COPY, listOf(3, 1, 2)))
        db.contentOrderDao().insertAll(listOf(ContentOrderEntity(profileId = profile, mediaType = MediaType.LIVE, contextKey = a, itemId = 2, position = 0)))
        db.favoriteDao().add(FavoriteEntity(profileId = profile, mediaType = MediaType.LIVE, itemId = 1))
        val before = service.revision()
        val result = service.compose(composition(GroupAction.COPY, GroupDestination(name = "Duplicate"), GroupSelection(a)))
        val target = result.destinationIds.single()
        assertEquals(3, result.added); assertEquals(3, result.selected); assertEquals(before + 1, result.revision)
        assertEquals(listOf(2L, 3L, 1L), db.customCategoryDao().snapshotChannels(profile, target, listOf(source), 10).map { it.id })
        assertEquals(setOf(a, target), db.customCategoryDao().contextsOf(profile, MediaType.LIVE, 1).toSet())
        assertTrue(custom().movedFromOrigin.isEmpty()); assertTrue(db.favoriteDao().exists(profile, MediaType.LIVE, 1))
        assertEquals(category, db.channelDao().getById(1)!!.categoryId)
    }

    @Test fun mergeCopyDeduplicatesOverlapAndPreservesExistingDestinationPositions() = runBlocking {
        service.edit(edit(GroupAction.COPY, listOf(1, 2)))
        service.edit(edit(GroupAction.COPY, listOf(2, 3), target = b))
        service.edit(edit(GroupAction.COPY, listOf(2), target = c))
        val previous = db.customCategoryDao().getAllOnce().single { it.contextKey == c }
        val result = service.compose(composition(GroupAction.COPY, GroupDestination(groupId = c), GroupSelection(a), GroupSelection(b)))
        assertEquals(2, result.added); assertEquals(3, result.selected)
        assertEquals(previous, db.customCategoryDao().getAllOnce().single { it.contextKey == c && it.itemId == 2L })
        assertEquals(listOf(2L, 1L, 3L), db.customCategoryDao().snapshotChannels(profile, c, listOf(source), 10).map { it.id })
        assertTrue(db.customCategoryDao().exists(profile, MediaType.LIVE, a, 2)); assertTrue(db.customCategoryDao().exists(profile, MediaType.LIVE, b, 2))
        val revision = service.revision()
        assertEquals(0, service.compose(composition(GroupAction.COPY, GroupDestination(groupId = c), GroupSelection(a), GroupSelection(b))).added)
        assertEquals(revision, service.revision())
    }

    @Test fun mergeMoveRemovesEverySelectedCustomOriginAndKeepsUnselectedMembershipAndFavorite() = runBlocking {
        service.edit(edit(GroupAction.COPY, listOf(1, 2)))
        service.edit(edit(GroupAction.COPY, listOf(2, 3), target = b))
        service.edit(edit(GroupAction.COPY, listOf(2), target = c))
        db.favoriteDao().add(FavoriteEntity(profileId = profile, mediaType = MediaType.LIVE, itemId = 2))
        val result = service.compose(composition(GroupAction.MOVE, GroupDestination(name = "Merged"), GroupSelection(a), GroupSelection(b)))
        val target = result.destinationIds.single()
        assertEquals(setOf(c, target), db.customCategoryDao().contextsOf(profile, MediaType.LIVE, 2).toSet())
        assertFalse(db.customCategoryDao().exists(profile, MediaType.LIVE, a, 1)); assertFalse(db.customCategoryDao().exists(profile, MediaType.LIVE, b, 3))
        assertEquals(4, resolver.exportTombstones(setOf("member")).length())
        assertTrue(db.favoriteDao().exists(profile, MediaType.LIVE, 2)); assertTrue(custom().movedFromOrigin.isEmpty())
        assertTrue(custom().customCategories.any { it.id == a }); assertTrue(custom().customCategories.any { it.id == b })
    }

    @Test fun splitCreatesSeveralGroupsInOneRevisionAndRejectsAnInvalidLatePartition() = runBlocking {
        val before = custom(); val revision = service.revision()
        val valid = GroupCompositionPart(GroupDestination(name = "First"), listOf(GroupSelection(origin, listOf(1, 2))))
        val invalid = GroupCompositionPart(GroupDestination(name = "Second"), listOf(GroupSelection(origin, listOf(999))))
        val scope = GroupScope(profile, MediaType.LIVE)
        assertTrue(service.composeFromTv(GroupCompositionEdit(scope, GroupAction.MOVE, listOf(valid, invalid))).isFailure)
        assertEquals(before, custom()); assertEquals(revision, service.revision()); assertTrue(db.customCategoryDao().getAllOnce().isEmpty())
        val second = invalid.copy(selections = listOf(GroupSelection(origin, listOf(3))))
        val result = service.compose(GroupCompositionEdit(scope, GroupAction.MOVE, listOf(valid, second)))
        assertEquals(revision + 1, result.revision); assertEquals(2, result.destinationIds.size)
        assertEquals(listOf(1L, 2L), db.customCategoryDao().snapshotChannels(profile, result.destinationIds[0], listOf(source), 10).map { it.id })
        assertEquals(listOf(3L), db.customCategoryDao().snapshotChannels(profile, result.destinationIds[1], listOf(source), 10).map { it.id })
        assertEquals(setOf(origin), custom().movedFromOrigin.values.toSet()); assertTrue(custom().hiddenItems.isEmpty())
    }

    @Test fun conflictingMovePartitionsFailBeforeCreatingAnyGroup() = runBlocking {
        val parts = listOf("One", "Two").map { GroupCompositionPart(GroupDestination(name = it), listOf(GroupSelection(origin, listOf(1)))) }
        try { service.compose(GroupCompositionEdit(GroupScope(profile, MediaType.LIVE), GroupAction.MOVE, parts)); fail("Overlapping Move accepted") }
        catch (failure: GroupEditException) { assertEquals(GroupError.INVALID_ITEM, failure.code) }
        assertEquals(3, custom().customCategories.size); assertTrue(custom().movedFromOrigin.isEmpty())
        assertEquals(2, service.compose(GroupCompositionEdit(GroupScope(profile, MediaType.LIVE), GroupAction.COPY, parts)).added)
        assertEquals(2, db.customCategoryDao().contextsOf(profile, MediaType.LIVE, 1).size)
    }

    @Test fun providerDuplicateSkipsSuppressedMembersAndFilteredCustomDuplicateKeepsOnlySelectedPlaylists() = runBlocking {
        service.edit(edit(GroupAction.MOVE, from = origin))
        val duplicate = service.compose(composition(GroupAction.COPY, GroupDestination(name = "Provider copy"), GroupSelection(origin))).destinationIds.single()
        assertEquals(listOf(2L, 3L), db.customCategoryDao().snapshotChannels(profile, duplicate, listOf(source), 10).map { it.id })
        channels(4..4, otherSource)
        service.edit(edit(GroupAction.COPY, listOf(4), target = a))
        val filtered = service.compose(composition(GroupAction.COPY, GroupDestination(name = "Filtered"), GroupSelection(a))
            .copy(scope = GroupScope(profile, MediaType.LIVE, setOf(otherSource)))).destinationIds.single()
        assertEquals(listOf(4L), db.customCategoryDao().snapshotChannels(profile, filtered, listOf(source, otherSource), 10).map { it.id })
        assertTrue(db.customCategoryDao().exists(profile, MediaType.LIVE, a, 1))
    }

    @Test fun compositionRecoversDefinitionsAndMembershipAtEveryCrossStoreBoundary() = runBlocking {
        for (stage in listOf(GroupMutationStage.GROUP_DEFINITION_WRITTEN, GroupMutationStage.MEMBERSHIP_WRITTEN,
            GroupMutationStage.ORIGIN_WRITTEN, GroupMutationStage.CHUNK_COMMITTED)) {
            val before = service.revision()
            val interrupted = newService { step, _ -> if (step == stage) error("Simulated process death") }
            assertTrue(interrupted.composeFromTv(composition(GroupAction.MOVE, GroupDestination(name = "Recover $stage"), GroupSelection(origin, listOf(1)))).isFailure)
            service = newService(); service.recover(); service.recover()
            assertEquals(before + 1, service.revision())
            val target = custom().customCategories.single { it.name == "Recover $stage" }.id
            assertTrue(db.customCategoryDao().exists(profile, MediaType.LIVE, target, 1))
            assertEquals(origin, custom().movedFromOrigin[CustomizeKeys.item(source, "channel-1", "Channel 1")])
        }
    }

    @Test fun mergeKeepsMissingPendingIdentitiesAndMovePreventsOriginResurrection() = runBlocking {
        val missing = JSONObject().put("p", profile).put("kind", "member").put("t", "LIVE").put("src", source)
            .put("rid", "channel-9").put("name", "Channel 9").put("ctx", a).put("pos", 0).put("at", 10)
        resolver.importAll(JSONArray().put(missing))
        val target = service.compose(composition(GroupAction.MOVE, GroupDestination(name = "Missing copy"), GroupSelection(a))).destinationIds.single()
        assertEquals(1, JSONArray(context.pendingStore.data.first()[PENDING_KEY]!!).length())
        channels(9..9); resolver.resolvePending()
        assertTrue(db.customCategoryDao().exists(profile, MediaType.LIVE, target, 9))
        assertFalse(db.customCategoryDao().exists(profile, MediaType.LIVE, a, 9))
        assertNull(context.pendingStore.data.first()[PENDING_KEY])
    }

    @Test fun compositionReusedIdRecoveryDoesNotAttachAnUnrelatedChannel() = runBlocking {
        val interrupted = newService { step, _ -> if (step == GroupMutationStage.MEMBERSHIP_WRITTEN) error("Simulated restart") }
        assertTrue(interrupted.composeFromTv(composition(GroupAction.COPY, GroupDestination(name = "Recover identity"), GroupSelection(origin, listOf(1)))).isFailure)
        val target = custom().customCategories.single { it.name == "Recover identity" }.id
        db.channelDao().clearSource(source)
        db.channelDao().insertAll(listOf(ChannelEntity(id = 1, sourceId = source, categoryId = category, name = "Unrelated", remoteId = "other", streamUrl = "https://test/other"),
            ChannelEntity(id = 9, sourceId = source, categoryId = category, name = "Channel 1", remoteId = "channel-1", streamUrl = "https://test/one")))
        assertEquals(9L, db.channelDao().findByRemote(source, "channel-1")!!.id)
        assertEquals("other", db.channelDao().getById(1)!!.remoteId)
        service = newService(); service.recover()
        assertTrue(db.customCategoryDao().exists(profile, MediaType.LIVE, target, 9))
        assertFalse(db.customCategoryDao().exists(profile, MediaType.LIVE, target, 1))
    }

    @Test fun wholeGroupCopyKeepsMissingMembersAtTheirStoredPositions() = runBlocking {
        db.customCategoryDao().insertAll(listOf(1L to 0, 3L to 2).map { (item, position) ->
            CustomCategoryMemberEntity(profileId = profile, mediaType = MediaType.LIVE, contextKey = a,
                itemId = item, position = position, addedAt = 10)
        })
        db.channelDao().clearSource(source)
        channels(1..1); channels(3..3)
        resolver.importAll(JSONArray().put(JSONObject().put("p", profile).put("kind", "member").put("t", "LIVE")
            .put("src", source).put("rid", "channel-2").put("name", "Channel 2").put("ctx", a).put("pos", 1).put("at", 10)))
        val target = service.compose(composition(GroupAction.COPY, GroupDestination(name = "Missing middle"), GroupSelection(a))).destinationIds.single()
        channels(2..2); resolver.resolvePending()
        assertEquals(listOf(1L, 2L, 3L), db.customCategoryDao().snapshotChannels(profile, target, listOf(source), 10).map { it.id })
        assertEquals(listOf(1L, 2L, 3L), db.customCategoryDao().snapshotChannels(profile, a, listOf(source), 10).map { it.id })
    }

    @Test fun wholeGroupCopyKeepsMissingManualOrderAheadOfUnorderedMembers() = runBlocking {
        service.edit(edit(GroupAction.COPY, listOf(1, 3)))
        resolver.importAll(JSONArray().put(JSONObject().put("p", profile).put("kind", "member").put("t", "LIVE")
            .put("src", source).put("rid", "channel-9").put("name", "Channel 9").put("ctx", a).put("pos", 2).put("at", 10))
            .put(JSONObject().put("p", profile).put("kind", "order").put("t", "LIVE").put("src", source)
                .put("rid", "channel-9").put("name", "Channel 9").put("ctx", a).put("pos", 0)))
        val target = service.compose(composition(GroupAction.COPY, GroupDestination(name = "Missing manual first"), GroupSelection(a))).destinationIds.single()
        channels(9..9); resolver.resolvePending()
        assertEquals(listOf(9L, 1L, 3L), db.customCategoryDao().snapshotChannels(profile, target, listOf(source), 10).map { it.id })
    }

    @Test fun compositionRecoveryDetachesSwappedIdsAcrossAllChunksBeforeReattachment() = runBlocking {
        channels(4..501)
        val failing = newService { step, index -> if (step == GroupMutationStage.CHUNK_COMMITTED && index == 1) error("Simulated restart") }
        assertTrue(failing.composeFromTv(composition(GroupAction.COPY, GroupDestination(name = "Swapped IDs"), GroupSelection(origin, (1L..501L).toList()))).isFailure)
        val target = custom().customCategories.single { it.name == "Swapped IDs" }.id
        db.channelDao().clearSource(source)
        db.channelDao().insertAll((1..501).map { id ->
            val original = if (id == 1) 501 else if (id == 501) 1 else id
            ChannelEntity(id = id.toLong(), sourceId = source, categoryId = category, name = "Channel $original",
                remoteId = "channel-$original", streamUrl = "https://stream.test/$original")
        })
        newService().recover()
        assertEquals(listOf(501L) + (2L..500L).toList() + 1L,
            db.customCategoryDao().snapshotChannels(profile, target, listOf(source), 510).map { it.id })
        assertEquals(1L, service.revision())
    }

    @Test fun wholeGroupCopyTraversesFiftyThousandRealCatalogRowsInBoundedPages() = runBlocking {
        channels(4..50000)
        val result = service.compose(composition(GroupAction.COPY, GroupDestination(name = "Large duplicate"), GroupSelection(origin)))
        assertEquals(50000, result.added); assertEquals(50000, result.selected)
        val rows = db.customCategoryDao().getAllOnce().filter { it.contextKey == result.destinationIds.single() }
        assertEquals(50000, rows.size); assertEquals((0 until 50000).toList(), rows.map { it.position }.sorted())
        assertTrue(custom().movedFromOrigin.isEmpty()); assertTrue(directory.listFiles()!!.all { it.name == "state.json" })
    }

    @Test fun compositionRejectsStaleRevisionForeignProfileAndUnlinkedSourceBeforePublishing() = runBlocking {
        val request = composition(GroupAction.COPY, GroupDestination(name = "Rejected"), GroupSelection(origin))
        for ((invalid, expected) in listOf(
            request.copy(expectedRevision = 42) to GroupError.REVISION_CONFLICT,
            request.copy(scope = request.scope.copy(profileId = -100)) to GroupError.INVALID_PROFILE,
            request.copy(scope = request.scope.copy(profileId = otherProfile, sourceIds = setOf(otherSource))) to GroupError.INVALID_SOURCE,
            request.copy(scope = request.scope.copy(mediaType = MediaType.EPISODE)) to GroupError.INVALID_TYPE,
            request.copy(parts = listOf(GroupCompositionPart(GroupDestination(groupId = a, name = "Ambiguous"), listOf(GroupSelection(origin))))) to GroupError.INVALID_TARGET,
            request.copy(parts = listOf(GroupCompositionPart(GroupDestination(groupId = "custom:unknown"), listOf(GroupSelection(origin))))) to GroupError.INVALID_TARGET,
        )) {
            val failure = service.composeFromTv(invalid).exceptionOrNull() as GroupEditException
            assertEquals(expected, failure.code)
            assertEquals(expected, service.progress.value.failure)
            assertFalse(GroupOperationJournal(directory).readState().has("pending"))
            assertEquals(0L, service.revision())
            assertEquals(setOf(a, b, c), custom().customCategories.map { it.id }.toSet())
            assertTrue(db.customCategoryDao().getAllOnce().isEmpty())
        }
    }

    @Test fun compositionWorksInMovieAndSeriesSectionsWithoutCrossingMediaBoundaries() = runBlocking {
        for (type in listOf(MediaType.MOVIE, MediaType.SERIES)) {
            val cat = db.categoryDao().insertAll(listOf(CategoryEntity(sourceId = source, mediaType = type, name = "Shows", remoteId = "shows"))).single()
            if (type == MediaType.MOVIE) db.movieDao().insertAll((1..3).map { MovieEntity(id = it.toLong(), sourceId = source,
                categoryId = cat, name = "Movie $it", remoteId = "movie-$it", streamUrl = "https://stream.test/movie/$it", sortOrder = 4 - it) })
            else db.seriesDao().insertSeries((1..3).map { SeriesEntity(id = it.toLong(), sourceId = source,
                categoryId = cat, name = "Series $it", remoteId = "series-$it", sortOrder = 4 - it) })
            val request = composition(GroupAction.MOVE, GroupDestination(name = "My $type"), GroupSelection("$source:shows"))
                .copy(scope = GroupScope(profile, type))
            val target = service.compose(request).destinationIds.single()
            val members = db.customCategoryDao().getAllOnce().filter { it.mediaType == type }.sortedBy { it.position }
            assertEquals(listOf(3L, 2L, 1L), members.map { it.itemId })
            assertTrue(members.all { it.contextKey == target })
            assertEquals(3, store.observe(profile, type).first().movedFromOrigin.size)
            val duplicate = service.compose(request.copy(action = GroupAction.COPY,
                parts = listOf(GroupCompositionPart(GroupDestination(name = "Copy $type"), listOf(GroupSelection(target)))))).destinationIds.single()
            assertEquals(listOf(3L, 2L, 1L), db.customCategoryDao().getAllOnce().filter { it.contextKey == duplicate }.sortedBy { it.position }.map { it.itemId })
        }
        assertTrue(custom().movedFromOrigin.isEmpty())
        assertTrue(db.customCategoryDao().getAllOnce().none { it.mediaType == MediaType.LIVE })
    }

    @Test fun kidsCompositionRejectsAdultOriginsNamesAndExplicitItemsWithoutPartialCreation() = runBlocking {
        val adult = db.categoryDao().insertAll(listOf(CategoryEntity(sourceId = source, mediaType = MediaType.LIVE, name = "XXX Adult", remoteId = "adult"))).single()
        db.channelDao().insertAll(listOf(ChannelEntity(id = 4, sourceId = source, categoryId = adult, name = "Adult", remoteId = "adult", streamUrl = "https://stream.test/adult")))
        service.edit(edit(GroupAction.COPY, listOf(1, 4)))
        db.profileDao().update(db.profileDao().getById(profile)!!.copy(isKids = true))
        val before = custom()
        for ((request, expected) in listOf(
            composition(GroupAction.COPY, GroupDestination(name = "XXX Adult"), GroupSelection(origin)) to GroupError.INVALID_NAME,
            composition(GroupAction.COPY, GroupDestination(name = "Kids"), GroupSelection("$source:adult")) to GroupError.INVALID_ORIGIN,
            composition(GroupAction.MOVE, GroupDestination(name = "Kids"), GroupSelection(a, listOf(1, 4))) to GroupError.INVALID_ITEM,
        )) {
            assertEquals(expected, (service.composeFromTv(request).exceptionOrNull() as GroupEditException).code)
            assertEquals(before, custom())
        }
        val target = service.compose(composition(GroupAction.COPY, GroupDestination(name = "Kids"), GroupSelection(a))).destinationIds.single()
        assertEquals(listOf(1L), db.customCategoryDao().snapshotChannels(profile, target, listOf(source), 10).map { it.id })
        assertTrue(db.customCategoryDao().exists(profile, MediaType.LIVE, a, 4))
    }

    @Test fun compositionCopiesEmptyGroupsAndRepeatIntoAnEmptyExistingGroupIsANoOp() = runBlocking {
        val empty = service.compose(composition(GroupAction.COPY, GroupDestination(name = "Empty copy"), GroupSelection(a)))
        assertEquals(0, empty.selected); assertEquals(0, empty.added); assertEquals(1L, empty.revision)
        assertTrue(custom().customCategories.any { it.id == empty.destinationIds.single() })
        val repeat = service.compose(composition(GroupAction.COPY, GroupDestination(groupId = b), GroupSelection(a)))
        assertEquals(1L, repeat.revision); assertEquals(0, repeat.added)
        assertFalse(GroupOperationJournal(directory).readState().has("pending"))
    }

    @Test fun compositionContinuesAfterItsTvEditorIsCancelledFollowingPublication() = runBlocking {
        withTimeout(30_000) {
            coroutineScope {
                val committed = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
                val operation = newService { step, _ -> if (step == GroupMutationStage.MEMBERSHIP_WRITTEN) { committed.complete(Unit); release.await() } }
                val job = async { operation.composeFromTv(composition(GroupAction.MOVE, GroupDestination(name = "Detached editor"), GroupSelection(origin))) }
                committed.await(); job.cancel(); release.complete(Unit); job.join()
                val target = custom().customCategories.single { it.name == "Detached editor" }.id
                assertEquals(listOf(1L, 2L, 3L), db.customCategoryDao().snapshotChannels(profile, target, listOf(source), 10).map { it.id })
                assertEquals(3, custom().movedFromOrigin.size)
                assertEquals(1L, service.revision())
                assertFalse(GroupOperationJournal(directory).readState().has("pending"))
            }
        }
    }

    private suspend fun noOpWhileAnotherImportFinishes(expected: Long?): Result<GroupCompositionResult> = coroutineScope {
        val imported = CompletableDeferred<Unit>(); val releaseImport = CompletableDeferred<Unit>()
        val planned = CompletableDeferred<Unit>(); val releasePlan = CompletableDeferred<Unit>()
        service = newService { stage, _ -> if (stage == GroupMutationStage.COMPOSITION_PLANNED) { planned.complete(Unit); releasePlan.await() } }
        val importJob = async { service.withCatalogSource(otherSource) { imported.complete(Unit); releaseImport.await() } }
        imported.await()
        val request = composition(GroupAction.COPY, GroupDestination(groupId = b), GroupSelection(a))
            .copy(scope = GroupScope(profile, MediaType.LIVE, setOf(source)), expectedRevision = expected)
        val operation = async { service.composeFromTv(request) }
        planned.await(); releaseImport.complete(Unit); importJob.await(); releasePlan.complete(Unit)
        operation.await()
    }

    @Test fun noOpCompositionStillRejectsARevisionChangedDuringPlanning() = runBlocking {
        withTimeout(30_000) {
            val result = noOpWhileAnotherImportFinishes(0)
            assertEquals(GroupError.REVISION_CONFLICT, (result.exceptionOrNull() as GroupEditException).code)
            assertEquals(1L, service.revision())
            assertFalse(GroupOperationJournal(directory).readState().has("pending"))
            assertTrue(directory.listFiles()!!.none { it.isDirectory })
        }
    }

    @Test fun noOpCompositionWithoutARevisionGuardReturnsTheCurrentCatalogRevision() = runBlocking {
        withTimeout(30_000) {
            val result = noOpWhileAnotherImportFinishes(null).getOrThrow()
            assertEquals(1L, result.revision); assertEquals(0, result.added)
            assertEquals(1L, service.revision())
            assertFalse(GroupOperationJournal(directory).readState().has("pending"))
        }
    }

    @Test fun cancellingCompositionDuringPlanningPublishesNothingAndOrphansAreRecoverable() = runBlocking {
        withTimeout(30_000) {
            coroutineScope {
                val planned = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
                val operation = newService { stage, _ -> if (stage == GroupMutationStage.COMPOSITION_PLANNED) { planned.complete(Unit); release.await() } }
                val job = async { operation.composeFromTv(composition(GroupAction.MOVE, GroupDestination(name = "Cancelled"), GroupSelection(origin))) }
                planned.await(); job.cancel(); job.join()
                assertFalse(GroupOperationJournal(directory).readState().has("pending"))
                assertEquals(0L, service.revision())
                assertEquals(setOf(a, b, c), custom().customCategories.map { it.id }.toSet())
                assertTrue(db.customCategoryDao().getAllOnce().isEmpty())
                assertTrue(custom().movedFromOrigin.isEmpty())
                newService().recover()
                assertTrue(directory.listFiles().orEmpty().none { it.isDirectory })
            }
        }
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

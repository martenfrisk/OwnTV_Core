package tv.own.owntv.core.customize

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Queued UI work and blocked background previews reproduce editor/profile switches deterministically. */
class BulkRenameSessionTest {
    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.addLast(block) }
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }
    private val rules = listOf(RenameRules.Rule(RenameRules.Action.ADD, RenameRules.Placement.PREFIX, "New "))

    @Test fun automaticCleanupCannotBypassAnOversizedSelectionRefusal() {
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val session = BulkRenameSession(scope, { error("Refused selection was saved") }, {}, { emptySet() })
        try {
            session.start(listOf("old" to "News"))
            session.start((0..BULK_RENAME_MAX_ROWS).map { "item-$it" to "Item $it" })
            session.autoCleanup(); dispatcher.drain()
            assertEquals(BulkRenameSession.Screen.REFUSED, session.screen.value)
            assertTrue(session.preview.value.isEmpty())
        } finally { scope.cancel(); dispatcher.drain() }
    }

    @Test fun queuedDoneKeepsItsAcceptedRowsAndOriginalScope() = runBlocking {
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val writes = mutableListOf<Pair<String, Map<String, String>>>()
        val session = BulkRenameSession(scope, { error("Uncaptured scope") }, {}, { emptySet() })
        try {
            session.start(listOf("old" to "News"), persistOverride = { writes += "first" to it })
            session.submitRules(rules, RenameRules.Options())
            withTimeout(5000) { session.screen.first { it == BulkRenameSession.Screen.REVIEW } }
            session.applyAll(); session.done()
            session.start(listOf("new" to "Sports"), persistOverride = { writes += "second" to it })
            dispatcher.drain()
            assertEquals(listOf("first" to mapOf("old" to "New News")), writes)
            assertEquals(BulkRenameSession.Screen.CHOICE, session.screen.value)
            assertEquals(listOf("new" to "Sports"), session.entries.value)
        } finally { scope.cancel(); dispatcher.drain() }
    }

    @Test fun queuedRestoreKeepsItsSelectionAndOriginalScope() {
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val restores = mutableListOf<Pair<String, Set<String>>>()
        val session = BulkRenameSession(scope, {}, { error("Uncaptured scope") }, { emptySet() })
        try {
            session.start(listOf("old" to "News"), restoreOverride = { restores += "first" to it })
            session.requestRestore(); session.confirmRestore()
            session.start(listOf("new" to "Sports"), restoreOverride = { restores += "second" to it })
            dispatcher.drain()
            assertEquals(listOf("first" to setOf("old")), restores)
            assertEquals(BulkRenameSession.Screen.CHOICE, session.screen.value)
        } finally { scope.cancel(); dispatcher.drain() }
    }

    @Test fun obsoletePreviewCannotReplaceANewEditor() = runBlocking {
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val session = BulkRenameSession(scope, {}, {}, { emptySet() })
        try {
            session.start(listOf("old" to "News"), existingNamesOverride = {
                entered.complete(Unit); release.await(); emptySet()
            })
            session.submitRules(rules, RenameRules.Options())
            withTimeout(5000) { entered.await() }
            val preview = scope.coroutineContext[Job]!!.children.single()
            session.start(listOf("new" to "Sports"))
            release.complete(Unit)
            withTimeout(5000) { preview.join() }
            assertEquals(BulkRenameSession.Screen.CHOICE, session.screen.value)
            assertTrue(session.preview.value.isEmpty())
        } finally { scope.cancel(); dispatcher.drain() }
    }

    @Test fun dismissedEditorCannotReopenWhenItsPreviewFinishes() = runBlocking {
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val session = BulkRenameSession(scope, {}, {}, { entered.complete(Unit); release.await(); emptySet() })
        try {
            session.start(listOf("old" to "News")); session.submitRules(rules, RenameRules.Options())
            withTimeout(5000) { entered.await() }
            val preview = scope.coroutineContext[Job]!!.children.single()
            session.close(); release.complete(Unit)
            withTimeout(5000) { preview.join() }
            assertEquals(BulkRenameSession.Screen.NONE, session.screen.value)
            assertTrue(session.preview.value.isEmpty())
        } finally { scope.cancel(); dispatcher.drain() }
    }
}

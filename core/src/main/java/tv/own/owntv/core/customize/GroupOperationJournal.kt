package tv.own.owntv.core.customize

import android.util.AtomicFile
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Private recovery commands, separate from canonical customization and backup data. Each chunk is
 * durable before its manifest becomes visible. Completion/revision is durable before chunks are
 * removed. A process death at any intermediate point therefore leaves a replayable command.
 */
internal class GroupOperationJournal(private val directory: File) {
    private val stateFile get() = AtomicFile(File(directory, "state.json"))

    fun readState(): JSONObject {
        if (!stateFile.baseFile.exists() && !File(directory, "state.json.bak").exists()) {
            return JSONObject().put("version", 1).put("revision", 0)
        }
        val bytes = read(stateFile, MAX_STATE_BYTES)
        val state = JSONObject(bytes.toString(Charsets.UTF_8))
        if (state.optInt("version") != 1 || state.optLong("revision", -1) < 0) {
            throw GroupEditException(GroupError.JOURNAL_UNAVAILABLE)
        }
        return state
    }

    fun writeState(state: JSONObject) = write(stateFile, state.toString(), MAX_STATE_BYTES)

    fun writeChunk(id: String, index: Int, rows: JSONArray) {
        write(AtomicFile(File(operationDirectory(id), "$index.json")), rows.toString(), MAX_CHUNK_BYTES)
    }

    fun readChunk(id: String, index: Int): JSONArray {
        val bytes = read(AtomicFile(File(operationDirectory(id), "$index.json")), MAX_CHUNK_BYTES)
        val rows = JSONArray(bytes.toString(Charsets.UTF_8))
        if (rows.length() > GROUP_BATCH_SIZE) throw GroupEditException(GroupError.JOURNAL_UNAVAILABLE)
        return rows
    }

    fun removeOperation(id: String) { operationDirectory(id).deleteRecursively() }

    /** Plans written before their manifest, or chunks left after completion, have no live command. */
    fun removeOrphans(activeId: String?) {
        directory.listFiles()?.filter { it.isDirectory && it.name != activeId }?.forEach {
            if (runCatching { UUID.fromString(it.name) }.isSuccess) it.deleteRecursively()
        }
    }

    private fun operationDirectory(id: String): File {
        if (UUID.fromString(id).toString() != id) throw GroupEditException(GroupError.JOURNAL_UNAVAILABLE)
        return File(directory, id)
    }

    private fun read(file: AtomicFile, limit: Int): ByteArray = file.openRead().use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > limit - output.size()) throw GroupEditException(GroupError.JOURNAL_UNAVAILABLE)
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }

    private fun write(file: AtomicFile, text: String, limit: Int) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > limit) throw GroupEditException(GroupError.JOURNAL_UNAVAILABLE)
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (failure: Throwable) {
            file.failWrite(output)
            throw failure
        }
    }

    private companion object {
        const val MAX_STATE_BYTES = 65_536
        const val MAX_CHUNK_BYTES = 8_388_608
    }
}

internal const val GROUP_BATCH_SIZE = 500

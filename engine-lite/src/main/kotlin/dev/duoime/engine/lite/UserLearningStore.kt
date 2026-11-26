/*
 * SPDX-License-Identifier: MIT
 * MIT License
 * Copyright (c) 2026 ShellDeck Contributors
 * Adapted for DuoIME by DuoIME Contributors (2026).
 */
package dev.duoime.engine.lite

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class LearnedEntry(
    val pinyinKey: String,
    val word: String,
    val count: Int,
    val lastUsedEpochMs: Long
)

/**
 * Pure local user frequency & custom phrase learning store.
 * Persists to a local JSON file when provided; zero network activity.
 */
class UserLearningStore(
    private val backingFile: File? = null,
    private val asyncSaveExecutor: Executor? = null
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }
    private val map = HashMap<String, LearnedEntry>()
    private val fileIoLock = Any()
    private val saveScheduled = AtomicBoolean(false)
    private var saveDirty = false

    init {
        backingFile?.let { file ->
            require(file.parentFile != null) {
                "UserLearningStore backingFile must specify an explicit parent directory: ${file.path}"
            }
        }
        load()
    }

    private fun compositeKey(pinyinKey: String, word: String): String = "$pinyinKey|$word"

    @Synchronized
    fun recordCommit(pinyinKey: String, simplifiedWord: String, nowMs: Long = System.currentTimeMillis()) {
        if (pinyinKey.isBlank() || simplifiedWord.isBlank()) return
        val k = compositeKey(pinyinKey, simplifiedWord)
        val prev = map[k]
        map[k] = LearnedEntry(
            pinyinKey = pinyinKey,
            word = simplifiedWord,
            count = (prev?.count ?: 0) + 1,
            lastUsedEpochMs = nowMs
        )
        val file = backingFile ?: return
        val executor = asyncSaveExecutor
        if (executor == null) {
            writeTrimmedEntries(file, buildTrimmedEntriesLocked())
        } else {
            saveDirty = true
            enqueueAsyncSaveLocked(executor)
        }
    }

    @Synchronized
    fun getBoost(pinyinKey: String, simplifiedWord: String): Long {
        val entry = map[compositeKey(pinyinKey, simplifiedWord)] ?: return 0L
        return 250_000L + minOf(entry.count, 50) * 35_000L
    }

    @Synchronized
    fun entriesForPinyin(pinyinKey: String): List<LearnedEntry> = map.values.filter { it.pinyinKey == pinyinKey }

    @Synchronized
    fun matchingEntriesForTokens(tokens: List<SyllableToken>): List<LearnedEntry> {
        if (tokens.isEmpty() || map.isEmpty()) return emptyList()
        val n = tokens.size
        return map.values.filter { entry ->
            val parts = entry.pinyinKey.split(' ')
            parts.size == n && parts.indices.all { idx -> parts[idx] in tokens[idx].options }
        }
    }

    @Synchronized
    fun load() {
        val file = backingFile ?: return
        if (!file.exists()) return
        runCatching {
            val list = json.decodeFromString<List<LearnedEntry>>(file.readText(Charsets.UTF_8))
            map.clear()
            for (item in list) {
                map[compositeKey(item.pinyinKey, item.word)] = item
            }
        }
    }

    fun save() {
        val file = backingFile ?: return
        val trimmed = synchronized(this) {
            saveDirty = false
            buildTrimmedEntriesLocked()
        }
        writeTrimmedEntries(file, trimmed)
    }

    private fun buildTrimmedEntriesLocked(): List<LearnedEntry> = map.values
        .sortedByDescending { it.lastUsedEpochMs }
        .take(MAX_PERSISTED_ENTRIES)

    private fun enqueueAsyncSaveLocked(executor: Executor) {
        if (!saveScheduled.compareAndSet(false, true)) return
        dispatchDrainTaskLocked(executor)
    }

    private fun dispatchDrainTaskLocked(executor: Executor) {
        runCatching {
            executor.execute(::drainAsyncSaves)
        }.onFailure {
            saveScheduled.set(false)
        }
    }

    private fun drainAsyncSaves() {
        val file = backingFile
        try {
            if (file == null) {
                synchronized(this) {
                    saveDirty = false
                }
                return
            }
            while (true) {
                val snapshot = synchronized(this) {
                    if (!saveDirty) {
                        null
                    } else {
                        saveDirty = false
                        buildTrimmedEntriesLocked()
                    }
                } ?: break
                writeTrimmedEntries(file, snapshot)
            }
        } finally {
            releaseOrRescheduleDrain()
        }
    }

    private fun releaseOrRescheduleDrain() {
        synchronized(this) {
            val executor = asyncSaveExecutor
            if (saveDirty && executor != null) {
                dispatchDrainTaskLocked(executor)
            } else {
                saveScheduled.set(false)
            }
        }
    }

    private fun writeTrimmedEntries(file: File, trimmed: List<LearnedEntry>) {
        runCatching {
            val encoded = json.encodeToString(trimmed)
            synchronized(fileIoLock) {
                val parent = requireNotNull(file.parentFile) {
                    "UserLearningStore backingFile must specify an explicit parent directory: ${file.path}"
                }.apply { mkdirs() }
                val tmpFile = File.createTempFile("${file.nameWithoutExtension}_", ".json.tmp", parent)
                try {
                    tmpFile.writeText(encoded, Charsets.UTF_8)
                    moveReplacingTarget(tmpFile, file)
                } finally {
                    if (tmpFile.exists()) {
                        tmpFile.delete()
                    }
                }
            }
        }
    }

    private fun moveReplacingTarget(tmpFile: File, targetFile: File) {
        try {
            Files.move(
                tmpFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                tmpFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    companion object {
        private const val MAX_PERSISTED_ENTRIES = 2000
    }
}

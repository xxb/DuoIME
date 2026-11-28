// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.app.settings

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dev.duoime.engine.rime.RimeImportEntry
import dev.duoime.engine.rime.RimeImportFailureReason
import dev.duoime.engine.rime.RimeStagingResult
import dev.duoime.engine.rime.RimeWorkspaceManager
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

internal data class SafDocumentNode(
    val documentId: String,
    val displayName: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = -1L
)

internal interface SafTreeNodeSource {
    fun listChildren(parentDocumentId: String): List<SafDocumentNode>

    fun openStream(documentId: String): InputStream?
}

/**
 * Pure framework-SAF (zero `androidx.documentfile`, zero storage permissions) importer streaming
 * user-selected `.zip` archives and `DocumentsContract` folder trees into Phase 1 staging.
 */
internal object RimeSafImporter {
    internal const val MAX_TREE_DEPTH: Int = 16
    internal val ZIP_MIME_TYPES: Array<String> = arrayOf(
        "application/zip",
        "application/x-zip-compressed",
        "application/octet-stream"
    )

    @Suppress("TooGenericExceptionCaught")
    fun stageZipUri(context: Context, uri: Uri, stageDir: File): RimeStagingResult {
        return try {
            val stream = context.contentResolver.openInputStream(uri)
                ?: return RimeStagingResult.Failure(
                    RimeImportFailureReason.IO_ERROR,
                    "Unable to open selected .zip file"
                )
            stream.use { input ->
                stageZipStream(input, stageDir)
            }
        } catch (err: Throwable) {
            RimeStagingResult.Failure(
                RimeImportFailureReason.IO_ERROR,
                err.message ?: err::class.simpleName.orEmpty()
            )
        }
    }

    @Suppress("TooGenericExceptionCaught")
    fun stageTreeUri(context: Context, treeUri: Uri, stageDir: File): RimeStagingResult {
        return try {
            val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
            if (rootDocId.isNullOrBlank()) {
                return RimeStagingResult.Failure(RimeImportFailureReason.IO_ERROR, "Invalid SAF tree URI")
            }
            val source = AndroidSafTreeNodeSource(context.contentResolver, treeUri)
            stageTreeNodes(rootDocId, source, stageDir)
        } catch (err: Throwable) {
            RimeStagingResult.Failure(
                RimeImportFailureReason.IO_ERROR,
                err.message ?: err::class.simpleName.orEmpty()
            )
        }
    }

    @Suppress("TooGenericExceptionCaught")
    internal fun stageZipStream(inputStream: InputStream, stageDir: File): RimeStagingResult = try {
        ZipInputStream(BufferedInputStream(inputStream)).use { zis ->
            val entries = sequence {
                while (true) {
                    val entry: ZipEntry = zis.nextEntry ?: break
                    val name = entry.name
                    if (!entry.isDirectory && name.isNotEmpty()) {
                        yield(
                            RimeImportEntry(
                                relativePath = name,
                                sizeBytes = entry.size,
                                openStream = { NonClosingInputStream(zis) }
                            )
                        )
                    }
                    zis.closeEntry()
                }
            }
            RimeWorkspaceManager.stageEntries(entries, stageDir)
        }
    } catch (err: Throwable) {
        RimeStagingResult.Failure(
            RimeImportFailureReason.IO_ERROR,
            err.message ?: err::class.simpleName.orEmpty()
        )
    }

    internal fun stageTreeNodes(rootDocumentId: String, source: SafTreeNodeSource, stageDir: File): RimeStagingResult {
        val collected = ArrayList<RimeImportEntry>()
        val walkError = collectTreeEntries(
            rootDocumentId = rootDocumentId,
            source = source,
            outEntries = collected
        )
        if (walkError != null) {
            return walkError
        }
        return RimeWorkspaceManager.stageEntries(collected.asSequence(), stageDir)
    }

    private class TreeWalkState(
        val source: SafTreeNodeSource,
        val visitedDirs: MutableSet<String> = HashSet(),
        val stack: ArrayDeque<TreeFrame> = ArrayDeque(),
        val outEntries: MutableList<RimeImportEntry> = ArrayList()
    )

    private fun collectTreeEntries(
        rootDocumentId: String,
        source: SafTreeNodeSource,
        outEntries: MutableList<RimeImportEntry>
    ): RimeStagingResult.Failure? {
        val state = TreeWalkState(source = source, outEntries = outEntries)
        state.visitedDirs.add(rootDocumentId)
        state.stack.addLast(TreeFrame(documentId = rootDocumentId, prefix = "", depth = 0))

        while (state.stack.isNotEmpty()) {
            val frame = state.stack.removeLast()
            for (child in source.listChildren(frame.documentId)) {
                val failure = processTreeChild(child, frame, state)
                if (failure != null) return failure
            }
        }
        return null
    }

    private fun processTreeChild(
        child: SafDocumentNode,
        frame: TreeFrame,
        state: TreeWalkState
    ): RimeStagingResult.Failure? {
        val safeName = validateSafDisplayName(child.displayName)
            ?: return RimeStagingResult.Failure(
                RimeImportFailureReason.UNSAFE_PATH,
                "Unsafe document name: ${child.displayName}"
            )
        val relPath = if (frame.prefix.isEmpty()) safeName else "${frame.prefix}/$safeName"
        if (child.isDirectory) {
            return enqueueDirectoryChild(child.documentId, relPath, frame.depth + 1, state)
        }
        if (state.outEntries.size >= RimeWorkspaceManager.MAX_IMPORT_FILES) {
            return RimeStagingResult.Failure(
                RimeImportFailureReason.TOO_MANY_FILES,
                "Folder exceeds maximum of ${RimeWorkspaceManager.MAX_IMPORT_FILES} files"
            )
        }
        val docId = child.documentId
        state.outEntries.add(
            RimeImportEntry(
                relativePath = relPath,
                sizeBytes = child.sizeBytes,
                openStream = {
                    state.source.openStream(docId)
                        ?: throw IOException("Unable to open SAF document: $relPath")
                }
            )
        )
        return null
    }

    private fun enqueueDirectoryChild(
        docId: String,
        relPath: String,
        nextDepth: Int,
        state: TreeWalkState
    ): RimeStagingResult.Failure? {
        if (nextDepth > MAX_TREE_DEPTH) {
            return RimeStagingResult.Failure(
                RimeImportFailureReason.UNSAFE_PATH,
                "Folder nesting exceeds maximum depth ($MAX_TREE_DEPTH): $relPath"
            )
        }
        if (state.visitedDirs.add(docId)) {
            state.stack.addLast(TreeFrame(docId, relPath, nextDepth))
        }
        return null
    }

    internal fun validateSafDisplayName(rawName: String?): String? {
        val name = rawName?.trim().orEmpty()
        if (name.isEmpty() || name == "." || name == "..") return null
        if (name.contains('/') || name.contains('\\') || name.indexOf('\u0000') >= 0) return null
        return name
    }

    private data class TreeFrame(
        val documentId: String,
        val prefix: String,
        val depth: Int
    )

    private class NonClosingInputStream(delegate: InputStream) : FilterInputStream(delegate) {
        override fun close() = Unit
    }

    private class AndroidSafTreeNodeSource(
        private val contentResolver: ContentResolver,
        private val treeUri: Uri
    ) : SafTreeNodeSource {
        override fun listChildren(parentDocumentId: String): List<SafDocumentNode> {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
            )
            val result = ArrayList<SafDocumentNode>()
            contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                while (cursor.moveToNext()) {
                    val docId = if (idCol >= 0) cursor.getString(idCol).orEmpty() else ""
                    val name = if (nameCol >= 0) cursor.getString(nameCol).orEmpty() else ""
                    val mime = if (mimeCol >= 0) cursor.getString(mimeCol).orEmpty() else ""
                    val size = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else -1L
                    if (docId.isNotEmpty()) {
                        result.add(
                            SafDocumentNode(
                                documentId = docId,
                                displayName = name,
                                isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                                sizeBytes = size
                            )
                        )
                    }
                }
            }
            return result
        }

        override fun openStream(documentId: String): InputStream? {
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
            return contentResolver.openInputStream(docUri)
        }
    }
}

package dev.hunkreview.pycharm.cli

import dev.hunkreview.pycharm.model.HunkFileDetail
import dev.hunkreview.pycharm.model.HunkFileSummary
import dev.hunkreview.pycharm.model.HunkHunk
import dev.hunkreview.pycharm.model.HunkNote
import dev.hunkreview.pycharm.model.HunkReview
import dev.hunkreview.pycharm.model.HunkSessionSummary
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// hunk is pre-1.0 and its `session`/`--repo` JSON surface is still stabilizing
// (see PLAN.md, issue #166) - tolerate fields we don't model yet.
internal val hunkJson = Json { ignoreUnknownKeys = true }

@Serializable
internal data class SessionListResponseDto(
    // TODO(verify): the top-level wrapper key for `session list --json` was
    // not confirmed against a live call when this was written - PLAN.md only
    // documents the per-session fields, not the envelope. Adjust if needed.
    val sessions: List<SessionSummaryDto> = emptyList()
)

@Serializable
internal data class SessionSummaryDto(
    val sessionId: String,
    val pid: Long,
    val cwd: String,
    val repoRoot: String,
    val launchedAt: String? = null,
    val terminal: TerminalInfoDto? = null,
    val inputKind: String? = null,
    val title: String? = null,
    val sourceLabel: String? = null,
    val experimentalFeatures: List<String> = emptyList(),
    val fileCount: Int = 0,
    val files: List<SessionFileDto> = emptyList()
)

@Serializable
internal data class TerminalInfoDto(val locations: List<TerminalLocationDto> = emptyList())

@Serializable
internal data class TerminalLocationDto(
    val source: String? = null,
    val tty: String? = null
)

@Serializable
internal data class SessionFileDto(
    val id: String,
    val path: String,
    val additions: Int = 0,
    val deletions: Int = 0,
    val hunkCount: Int = 0
)

@Serializable
internal data class SessionReviewResponseDto(val review: ReviewDto)

@Serializable
internal data class ReviewDto(
    val sessionId: String,
    val title: String? = null,
    val sourceLabel: String? = null,
    val cwd: String? = null,
    val repoRoot: String? = null,
    val inputKind: String? = null,
    val experimentalFeatures: List<String> = emptyList(),
    val selectedFile: SelectedFileDto? = null,
    val selectedHunk: SelectedHunkDto? = null,
    val showAgentNotes: Boolean = false,
    val liveCommentCount: Int = 0,
    val reviewNoteCount: Int = 0,
    val reviewNotes: List<ReviewNoteDto> = emptyList()
)

@Serializable
internal data class SelectedFileDto(
    val id: String? = null,
    val path: String,
    val additions: Int = 0,
    val deletions: Int = 0,
    val hunkCount: Int = 0,
    val patch: String? = null,
    val hunks: List<HunkRangeDto> = emptyList()
)

@Serializable
internal data class HunkRangeDto(
    val index: Int,
    val header: String? = null,
    val oldRange: List<Int> = emptyList(),
    val newRange: List<Int> = emptyList()
)

@Serializable
internal data class SelectedHunkDto(
    val index: Int,
    val header: String? = null,
    val oldRange: List<Int> = emptyList(),
    val newRange: List<Int> = emptyList()
)

@Serializable
internal data class ReviewNoteDto(
    val noteId: String,
    val parentId: String? = null,
    val source: String,
    val filePath: String,
    val hunkIndex: Int,
    val newRange: List<Int> = emptyList(),
    val body: String,
    val author: String? = null,
    val createdAt: String? = null,
    val editable: Boolean = false
)

internal fun SessionSummaryDto.toModel(): HunkSessionSummary = HunkSessionSummary(
    sessionId = sessionId,
    pid = pid,
    repoRoot = repoRoot,
    title = title,
    sourceLabel = sourceLabel,
    files = files.map { it.toModel() }
)

internal fun SessionFileDto.toModel(): HunkFileSummary = HunkFileSummary(
    path = path,
    additions = additions,
    deletions = deletions,
    hunkCount = hunkCount
)

internal fun ReviewDto.toModel(): HunkReview = HunkReview(
    sessionId = sessionId,
    title = title,
    repoRoot = repoRoot.orEmpty(),
    selectedFilePath = selectedFile?.path,
    selectedHunkIndex = selectedHunk?.index,
    liveCommentCount = liveCommentCount,
    reviewNotes = reviewNotes.map { it.toModel() }
)

internal fun SelectedFileDto.toModel(): HunkFileDetail = HunkFileDetail(
    path = path,
    patch = patch,
    hunks = hunks.map { it.toModel() }
)

internal fun HunkRangeDto.toModel(): HunkHunk = HunkHunk(
    index = index,
    oldStart = oldRange.getOrNull(0),
    oldCount = oldRange.getOrNull(1),
    newStart = newRange.getOrNull(0),
    newCount = newRange.getOrNull(1)
)

internal fun ReviewNoteDto.toModel(): HunkNote = HunkNote(
    noteId = noteId,
    parentId = parentId,
    source = source,
    filePath = filePath,
    hunkIndex = hunkIndex,
    newRangeStart = newRange.getOrNull(0),
    newRangeCount = newRange.getOrNull(1),
    body = body,
    author = author,
    editable = editable
)

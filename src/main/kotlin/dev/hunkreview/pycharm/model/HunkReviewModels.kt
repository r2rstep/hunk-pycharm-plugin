package dev.hunkreview.pycharm.model

const val PYCHARM_COMMENT_PREFIX = "[author:user]"

data class HunkSessionSummary(
    val sessionId: String,
    val pid: Long,
    val repoRoot: String,
    val title: String?,
    val sourceLabel: String?,
    val files: List<HunkFileSummary>
)

data class HunkFileSummary(
    val path: String,
    val additions: Int,
    val deletions: Int,
    val hunkCount: Int
)

data class HunkReview(
    val sessionId: String,
    val title: String?,
    val repoRoot: String,
    val selectedFilePath: String?,
    val selectedHunkIndex: Int?,
    val liveCommentCount: Int,
    val reviewNotes: List<HunkNote>
)

data class HunkFileDetail(
    val path: String,
    val patch: String?,
    val hunks: List<HunkHunk>
)

data class HunkHunk(
    val index: Int,
    val oldStart: Int?,
    val oldCount: Int?,
    val newStart: Int?,
    val newCount: Int?
)

data class HunkNote(
    val noteId: String,
    val parentId: String?,
    val source: String,
    val filePath: String,
    val hunkIndex: Int,
    val newRangeStart: Int?,
    val newRangeCount: Int?,
    val body: String,
    val author: String?,
    val editable: Boolean
)

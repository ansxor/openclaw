package ai.openclaw.app.chat

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class SessionDiffScope(
  val wireValue: String,
) {
  All("all"),
  Uncommitted("uncommitted"),
  Commit("commit"),
}

@Serializable
data class SessionDiffSnapshot(
  val sessionKey: String,
  val files: List<SessionDiffFile>,
  val additions: Int,
  val deletions: Int,
  val root: String? = null,
  val branch: String? = null,
  val baseRef: String? = null,
  val aheadCount: Int? = null,
  val commits: List<SessionDiffCommit> = emptyList(),
  val mergeBase: SessionDiffCommit? = null,
  val truncated: Boolean = false,
  val unavailableReason: String? = null,
)

@Serializable
data class SessionDiffFile(
  val path: String,
  val status: String,
  val additions: Int,
  val deletions: Int,
  val oldPath: String? = null,
  val binary: Boolean = false,
  val untracked: Boolean = false,
  val patch: String? = null,
  val truncated: Boolean = false,
)

@Serializable
data class SessionDiffCommit(
  val sha: String,
  val subject: String,
)

internal fun parseSessionDiff(
  json: Json,
  payload: String,
): SessionDiffSnapshot = json.decodeFromString(payload)

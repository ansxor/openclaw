package ai.openclaw.app.ui.chat

import ai.openclaw.app.MainViewModel
import ai.openclaw.app.chat.SessionDiffFile
import ai.openclaw.app.chat.SessionDiffLine
import ai.openclaw.app.chat.SessionDiffLineKind
import ai.openclaw.app.chat.SessionDiffScope
import ai.openclaw.app.chat.SessionDiffSnapshot
import ai.openclaw.app.chat.parseSessionDiffPatch
import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.ui.FoldAwareDropdownMenu
import ai.openclaw.app.ui.FoldAwareMenuItem
import ai.openclaw.app.ui.design.ClawPlainIconButton
import ai.openclaw.app.ui.design.ClawTheme
import ai.openclaw.app.ui.foldAwareSheet
import android.content.ClipData
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.horizontalScrollAxisRange
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class SessionDiffFileView(
  val file: SessionDiffFile,
  val lines: List<SessionDiffLine>,
)

internal fun prepareSessionDiffFiles(snapshot: SessionDiffSnapshot): List<SessionDiffFileView> =
  snapshot.files.map { file ->
    SessionDiffFileView(
      file,
      file.patch
        ?.let(::parseSessionDiffPatch)
        .orEmpty()
        .map { it.copy(text = it.text.replace("\t", "    ")) },
    )
  }

@Composable
internal fun SessionDiffSheet(
  viewModel: MainViewModel,
  opening: ChatModelPickerSession,
  admit: () -> Boolean,
  onDismiss: () -> Unit,
) {
  var selectedScope by remember { mutableStateOf(SessionDiffScope.All) }
  var selectedCommit by remember { mutableStateOf<String?>(null) }
  var refresh by remember { mutableIntStateOf(0) }
  var snapshot by remember { mutableStateOf<SessionDiffSnapshot?>(null) }
  var files by remember { mutableStateOf<List<SessionDiffFileView>>(emptyList()) }
  var loading by remember { mutableStateOf(true) }
  var error by remember { mutableStateOf<String?>(null) }

  fun isCurrent() = !opening.geometry.revoked && viewModel.isCurrentChatComposerOwner(opening.composerOwner)

  LaunchedEffect(opening, selectedScope, selectedCommit, refresh) {
    loading = true
    error = null
    snapshot = null
    files = emptyList()
    try {
      val gateway = opening.composerOwner.gatewayStableId ?: error(nativeString("Connect to a Gateway to review changes."))
      if (!isCurrent()) return@LaunchedEffect
      val result =
        viewModel.loadSessionDiff(
          sessionKey = opening.sessionKey,
          agentId = opening.composerOwner.agentId,
          scope = selectedScope,
          commit = selectedCommit,
          expectedGatewayStableId = gateway,
        )
      val prepared = withContext(Dispatchers.Default) { prepareSessionDiffFiles(result) }
      if (isCurrent()) {
        snapshot = result
        files = prepared
      }
    } catch (failure: Exception) {
      currentCoroutineContext().ensureActive()
      if (isCurrent()) {
        error =
          if (failure is CancellationException) {
            nativeString("The connection changed. Refresh to load a new snapshot.")
          } else {
            failure.message ?: nativeString("Couldn’t load changes. Try refreshing.")
          }
      }
    } finally {
      if (isCurrent()) loading = false
    }
  }

  Dialog(
    onDismissRequest = onDismiss,
    properties =
      DialogProperties(
        usePlatformDefaultWidth = false,
        dismissOnClickOutside = false,
        decorFitsSystemWindows = false,
      ),
  ) {
    SessionDiffContent(
      snapshot = snapshot,
      files = files,
      loading = loading,
      error = error,
      selectedScope = selectedScope,
      selectedCommit = selectedCommit,
      onScope = { scope, commit ->
        if (admit()) {
          selectedScope = scope
          selectedCommit = commit
        }
      },
      onRefresh = { if (admit()) refresh++ },
      onClose = onDismiss,
      modifier =
        Modifier
          .fillMaxSize()
          .foldAwareSheet(opening.geometry)
          .background(ClawTheme.colors.surface)
          .windowInsetsPadding(WindowInsets.safeDrawing),
    )
  }
}

/** Native, viewport-only code rows keep long patches out of Android text layout. */
@Composable
internal fun SessionDiffContent(
  snapshot: SessionDiffSnapshot?,
  files: List<SessionDiffFileView>,
  loading: Boolean,
  error: String?,
  selectedScope: SessionDiffScope,
  selectedCommit: String?,
  onScope: (SessionDiffScope, String?) -> Unit,
  onRefresh: () -> Unit,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var scopeMenu by remember { mutableStateOf(false) }
  var collapsed by remember { mutableStateOf(emptySet<String>()) }
  val scopeLabel =
    when (selectedScope) {
      SessionDiffScope.All -> nativeString("All changes")
      SessionDiffScope.Uncommitted -> nativeString("Uncommitted")
      SessionDiffScope.Commit -> selectedCommit.orEmpty().take(8)
    }
  Column(modifier.background(ClawTheme.colors.surface)) {
    Row(
      Modifier.fillMaxWidth().padding(start = ClawTheme.spacing.sm),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(nativeString("Review changes"), style = ClawTheme.type.section, color = ClawTheme.colors.text, modifier = Modifier.weight(1f))
      ClawPlainIconButton(Icons.Default.Refresh, nativeString("Refresh changes"), onRefresh, enabled = !loading)
      ClawPlainIconButton(Icons.Default.Close, nativeString("Close review"), onClose)
    }
    Row(
      Modifier.fillMaxWidth().padding(horizontal = ClawTheme.spacing.sm),
      horizontalArrangement = Arrangement.spacedBy(ClawTheme.spacing.xxs),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box {
        TextButton(onClick = { scopeMenu = true }, enabled = !loading) {
          Text(scopeLabel, color = ClawTheme.colors.text, style = ClawTheme.type.label)
          Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = ClawTheme.colors.textMuted)
        }
        FoldAwareDropdownMenu(
          expanded = scopeMenu,
          onDismissRequest = { scopeMenu = false },
          items =
            buildList {
              add(FoldAwareMenuItem("all", nativeString("All changes"), { onScope(SessionDiffScope.All, null) }))
              add(FoldAwareMenuItem("uncommitted", nativeString("Uncommitted"), { onScope(SessionDiffScope.Uncommitted, null) }))
              snapshot?.commits?.forEach { commit ->
                add(FoldAwareMenuItem(commit.sha, "${commit.sha.take(8)} ${commit.subject}", { onScope(SessionDiffScope.Commit, commit.sha) }))
              }
            },
        )
      }
      Text(
        snapshot?.branch.orEmpty(),
        style = ClawTheme.type.caption,
        color = ClawTheme.colors.textMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      snapshot?.let {
        Text("+${it.additions}", style = ClawTheme.type.mono, color = ClawTheme.colors.success)
        Text("−${it.deletions}", style = ClawTheme.type.mono, color = ClawTheme.colors.danger)
      }
    }
    HorizontalDivider(color = ClawTheme.colors.border)
    when {
      loading -> {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          CircularProgressIndicator(color = ClawTheme.colors.textMuted, modifier = Modifier.size(28.dp))
        }
      }

      error != null -> {
        Column(Modifier.padding(ClawTheme.spacing.sm)) {
          Text(error, style = ClawTheme.type.body, color = ClawTheme.colors.danger)
          TextButton(onClick = onRefresh) { Text(nativeString("Try again")) }
        }
      }

      snapshot != null -> {
        SessionDiffFiles(snapshot, files, collapsed, { path ->
          collapsed = if (path in collapsed) collapsed - path else collapsed + path
        }, Modifier.weight(1f))
      }
    }
  }
}

@Composable
private fun SessionDiffFiles(
  snapshot: SessionDiffSnapshot,
  files: List<SessionDiffFileView>,
  collapsed: Set<String>,
  toggle: (String) -> Unit,
  modifier: Modifier,
) {
  val clipboard = LocalClipboard.current
  val copyScope = rememberCoroutineScope()
  val density = LocalDensity.current
  val monoStyle = ClawTheme.type.mono
  val codeColor = ClawTheme.colors.codeText
  val fontSize = with(density) { monoStyle.fontSize.toPx() }
  val codePaint =
    remember(fontSize, codeColor) {
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = fontSize
        color = codeColor.toArgb()
      }
    }
  val gutterWidth = remember(codePaint) { codePaint.measureText("00000 00000 + ") }
  val contentWidth by produceState(0f, files, codePaint) {
    // Use the same native shaping for scroll bounds and drawing: Unicode glyphs
    // need not occupy one ASCII cell, even with a monospace primary typeface.
    value =
      withContext(Dispatchers.Default) {
        val paint = Paint(codePaint)
        files.maxOfOrNull { file -> file.lines.maxOfOrNull { paint.measureText(it.text) } ?: 0f } ?: 0f
      }
  }
  var viewportWidth by remember { mutableIntStateOf(0) }
  var horizontalOffset by remember(files) { mutableFloatStateOf(0f) }
  val maxOffset = (contentWidth - (viewportWidth - gutterWidth).coerceAtLeast(0f)).coerceAtLeast(0f)
  val horizontalScroll =
    rememberScrollableState { delta ->
      val previous = horizontalOffset
      horizontalOffset = (previous - delta).coerceIn(0f, maxOffset)
      previous - horizontalOffset
    }
  LaunchedEffect(maxOffset) { horizontalOffset = horizontalOffset.coerceAtMost(maxOffset) }
  LazyColumn(
    modifier
      .fillMaxWidth()
      .onSizeChanged { viewportWidth = it.width }
      .scrollable(horizontalScroll, Orientation.Horizontal)
      .semantics { horizontalScrollAxisRange = ScrollAxisRange({ horizontalOffset }, { maxOffset }) },
  ) {
    val unavailable =
      when (snapshot.unavailableReason) {
        "not_git" -> nativeString("This conversation’s workspace is not a Git repository.")
        "unknown_session" -> nativeString("This conversation is no longer available. Reopen it and try again.")
        "unknown_commit" -> nativeString("This commit is no longer available. Choose All changes or refresh.")
        "workspace_stopped" -> nativeString("The workspace is stopped. Showing its saved changes, if available.")
        null -> null
        else -> nativeString("Changes are unavailable for this workspace. Try refreshing.")
      }
    if (unavailable != null) item { DiffNotice(unavailable) }
    if (snapshot.truncated) item { DiffNotice(nativeString("Large diff: some files or patches were omitted from this snapshot.")) }
    if (files.isEmpty() && unavailable == null) item { DiffNotice(nativeString("No changes in this snapshot.")) }
    files.forEach { view ->
      val file = view.file
      item(key = "header:${file.path}", contentType = "file") {
        Row(Modifier.fillMaxWidth().background(ClawTheme.colors.surfaceRaised), verticalAlignment = Alignment.CenterVertically) {
          val expandedLabel = if (file.path in collapsed) nativeString("Collapsed") else nativeString("Expanded")
          Row(
            Modifier
              .weight(1f)
              .heightIn(min = ClawTheme.spacing.touchTarget)
              .clickable(role = Role.Button) { toggle(file.path) }
              .semantics { stateDescription = expandedLabel }
              .padding(ClawTheme.spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ClawTheme.spacing.xxs),
          ) {
            Icon(
              if (file.path in collapsed) Icons.Default.ChevronRight else Icons.Default.ExpandMore,
              contentDescription = null,
              modifier = Modifier.size(ClawTheme.spacing.icon),
              tint = ClawTheme.colors.textMuted,
            )
            Column(Modifier.weight(1f)) {
              Text(file.path, style = ClawTheme.type.label, color = ClawTheme.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
              if (file.oldPath != null) {
                Text(
                  file.oldPath,
                  style = ClawTheme.type.caption,
                  color = ClawTheme.colors.textMuted,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
            }
            Text("+${file.additions}", style = ClawTheme.type.caption, color = ClawTheme.colors.success)
            Text("−${file.deletions}", style = ClawTheme.type.caption, color = ClawTheme.colors.danger)
          }
          ClawPlainIconButton(
            Icons.Default.ContentCopy,
            nativeString("Copy patch"),
            { file.patch?.let { patch -> copyScope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Patch", patch))) } } },
            enabled = file.patch != null,
          )
        }
      }
      if (file.path !in collapsed) {
        if (file.binary || view.lines.isEmpty()) {
          item(key = "notice:${file.path}") {
            DiffNotice(if (file.binary) nativeString("Binary file changed") else nativeString("No text patch available"))
          }
        }
        itemsIndexed(view.lines, key = { index, _ -> "line:${file.path}:$index" }, contentType = { _, _ -> "line" }) { _, line ->
          SessionDiffCodeRow(line, horizontalOffset, codePaint, gutterWidth)
        }
        if (file.truncated) item(key = "truncated:${file.path}") { DiffNotice(nativeString("This file’s patch was truncated.")) }
      }
    }
  }
}

@Composable
private fun DiffNotice(message: String) {
  Text(message, Modifier.fillMaxWidth().padding(ClawTheme.spacing.sm), style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted)
}

@Composable
private fun SessionDiffCodeRow(
  line: SessionDiffLine,
  offset: Float,
  codePaint: Paint,
  gutterWidth: Float,
) {
  val colors = ClawTheme.colors
  val style = ClawTheme.type.mono
  val density = LocalDensity.current
  val rowHeight = with(density) { style.lineHeight.toDp() }
  val gutterPaint = remember(codePaint, colors.textMuted) { Paint(codePaint).apply { color = colors.textMuted.toArgb() } }
  val sign =
    when (line.kind) {
      SessionDiffLineKind.Addition -> "+"
      SessionDiffLineKind.Deletion -> "−"
      else -> " "
    }
  Canvas(
    Modifier.fillMaxWidth().height(rowHeight).semantics {
      text = AnnotatedString("${line.oldLine ?: ""} ${line.newLine ?: ""} $sign ${line.text}")
    },
  ) {
    val background =
      when (line.kind) {
        SessionDiffLineKind.Addition -> colors.successSoft
        SessionDiffLineKind.Deletion -> colors.dangerSoft
        SessionDiffLineKind.Hunk, SessionDiffLineKind.NoNewline -> colors.surfaceRaised
        else -> colors.codeBg
      }
    drawRect(colors.codeBg)
    drawRect(background)
    val gutter = "${line.oldLine?.toString().orEmpty().padStart(5)} ${line.newLine?.toString().orEmpty().padStart(5)} $sign "
    val baseline = (size.height - codePaint.fontMetrics.bottom - codePaint.fontMetrics.top) / 2f
    drawIntoCanvas { it.nativeCanvas.drawText(gutter, 0f, baseline, gutterPaint) }
    // Let Android shape intact text, including surrogate pairs and combining
    // sequences. Canvas clipping avoids a giant Compose text-layout surface.
    clipRect(left = gutterWidth) {
      drawIntoCanvas { it.nativeCanvas.drawText(line.text, gutterWidth - offset, baseline, codePaint) }
    }
  }
}

package ai.openclaw.app.ui.chat

import ai.openclaw.app.chat.SessionDiffCommit
import ai.openclaw.app.chat.SessionDiffFile
import ai.openclaw.app.chat.SessionDiffScope
import ai.openclaw.app.chat.SessionDiffSnapshot
import ai.openclaw.app.ui.design.ClawDesignTheme
import android.content.ClipboardManager
import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SessionDiffContentTest {
  @get:Rule val composeRule = createComposeRule()

  @Test
  fun nativeReviewKeepsHunksAndIncompleteFileNoticesAcrossDisclosureInBothThemes() {
    val dark = mutableStateOf(false)
    val snapshot = snapshot()
    val files = prepareSessionDiffFiles(snapshot)
    composeRule.setContent {
      ClawDesignTheme(dark = dark.value) {
        SessionDiffContent(snapshot, files, false, null, SessionDiffScope.All, null, { _, _ -> }, {}, {}, Modifier.fillMaxSize())
      }
    }
    val evidence = File("build/outputs/session-diff", UUID.randomUUID().toString())
    check(evidence.mkdirs())
    for (isDark in listOf(false, true)) {
      composeRule.runOnIdle { dark.value = isDark }
      composeRule.onNodeWithText("Review changes").assertIsDisplayed()
      composeRule.onNodeWithText("+ const retries = 3;", substring = true).assertIsDisplayed()
      composeRule.onNodeWithText("− const retries = 1;", substring = true).assertIsDisplayed()
      composeRule.onNodeWithText("@@ -8,3 +8,3 @@", substring = true).assertIsDisplayed()
      composeRule.onNodeWithText("Binary file changed").assertIsDisplayed()
      composeRule.onNodeWithText("This file’s patch was truncated.").assertIsDisplayed()
      val theme = if (isDark) "dark" else "light"
      val addedLine = composeRule.onNodeWithText("+ const retries = 3;", substring = true)
      addedLine.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Line numbers hidden"))
      composeRule.onNodeWithText("+ const retries = 3;").assertIsDisplayed()
      capture(File(evidence, "review-$theme-hidden.png"))
      addedLine.performTouchInput { click() }
      addedLine.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Line numbers shown"))
      composeRule.onNodeWithText(" 8 + const retries = 3;").assertIsDisplayed()
      // One tap reveals the gutters throughout the viewer, including other files.
      composeRule.onNodeWithText(" 1 + export const ready = true;").assertIsDisplayed()
      capture(File(evidence, "review-$theme-shown.png"))
      addedLine.performTouchInput { click() }
      addedLine.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Line numbers hidden"))
      composeRule.onNodeWithText("+ export const ready = true;").assertIsDisplayed()

      val header = composeRule.onNodeWithText("src/retry.ts")
      header.performClick()
      header.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
      composeRule.onNodeWithText("+ const retries = 3;", substring = true).assertDoesNotExist()
      composeRule.onNodeWithText("Binary file changed").assertIsDisplayed()
      header.performClick()
      header.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
      composeRule.onNodeWithText("+ const retries = 3;", substring = true).assertIsDisplayed()
    }
  }

  @Test
  fun wideUnicodeLineCanScrollToItsEndWithoutDependingOnAsciiCharacterWidth() {
    // At 360dp these 22 UTF-16 units fit an ASCII-cell estimate but the actual
    // CJK and emoji glyphs exceed the code viewport beside the fixed gutter.
    val text = "你好世界".repeat(5) + "🙂"
    val snapshot =
      SessionDiffSnapshot(
        sessionKey = "unicode-review",
        additions = 1,
        deletions = 0,
        files = listOf(SessionDiffFile("unicode.txt", "added", 1, 0, patch = "@@ -0,0 +1 @@\n+$text\n")),
      )
    val files = prepareSessionDiffFiles(snapshot)
    composeRule.setContent {
      ClawDesignTheme {
        SessionDiffContent(snapshot, files, false, null, SessionDiffScope.All, null, { _, _ -> }, {}, {}, Modifier.fillMaxSize())
      }
    }
    val codeLine = composeRule.onNodeWithText(text, substring = true)
    codeLine.performTouchInput { click() }
    val scroller = composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
    composeRule.waitForIdle()
    val before = scroller.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
    assertTrue("Wide glyphs must expose their overflow instead of clipping permanently", before.maxValue() > 0f)
    assertEquals(0f, before.value(), 0.01f)
    codeLine.performTouchInput { swipeLeft() }
    composeRule.waitForIdle()
    val after = scroller.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
    assertTrue("A horizontal gesture must reveal the rest of the Unicode line", after.value() > 0f)
    assertTrue(after.value() <= after.maxValue())
    codeLine.assertIsDisplayed()
    codeLine.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Line numbers shown"))
    // Hiding gutters widens the code viewport and must clamp any previous pan.
    codeLine.performTouchInput { click() }
    composeRule.waitForIdle()
    val hidden = scroller.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
    assertTrue(hidden.value() <= hidden.maxValue())
    codeLine.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Line numbers hidden"))
  }

  @Test
  fun toolbarAndFileActionsRouteScopeCommitRefreshCopyAndClose() {
    val scope = mutableStateOf(SessionDiffScope.All)
    val commit = mutableStateOf<String?>(null)
    val selections = mutableListOf<Pair<SessionDiffScope, String?>>()
    var refreshes = 0
    var closes = 0
    val snapshot = snapshot()
    val files = prepareSessionDiffFiles(snapshot)
    composeRule.setContent {
      ClawDesignTheme {
        SessionDiffContent(
          snapshot,
          files,
          false,
          null,
          scope.value,
          commit.value,
          { selected, sha ->
            selections += selected to sha
            scope.value = selected
            commit.value = sha
          },
          { refreshes++ },
          { closes++ },
          Modifier.fillMaxSize(),
        )
      }
    }
    composeRule.onNodeWithText("All changes").performClick()
    composeRule.onNodeWithText("Uncommitted").performClick()
    composeRule.onNodeWithText("Uncommitted").performClick()
    composeRule.onNodeWithText("abc12345 Tune retry count").performClick()
    composeRule.onNodeWithContentDescription("Refresh changes").performClick()
    val clipboard = requireNotNull(RuntimeEnvironment.getApplication().getSystemService(ClipboardManager::class.java))
    val previousClip = clipboard.primaryClip
    try {
      composeRule.onAllNodesWithContentDescription("Copy patch")[0].performClick()
      composeRule.onNodeWithContentDescription("Close review").performClick()
      composeRule.runOnIdle {
        assertEquals(listOf(SessionDiffScope.Uncommitted to null, SessionDiffScope.Commit to "abc12345def67890"), selections)
        assertEquals(1, refreshes)
        assertEquals(1, closes)
        assertEquals(
          snapshot.files.first().patch,
          clipboard.primaryClip
            ?.getItemAt(0)
            ?.text
            ?.toString(),
        )
      }
    } finally {
      if (previousClip == null) clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(previousClip)
    }
  }

  @Test
  fun loadingAndFailureDoNotMasqueradeAsNoChanges() {
    val loading = mutableStateOf(true)
    val error = mutableStateOf<String?>(null)
    var retries = 0
    composeRule.setContent {
      ClawDesignTheme {
        SessionDiffContent(
          null,
          emptyList(),
          loading.value,
          error.value,
          SessionDiffScope.All,
          null,
          { _, _ -> },
          { retries++ },
          {},
          Modifier.fillMaxSize(),
        )
      }
    }
    composeRule.onNodeWithContentDescription("Refresh changes").assertIsNotEnabled()
    composeRule.onNodeWithText("No changes in this snapshot.").assertDoesNotExist()
    composeRule.runOnIdle {
      loading.value = false
      error.value = "Connection changed. Reopen review."
    }
    composeRule.onNodeWithText("Connection changed. Reopen review.").assertIsDisplayed()
    composeRule.onNodeWithText("No changes in this snapshot.").assertDoesNotExist()
    composeRule.onNodeWithText("Try again").performClick()
    composeRule.runOnIdle { assertEquals(1, retries) }
  }

  private fun capture(file: File) {
    file.outputStream().use { stream ->
      assertTrue(
        composeRule
          .onRoot()
          .captureToImage()
          .asAndroidBitmap()
          .compress(Bitmap.CompressFormat.PNG, 100, stream),
      )
    }
  }

  private fun snapshot() =
    SessionDiffSnapshot(
      sessionKey = "synthetic-review",
      branch = "feature/retries",
      baseRef = "main",
      additions = 2,
      deletions = 1,
      commits = listOf(SessionDiffCommit("abc12345def67890", "Tune retry count")),
      files =
        listOf(
          SessionDiffFile(
            "src/retry.ts",
            "modified",
            1,
            1,
            patch = "@@ -8,3 +8,3 @@\n-const retries = 1;\n+const retries = 3;\n export { retries };\n // 你好世界 · ready 🙂\n",
          ),
          SessionDiffFile("assets/logo.png", "renamed", 0, 0, oldPath = "assets/mark.png", binary = true),
          SessionDiffFile("generated/index.ts", "added", 1, 0, patch = "@@ -0,0 +1 @@\n+export const ready = true;\n", truncated = true),
        ),
    )
}

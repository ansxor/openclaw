package ai.openclaw.app.ui.chat

import ai.openclaw.app.chat.SessionDiffFile
import ai.openclaw.app.chat.parseSessionDiffPatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SessionDiffSelectionTest {
  @Test
  fun referencesStayOnTheirOriginalSideAndNeverBridgeMissingHunks() {
    val file = SessionDiffFile("new/name.cpp", "renamed", 2, 1, oldPath = "old/name.cpp")
    val view = SessionDiffFileView(file, parseSessionDiffPatch("@@ -71,2 +81,3 @@\n-\tbefore();\n+\tafter();\n+extra();\n shared();\n@@ -200 +300 @@\n later();\n"))
    val before = requireNotNull(SessionDiffSelection.start(view, 1)).extend(view.lines.lastIndex)
    assertEquals("old/name.cpp:71-72", before.reference)
    assertEquals("\tbefore();\nshared();", before.text)
    assertFalse(before.contains(2))
    assertFalse(before.contains(view.lines.lastIndex))
    val after = requireNotNull(SessionDiffSelection.start(view, 4)).extend(0)
    assertEquals("new/name.cpp:81-83", after.reference)
    assertEquals("\tafter();\nextra();\nshared();", after.text)
    assertFalse(after.contains(1))
    assertNull(SessionDiffSelection.start(view, 0))
    val clamped = after.moveEdge(view.lines.lastIndex, start = true)
    assertEquals("new/name.cpp:83-83", clamped.reference)
    assertEquals("new/name.cpp:83-83", clamped.moveEdge(0, start = false).reference)
    assertEquals("new/name.cpp:81-83", clamped.moveEdge(0, start = true).reference)
  }
}

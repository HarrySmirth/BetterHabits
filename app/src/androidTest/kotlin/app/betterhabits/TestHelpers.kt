package app.betterhabits

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput

/** Waits for a node with exactly [text] (the UI updates asynchronously from fake repositories). */
fun ComposeTestRule.onNodeWithTextEventually(text: String, substring: Boolean = false, timeoutMs: Long = 5_000): SemanticsNodeInteraction {
    waitUntil(timeoutMs) { onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty() }
    return onAllNodes(hasText(text, substring = substring))[0]
}

fun ComposeTestRule.typeInto(tag: String, text: String) {
    waitUntil(5_000) { onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
    onNodeWithTag(tag).performTextInput(text)
}

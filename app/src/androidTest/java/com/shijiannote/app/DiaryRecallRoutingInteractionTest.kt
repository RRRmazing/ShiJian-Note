package com.shijiannote.app

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class DiaryRecallRoutingInteractionTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun notificationOpensRecallAgainAfterActivityRecreation() {
        rule.waitUntil(5_000) { rule.onAllNodesWithText("设置").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("设置").performClick()
        openRecall()
        rule.onNodeWithText("往年今日").assertExists()
        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNodeWithText("设置").performClick()
        rule.activityRule.scenario.recreate()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("使用偏好").fetchSemanticsNodes().isNotEmpty() }
        openRecall()
        rule.onNodeWithText("往年今日").assertExists()
        rule.onNodeWithText("使用偏好").assertDoesNotExist()
    }

    private fun openRecall() {
        rule.runOnUiThread {
            InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(rule.activity,
                // Retain ActivityScenario's launch tracker so it can observe the recreated Activity.
                Intent(rule.activity.intent).setAction(DiaryRecallReminder.ACTION_OPEN_RECALL))
        }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("往年今日").fetchSemanticsNodes().isNotEmpty() }
    }
}

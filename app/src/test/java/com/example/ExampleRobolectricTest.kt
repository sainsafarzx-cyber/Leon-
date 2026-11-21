package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.ai.AliAiBrain
import com.example.ai.LocalCommandRouter
import com.example.data.ActionType
import com.example.data.AssistantLanguage
import com.example.data.CapturedNotification
import com.example.data.DailyTask
import com.example.data.DeviceAction
import com.example.data.DeviceTelemetry
import androidx.room.Room
import com.example.data.LeonDatabase
import com.example.data.LeonRepository
import com.example.data.MemoryCategory
import com.example.data.MemoryItem
import com.example.media.LeonMusicEngine
import com.example.sensors.SixthSenseEngine
import com.example.service.LockScreenPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `read app name and wake greeting from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("LEON AI", context.getString(R.string.app_name))
        assertEquals(
            "जी बॉस! बताइए, मैं आपकी क्या मदद कर सकता हूँ?",
            context.getString(R.string.default_greeting_hi)
        )
    }

    @Test
    fun `main activity launches and renders hud and navigation tabs`() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("voice_orb_button").assertIsDisplayed()
        composeRule.onNodeWithTag("command_input").assertIsDisplayed()
        composeRule.onNodeWithTag("hud_always_ready_toggle").assertIsDisplayed()
        composeRule.onNodeWithTag("emergency_stop_button").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_control").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("LEON CONTROL DECK").assertIsDisplayed()
        composeRule.onNodeWithText("SMARTPHONE CONTROLS").assertIsDisplayed()
        composeRule.onNodeWithTag("music_toggle_button").assertIsDisplayed()
        composeRule.onNodeWithTag("music_next_button").assertIsDisplayed()
        composeRule.onNodeWithTag("internet_toggle_button").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_radar").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("sixth_sense_title").assertIsDisplayed()
        composeRule.onNodeWithTag("sixth_sense_scan_button").assertIsDisplayed()
        composeRule.onNodeWithTag("sixth_sense_list").performScrollToIndex(4)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("morse_transmit_button").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_memory").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("ALI MEMORY & FOREVER IDENTITY").assertIsDisplayed()
        composeRule.onNodeWithTag("owner_identity_card").assertIsDisplayed()
        composeRule.onNodeWithTag("save_owner_button").assertIsDisplayed()
        composeRule.onNodeWithTag("memory_list").performScrollToIndex(2)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("always_ready_switch").assertIsDisplayed()
        composeRule.onNodeWithTag("lock_screen_mode_switch").assertIsDisplayed()
        composeRule.onNodeWithTag("lock_screen_briefing_button").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_security").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("SECURITY & PERMISSIONS VAULT").assertIsDisplayed()
        composeRule.onNodeWithText("Zero-Bypass Security Guarantee").assertIsDisplayed()
        composeRule.onNodeWithText("Halt All").assertIsDisplayed()
    }

    @Test
    fun `submitting Hey Leon renders default Hindi boss greeting`() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("command_input").performTextInput("Hey Leon")
        composeRule.onNodeWithTag("send_command_button").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("जी बॉस! बताइए, मैं आपकी क्या मदद कर सकता हूँ?")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("जी बॉस! बताइए, मैं आपकी क्या मदद कर सकता हूँ?")
            .assertIsDisplayed()
    }

    @Test
    fun `ali structured parser handles null fields and enforces sensitive action confirmation`() {
        val brain = AliAiBrain()
        val raw = """
            ```json
            {
              "replyText": "जी बॉस, राहुल को WhatsApp मैसेज भेजने के लिए कन्फर्म करें।",
              "spokenLanguage": "hi",
              "actions": [
                {
                  "type": "SEND_WHATSAPP",
                  "target": "Rahul",
                  "payload": "Namaste!",
                  "requiresConfirmation": false,
                  "confirmationReason": null
                },
                {
                  "type": "TOGGLE_FLASHLIGHT",
                  "target": null,
                  "payload": "on",
                  "requiresConfirmation": false
                }
              ],
              "memoryProposal": null,
              "suggestions": ["Flashlight off करो", null]
            }
            ```
        """.trimIndent()

        val result = brain.parseStructuredResult(raw, AssistantLanguage.HINDI)
        assertEquals(AssistantLanguage.HINDI, result.spokenLanguage)
        assertEquals(2, result.actions.size)
        assertEquals(ActionType.SEND_WHATSAPP, result.actions[0].type)
        assertTrue(result.actions[0].requiresConfirmation)
        assertEquals("", result.actions[0].confirmationReason)
        assertEquals("", result.actions[1].target)
        assertEquals("on", result.actions[1].payload)
        assertNull(result.memoryProposal)
        assertEquals(listOf("Flashlight off करो"), result.suggestions)
    }

    @Test
    fun `ali error classifier preserves nested cause chain and error type`() {
        val dnsError = RuntimeException(
            "Request failed",
            java.net.UnknownHostException("Unable to resolve host firebasevertexai.googleapis.com")
        )
        val dnsDesc = AliAiBrain.describeError(dnsError)
        assertTrue(dnsDesc.contains("Internet / DNS unavailable"))
        assertTrue(dnsDesc.contains("Unable to resolve host"))

        val appCheckError = RuntimeException("HTTP 403 App Check token rejected")
        val appCheckDesc = AliAiBrain.describeError(appCheckError)
        assertTrue(appCheckDesc.contains("403"))
    }

    @Test
    fun `lock screen policy allows safe hands-free actions and gates sensitive ones`() {
        assertTrue(
            LockScreenPolicy.evaluate(DeviceAction(ActionType.SPEAK_ONLY), isDeviceLocked = true).allowed
        )
        assertTrue(
            LockScreenPolicy.evaluate(DeviceAction(ActionType.TOGGLE_FLASHLIGHT, payload = "on"), isDeviceLocked = true).allowed
        )
        assertTrue(
            LockScreenPolicy.evaluate(DeviceAction(ActionType.SET_VOLUME, payload = "70"), isDeviceLocked = true).allowed
        )
        assertTrue(
            LockScreenPolicy.evaluate(DeviceAction(ActionType.READ_NOTIFICATIONS), isDeviceLocked = true).allowed
        )
        assertTrue(
            LockScreenPolicy.evaluate(DeviceAction(ActionType.CREATE_TASK, target = "Drink water"), isDeviceLocked = true).allowed
        )
        assertTrue(
            LockScreenPolicy.evaluate(
                DeviceAction(ActionType.SET_BRIGHTNESS, payload = "50"),
                isDeviceLocked = true,
                canWriteSettings = true
            ).allowed
        )
        assertFalse(
            LockScreenPolicy.evaluate(
                DeviceAction(ActionType.SET_BRIGHTNESS, payload = "50"),
                isDeviceLocked = true,
                canWriteSettings = false
            ).allowed
        )

        val callDecision = LockScreenPolicy.evaluate(
            DeviceAction(ActionType.MAKE_CALL, target = "Mom", requiresConfirmation = true),
            isDeviceLocked = true
        )
        assertFalse(callDecision.allowed)
        assertTrue(callDecision.reasonHindi.contains("अनलॉक"))

        val whatsappDecision = LockScreenPolicy.evaluate(
            DeviceAction(ActionType.SEND_WHATSAPP, target = "Rahul", payload = "Hi", requiresConfirmation = true),
            isDeviceLocked = true
        )
        assertFalse(whatsappDecision.allowed)

        // When unlocked, all actions are permitted through lock screen guard
        assertTrue(
            LockScreenPolicy.evaluate(
                DeviceAction(ActionType.MAKE_CALL, target = "Mom", requiresConfirmation = true),
                isDeviceLocked = false
            ).allowed
        )
    }

    @Test
    fun `lock screen briefing summarizes battery notifications and pending tasks`() {
        val briefing = LockScreenPolicy.buildLockScreenBriefing(
            telemetry = DeviceTelemetry(batteryPercent = 82, charging = false),
            notifications = listOf(
                CapturedNotification(
                    key = "n1",
                    appName = "WhatsApp",
                    packageName = "com.whatsapp",
                    title = "Aman",
                    content = "Meeting 5 baje hai",
                    timestampMillis = 1000L
                )
            ),
            tasks = listOf(
                DailyTask(id = 1L, title = "Call client", completed = false),
                DailyTask(id = 2L, title = "Done task", completed = true)
            ),
            isDeviceLocked = true
        )
        assertTrue(briefing.contains("82"))
        assertTrue(briefing.contains("WhatsApp"))
        assertTrue(briefing.contains("1 पेंडिंग टास्क"))
        assertTrue(briefing.contains("Call client"))
    }

    @Test
    fun `lock screen policy allows music playback and background timers while locked`() {
        assertTrue(
            LockScreenPolicy.evaluate(
                DeviceAction(ActionType.PLAY_MUSIC, payload = "play"),
                isDeviceLocked = true
            ).allowed
        )
        assertTrue(
            LockScreenPolicy.evaluate(
                DeviceAction(ActionType.SET_TIMER, target = "Focus", payload = "300"),
                isDeviceLocked = true
            ).allowed
        )
        assertTrue(
            LockScreenPolicy.evaluate(
                DeviceAction(ActionType.LOCK_SCREEN_BRIEFING),
                isDeviceLocked = true
            ).allowed
        )
    }

    @Test
    fun `local command router handles music torch compound commands and offline fallbacks instantly`() {
        val telemetry = DeviceTelemetry(batteryPercent = 76, charging = true)

        val playMusic = LocalCommandRouter.tryFastPath(
            rawPrompt = "गाना बजाओ",
            defaultLanguage = AssistantLanguage.HINDI,
            telemetry = telemetry,
            notifications = emptyList(),
            tasks = emptyList(),
            isDeviceLocked = true
        )
        assertEquals(ActionType.PLAY_MUSIC, playMusic?.actions?.firstOrNull()?.type)
        assertEquals("play", playMusic?.actions?.firstOrNull()?.payload)

        val pauseMusic = LocalCommandRouter.tryFastPath(
            rawPrompt = "Pause music",
            defaultLanguage = AssistantLanguage.ENGLISH,
            telemetry = telemetry,
            notifications = emptyList(),
            tasks = emptyList(),
            isDeviceLocked = true
        )
        assertEquals(ActionType.PLAY_MUSIC, pauseMusic?.actions?.firstOrNull()?.type)
        assertEquals("pause", pauseMusic?.actions?.firstOrNull()?.payload)

        val compound = LocalCommandRouter.tryFastPath(
            rawPrompt = "Flashlight on करो और YouTube खोलो",
            defaultLanguage = AssistantLanguage.HINDI,
            telemetry = telemetry,
            notifications = emptyList(),
            tasks = emptyList(),
            isDeviceLocked = false
        )
        assertEquals(2, compound?.actions?.size)
        assertEquals(ActionType.TOGGLE_FLASHLIGHT, compound?.actions?.get(0)?.type)
        assertEquals(ActionType.OPEN_APP, compound?.actions?.get(1)?.type)

        val timer = LocalCommandRouter.tryFastPath(
            rawPrompt = "5 मिनट का टाइमर लगाओ",
            defaultLanguage = AssistantLanguage.HINDI,
            telemetry = telemetry,
            notifications = emptyList(),
            tasks = emptyList(),
            isDeviceLocked = true
        )
        assertEquals(ActionType.SET_TIMER, timer?.actions?.firstOrNull()?.type)
        assertEquals("300", timer?.actions?.firstOrNull()?.payload)

        val offlineYt = LocalCommandRouter.tryFallback(
            rawPrompt = "YouTube पर लियोन सॉन्ग सर्च करो",
            defaultLanguage = AssistantLanguage.HINDI,
            telemetry = telemetry,
            notifications = emptyList(),
            tasks = emptyList(),
            isDeviceLocked = false
        )
        assertEquals(ActionType.YOUTUBE_SEARCH, offlineYt?.actions?.firstOrNull()?.type)

        // General open-ended Q&A should still defer to ALI AI cloud brain
        val generalQuestion = LocalCommandRouter.tryFastPath(
            rawPrompt = "भारत का इतिहास संक्षेप में समझाओ",
            defaultLanguage = AssistantLanguage.HINDI,
            telemetry = telemetry,
            notifications = emptyList(),
            tasks = emptyList(),
            isDeviceLocked = false
        )
        assertNull(generalQuestion)
    }

    @Test
    fun `leon music engine toggles playback and switches tracks cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        LeonMusicEngine.setTtsSpeaking(true)
        val startMsg = LeonMusicEngine.handleAction(context, "calm", "play")
        LeonMusicEngine.setTtsSpeaking(false)
        assertTrue(LeonMusicEngine.isPlaying())
        assertTrue(startMsg.contains("Playing"))

        val nextMsg = LeonMusicEngine.handleAction(context, "", "next")
        assertTrue(LeonMusicEngine.isPlaying())
        assertTrue(nextMsg.contains("Switched"))

        val stopMsg = LeonMusicEngine.handleAction(context, "", "stop")
        assertFalse(LeonMusicEngine.isPlaying())
        assertTrue(stopMsg.contains("stopped"))
    }

    @Test
    fun `local command router handles Hindi flashlight wifi settings brightness and screen off music`() {
        val telemetry = DeviceTelemetry(
            batteryPercent = 80,
            brightnessPercent = 50,
            writeSettingsGranted = true
        )

        val torchOn = LocalCommandRouter.tryFastPath(
            rawPrompt = "फ्लैशलाइट ऑन करो",
            defaultLanguage = AssistantLanguage.HINDI,
            telemetry = telemetry,
            notifications = emptyList(),
            tasks = emptyList(),
            isDeviceLocked = false
        )
        assertEquals(ActionType.TOGGLE_FLASHLIGHT, torchOn?.actions?.firstOrNull()?.type)
        assertEquals("on", torchOn?.actions?.firstOrNull()?.payload)

        val torchOpenSpaced = LocalCommandRouter.tryFastPath(
            rawPrompt = "फ्लैश लाइट ओपन करो",
            defaultLanguage = AssistantLanguage.HINDI,
            telemetry = telemetry,
            notifications = emptyList(),
            tasks = emptyList(),
            isDeviceLocked = false
        )
        assertEquals(ActionType.TOGGLE_FLASHLIGHT, torchOpenSpaced?.actions?.firstOrNull()?.type)
        assertEquals("on", torchOpenSpaced?.actions?.firstOrNull()?.payload)

        val torchOff = LocalCommandRouter.tryFastPath(
            rawPrompt = "फ्लैशलाइट बंद करो",
            defaultLanguage = AssistantLanguage.HINDI,
            telemetry = telemetry,
            notifications = emptyList(),
            tasks = emptyList(),
            isDeviceLocked = false
        )
        assertEquals(ActionType.TOGGLE_FLASHLIGHT, torchOff?.actions?.firstOrNull()?.type)
        assertEquals("off", torchOff?.actions?.firstOrNull()?.payload)

        val wifiInSettings = LocalCommandRouter.tryFastPath(
            rawPrompt = "सेटिंग में वाई-फाई खोलो",
            defaultLanguage = AssistantLanguage.HINDI,
            telemetry = telemetry,
            notifications = emptyList(),
            tasks = emptyList(),
            isDeviceLocked = false
        )
        assertEquals(ActionType.OPEN_WIFI_PANEL, wifiInSettings?.actions?.firstOrNull()?.type)

        val wifiSpaced = LocalCommandRouter.tryFastPath(
            rawPrompt = "वाई फाई ओपन करो",
            defaultLanguage = AssistantLanguage.HINDI,
            telemetry = telemetry,
            notifications = emptyList(),
            tasks = emptyList(),
            isDeviceLocked = false
        )
        assertEquals(ActionType.OPEN_WIFI_PANEL, wifiSpaced?.actions?.firstOrNull()?.type)

        val brightnessDown = LocalCommandRouter.tryFastPath(
            rawPrompt = "लाइट कम करो",
            defaultLanguage = AssistantLanguage.HINDI,
            telemetry = telemetry,
            notifications = emptyList(),
            tasks = emptyList(),
            isDeviceLocked = false
        )
        assertEquals(ActionType.SET_BRIGHTNESS, brightnessDown?.actions?.firstOrNull()?.type)
        assertEquals("down", brightnessDown?.actions?.firstOrNull()?.payload)

        val brightnessUp = LocalCommandRouter.tryFastPath(
            rawPrompt = "लाइट ज्यादा करो",
            defaultLanguage = AssistantLanguage.HINDI,
            telemetry = telemetry,
            notifications = emptyList(),
            tasks = emptyList(),
            isDeviceLocked = false
        )
        assertEquals(ActionType.SET_BRIGHTNESS, brightnessUp?.actions?.firstOrNull()?.type)
        assertEquals("up", brightnessUp?.actions?.firstOrNull()?.payload)

        val screenOffMusic = LocalCommandRouter.tryFastPath(
            rawPrompt = "स्क्रीन बंद होने के बाद म्यूजिक प्ले करो",
            defaultLanguage = AssistantLanguage.HINDI,
            telemetry = telemetry,
            notifications = emptyList(),
            tasks = emptyList(),
            isDeviceLocked = true
        )
        assertEquals(ActionType.PLAY_MUSIC, screenOffMusic?.actions?.firstOrNull()?.type)
        assertEquals("play", screenOffMusic?.actions?.firstOrNull()?.payload)

        // Neural Sixth Sense commands
        val morseCmd = LocalCommandRouter.tryFastPath(
            rawPrompt = "फ्लैशलाइट से SOS मॉर्स कोड भेजो",
            defaultLanguage = AssistantLanguage.HINDI,
            telemetry = telemetry,
            notifications = emptyList(),
            tasks = emptyList(),
            isDeviceLocked = false
        )
        assertEquals(ActionType.OPTICAL_MORSE, morseCmd?.actions?.firstOrNull()?.type)
        assertEquals("SOS", morseCmd?.actions?.firstOrNull()?.target)

        val airGestureCmd = LocalCommandRouter.tryFastPath(
            rawPrompt = "एयर जेस्चर ऑन करो",
            defaultLanguage = AssistantLanguage.HINDI,
            telemetry = telemetry,
            notifications = emptyList(),
            tasks = emptyList(),
            isDeviceLocked = false
        )
        assertEquals(ActionType.TOGGLE_AIR_GESTURES, airGestureCmd?.actions?.firstOrNull()?.type)
        assertEquals("on", airGestureCmd?.actions?.firstOrNull()?.payload)

        val envScanCmd = LocalCommandRouter.tryFastPath(
            rawPrompt = "आसपास स्कैन करो",
            defaultLanguage = AssistantLanguage.HINDI,
            telemetry = telemetry,
            notifications = emptyList(),
            tasks = emptyList(),
            isDeviceLocked = false
        )
        assertEquals(ActionType.ANALYZE_ENVIRONMENT, envScanCmd?.actions?.firstOrNull()?.type)
    }

    @Test
    fun `optical morse encoder encodes ITU patterns accurately`() {
        assertEquals("··· ——— ···", SixthSenseEngine.encodeToMorse("SOS"))
        assertEquals("·—·· · ——— —· / ·— ··", SixthSenseEngine.encodeToMorse("LEON AI"))
    }

    @Test
    fun `local command router handles one-tell forever memory and owner creator identity even when locked`() {
        val telemetry = DeviceTelemetry(batteryPercent = 90)

        // 1. Tell owner name + download instruction once -> SET_OWNER_IDENTITY
        val setOwner = LocalCommandRouter.tryFastPath(
            rawPrompt = "मेरा नाम राहुल है, कोई ऐप डाउनलोड करे तो मेरा ही नाम बताना",
            defaultLanguage = AssistantLanguage.HINDI,
            telemetry = telemetry,
            notifications = emptyList(),
            tasks = emptyList(),
            isDeviceLocked = true
        )
        assertNotNull(setOwner)
        assertEquals(ActionType.SET_OWNER_IDENTITY, setOwner?.actions?.firstOrNull()?.type)
        assertEquals("राहुल", setOwner?.actions?.firstOrNull()?.target)
        assertTrue(setOwner!!.replyText.contains("राहुल"))
        assertTrue(
            LockScreenPolicy.evaluate(setOwner.actions.first(), isDeviceLocked = true).allowed
        )

        // 2. Ask who created the app / whose name is shown on download
        val askCreator = LocalCommandRouter.tryFastPath(
            rawPrompt = "तुमको किसने बनाया और कोई ऐप डाउनलोड करे तो किसका नाम बताओगे?",
            defaultLanguage = AssistantLanguage.HINDI,
            telemetry = telemetry,
            notifications = emptyList(),
            tasks = emptyList(),
            isDeviceLocked = true,
            ownerName = "राहुल"
        )
        assertNotNull(askCreator)
        assertTrue(askCreator!!.replyText.contains("राहुल"))

        // 3. One-tell permanent memory save & lock-screen safety
        val saveFact = LocalCommandRouter.tryFastPath(
            rawPrompt = "हमेशा याद रखो मेरी बाइक का नंबर UP32AB1234 है",
            defaultLanguage = AssistantLanguage.HINDI,
            telemetry = telemetry,
            notifications = emptyList(),
            tasks = emptyList(),
            isDeviceLocked = true,
            ownerName = "राहुल"
        )
        assertNotNull(saveFact)
        val saveAction = saveFact!!.actions.first()
        assertEquals(ActionType.SAVE_MEMORY, saveAction.type)
        assertTrue(saveAction.payload.contains("UP32AB1234"))
        assertTrue(LockScreenPolicy.evaluate(saveAction, isDeviceLocked = true).allowed)

        // 4. Offline memory recall
        val savedMemories = listOf(
            MemoryItem(
                id = 1L,
                memoryKey = "मेरी बाइक का नंबर",
                memoryValue = "मेरी बाइक का नंबर UP32AB1234 है",
                category = MemoryCategory.CONTACT.wireValue,
                isPermanent = true
            )
        )
        val recallAll = LocalCommandRouter.tryFastPath(
            rawPrompt = "मेरी यादें बताओ",
            defaultLanguage = AssistantLanguage.HINDI,
            telemetry = telemetry,
            notifications = emptyList(),
            tasks = emptyList(),
            isDeviceLocked = true,
            ownerName = "राहुल",
            memories = savedMemories
        )
        assertNotNull(recallAll)
        assertTrue(recallAll!!.replyText.contains("UP32AB1234"))

        val recallTopic = LocalCommandRouter.tryFallback(
            rawPrompt = "मेरी बाइक का नंबर क्या है?",
            defaultLanguage = AssistantLanguage.HINDI,
            telemetry = telemetry,
            notifications = emptyList(),
            tasks = emptyList(),
            isDeviceLocked = true,
            ownerName = "राहुल",
            memories = savedMemories
        )
        assertNotNull(recallTopic)
        assertTrue(recallTopic!!.replyText.contains("UP32AB1234"))

        // 5. Personalized wake greeting uses saved owner name
        val ownerGreet = LocalCommandRouter.tryFastPath(
            rawPrompt = "Hey Leon",
            defaultLanguage = AssistantLanguage.HINDI,
            telemetry = telemetry,
            notifications = emptyList(),
            tasks = emptyList(),
            isDeviceLocked = true,
            ownerName = "राहुल"
        )
        assertTrue(ownerGreet!!.replyText.contains("राहुल बॉस"))
    }

    @Test
    fun `repository upserts permanent memories and persists owner identity without duplicates`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LeonDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = LeonRepository(db, null)
            repo.ensureDefaultPreferences()

            val ownerRes = repo.setOwnerIdentity("अली मालिक", "Built with Love")
            assertTrue(ownerRes.isSuccess)
            assertEquals("अली मालिक", repo.getPreferencesOnce().ownerName)
            assertEquals("Built with Love", repo.getPreferencesOnce().creatorTagline)

            // Updating owner identity should update existing Owner & App Creator memory row, not duplicate
            repo.setOwnerIdentity("अली बॉस", "Creator Edition")
            val firstSave = repo.saveMemory("WiFi Password", "Leon@2026", MemoryCategory.FACT, isPermanent = true)
            assertTrue(firstSave.isSuccess)
            val secondSave = repo.saveMemory("wifi password", "Leon@2027", MemoryCategory.FACT, isPermanent = true)
            assertTrue(secondSave.isSuccess)

            val allMemories = repo.getMemoriesOnce()
            val ownerRows = allMemories.filter { it.memoryKey == LeonRepository.OWNER_IDENTITY_MEMORY_KEY }
            val wifiRows = allMemories.filter { it.memoryKey.equals("wifi password", ignoreCase = true) }

            assertEquals(1, ownerRows.size)
            assertTrue(ownerRows.first().memoryValue.contains("अली बॉस"))
            assertEquals(1, wifiRows.size)
            assertEquals("Leon@2027", wifiRows.first().memoryValue)
            assertTrue(wifiRows.first().isPermanent)
        } finally {
            db.close()
        }
    }

    @Test
    fun `notification icon resource is valid drawable and offline intro works immediately`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val iconDrawable = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_leon_notification)
        assertNotNull(iconDrawable)

        val intro = LocalCommandRouter.tryFastPath(
            rawPrompt = "तुम कौन हो और क्या कर सकते हो?",
            defaultLanguage = AssistantLanguage.HINDI,
            telemetry = DeviceTelemetry(batteryPercent = 85),
            notifications = emptyList(),
            tasks = emptyList(),
            isDeviceLocked = false,
            ownerName = "अली"
        )
        assertNotNull(intro)
        assertTrue(intro!!.replyText.contains("LEON AI"))
        assertTrue(intro.replyText.contains("अली"))
    }

    @Test
    fun `live news engine parses rss xml and builds hindi spoken digest`() {
        val sampleXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
              <channel>
                <title>Top stories - Google News</title>
                <item>
                  <title>भारत ने जीता शानदार क्रिकेट मैच - Amar Ujala</title>
                  <link>https://news.example.com/cricket</link>
                  <pubDate>Fri, 20 Nov 2026 06:00:00 GMT</pubDate>
                  <source url="https://www.amarujala.com">Amar Ujala</source>
                </item>
                <item>
                  <title>AI टेक्नोलॉजी में नया रिकॉर्ड - Dainik Bhaskar</title>
                  <link>https://news.example.com/ai</link>
                  <pubDate>Fri, 20 Nov 2026 05:15:00 GMT</pubDate>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val parsed = com.example.news.LiveNewsEngine.parseRssXml(sampleXml.byteInputStream())
        assertEquals(2, parsed.size)
        assertEquals("भारत ने जीता शानदार क्रिकेट मैच", parsed[0].title)
        assertEquals("Amar Ujala", parsed[0].source)
        assertEquals("https://news.example.com/cricket", parsed[0].link)
        assertEquals("Dainik Bhaskar", parsed[1].source)

        val digest = com.example.news.LiveNewsEngine.buildSpokenDigest(
            articles = parsed,
            category = com.example.news.NewsCategory.TOP,
            ownerName = "राहुल"
        )
        assertTrue(digest.contains("राहुल बॉस"))
        assertTrue(digest.contains("खबर 1"))
        assertTrue(digest.contains("भारत ने जीता"))
    }

    @Test
    fun `leon speaker romanizes hindi cleanly for english fallback tts and routes news math date locally`() {
        val romanized = com.example.audio.LeonSpeaker.romanizeDevanagariForEnglishTts("जी बॉस! फ्लैशलाइट चालू है।")
        assertTrue(romanized.contains("Boss"))
        assertTrue(romanized.contains("Flashlight"))
        assertFalse(com.example.audio.LeonSpeaker.containsDevanagari(romanized))

        val telemetry = DeviceTelemetry(batteryPercent = 88)

        val topNews = LocalCommandRouter.tryFastPath(
            rawPrompt = "ताज़ा न्यूज़ सुनाओ",
            defaultLanguage = AssistantLanguage.HINDI,
            telemetry = telemetry,
            notifications = emptyList(),
            tasks = emptyList(),
            isDeviceLocked = true
        )
        assertEquals(ActionType.FETCH_NEWS, topNews?.actions?.firstOrNull()?.type)
        assertEquals("top", topNews?.actions?.firstOrNull()?.target)
        assertTrue(LockScreenPolicy.evaluate(topNews!!.actions.first(), isDeviceLocked = true).allowed)

        val techNews = LocalCommandRouter.tryFastPath(
            rawPrompt = "tech news",
            defaultLanguage = AssistantLanguage.ENGLISH,
            telemetry = telemetry,
            notifications = emptyList(),
            tasks = emptyList(),
            isDeviceLocked = false
        )
        assertEquals(ActionType.FETCH_NEWS, techNews?.actions?.firstOrNull()?.type)
        assertEquals("tech", techNews?.actions?.firstOrNull()?.target)

        val mathRes = LocalCommandRouter.tryFastPath(
            rawPrompt = "25 + 15",
            defaultLanguage = AssistantLanguage.HINDI,
            telemetry = telemetry,
            notifications = emptyList(),
            tasks = emptyList(),
            isDeviceLocked = false
        )
        assertTrue(mathRes!!.replyText.contains("40"))

        val dateRes = LocalCommandRouter.tryFastPath(
            rawPrompt = "आज की तारीख क्या है?",
            defaultLanguage = AssistantLanguage.HINDI,
            telemetry = telemetry,
            notifications = emptyList(),
            tasks = emptyList(),
            isDeviceLocked = false
        )
        assertNotNull(dateRes)
        assertEquals(ActionType.SPEAK_ONLY, dateRes?.actions?.firstOrNull()?.type)
    }

    @Test
    fun `hud voice toggle and live news hub navigation work cleanly without blocking`() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("hud_voice_toggle").performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("hud_internet_toggle").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("hud_news_button").performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("news_screen_title").assertIsDisplayed()
        composeRule.onNodeWithTag("news_read_aloud_button").assertIsDisplayed()
        composeRule.onNodeWithTag("news_refresh_button").assertIsDisplayed()
        composeRule.onNodeWithTag("news_back_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("voice_orb_button").assertIsDisplayed()
    }

    @Test
    fun `local command router routes internet and mobile data on off commands accurately`() {
        val telemetry = DeviceTelemetry(batteryPercent = 85, internetConnected = true)

        val netOff = LocalCommandRouter.tryFastPath(
            rawPrompt = "इंटरनेट ऑफ करो",
            defaultLanguage = AssistantLanguage.HINDI,
            telemetry = telemetry,
            notifications = emptyList(),
            tasks = emptyList(),
            isDeviceLocked = false
        )
        assertEquals(ActionType.TOGGLE_INTERNET, netOff?.actions?.firstOrNull()?.type)
        assertEquals("off", netOff?.actions?.firstOrNull()?.payload)

        val netOn = LocalCommandRouter.tryFastPath(
            rawPrompt = "internet on karo",
            defaultLanguage = AssistantLanguage.HINGLISH,
            telemetry = telemetry.copy(internetConnected = false),
            notifications = emptyList(),
            tasks = emptyList(),
            isDeviceLocked = false
        )
        assertEquals(ActionType.TOGGLE_INTERNET, netOn?.actions?.firstOrNull()?.type)
        assertEquals("on", netOn?.actions?.firstOrNull()?.payload)
    }

    @Test
    fun `leon speaker builds media stream params and coordinates foreground mic bridge safely`() {
        val params = com.example.audio.LeonSpeaker.buildSpeakParams()
        assertEquals(
            android.media.AudioManager.STREAM_MUSIC,
            params.getInt(android.speech.tts.TextToSpeech.Engine.KEY_PARAM_STREAM)
        )
        assertEquals(
            1.0f,
            params.getFloat(android.speech.tts.TextToSpeech.Engine.KEY_PARAM_VOLUME),
            0.01f
        )

        val cleaned = com.example.audio.LeonSpeaker.cleanForSpeech("**जी बॉस!** #समाचार तैयार हैं।")
        assertFalse(cleaned.contains("*"))
        assertFalse(cleaned.contains("#"))
        assertTrue(cleaned.contains("जी बॉस!"))

        com.example.service.VoiceServiceBridge.setForegroundMicActive(true)
        assertTrue(com.example.service.VoiceServiceBridge.foregroundMicActive.value)
        com.example.service.VoiceServiceBridge.setForegroundMicActive(false)
        assertFalse(com.example.service.VoiceServiceBridge.foregroundMicActive.value)
    }

    @Test
    fun `offline vision interpreter recognizes objects reads text understands context and routes camera command`() {
        val analysis = com.example.vision.OfflineVisionInterpreter.interpret(
            rawLabels = listOf("Laptop" to 0.92f, "Book" to 0.81f),
            recognizedText = "MRP ₹499 Study Guide",
            rawBarcodes = listOf("upi://pay?pa=shop@upi&pn=BookStore"),
            frameWidth = 1080,
            frameHeight = 1920
        )
        assertEquals(2, analysis.labels.size)
        assertEquals("लैपटॉप", analysis.labels[0].hindiName)
        assertTrue(analysis.summaryHindi.contains("लैपटॉप"))
        assertTrue(analysis.summaryHindi.contains("UPI"))
        assertTrue(analysis.contextualInsightHindi.contains("प्राइस टैग"))
        val spoken = analysis.buildSpokenSummary("राहुल")
        assertTrue(spoken.contains("राहुल बॉस"))

        val visionRoute = LocalCommandRouter.tryFastPath(
            rawPrompt = "कैमरा से देखकर चीज पहचानो",
            defaultLanguage = AssistantLanguage.HINDI,
            telemetry = DeviceTelemetry(batteryPercent = 85),
            notifications = emptyList(),
            tasks = emptyList(),
            isDeviceLocked = false
        )
        assertEquals(ActionType.OPEN_OFFLINE_VISION, visionRoute?.actions?.firstOrNull()?.type)
    }

    @Test
    fun `hud eye ai button opens offline vision screen and navigates back cleanly`() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("hud_offline_vision_button").performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("vision_screen_title").assertIsDisplayed()
        composeRule.onNodeWithTag("vision_speak_button").assertIsDisplayed()
        composeRule.onNodeWithTag("vision_back_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("voice_orb_button").assertIsDisplayed()
    }
}

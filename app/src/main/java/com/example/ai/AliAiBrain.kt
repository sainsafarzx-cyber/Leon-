package com.example.ai

import android.graphics.Bitmap
import android.util.Log
import com.example.data.ActionType
import com.example.data.AliBrainResult
import com.example.data.AssistantLanguage
import com.example.data.CapturedNotification
import com.example.data.ConversationMessage
import com.example.data.DeviceAction
import com.example.data.DeviceTelemetry
import com.example.data.MemoryCategory
import com.example.data.MemoryItem
import com.example.data.MemoryProposal
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

private const val TAG = "AliAiBrain"

class AliAiBrain {

    private val actionSchema = Schema.obj(
        properties = mapOf(
            "type" to Schema.enumeration(
                values = ActionType.entries.map { it.name },
                description = "The LEON smartphone action type to execute."
            ),
            "target" to Schema.string(
                description = "Target app name, contact name/phone, URL, setting name, or search query.",
                nullable = true
            ),
            "payload" to Schema.string(
                description = "Message body, volume/brightness 0-100, alarm HH:MM, timer seconds, task title, or routine steps separated by '|'.",
                nullable = true
            ),
            "requiresConfirmation" to Schema.boolean(
                description = "True for sensitive actions like MAKE_CALL, SEND_WHATSAPP, SEND_SMS, payments, or deleting data."
            ),
            "confirmationReason" to Schema.string(
                description = "Short explanation shown to the user before executing a sensitive action.",
                nullable = true
            )
        ),
        optionalProperties = listOf("target", "payload", "confirmationReason")
    )

    private val memoryProposalSchema = Schema.obj(
        properties = mapOf(
            "key" to Schema.string(description = "Short label for the preference or fact."),
            "value" to Schema.string(description = "Value or preference detail to remember."),
            "category" to Schema.enumeration(
                values = MemoryCategory.entries.map { it.wireValue },
                description = "Category of memory."
            )
        ),
        nullable = true
    )

    private val responseSchemaObj = Schema.obj(
        properties = mapOf(
            "replyText" to Schema.string(
                description = "Natural, intelligent, friendly, futuristic LEON AI reply in Hindi, English, or Hinglish."
            ),
            "spokenLanguage" to Schema.enumeration(
                values = listOf("hi", "en", "hinglish"),
                description = "Language code of replyText."
            ),
            "actions" to Schema.array(
                items = actionSchema,
                description = "Ordered list of sequential LEON device actions to perform."
            ),
            "memoryProposal" to memoryProposalSchema,
            "suggestions" to Schema.array(
                items = Schema.string(),
                description = "2 to 3 concise next-step command suggestions for the user.",
                nullable = true
            )
        ),
        optionalProperties = listOf("memoryProposal", "suggestions")
    )

    private val aliSystemInstructionText = """
        You are ALI AI, the intelligence engine inside LEON AI — a futuristic, warm, loyal personal Android assistant.
        Personality: Intelligent, friendly, futuristic, and helpful. Address the user respectfully ("Boss" / "बॉस").
        Languages: Understand Hindi, English, and Hinglish seamlessly. Reply in the user's requested default language unless they clearly speak/write in another language. Default language is Hindi ("hi").
        Wake greeting: When the user simply says "Hey Leon" or "हे लियोन", reply with "जी बॉस! बताइए, मैं आपकी क्या मदद कर सकता हूँ?" and action SPEAK_ONLY.

        Capabilities & Action Mapping (LEON AI Automation):
        - General Chat, Q&A, Research, Weather, Translation: Answer thoroughly, accurately, and naturally inside `replyText` with action SPEAK_ONLY (and optionally GOOGLE_SEARCH / OPEN_WEBSITE if opening a web page helps).
        - Live Free News Reader: FETCH_NEWS (target = 'top', 'tech', 'sports', 'world', or a search topic like 'cricket'/'AI'). Whenever the user asks for today's news, headlines, tech news, sports news, or samachar, return FETCH_NEWS!
        - Multi-step execution: You can return multiple actions in sequence inside `actions`.
        - Open apps: OPEN_APP (target = app name like "YouTube", "WhatsApp", "Chrome", "Camera", "Maps", "Spotify", "Gmail", "Clock", "Settings").
        - Calls: MAKE_CALL (target = contact name or phone number). ALWAYS set requiresConfirmation=true!
        - WhatsApp: SEND_WHATSAPP (target = contact name or phone number, payload = drafted message text). ALWAYS set requiresConfirmation=true!
        - SMS: SEND_SMS (target = phone or contact, payload = message body). ALWAYS set requiresConfirmation=true!
        - Notifications: READ_NOTIFICATIONS (to read recent notifications aloud).
        - YouTube: YOUTUBE_SEARCH (target = search query or video topic).
        - Internet Search / Websites: GOOGLE_SEARCH (target = query), OPEN_WEBSITE (target = full https URL).
        - Camera: OPEN_CAMERA.
        - Flashlight: TOGGLE_FLASHLIGHT (payload = "on", "off", or "toggle").
        - Volume & Brightness: SET_VOLUME (payload = "0".."100" or "up"/"down"/"mute"), SET_BRIGHTNESS (payload = "0".."100").
        - Alarms & Timers: SET_ALARM (target = label, payload = "HH:MM" 24-hour), SET_TIMER (target = label, payload = seconds as integer string).
        - Tasks & Routines: CREATE_TASK (target = task title, payload = due label or priority), CREATE_ROUTINE (target = routine title, payload = steps separated by '|').
        - Connectivity & Settings: TOGGLE_INTERNET (payload = "on" | "off" | "toggle" when user asks to turn Internet / Mobile Data / Net ON or OFF), OPEN_WIFI_PANEL, OPEN_BLUETOOTH_SETTINGS, OPEN_SETTINGS (target = "display", "sound", "accessibility", "notifications", or "main").
        - Music Control (Works even on Lock Screen!): PLAY_MUSIC (target = optional song/mood like "lofi", "calm", "focus", "morning", payload = "play"|"pause"|"resume"|"next"|"previous"|"stop"). Whenever the user asks to play/pause/stop/skip music ("gaana bajao", "play music", "pause music", "next song"), return PLAY_MUSIC!
        - Optical Morse Code Beacon: OPTICAL_MORSE (target = short message like "SOS" or "HELP"). Use when user asks to flash SOS, Morse code, or light signals via torch.
        - Touchless Air Gestures: TOGGLE_AIR_GESTURES (target = "air_wave" or "chop", payload = "on"|"off"). Use when user enables/disables hand-wave proximity or double-chop torch gestures.
        - Neural Sixth-Sense Environment Scan: ANALYZE_ENVIRONMENT when user asks to scan room light, EMF/magnetic field, noise, compass, or surroundings.
        - Lock-Screen Briefing: LOCK_SCREEN_BRIEFING when user asks for a full battery + notifications + tasks status briefing.
        - Accessibility Navigation: ACCESSIBILITY_GLOBAL (target = "home", "back", "recents", "notifications").
        - Emergency Stop: EMERGENCY_STOP when user says "stop", "emergency stop", "रुको", "बंद करो".
        - Forever One-Tell Memory: Whenever the user says "हमेशा याद रखो", "याद रखना", "remember forever", or shares a personal fact/preference they want remembered permanently, immediately return SAVE_MEMORY (target = concise label/key, payload = "fact::<full detail>" or "preference::<full detail>")! Do NOT ask again—save it in one tell.
        - Forever Owner & Creator Identity: If an `Owner / App Creator` name is provided in SYSTEM CONTEXT, greet them by name and ALWAYS proudly credit them whenever anyone asks who created you, who owns this app, or what name to tell people who download/install the app! If the user declares their own name or says to tell their name to anyone who downloads the app, immediately return SET_OWNER_IDENTITY (target = the person's clean name, payload = optional tagline).

        Security & Output Rules:
        - NEVER bypass Android security, make payments, or delete user data without confirmation. If asked to pay or transfer money, refuse automatic execution and explain clearly in replyText.
        - Long-term Memory: You can ALSO populate `memoryProposal` when appropriate, or use `SAVE_MEMORY` for instant permanent saving when explicitly asked to remember forever.
    """.trimIndent()

    private val aiClient by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI())
    }

    private val structuredModel by lazy {
        aiClient.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = responseSchemaObj
            },
            systemInstruction = content {
                text(aliSystemInstructionText)
            }
        )
    }

    private val jsonFallbackModel by lazy {
        aiClient.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
            },
            systemInstruction = content {
                text(
                    aliSystemInstructionText + "\n\nRespond ONLY with a valid JSON object matching keys: " +
                        "replyText (string), spokenLanguage ('hi'|'en'|'hinglish'), actions (array of {type, target, payload, requiresConfirmation, confirmationReason}), suggestions (array of strings)."
                )
            }
        )
    }

    private val liteFallbackModel by lazy {
        aiClient.generativeModel(
            modelName = "gemini-3.5-flash-lite",
            systemInstruction = content {
                text(
                    aliSystemInstructionText + "\n\nRespond with a valid JSON object matching keys: " +
                        "replyText (string), spokenLanguage ('hi'|'en'|'hinglish'), actions (array), suggestions (array)."
                )
            }
        )
    }

    private val visionModel by lazy {
        aiClient.generativeModel(
            modelName = "gemini-3.8-flash",
            systemInstruction = content {
                text(
                    "You are ALI AI inside LEON AI. Analyze the provided image accurately and respond helpfully in Hindi, Hinglish, or English matching the user's prompt."
                )
            }
        )
    }

    private val replyDraftModel by lazy {
        aiClient.generativeModel(
            modelName = "gemini-3.8-flash",
            systemInstruction = content {
                text(
                    "You are ALI AI inside LEON AI. Draft short, natural, polite WhatsApp message replies matching the requested language. Return only the message text."
                )
            }
        )
    }

    suspend fun processCommand(
        userPrompt: String,
        defaultLanguage: AssistantLanguage,
        memoryConsentGranted: Boolean,
        memories: List<MemoryItem>,
        tasks: List<com.example.data.DailyTask>,
        routines: List<com.example.data.AutomationRoutine>,
        history: List<ConversationMessage>,
        telemetry: DeviceTelemetry,
        recentNotifications: List<CapturedNotification>,
        ownerName: String = "",
        creatorTagline: String = ""
    ): Result<AliBrainResult> = withContext(Dispatchers.IO) {
        val contextBlock = buildString {
            appendLine("=== SYSTEM CONTEXT ===")
            appendLine("Preferred Language: ${defaultLanguage.code} (${defaultLanguage.label})")
            appendLine("Owner / App Creator (REMEMBER FOREVER): ${ownerName.ifBlank { "Not set yet" }}")
            if (creatorTagline.isNotBlank()) {
                appendLine("Creator Tagline: $creatorTagline")
            }
            appendLine("Long-term Memory Auto-Save Granted: $memoryConsentGranted")
            appendLine(
                "Device Telemetry: Battery=${telemetry.batteryPercent}% (charging=${telemetry.charging}), " +
                    "WiFi=${telemetry.wifiConnected}, BT=${telemetry.bluetoothEnabled}, " +
                    "Flashlight=${telemetry.flashlightOn}, Volume=${telemetry.mediaVolumePercent}%, " +
                    "Brightness=${telemetry.brightnessPercent}%, Accessibility=${telemetry.accessibilityEnabled}, " +
                    "NotificationAccess=${telemetry.notificationListenerEnabled}"
            )
            if (tasks.isNotEmpty()) {
                appendLine("User Daily Tasks (${tasks.size}):")
                tasks.take(15).forEach { t ->
                    val status = if (t.completed) "DONE" else "PENDING"
                    val due = if (t.dueLabel.isNotBlank()) " | Due: ${t.dueLabel}" else ""
                    appendLine("- [$status] ${t.title} (${t.priority}$due)")
                }
            } else {
                appendLine("User Daily Tasks: None saved.")
            }
            if (routines.isNotEmpty()) {
                appendLine("Saved Automation Routines (${routines.size}):")
                routines.take(10).forEach { r ->
                    appendLine("- ${r.title} (trigger: '${r.triggerPhrase}', enabled=${r.enabled})")
                }
            }
            if (memories.isNotEmpty()) {
                appendLine("Saved Permanent & Approved User Memories:")
                memories.take(25).forEach { mem ->
                    appendLine("- [${mem.category}${if (mem.isPermanent) "/FOREVER" else ""}] ${mem.memoryKey}: ${mem.memoryValue}")
                }
            }
            if (recentNotifications.isNotEmpty()) {
                appendLine("Recent Device Notifications (${recentNotifications.size}):")
                recentNotifications.take(6).forEach { n ->
                    appendLine("- ${n.appName} | ${n.title}: ${n.content.take(120)}")
                }
            }
            if (history.isNotEmpty()) {
                appendLine("Recent Conversation:")
                history.takeLast(8).forEach { msg ->
                    appendLine("${msg.role.uppercase()}: ${msg.text.take(280)}")
                }
            }
            appendLine("=== USER INPUT ===")
            appendLine(userPrompt)
        }

        return@withContext try {
            val response = withTimeout(10_000L) {
                structuredModel.generateContent(contextBlock)
            }
            val rawJson = response.text
                ?: return@withContext Result.failure(IllegalStateException("ALI AI returned an empty response."))
            Result.success(parseStructuredResult(rawJson, defaultLanguage))
        } catch (e: CancellationException) {
            if (e !is TimeoutCancellationException) throw e
            Log.w(TAG, "Primary structured inference timed out (10s), attempting gemini-3.5-flash-lite recovery", e)
            runLiteFallback(contextBlock, defaultLanguage, e)
        } catch (primaryError: Exception) {
            val described = describeError(primaryError)
            Log.w(TAG, "Primary structured inference failed: $described", primaryError)
            val skipSameModelRetry = described.contains("403") ||
                described.contains("Internet / DNS unavailable") ||
                described.contains("timed out", ignoreCase = true)
            if (skipSameModelRetry) {
                return@withContext runLiteFallback(contextBlock, defaultLanguage, primaryError)
            }
            runLiteFallback(contextBlock, defaultLanguage, primaryError)
        }
    }

    private suspend fun runLiteFallback(
        contextBlock: String,
        defaultLanguage: AssistantLanguage,
        previousError: Throwable
    ): Result<AliBrainResult> {
        return try {
            val liteResponse = withTimeout(8_000L) {
                liteFallbackModel.generateContent(contextBlock)
            }
            val rawLite = liteResponse.text?.trim()
                ?: return Result.failure(previousError)
            val parsed = runCatching {
                parseStructuredResult(rawLite, defaultLanguage)
            }.getOrElse {
                AliBrainResult(
                    replyText = rawLite,
                    spokenLanguage = defaultLanguage,
                    actions = listOf(DeviceAction(type = ActionType.SPEAK_ONLY)),
                    suggestions = emptyList()
                )
            }
            Result.success(parsed)
        } catch (e: CancellationException) {
            if (e !is TimeoutCancellationException) throw e
            Log.e(TAG, "ALI AI lite fallback timed out (8s)", e)
            Result.failure(e)
        } catch (liteError: Exception) {
            Log.e(TAG, "ALI AI inference failed completely: ${describeError(liteError)}", liteError)
            Result.failure(liteError)
        }
    }

    suspend fun analyzeImageWithAli(
        bitmap: Bitmap,
        question: String,
        language: AssistantLanguage
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val promptText = if (question.isBlank()) {
                when (language) {
                    AssistantLanguage.HINDI -> "इस इमेज का विश्लेषण करें और मुख्य बातें हिंदी में बताएं।"
                    AssistantLanguage.HINGLISH -> "Is image ko analyze karo aur key details Hinglish me batao."
                    AssistantLanguage.ENGLISH -> "Analyze this image and summarize the key details concisely."
                }
            } else {
                question
            }
            val response = withTimeout(60_000L) {
                visionModel.generateContent(
                    content {
                        image(bitmap)
                        text(promptText)
                    }
                )
            }
            val text = response.text
            if (text.isNullOrBlank()) {
                Result.failure(IllegalStateException("ALI Vision returned an empty analysis."))
            } else {
                Result.success(text)
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "ALI Vision timed out", e)
            Result.failure(Exception("Image analysis timed out. Please try again.", e))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "ALI Vision failed", e)
            Result.failure(e)
        }
    }

    suspend fun analyzePhysicalEnvironment(
        sixthSense: com.example.data.SixthSenseTelemetry,
        deviceTelemetry: DeviceTelemetry,
        language: AssistantLanguage
    ): Result<AliBrainResult> = withContext(Dispatchers.IO) {
        val luxDesc = if (sixthSense.lightAvailable && sixthSense.ambientLux != null) {
            "${sixthSense.ambientLux.toInt()} Lux"
        } else {
            "Light sensor unavailable on this device"
        }
        val magDesc = if (sixthSense.magnetometerAvailable) {
            "Heading=${sixthSense.compassHeadingDeg.toInt()} deg, MagneticFlux=${sixthSense.magneticFluxMicroTesla.toInt()} uT"
        } else {
            "Magnetometer unavailable"
        }
        val motionDesc = if (sixthSense.accelerometerAvailable) {
            "GForce=${"%.2f".format(sixthSense.motionGForce)}g, Stability=${sixthSense.stabilityPercent}%"
        } else {
            "Accelerometer unavailable"
        }
        val dbDesc = if (sixthSense.audioMeterActive && sixthSense.ambientDbSpl != null) {
            "${sixthSense.ambientDbSpl.toInt()} dB SPL"
        } else {
            "Acoustic room meter off"
        }

        val promptBlock = """
            === NEURAL SIXTH SENSE PHYSICAL TELEMETRY ===
            Preferred Language: ${language.code} (${language.label})
            Ambient Light: $luxDesc
            Magnetometer & Compass: $magDesc
            Motion & Stability: $motionDesc
            Proximity Sensor: ${if (sixthSense.proximityAvailable) (if (sixthSense.proximityNear) "NEAR" else "CLEAR") else "Unavailable"}
            Room Acoustic Level: $dbDesc
            Battery: ${deviceTelemetry.batteryPercent}% (Charging=${deviceTelemetry.charging}), Temp=${sixthSense.batteryTempCelsius}C
            Touchless Air-Wave=${sixthSense.airWaveEnabled}, Wrist-Chop=${sixthSense.chopFlashEnabled}
            Task: Give Boss a futuristic, concise 2-3 sentence Neural Sixth Sense briefing about their real physical environment (lighting comfort, focus/noise level, magnetic EMF normalcy, battery thermal safety, posture/stability) and optionally recommend helpful actions (like SET_BRIGHTNESS, TOGGLE_FLASHLIGHT, or PLAY_MUSIC focus audio if suitable — keep SPEAK_ONLY if no action needed).
        """.trimIndent()

        return@withContext try {
            val response = withTimeout(12_000L) {
                structuredModel.generateContent(promptBlock)
            }
            val rawJson = response.text
                ?: return@withContext Result.failure(IllegalStateException("ALI Sixth Sense returned an empty response."))
            Result.success(parseStructuredResult(rawJson, language))
        } catch (e: CancellationException) {
            if (e !is TimeoutCancellationException) throw e
            runLiteFallback(promptBlock, language, e)
        } catch (primaryError: Exception) {
            Log.w(TAG, "Sixth Sense primary failed, trying lite fallback: ${describeError(primaryError)}")
            runLiteFallback(promptBlock, language, primaryError)
        }
    }

    suspend fun draftWhatsAppReply(
        sender: String,
        incomingText: String,
        language: AssistantLanguage
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val prompt = "Draft a polite, short, natural WhatsApp reply to '$sender' who wrote: \"$incomingText\". " +
                "Language: ${language.label}. Return only the reply message text."
            val response = withTimeout(30_000L) {
                replyDraftModel.generateContent(prompt)
            }
            val reply = response.text?.trim()
            if (reply.isNullOrBlank()) {
                Result.failure(IllegalStateException("Could not draft reply."))
            } else {
                Result.success(reply)
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Draft WhatsApp reply timed out", e)
            Result.failure(Exception("Drafting reply timed out. Please try again.", e))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to draft WhatsApp reply", e)
            Result.failure(e)
        }
    }

    internal fun parseStructuredResult(
        rawResponse: String,
        fallbackLanguage: AssistantLanguage
    ): AliBrainResult {
        val jsonText = extractJsonObject(rawResponse)
        val json = JSONObject(jsonText)
        val replyText = json.optCleanString("replyText").ifBlank {
            "जी बॉस, कमांड प्रोसेस हो गई है।"
        }
        val lang = AssistantLanguage.fromCode(
            json.optCleanString("spokenLanguage").ifBlank { fallbackLanguage.code }
        )

        val actions = mutableListOf<DeviceAction>()
        val actionsArray = json.optJSONArray("actions")
        if (actionsArray != null) {
            for (i in 0 until actionsArray.length()) {
                val item = actionsArray.optJSONObject(i) ?: continue
                val typeName = item.optCleanString("type").ifBlank { ActionType.SPEAK_ONLY.name }
                val type = ActionType.entries.firstOrNull {
                    it.name.equals(typeName, ignoreCase = true)
                } ?: ActionType.SPEAK_ONLY
                val sensitiveByDefault = type in setOf(
                    ActionType.MAKE_CALL,
                    ActionType.SEND_WHATSAPP,
                    ActionType.SEND_SMS
                )
                val requiresConfirmation = item.optBoolean("requiresConfirmation", sensitiveByDefault) ||
                    sensitiveByDefault
                actions.add(
                    DeviceAction(
                        type = type,
                        target = item.optCleanString("target"),
                        payload = item.optCleanString("payload"),
                        requiresConfirmation = requiresConfirmation,
                        confirmationReason = item.optCleanString("confirmationReason")
                    )
                )
            }
        }
        if (actions.isEmpty()) {
            actions.add(DeviceAction(type = ActionType.SPEAK_ONLY))
        }

        val memObj = if (json.isNull("memoryProposal")) null else json.optJSONObject("memoryProposal")
        val memoryProposal = if (memObj != null) {
            val key = memObj.optCleanString("key").trim()
            val value = memObj.optCleanString("value").trim()
            if (key.isNotEmpty() && value.isNotEmpty()) {
                MemoryProposal(
                    key = key,
                    value = value,
                    category = MemoryCategory.fromWire(memObj.optCleanString("category"))
                )
            } else null
        } else null

        val suggestions = mutableListOf<String>()
        val sugArray = json.optJSONArray("suggestions")
        if (sugArray != null) {
            for (i in 0 until sugArray.length()) {
                if (sugArray.isNull(i)) continue
                val s = sugArray.optString(i).trim()
                if (s.isNotEmpty() && !s.equals("null", ignoreCase = true)) suggestions.add(s)
            }
        }

        return AliBrainResult(
            replyText = replyText,
            spokenLanguage = lang,
            actions = actions,
            memoryProposal = memoryProposal,
            suggestions = suggestions.take(3)
        )
    }

    private fun extractJsonObject(raw: String): String {
        val trimmed = raw.trim()
        val firstBrace = trimmed.indexOf('{')
        val lastBrace = trimmed.lastIndexOf('}')
        return if (firstBrace in 0 until lastBrace) {
            trimmed.substring(firstBrace, lastBrace + 1)
        } else {
            trimmed
        }
    }

    private fun JSONObject.optCleanString(key: String): String {
        if (isNull(key)) return ""
        val value = optString(key, "").trim()
        return if (value.equals("null", ignoreCase = true)) "" else value
    }

    companion object {
        fun describeError(error: Throwable): String {
            val chain = generateSequence(error) { it.cause }.toList()
            val messages = chain.mapNotNull { it.localizedMessage?.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            val combined = messages.joinToString(" -> ").take(240)
            val isTimeout = error is TimeoutCancellationException ||
                chain.any { it is SocketTimeoutException } ||
                combined.contains("timeout", ignoreCase = true)
            val isNetwork = chain.any { it is UnknownHostException || it is IOException } ||
                combined.contains("unable to resolve host", ignoreCase = true) ||
                combined.contains("failed to connect", ignoreCase = true) ||
                combined.contains("network", ignoreCase = true)
            val isAppCheck = combined.contains("403") ||
                combined.contains("app check", ignoreCase = true) ||
                combined.contains("attestation", ignoreCase = true)
            val appCheckHint = if (
                com.example.core.AppCheckDiagnostics.providerMode == "debug" &&
                !com.example.core.AppCheckDiagnostics.hasDebugSecret
            ) {
                " [Debug token missing from launch intent]"
            } else {
                ""
            }

            return when {
                isAppCheck -> "App Check / Firebase permission error (403)$appCheckHint: $combined"
                isTimeout -> "Request timed out. Please check internet speed and try again."
                isNetwork -> "Internet / DNS unavailable: ${combined.ifBlank { error::class.java.simpleName }}"
                else -> "${error::class.java.simpleName}$appCheckHint: ${combined.ifBlank { "Unknown ALI AI error" }}"
            }
        }
    }
}

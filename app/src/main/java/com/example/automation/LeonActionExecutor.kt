package com.example.automation

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.example.data.ActionType
import com.example.data.DeviceAction
import com.example.data.DeviceTelemetry
import com.example.media.LeonMusicEngine
import com.example.service.AccessibilityBridge
import com.example.service.BrightnessBridge
import com.example.service.LockScreenPolicy
import com.example.service.NotificationBridge
import java.net.URLEncoder

data class ActionExecutionOutcome(
    val success: Boolean,
    val logMessage: String
)

class LeonActionExecutor(private val context: Context) {

    @Volatile
    private var flashlightActive = false

    private var torchCameraId: String? = null
    private var torchCallbackRegistered = false

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (torchCameraId == null || torchCameraId == cameraId) {
                flashlightActive = enabled
            }
        }
    }

    init {
        registerTorchStateListener()
    }

    private fun registerTorchStateListener() {
        if (torchCallbackRegistered) return
        runCatching {
            val camManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
            torchCameraId = findFlashCameraId(camManager)
            camManager.registerTorchCallback(torchCallback, Handler(Looper.getMainLooper()))
            torchCallbackRegistered = true
        }
    }

    private fun findFlashCameraId(camManager: CameraManager): String? {
        val backFlash = camManager.cameraIdList.firstOrNull { id ->
            runCatching {
                val chars = camManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }.getOrDefault(false)
        }
        return backFlash ?: camManager.cameraIdList.firstOrNull { id ->
            runCatching {
                camManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }.getOrDefault(false)
        }
    }

    fun readTelemetry(): DeviceTelemetry {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: 100
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val batteryPct = if (scale > 0) ((level * 100f) / scale).toInt().coerceIn(0, 100) else 100
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNetwork = cm?.activeNetwork
        val caps = cm?.getNetworkCapabilities(activeNetwork)
        val wifiConnected = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val cellularConnected = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val internetConnected = wifiConnected || cellularConnected ||
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val btAdapter = btManager?.adapter
        val btAvailable = btAdapter != null
        val btEnabled = try {
            btAdapter?.isEnabled == true
        } catch (_: SecurityException) {
            false
        }

        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val curVol = am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 5
        val maxVol = (am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15).coerceAtLeast(1)
        val volPct = ((curVol * 100f) / maxVol).toInt().coerceIn(0, 100)

        val sysBrightness = try {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 150)
        } catch (_: Exception) {
            150
        }
        val brightPct = ((sysBrightness * 100f) / 255f).toInt().coerceIn(0, 100)

        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        } else true

        return DeviceTelemetry(
            batteryPercent = batteryPct,
            charging = charging,
            wifiConnected = wifiConnected,
            internetConnected = internetConnected,
            bluetoothAvailable = btAvailable,
            bluetoothEnabled = btEnabled,
            flashlightOn = flashlightActive,
            mediaVolumePercent = volPct,
            brightnessPercent = brightPct,
            micPermissionGranted = hasPermission(Manifest.permission.RECORD_AUDIO),
            notificationsPermissionGranted = notifGranted,
            cameraPermissionGranted = hasPermission(Manifest.permission.CAMERA),
            contactsPermissionGranted = hasPermission(Manifest.permission.READ_CONTACTS),
            phonePermissionGranted = hasPermission(Manifest.permission.CALL_PHONE),
            writeSettingsGranted = Settings.System.canWrite(context),
            notificationListenerEnabled = NotificationBridge.listenerConnected.value,
            accessibilityEnabled = AccessibilityBridge.serviceConnected.value
        )
    }

    fun execute(action: DeviceAction): ActionExecutionOutcome {
        return when (action.type) {
            ActionType.SPEAK_ONLY -> ActionExecutionOutcome(true, "Spoken response ready.")
            ActionType.OPEN_APP -> openApp(action.target)
            ActionType.MAKE_CALL -> makeCall(action.target)
            ActionType.SEND_WHATSAPP -> sendWhatsApp(action.target, action.payload)
            ActionType.SEND_SMS -> sendSms(action.target, action.payload)
            ActionType.READ_NOTIFICATIONS -> summarizeNotifications()
            ActionType.PLAY_MUSIC -> {
                val msg = LeonMusicEngine.handleAction(context, action.target, action.payload.ifBlank { "play" })
                ActionExecutionOutcome(true, msg)
            }
            ActionType.LOCK_SCREEN_BRIEFING -> {
                val t = readTelemetry()
                val summary = LockScreenPolicy.buildLockScreenBriefing(
                    telemetry = t,
                    notifications = NotificationBridge.notifications.value,
                    tasks = emptyList(),
                    isDeviceLocked = false
                )
                ActionExecutionOutcome(true, summary)
            }
            ActionType.YOUTUBE_SEARCH -> openYouTubeSearch(action.target)
            ActionType.GOOGLE_SEARCH -> openGoogleSearch(action.target)
            ActionType.OPEN_WEBSITE -> openWebsite(action.target)
            ActionType.OPEN_CAMERA -> openCamera()
            ActionType.TOGGLE_FLASHLIGHT -> setFlashlight(action.payload)
            ActionType.SET_VOLUME -> setMediaVolume(action.payload)
            ActionType.SET_BRIGHTNESS -> setSystemBrightness(action.payload)
            ActionType.SET_ALARM -> setAlarm(action.target, action.payload)
            ActionType.SET_TIMER -> setTimer(action.target, action.payload)
            ActionType.OPEN_WIFI_PANEL -> openWifiSettings()
            ActionType.TOGGLE_INTERNET -> openInternetPanel(action.payload)
            ActionType.OPEN_BLUETOOTH_SETTINGS -> openBluetoothSettings()
            ActionType.OPEN_SETTINGS -> openSettings(action.target)
            ActionType.ACCESSIBILITY_GLOBAL -> performAccessibilityGlobal(action.target)
            ActionType.CREATE_TASK, ActionType.CREATE_ROUTINE, ActionType.EMERGENCY_STOP,
            ActionType.OPTICAL_MORSE, ActionType.TOGGLE_AIR_GESTURES, ActionType.ANALYZE_ENVIRONMENT,
            ActionType.SAVE_MEMORY, ActionType.SET_OWNER_IDENTITY, ActionType.FETCH_NEWS,
            ActionType.OPEN_OFFLINE_VISION ->
                ActionExecutionOutcome(true, "Handled by LEON controller.")
        }
    }

    fun setFlashlight(mode: String): ActionExecutionOutcome {
        return try {
            val camManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                ?: return ActionExecutionOutcome(false, "Camera service unavailable on this device.")
            val cameraId = torchCameraId ?: findFlashCameraId(camManager)?.also { torchCameraId = it }
                ?: return ActionExecutionOutcome(false, "No camera flash hardware found on this device.")

            val nextState = when (mode.lowercase().trim()) {
                "on", "true", "enable", "open" -> true
                "off", "false", "disable", "close" -> false
                else -> !flashlightActive
            }
            camManager.setTorchMode(cameraId, nextState)
            flashlightActive = nextState
            ActionExecutionOutcome(true, if (nextState) "Flashlight turned ON." else "Flashlight turned OFF.")
        } catch (se: SecurityException) {
            ActionExecutionOutcome(false, "Grant Camera permission in Security tab so LEON can control Flashlight.")
        } catch (e: Exception) {
            ActionExecutionOutcome(false, "Flashlight unavailable right now: ${e.localizedMessage ?: "Camera busy"}")
        }
    }

    fun setMediaVolume(value: String): ActionExecutionOutcome {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            when (value.lowercase().trim()) {
                "up", "increase" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                "down", "decrease" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                "mute", "0" -> am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
                else -> {
                    val pct = value.filter { it.isDigit() }.toIntOrNull()?.coerceIn(0, 100) ?: 60
                    val target = ((pct / 100f) * max).toInt().coerceIn(0, max)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
                }
            }
            val nowVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val nowPct = ((nowVol * 100f) / max).toInt().coerceIn(0, 100)
            ActionExecutionOutcome(true, "Media volume set to $nowPct%.")
        } catch (e: Exception) {
            ActionExecutionOutcome(false, "Could not adjust volume: ${e.localizedMessage}")
        }
    }

    fun setSystemBrightness(value: String): ActionExecutionOutcome {
        val currentWindowPct = BrightnessBridge.windowBrightness.value?.let { (it * 100f).toInt() }
        val currentSystemPct = try {
            val raw = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 150)
            ((raw * 100f) / 255f).toInt().coerceIn(5, 100)
        } catch (_: Exception) {
            60
        }
        val basePct = currentWindowPct ?: currentSystemPct
        val lower = value.lowercase().trim()
        val pct = when (lower) {
            "up", "increase", "high" -> (basePct + 25).coerceIn(15, 100)
            "down", "decrease", "low", "dim" -> (basePct - 25).coerceIn(10, 100)
            else -> lower.filter { it.isDigit() }.toIntOrNull()?.coerceIn(5, 100) ?: 60
        }

        // Apply immediately to the LEON window for instant feedback even before system write permission
        BrightnessBridge.setWindowBrightness(pct / 100f)

        return if (Settings.System.canWrite(context)) {
            try {
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                val level = ((pct / 100f) * 255f).toInt().coerceIn(10, 255)
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, level)
                ActionExecutionOutcome(true, "Screen brightness set to $pct%.")
            } catch (e: Exception) {
                ActionExecutionOutcome(false, "App brightness set to $pct%, but system write failed: ${e.localizedMessage}")
            }
        } else {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (!launchSafe(intent).success) {
                launchSafe(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            ActionExecutionOutcome(
                true,
                "App brightness set to $pct%. Enable 'Modify System Settings' on the screen just opened for full device brightness control."
            )
        }
    }

    private fun openApp(target: String): ActionExecutionOutcome {
        val query = target.trim().lowercase()
            .removePrefix("open ")
            .removeSuffix(" खोलो")
            .trim()
        val knownPackages = mapOf(
            "whatsapp" to "com.whatsapp",
            "व्हाट्सएप" to "com.whatsapp",
            "youtube" to "com.google.android.youtube",
            "यूट्यूब" to "com.google.android.youtube",
            "chrome" to "com.android.chrome",
            "क्रोम" to "com.android.chrome",
            "maps" to "com.google.android.apps.maps",
            "मैप्स" to "com.google.android.apps.maps",
            "gmail" to "com.google.android.gm",
            "जीमेल" to "com.google.android.gm",
            "spotify" to "com.spotify.music",
            "clock" to "com.google.android.deskclock",
            "अलार्म" to "com.google.android.deskclock",
            "calendar" to "com.google.android.calendar",
            "कैलेंडर" to "com.google.android.calendar",
            "calculator" to "com.google.android.calculator",
            "कैलकुलेटर" to "com.google.android.calculator",
            "photos" to "com.google.android.apps.photos",
            "फोटो" to "com.google.android.apps.photos",
            "messages" to "com.google.android.apps.messaging",
            "मैसेज" to "com.google.android.apps.messaging",
            "play store" to "com.android.vending",
            "प्ले स्टोर" to "com.android.vending",
            "instagram" to "com.instagram.android",
            "telegram" to "org.telegram.messenger"
        )
        knownPackages.entries.firstOrNull { query.contains(it.key) }?.let { (_, pkg) ->
            val launch = context.packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
                return ActionExecutionOutcome(true, "Opened $target.")
            }
        }
        if (query.contains("camera") || query.contains("कैमरा")) return openCamera()
        if (query.contains("setting") || query.contains("सेटिंग")) return openSettings("main")
        if (query.contains("phone") || query.contains("dialer") || query.contains("फ़ोन") || query.contains("फोन")) {
            return launchSafe(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = context.packageManager.queryIntentActivities(mainIntent, 0)
        val matched = apps.firstOrNull { info ->
            info.loadLabel(context.packageManager).toString().lowercase().contains(query)
        }
        if (matched != null) {
            val launch = context.packageManager.getLaunchIntentForPackage(matched.activityInfo.packageName)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
                return ActionExecutionOutcome(true, "Opened ${matched.loadLabel(context.packageManager)}.")
            }
        }
        if (query.contains("youtube") || query.contains("यूट्यूब")) {
            return openWebsite("https://www.youtube.com")
        }
        if (query.contains("google") || query.contains("गूगल")) {
            return openWebsite("https://www.google.com")
        }
        return ActionExecutionOutcome(false, "App '$target' is not installed on this device.")
    }

    private fun makeCall(target: String): ActionExecutionOutcome {
        val phoneNumber = resolvePhoneNumber(target)
        if (phoneNumber.isBlank()) {
            val dialIntent = Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            launchSafe(dialIntent)
            return ActionExecutionOutcome(true, "Opened Phone dialer for $target.")
        }
        val uri = Uri.parse("tel:$phoneNumber")
        return if (hasPermission(Manifest.permission.CALL_PHONE)) {
            launchSafe(Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            launchSafe(Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun sendWhatsApp(target: String, message: String): ActionExecutionOutcome {
        val phone = resolvePhoneNumber(target).filter { it.isDigit() || it == '+' }
        val encodedMsg = URLEncoder.encode(message, "UTF-8")
        if (phone.isNotBlank()) {
            val url = "https://api.whatsapp.com/send?phone=${phone.removePrefix("+")}&text=$encodedMsg"
            for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    setPackage(pkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (launchSafe(intent).success) {
                    return ActionExecutionOutcome(true, "Opened WhatsApp chat for $target.")
                }
            }
            val webFallback = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (launchSafe(webFallback).success) {
                return ActionExecutionOutcome(true, "Opened WhatsApp link for $target.")
            }
        }
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                setPackage(pkg)
                putExtra(Intent.EXTRA_TEXT, message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (launchSafe(sendIntent).success) {
                return ActionExecutionOutcome(true, "Opened WhatsApp share for $target.")
            }
        }
        return ActionExecutionOutcome(false, "WhatsApp is not installed on this device.")
    }

    private fun sendSms(target: String, message: String): ActionExecutionOutcome {
        val phone = resolvePhoneNumber(target)
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phone")).apply {
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launchSafe(intent)
    }

    private fun summarizeNotifications(): ActionExecutionOutcome {
        if (!NotificationBridge.listenerConnected.value) {
            return ActionExecutionOutcome(
                false,
                "Notification Listener permission is off. Enable LEON AI Notification Reader in Security tab."
            )
        }
        val items = NotificationBridge.notifications.value
        return if (items.isEmpty()) {
            ActionExecutionOutcome(true, "No new device notifications found.")
        } else {
            val summary = items.take(5).joinToString(" | ") { "${it.appName}: ${it.title} - ${it.content}" }
            ActionExecutionOutcome(true, summary)
        }
    }

    private fun openYouTubeSearch(query: String): ActionExecutionOutcome {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val ytAppIntent = Intent(Intent.ACTION_SEARCH).apply {
            setPackage("com.google.android.youtube")
            putExtra("query", query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (launchSafe(ytAppIntent).success) {
            return ActionExecutionOutcome(true, "Searching YouTube for '$query'.")
        }
        val webIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.youtube.com/results?search_query=$encoded")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launchSafe(webIntent)
    }

    private fun openGoogleSearch(query: String): ActionExecutionOutcome {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.google.com/search?q=$encoded")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launchSafe(intent)
    }

    private fun openWebsite(url: String): ActionExecutionOutcome {
        val normalized = if (url.startsWith("http://") || url.startsWith("https://")) url
        else "https://$url"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(normalized))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launchSafe(intent)
    }

    private fun openCamera(): ActionExecutionOutcome {
        val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launchSafe(intent)
    }

    private fun setAlarm(label: String, timePayload: String): ActionExecutionOutcome {
        val lower = timePayload.lowercase()
        val cleanTime = timePayload.replace(Regex("[^0-9:]"), "")
        val parts = cleanTime.split(":")
        var hour = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 7
        val minute = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
        if ((lower.contains("pm") || lower.contains("शाम") || lower.contains("रात")) && hour in 1..11) {
            hour += 12
        } else if ((lower.contains("am") || lower.contains("सुबह")) && hour == 12) {
            hour = 0
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, label.ifBlank { "LEON Alarm" })
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launchSafe(intent)
    }

    private fun setTimer(label: String, secondsPayload: String): ActionExecutionOutcome {
        val lower = secondsPayload.lowercase()
        val numeric = secondsPayload.filter { it.isDigit() }.toIntOrNull() ?: 60
        val seconds = when {
            lower.contains("hour") || lower.contains("hr") || lower.contains("घंट") -> numeric * 3600
            lower.contains("min") || lower.contains("मिनट") || lower.endsWith("m") -> numeric * 60
            else -> numeric
        }.coerceIn(1, 86400)
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, label.ifBlank { "LEON Timer" })
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launchSafe(intent)
    }

    fun openInternetPanel(mode: String = "toggle"): ActionExecutionOutcome {
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                add(Settings.Panel.ACTION_WIFI)
            }
            add(Settings.ACTION_WIRELESS_SETTINGS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                add(Settings.ACTION_DATA_USAGE_SETTINGS)
            }
            add(Settings.ACTION_DATA_ROAMING_SETTINGS)
            add(Settings.ACTION_WIFI_SETTINGS)
            add(Settings.ACTION_SETTINGS)
        }
        val stateWord = when (mode.lowercase().trim()) {
            "on", "enable", "open" -> "ON"
            "off", "disable", "close" -> "OFF"
            else -> "ON / OFF"
        }
        for (action in candidates) {
            val res = launchSafe(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            if (res.success) {
                return ActionExecutionOutcome(true, "Opened Quick Internet Panel ($stateWord switch ready).")
            }
        }
        return ActionExecutionOutcome(false, "Unable to open Internet settings on this device.")
    }

    fun openWifiSettings(): ActionExecutionOutcome {
        val candidates = buildList {
            add(Settings.ACTION_WIFI_SETTINGS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(Settings.Panel.ACTION_WIFI)
                add(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
            }
            add(Settings.ACTION_WIRELESS_SETTINGS)
            add(Settings.ACTION_SETTINGS)
        }
        for (action in candidates) {
            val res = launchSafe(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            if (res.success) {
                return ActionExecutionOutcome(true, "Opened Wi-Fi Settings.")
            }
        }
        return ActionExecutionOutcome(false, "Unable to open Wi-Fi Settings on this device.")
    }

    fun openBluetoothSettings(): ActionExecutionOutcome {
        val res = launchSafe(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return if (res.success) ActionExecutionOutcome(true, "Opened Bluetooth Settings.")
        else launchSafe(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openSettings(target: String): ActionExecutionOutcome {
        val normalized = target.lowercase().trim()
        if (normalized == "wifi" || normalized == "wi-fi" || normalized == "internet") {
            return openWifiSettings()
        }
        if (normalized == "bluetooth") {
            return openBluetoothSettings()
        }
        val primaryAction = when (normalized) {
            "display", "brightness" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound", "volume" -> Settings.ACTION_SOUND_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "notifications", "notification_listener" -> Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
            "write_settings" -> Settings.ACTION_MANAGE_WRITE_SETTINGS
            "battery", "battery_optimization" -> Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        val intent = Intent(primaryAction).apply {
            if (primaryAction == Settings.ACTION_MANAGE_WRITE_SETTINGS) {
                data = Uri.parse("package:${context.packageName}")
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val res = launchSafe(intent)
        if (res.success) return ActionExecutionOutcome(true, "Opened Settings ($target).")

        // OEM fallbacks if a deep settings activity is unavailable
        if (primaryAction == Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS ||
            primaryAction == Settings.ACTION_MANAGE_WRITE_SETTINGS
        ) {
            val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (launchSafe(appDetails).success) {
                return ActionExecutionOutcome(true, "Opened LEON AI App Settings.")
            }
        }
        return launchSafe(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun performAccessibilityGlobal(target: String): ActionExecutionOutcome {
        val globalAction = when (target.lowercase()) {
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            else -> AccessibilityService.GLOBAL_ACTION_HOME
        }
        return if (AccessibilityBridge.requestGlobalAction(globalAction)) {
            ActionExecutionOutcome(true, "Executed Accessibility navigation: $target.")
        } else {
            ActionExecutionOutcome(false, "Enable LEON Accessibility Helper in Permissions to automate $target.")
        }
    }

    fun resolvePhoneNumber(nameOrNumber: String): String {
        val trimmed = nameOrNumber.trim()
        if (trimmed.any { it.isDigit() } && trimmed.count { it.isDigit() } >= 6) {
            return trimmed
        }
        if (!hasPermission(Manifest.permission.READ_CONTACTS) || trimmed.isBlank()) {
            return ""
        }
        return try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val args = arrayOf("%$trimmed%")
            context.contentResolver.query(uri, projection, selection, args, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
            } ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun launchSafe(intent: Intent): ActionExecutionOutcome {
        return try {
            context.startActivity(intent)
            ActionExecutionOutcome(true, "Launched ${intent.action ?: "action"}.")
        } catch (e: Exception) {
            ActionExecutionOutcome(false, "Unable to open target on this device: ${e.localizedMessage}")
        }
    }
}

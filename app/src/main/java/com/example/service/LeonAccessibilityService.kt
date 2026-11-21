package com.example.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class LeonAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityBridge.setConnected(true)
        collectJob?.cancel()
        collectJob = serviceScope.launch {
            AccessibilityBridge.globalActionRequests.collect { actionId ->
                performGlobalAction(actionId)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString().orEmpty()
        if (pkg.isNotEmpty()) {
            AccessibilityBridge.updateActivePackage(pkg)
        }
    }

    override fun onInterrupt() {
        // Required override
    }

    override fun onDestroy() {
        super.onDestroy()
        AccessibilityBridge.setConnected(false)
        collectJob?.cancel()
        serviceScope.cancel()
    }
}

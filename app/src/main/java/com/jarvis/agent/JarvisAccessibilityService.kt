package com.jarvis.agent

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Does nothing until you ask Jarvis to press Home/Back, scroll, tap a label or type.
 *  It never reads screen content out of the app. */
class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: JarvisAccessibilityService? = null
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun globalAction(name: String): Boolean = when (name) {
        "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        "quick_settings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        "lock_screen" -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        "screenshot" -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        else -> false
    }

    fun scroll(down: Boolean): Boolean {
        val node = findScrollable(rootInActiveWindow) ?: return false
        val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return node.performAction(action)
    }

    fun tapText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        for (match in root.findAccessibilityNodeInfosByText(text)) {
            var cur: AccessibilityNodeInfo? = match
            while (cur != null && !cur.isClickable) cur = cur.parent
            if (cur != null && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        return false
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val existing = if (field.isShowingHintText) "" else field.text?.toString().orEmpty()
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, existing + text)
        }
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            findScrollable(node.getChild(i))?.let { return it }
        }
        return null
    }
}

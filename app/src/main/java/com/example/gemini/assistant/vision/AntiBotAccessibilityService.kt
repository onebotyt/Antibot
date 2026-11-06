package com.example.gemini.assistant.vision

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.gemini.data.automation.AutomationAccessibilityService
import java.lang.ref.WeakReference

class AntiBotAccessibilityService : AccessibilityService() {

    companion object {
        private var instanceRef: WeakReference<AntiBotAccessibilityService>? = null

        val instance: AntiBotAccessibilityService?
            get() = instanceRef?.get()

        val activeService: AccessibilityService?
            get() = instance ?: AutomationAccessibilityService.instance

        val isServiceRunning: Boolean
            get() = activeService != null

        fun getRootNode(): AccessibilityNodeInfo? = activeService?.rootInActiveWindow

        /**
         * Extracts all visible text from the currently active screen window.
         */
        fun extractVisibleScreenText(): String {
            val root = getRootNode() ?: return ""
            val sb = StringBuilder()
            traverseNodesForText(root, sb, depth = 0)
            return sb.toString().trim()
        }

        /**
         * Extracts text specifically intersecting with a given screen rectangle (e.g. circled region).
         */
        fun extractTextInBounds(bounds: Rect): String {
            val root = getRootNode() ?: return ""
            val sb = StringBuilder()
            traverseNodesInBounds(root, bounds, sb)
            return sb.toString().trim()
        }

        /**
         * Autonomously tap on screen coordinates (x, y) using GestureDescription.
         */
        fun performTapAt(x: Float, y: Float, onComplete: (() -> Unit)? = null): Boolean {
            val service = activeService ?: return false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
            val path = Path().apply {
                moveTo(x, y)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build()

            return service.dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    onComplete?.invoke()
                }
            }, null)
        }

        /**
         * Autonomously scroll down or up.
         */
        fun performScroll(forward: Boolean = true): Boolean {
            val root = getRootNode() ?: return false
            val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            val scrollable = findScrollableNode(root)
            return scrollable?.performAction(action) ?: false
        }

        /**
         * Click a node that contains specific text (e.g. "Continue", "Next", "Submit").
         */
        fun clickNodeWithText(query: String): Boolean {
            val root = getRootNode() ?: return false
            val nodes = root.findAccessibilityNodeInfosByText(query)
            for (node in nodes) {
                var clickable: AccessibilityNodeInfo? = node
                while (clickable != null && !clickable.isClickable) {
                    clickable = clickable.parent
                }
                if (clickable != null && clickable.isClickable) {
                    return clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            }
            return false
        }

        private fun traverseNodesForText(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
            if (!node.isVisibleToUser) return

            val text = node.text?.toString()?.trim()
            val desc = node.contentDescription?.toString()?.trim()

            if (!text.isNullOrBlank()) {
                sb.append(text).append("\n")
            } else if (!desc.isNullOrBlank()) {
                sb.append(desc).append("\n")
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                traverseNodesForText(child, sb, depth + 1)
            }
        }

        private fun traverseNodesInBounds(node: AccessibilityNodeInfo, bounds: Rect, sb: StringBuilder) {
            val nodeBounds = Rect()
            node.getBoundsInScreen(nodeBounds)

            if (Rect.intersects(bounds, nodeBounds)) {
                val text = node.text?.toString()?.trim()
                if (!text.isNullOrBlank()) {
                    sb.append(text).append("\n")
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                traverseNodesInBounds(child, bounds, sb)
            }
        }

        private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isScrollable) return node
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val found = findScrollableNode(child)
                if (found != null) return found
            }
            return null
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instanceRef = WeakReference(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Can monitor window state changes if needed
    }

    override fun onInterrupt() {
        // Required callback
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instanceRef?.get() == this) {
            instanceRef = null
        }
    }

    // Instance forwarding methods
    fun extractVisibleScreenText(): String = Companion.extractVisibleScreenText()
    fun extractTextInBounds(bounds: Rect): String = Companion.extractTextInBounds(bounds)
    fun performTapAt(x: Float, y: Float, onComplete: (() -> Unit)? = null): Boolean = Companion.performTapAt(x, y, onComplete)
    fun performScroll(forward: Boolean = true): Boolean = Companion.performScroll(forward)
    fun clickNodeWithText(query: String): Boolean = Companion.clickNodeWithText(query)
}

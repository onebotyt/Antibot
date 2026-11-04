package com.example.gemini.ui.components

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import java.io.File

/**
 * ClipboardImageHelper: Detects and retrieves image URIs from the system clipboard,
 * enabling full clipboard image paste support in the chat workspace.
 */
object ClipboardImageHelper {

    fun getClipboardImageUri(context: Context): Uri? {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = try {
            clipboard.primaryClip
        } catch (e: Exception) {
            null
        } ?: return null

        if (clip.itemCount == 0) return null

        val desc = clip.description
        val item = clip.getItemAt(0)
        val uri = item.uri

        if (uri != null) {
            val mimeType = try {
                context.contentResolver.getType(uri)
            } catch (e: Exception) {
                null
            }

            val uriStr = uri.toString().lowercase()
            val isImageMime = mimeType?.startsWith("image/") == true || desc.hasMimeType("image/*")
            val isImageExt = uriStr.endsWith(".png") || uriStr.endsWith(".jpg") || uriStr.endsWith(".jpeg") ||
                    uriStr.endsWith(".webp") || uriStr.endsWith(".gif") || uriStr.endsWith(".bmp") || uriStr.endsWith(".svg")

            if (isImageMime || isImageExt) {
                return uri
            }
        }

        // Fallback: Check if copied text contains a local image file path or file URI
        val text = item.text?.toString()?.trim()
        if (!text.isNullOrBlank()) {
            if (text.startsWith("content://") || text.startsWith("file://")) {
                try {
                    val parsed = Uri.parse(text)
                    val mime = try { context.contentResolver.getType(parsed) } catch (e: Exception) { null }
                    val cleanText = text.lowercase()
                    if (mime?.startsWith("image/") == true ||
                        cleanText.endsWith(".png") || cleanText.endsWith(".jpg") ||
                        cleanText.endsWith(".jpeg") || cleanText.endsWith(".webp")
                    ) {
                        return parsed
                    }
                } catch (e: Exception) {}
            } else if (text.startsWith("/") && File(text).exists()) {
                val file = File(text)
                val ext = file.extension.lowercase()
                if (ext in listOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "svg")) {
                    return Uri.fromFile(file)
                }
            }
        }

        return null
    }
}

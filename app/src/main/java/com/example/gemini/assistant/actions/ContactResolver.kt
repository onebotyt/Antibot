package com.example.gemini.assistant.actions

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ContactMatch(
    val name: String,
    val phoneNumber: String
)

object ContactResolver {

    /**
     * Look up phone number by contact name (e.g. "Mom", "Rahul", "John").
     * Returns the closest matching contact or null if not found or permission not granted.
     */
    suspend fun findContactByName(context: Context, nameQuery: String): ContactMatch? = withContext(Dispatchers.IO) {
        val trimmed = nameQuery.trim()
        if (trimmed.isEmpty()) return@withContext null

        val uri: Uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$trimmed%")

        try {
            val cursor: Cursor? = context.contentResolver.query(
                uri,
                projection,
                selection,
                selectionArgs,
                "${ContactsContract.CommonDataKinds.Phone.IS_PRIMARY} DESC, ${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    val name = it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME))
                    val number = it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
                    return@withContext ContactMatch(name = name, phoneNumber = number)
                }
            }
        } catch (_: SecurityException) {
            // READ_CONTACTS permission not granted
        }
        return@withContext null
    }
}

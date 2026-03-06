package com.example.cactuspoc

import android.content.Context
import android.provider.ContactsContract
import android.util.Log

object ContactResolver {

    private const val TAG = "ContactResolver"

    /**
     * Resolves a phone number to a display name using Android ContactsContract.
     * Returns the display name, or the raw number if no contact is found.
     */
    fun resolve(context: Context, phoneNumber: String): String {
        if (phoneNumber.isBlank()) return "Unknown"

        return try {
            val uri = android.net.Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                android.net.Uri.encode(phoneNumber)
            )
            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME))
                } else null
            } ?: phoneNumber
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve contact for $phoneNumber", e)
            phoneNumber
        }
    }

    /**
     * Heuristic: classify a contact as "work" or "personal" based on
     * the contact's organization field. Falls back to "personal".
     */
    fun resolveGroup(context: Context, phoneNumber: String): String {
        if (phoneNumber.isBlank()) return "personal"

        return try {
            val uri = android.net.Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                android.net.Uri.encode(phoneNumber)
            )
            val lookupProjection = arrayOf(ContactsContract.PhoneLookup.LOOKUP_KEY)
            val lookupKey = context.contentResolver.query(uri, lookupProjection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }

            if (lookupKey != null) {
                val orgUri = ContactsContract.Data.CONTENT_URI
                val orgProjection = arrayOf(ContactsContract.CommonDataKinds.Organization.COMPANY)
                val selection = "${ContactsContract.Data.LOOKUP_KEY} = ? AND ${ContactsContract.Data.MIMETYPE} = ?"
                val selectionArgs = arrayOf(lookupKey, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
                val company = context.contentResolver.query(orgUri, orgProjection, selection, selectionArgs, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
                if (!company.isNullOrBlank()) "work" else "personal"
            } else {
                "personal"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve group for $phoneNumber", e)
            "personal"
        }
    }
}

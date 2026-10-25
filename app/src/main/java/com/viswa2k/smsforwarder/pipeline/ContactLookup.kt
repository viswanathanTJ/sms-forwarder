package com.viswa2k.smsforwarder.pipeline

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.viswa2k.smsforwarder.classify.SenderInfo
import com.viswa2k.smsforwarder.core.SenderKind

/** Resolves whether a sender is a saved contact. Business headers never need the lookup. */
class ContactLookup(private val context: Context) {
    fun kind(sender: String, info: SenderInfo): SenderKind {
        if (info.isBusiness) return SenderKind.BUSINESS
        if (sender.isBlank()) return SenderKind.UNVERIFIED
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return SenderKind.UNVERIFIED
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(sender))
        return try {
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)?.use {
                if (it.moveToFirst()) SenderKind.CONTACT else SenderKind.UNKNOWN_NUMBER
            } ?: SenderKind.UNVERIFIED
        } catch (e: Exception) {
            SenderKind.UNVERIFIED
        }
    }

    /**
     * WhatsApp chats are named, not numbered (spec addendum §3): CONTACT when a contact's display name equals
     * [displayName] (case-insensitive), UNKNOWN_NUMBER otherwise, UNVERIFIED without READ_CONTACTS.
     */
    fun kindForName(displayName: String): SenderKind {
        val name = displayName.trim()
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return SenderKind.UNVERIFIED
        if (name.isEmpty()) return SenderKind.UNKNOWN_NUMBER
        return try {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts._ID),
                "${ContactsContract.Contacts.DISPLAY_NAME} = ? COLLATE NOCASE", arrayOf(name), null,
            )?.use {
                if (it.moveToFirst()) SenderKind.CONTACT else SenderKind.UNKNOWN_NUMBER
            } ?: SenderKind.UNVERIFIED
        } catch (e: Exception) {
            SenderKind.UNVERIFIED
        }
    }
}

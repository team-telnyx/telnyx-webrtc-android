package org.telnyx.webrtc.xml_app.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.text.SimpleDateFormat
import java.util.*

object Utils {
    fun formatCallHistoryItemDate(date: Long): String {
        return SimpleDateFormat("YYYY-MM-dd HH:mm:ss", Locale.getDefault()).format(date)
    }

    fun uriToBase64(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val bytes = inputStream.readBytes()
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun base64ToBitmap(base64String: String): Bitmap? {
        return try {
            // Remove data URL prefix if present (e.g., "data:image/jpeg;base64,")
            val base64Data = if (base64String.contains(",")) {
                base64String.substring(base64String.indexOf(",") + 1)
            } else {
                base64String
            }

            // Decode base64 string to byte array
            val decodedBytes = Base64.decode(base64Data, Base64.DEFAULT)

            // Convert byte array to Bitmap
            BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
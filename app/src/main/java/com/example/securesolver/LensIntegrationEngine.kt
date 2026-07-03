package com.example.securesolver

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

class LensIntegrationEngine(private val context: Context) {

    fun solveMCQ(bitmap: Bitmap) {
        val uri = saveBitmapToCache(bitmap) ?: return
        copyToClipboard("Solve MCQ: Option & Explanation")
        launchGoogleLens(uri)
    }

    fun solveCode(bitmap: Bitmap) {
        val uri = saveBitmapToCache(bitmap) ?: return
        copyToClipboard("Solve Code: Complexity & Solution")
        launchGoogleLens(uri)
    }

    private fun saveBitmapToCache(bitmap: Bitmap): Uri? {
        return try {
            val cacheDir = File(context.cacheDir, "shared_images").apply { mkdirs() }
            val file = File(cacheDir, "temp_capture.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
            }
            FileProvider.getUriForFile(context, "com.example.securesolver.fileprovider", file)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Prompt", text)
        clipboard.setPrimaryClip(clip)
    }

    private fun launchGoogleLens(uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            `package` = "com.google.android.googlequicksearchbox"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        
        // Use resolveActivity to verify if Google App (which hosts Lens) can handle the Intent.
        // Fallback to a generic chooser if not found.
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        } else {
            val chooserIntent = Intent.createChooser(intent, "Open image in Lens / Search").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooserIntent)
        }
    }
}

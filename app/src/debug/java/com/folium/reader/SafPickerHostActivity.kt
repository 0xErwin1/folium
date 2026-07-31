package com.folium.reader

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class SafPickerHostActivity : Activity() {
    var resultData: Intent? = null
    var resultCode: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivityForResult(Intent(requireNotNull(intent.getParcelableExtra(EXTRA_INTENT))), REQUEST)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST) {
            this.resultCode = resultCode
            resultData = data

            // The transient grant from ACTION_OPEN_DOCUMENT_TREE is owned by this activity;
            // it must be persisted here, while the activity is still alive, or the system
            // asynchronously releases it once this activity is destroyed.
            val treeUri = data?.data
            if (resultCode == RESULT_OK && treeUri != null) {
                try {
                    contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: SecurityException) { }
            }

            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putInt("code", resultCode)
                .putString("uri", data?.data?.toString()).putInt("flags", data?.flags ?: 0).apply()
        }
    }

    companion object {
        const val EXTRA_INTENT = "intent"
        const val REQUEST = 42
        private const val PREFERENCES = "saf-picker-result"
        fun clearResult(context: android.content.Context) { context.getSharedPreferences(PREFERENCES, 0).edit().clear().commit() }
        fun result(context: android.content.Context): Pair<Int?, Intent?> {
            val preferences = context.getSharedPreferences(PREFERENCES, 0)
            if (!preferences.contains("code")) return null to null
            val data = preferences.getString("uri", null)?.let { Intent().setData(android.net.Uri.parse(it)).setFlags(preferences.getInt("flags", 0)) }
            return preferences.getInt("code", Activity.RESULT_CANCELED) to data
        }
    }
}

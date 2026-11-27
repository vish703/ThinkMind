package com.vish.thinkmind

import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.widget.*

class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "Think Mind 🤔"; textSize = 26f; gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "कुछ लिखने की ज़रूरत नहीं। YouTube या Instagram खुलते ही ऐप याद दिलाएगा कि आप क्या सर्च करने आए थे, और 8 मिनट बाद भी रुकने पर पढ़ाई चालू करने को कहेगा।"
            textSize = 16f; setPadding(0, pad, 0, pad)
        })
        root.addView(button("1️⃣ Display over other apps की अनुमति दो") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        })
        root.addView(button("2️⃣ Usage access की अनुमति दो") {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        })
        root.addView(button("▶️ रिमाइंडर चालू करो") { startReminder() })
        root.addView(button("⏹ रिमाइंडर बंद करो") {
            stopService(Intent(this, OverlayService::class.java))
            Toast.makeText(this, "बंद किया", Toast.LENGTH_SHORT).show()
        })
        status = TextView(this).apply { textSize = 14f; setPadding(0, pad, 0, 0) }
        root.addView(status)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun startReminder() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "पहले स्टेप 1 की अनुमति दो", Toast.LENGTH_LONG).show(); return
        }
        if (!hasUsageAccess()) {
            Toast.makeText(this, "पहले स्टेप 2 की अनुमति दो", Toast.LENGTH_LONG).show(); return
        }
        startForegroundService(Intent(this, OverlayService::class.java))
        Toast.makeText(this, "रिमाइंडर चालू ✅", Toast.LENGTH_SHORT).show()
    }

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
    }

    private fun refresh() {
        status.text = "Overlay अनुमति: ${if (Settings.canDrawOverlays(this)) "✅" else "❌"}\n" +
                "Usage access: ${if (hasUsageAccess()) "✅" else "❌"}"
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setOnClickListener { onClick() }
    }
}

package com.vish.thinkmind

import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

class OverlayService : Service() {

    companion object {
        // इन ऐप्स पर रिमाइंडर आएगा (और ऐप्स जोड़ने हों तो यहाँ package name डालें)
        val TARGETS = setOf("com.google.android.youtube", "com.instagram.android")
        const val REMIND_EVERY_MIN = 8   // इतने मिनट बाद "पढ़ाई चालू करो" याद दिलाएगा
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var usm: UsageStatsManager
    private lateinit var pm: PowerManager
    private var overlay: View? = null
    private var currentPkg: String? = null
    private var prevPkg: String? = null
    private var prevLeftAt = 0L
    private var enteredAt = 0L
    private var nextReminderAt = Long.MAX_VALUE
    private var lastCheck = 0L
    private var dismissed = false
    private var homePkgs: Set<String> = emptySet()

    private val tick = object : Runnable {
        override fun run() { check(); handler.postDelayed(this, 1000) }
    }
    private val autoHide = Runnable { dismissed = true; hideOverlay() }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        homePkgs = packageManager.queryIntentActivities(home, 0)
            .map { it.activityInfo.packageName }.toSet()
        startAsForeground()
        lastCheck = System.currentTimeMillis() - 10 * 60_000
        handler.post(tick)
    }

    private fun ignored(p: String?) =
        p == null || p in TARGETS || p == packageName ||
        p == "com.android.systemui" || p in homePkgs

    private fun startAsForeground() {
        val channelId = "yaad"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channelId, "Think Mind", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, channelId)
            .setContentTitle("Think Mind चालू है")
            .setContentText("YouTube / Instagram खुलने पर याद दिलाएगा")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .build()
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }

    private fun check() {
        val now = System.currentTimeMillis()
        val before = currentPkg
        val events = usm.queryEvents(lastCheck, now)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            @Suppress("DEPRECATION")
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND &&
                 e.packageName != currentPkg && e.packageName != "com.android.systemui") {
                if (!ignored(currentPkg)) { prevPkg = currentPkg; prevLeftAt = e.timeStamp }
                currentPkg = e.packageName
            }
        }
        lastCheck = now

        if (currentPkg != before) {           // ऐप बदला
            dismissed = false
            handler.removeCallbacks(autoHide)
            hideOverlay()
            if (currentPkg in TARGETS) {
                enteredAt = now
                nextReminderAt = now + REMIND_EVERY_MIN * 60_000L
            } else nextReminderAt = Long.MAX_VALUE
        }

        if (currentPkg !in TARGETS) return

        if (!pm.isInteractive) {              // स्क्रीन बंद हो तो टाइमर रुका रहे
            nextReminderAt = now + REMIND_EVERY_MIN * 60_000L
            return
        }
        if (now >= nextReminderAt) {
            val mins = ((now - enteredAt) / 60_000).toInt()
            handler.removeCallbacks(autoHide)
            hideOverlay()
            showOverlay(reminder = true, mins = mins)
            nextReminderAt = now + REMIND_EVERY_MIN * 60_000L
        } else if (overlay == null && !dismissed) {
            showOverlay(reminder = false, mins = 0)
        }
    }

    private fun label(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    private fun showOverlay(reminder: Boolean, mins: Int) {
        val dp = resources.displayMetrics.density
        val appName = currentPkg?.let { label(it) } ?: "ऐप"

        val title: String
        val msg: String
        val bg: String
        val stroke: String
        if (reminder) {
            title = "⏰ $mins मिनट हो गए!"
            msg = "कृपया $appName बंद करें और अपनी पढ़ाई चालू करें 📚"
            bg = "#F2FFCDD2"; stroke = "#D32F2F"
        } else {
            title = "⏸ रुको, एक सेकंड सोचो!"
            val p = prevPkg
            val hint = if (p != null) {
                val m = ((System.currentTimeMillis() - prevLeftAt) / 60_000).toInt()
                val ago = if (m < 1) "अभी" else "$m मिनट पहले"
                "\n\n💡 हिंट: आप ${label(p)} में थे ($ago)"
            } else ""
            msg = "अभी आप किसके बारे में सोच रहे थे?\nवही यहाँ सर्च करो।\n\nShorts/Reels में मत जाओ!$hint"
            bg = "#F2FFF3B0"; stroke = "#FF9800"
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bg))
                cornerRadius = 16 * dp
                setStroke((2 * dp).toInt(), Color.parseColor(stroke))
            }
        }
        box.addView(TextView(this).apply {
            text = title; setTextColor(Color.DKGRAY); textSize = 14f
        })
        box.addView(TextView(this).apply {
            text = msg; setTextColor(Color.BLACK); textSize = 18f
            setPadding(0, (4 * dp).toInt(), 0, (8 * dp).toInt())
        })
        box.addView(TextView(this).apply {
            text = "✕ बंद करो"; setTextColor(Color.parseColor("#D32F2F")); textSize = 14f
            gravity = Gravity.END
            setOnClickListener { handler.removeCallbacks(autoHide); dismissed = true; hideOverlay() }
        })
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP; y = (80 * dp).toInt(); horizontalMargin = 0.04f }
        wm.addView(box, lp)
        overlay = box
        handler.postDelayed(autoHide, if (reminder) 20_000 else 15_000)
    }

    private fun hideOverlay() {
        overlay?.let { runCatching { wm.removeView(it) } }
        overlay = null
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick); handler.removeCallbacks(autoHide)
        hideOverlay(); super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}

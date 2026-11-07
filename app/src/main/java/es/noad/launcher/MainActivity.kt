package es.noad.launcher

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.animation.ValueAnimator
import android.os.Build
import android.view.animation.AccelerateInterpolator
import android.view.animation.PathInterpolator
import java.io.File
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import java.net.NetworkInterface
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ResolveInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Home de NOAD para Android TV: navy, teal, slate, Space Grotesk + Inter, un solo rojo (la A del wordmark). */
class MainActivity : Activity() {

    private data class AppEntry(val pkg: String, val label: String, val intent: Intent, val banner: Drawable?, val icon: Drawable?)

    private val hidden = setOf("com.gathin.tvhome", "com.orange.es.stb")
    private val ground = Color.parseColor("#0F1419")
    private val ink = Color.parseColor("#F5F3F0")
    private val teal = Color.parseColor("#1B4A5C")
    private val slate = Color.parseColor("#64748B")
    private val red = Color.parseColor("#E14434")
    private val panel = Color.parseColor("#17202A")

    private lateinit var prefs: SharedPreferences
    private lateinit var fDisplay: Typeface
    private lateinit var fLabel: Typeface
    private lateinit var content: LinearLayout
    private lateinit var greeting: TextView
    private lateinit var dateView: TextView
    private var s = 1f
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { updateHeader(); handler.postDelayed(this, 30_000) }
    }
    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { rebuild(null) }
    }

    private fun px(n: Int) = (n * s).toInt()
    private fun sp(v: Int) = v * s

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        s = resources.displayMetrics.widthPixels / 1920f
        prefs = getSharedPreferences("noad-home", MODE_PRIVATE)
        fDisplay = Typeface.Builder(assets, "SpaceGrotesk-VariableFont_wght.ttf").setFontVariationSettings("'wght' 700").build()
        fLabel = Typeface.Builder(assets, "Inter.ttf").setFontVariationSettings("'wght' 600").build()
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN

        val root = FrameLayout(this)
        root.addView(BgView(this), ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false; clipToPadding = false
            setPadding(px(96), px(56), px(96), 0)
        }
        root.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED); addDataScheme("package")
        }
        registerReceiver(pkgReceiver, f)
        rebuild(null)
    }

    override fun onResume() { super.onResume(); handler.post(tick); if (::wTemp.isInitialized) { renderWeather(); fetchWeather() } }
    override fun onPause() { super.onPause(); handler.removeCallbacks(tick) }
    override fun onDestroy() { super.onDestroy(); unregisterReceiver(pkgReceiver) }
    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); if (intent?.action == Intent.ACTION_MAIN) rebuild(null) }
    @Deprecated("Deprecated in Java") override fun onBackPressed() { if (overlay != null) closePanel() /* en la home, Atrás no hace nada más */ }
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SETTINGS || keyCode == KeyEvent.KEYCODE_MENU) { openSettings(); return true }
        return super.onKeyDown(keyCode, event)
    }

    private fun openSettings() {
        try { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Exception) { Toast.makeText(this, "No se pudo abrir Ajustes", Toast.LENGTH_SHORT).show() }
    }

    // ---- datos ----
    private fun loadApps(): List<AppEntry> {
        val pm = packageManager
        val found = LinkedHashMap<String, ResolveInfo>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER)) {
            val q = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (ri in pm.queryIntentActivities(q, 0)) {
                val p = ri.activityInfo.packageName
                if (p != packageName && p !in hidden && !found.containsKey(p)) found[p] = ri
            }
        }
        return found.values.map { ri ->
            val p = ri.activityInfo.packageName
            val i = Intent(Intent.ACTION_MAIN).setClassName(p, ri.activityInfo.name)
                .addCategory(if (pm.getLeanbackLaunchIntentForPackage(p) != null) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            AppEntry(p, ri.loadLabel(pm).toString(), i, ri.activityInfo.loadBanner(pm), ri.loadIcon(pm))
        }.sortedBy { it.label.lowercase(Locale.getDefault()) }
    }

    private fun favorites(all: List<AppEntry>): List<AppEntry> {
        val saved = prefs.getString("favs", null)
        val order = if (saved != null) saved.split(",").filter { it.isNotEmpty() } else listOf(
            "com.netflix.ninja", "com.disney.disneyplus", "com.wbd.stream", "com.amazon.amazonvideo.livingroom", "com.google.android.youtube.tv")
        val byPkg = all.associateBy { it.pkg }
        return order.mapNotNull { byPkg[it] }
    }

    private fun toggleFavorite(pkg: String, all: List<AppEntry>) {
        val cur = favorites(all).map { it.pkg }.toMutableList()
        val now = if (cur.remove(pkg)) false else { cur.add(pkg); true }
        prefs.edit().putString("favs", cur.joinToString(",")).apply()
        Toast.makeText(this, if (now) "Añadida a favoritas" else "Quitada de favoritas", Toast.LENGTH_SHORT).show()
        rebuild(pkg)
    }

    // ---- interfaz ----
    private fun rebuild(refocus: String?) {
        content.removeAllViews()
        val all = loadApps()
        lastAppCount = all.size
        val favs = favorites(all)
        val favSet = favs.map { it.pkg }.toSet()

        // cabecera: wordmark + atajos de ajustes
        val top = FrameLayout(this)
        top.addView(TextView(this).apply {
            val t = SpannableString("NOAD"); t.setSpan(ForegroundColorSpan(red), 2, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text = t; typeface = fDisplay; setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(36)); letterSpacing = 0.16f; setTextColor(ink)
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL))
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
        val shortcuts = listOf("Wi-Fi" to Settings.ACTION_WIFI_SETTINGS, "Bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
            "Sonido" to Settings.ACTION_SOUND_SETTINGS, "Pantalla" to Settings.ACTION_DISPLAY_SETTINGS, "Ajustes" to Settings.ACTION_SETTINGS)
            .filter { packageManager.resolveActivity(Intent(it.second), 0) != null }
        chips.addView(chip("TV en vivo") { openLiveTv() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(60)))
        chips.addView(chip("Sistema") { showSystem() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(60)).apply { leftMargin = px(14) })
        shortcuts.forEach { (t, a) ->
            chips.addView(chip(t) { openAction(a, t) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(60)).apply { leftMargin = px(14) })
        }
        top.addView(chips, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
        content.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(76)))

        // hero: saludo, hora y fecha a la izquierda; el tiempo a la derecha
        val hero = FrameLayout(this).apply { clipChildren = false }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        greeting = TextView(this).apply { typeface = fLabel; setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(26)); letterSpacing = 0.22f; setTextColor(slate) }
        left.addView(greeting, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(20) })
        left.addView(TextClock(this).apply {
            format24Hour = "HH:mm"; format12Hour = "HH:mm"; typeface = fDisplay
            setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(132)); letterSpacing = -0.04f; setTextColor(ink); includeFontPadding = false
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(6) })
        dateView = TextView(this).apply { typeface = fLabel; setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(30)); setTextColor(slate) }
        left.addView(dateView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(14) })
        hero.addView(left, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.TOP))
        hero.addView(weatherPanel(), FrameLayout.LayoutParams(px(520), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.TOP).apply { topMargin = px(34) })
        content.addView(hero, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        updateHeader()
        renderWeather(); fetchWeather()

        // favoritas
        if (favs.isNotEmpty()) {
            content.addView(sectionTitle("Favoritas"), titleParams(px(24)))
            content.addView(rowOf(favs.map { tile(it, true, all) }), rowParams().apply { leftMargin = -px(30) })
        }
        // todas
        val recent = prefs.getString("recent", "")!!.split(",").filter { it.isNotEmpty() }
        val rest = all.filter { it.pkg !in favSet }.sortedWith(compareBy({ recent.indexOf(it.pkg).let { i -> if (i < 0) 999 else i } }, { it.label.lowercase(Locale.getDefault()) }))
        if (rest.isNotEmpty()) {
            content.addView(sectionTitle("Todas las apps"), titleParams(px(8)))
            content.addView(rowOf(rest.map { tile(it, false, all) }), rowParams().apply { leftMargin = -px(30) })
        }
        // foco inicial
        content.post {
            val target = refocus?.let { content.findViewWithTag<View>(it) }
                ?: (content.findViewWithTag<View>(favs.firstOrNull()?.pkg ?: rest.firstOrNull()?.pkg ?: ""))
            target?.requestFocus()
        }
    }

    private fun updateHeader() {
        if (!::greeting.isInitialized) return
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        greeting.text = (if (h in 6..12) "Buenos días, José" else if (h in 13..20) "Buenas tardes, José" else "Buenas noches, José").uppercase(Locale("es", "ES"))
        val d = SimpleDateFormat("EEEE d 'de' MMMM", Locale("es", "ES")).format(Date())
        dateView.text = d.replaceFirstChar { it.uppercase() }
    }

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t.uppercase(Locale("es", "ES")); typeface = fLabel; setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(26)); letterSpacing = 0.22f; setTextColor(slate)
    }
    private fun titleParams(top: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = top }
    private fun rowParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun rowOf(items: List<View>): HorizontalScrollView {
        val inner = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
        items.forEachIndexed { i, v ->
            inner.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i > 0) leftMargin = px(30) })
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false
            setPadding(px(30), px(20), px(30), px(18)); addView(inner)
            overScrollMode = View.OVER_SCROLL_NEVER
        }
    }

    private fun tile(a: AppEntry, banner: Boolean, all: List<AppEntry>): View {
        val w = px(if (banner) 318 else 136); val hgt = px(if (banner) 179 else 136)
        val card = FrameLayout(this)
        val pad = px(if (banner) 3 else 3)
        fun bg(focused: Boolean) = GradientDrawable().apply {
            cornerRadius = px(if (banner) 22 else 26).toFloat()
            if (focused) setColor(ink) else { setColor(panel); setStroke(pad, teal) }
        }
        card.background = bg(false)
        val img = ImageView(this)
        val bn = a.banner
        if (banner && bn != null) {
            img.setImageDrawable(bn); img.scaleType = ImageView.ScaleType.CENTER_CROP
            img.outlineProvider = ViewOutlineProvider.BACKGROUND; img.clipToOutline = true
            card.addView(img, FrameLayout.LayoutParams(w, hgt))
            img.background = GradientDrawable().apply { cornerRadius = px(22).toFloat(); setColor(Color.TRANSPARENT) }
        } else {
            img.setImageDrawable(a.icon); img.scaleType = ImageView.ScaleType.FIT_CENTER
            val m = px(if (banner) 40 else 22)
            card.addView(img, FrameLayout.LayoutParams(w - m * 2, hgt - m * 2, Gravity.CENTER))
        }
        val name = TextView(this).apply {
            text = a.label; typeface = fLabel; setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(24)); setTextColor(slate)
            gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false; tag = a.pkg
            isFocusable = true; isClickable = true; isFocusableInTouchMode = false
            addView(card, LinearLayout.LayoutParams(w, hgt))
            addView(name, LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(14) })
        }
        col.setOnClickListener { recordRecent(a.pkg); try { go(a.intent) } catch (e: Exception) { Toast.makeText(this, "No se pudo abrir ${a.label}", Toast.LENGTH_SHORT).show() } }
        col.setOnLongClickListener { toggleFavorite(a.pkg, all); true }
        col.setOnFocusChangeListener { _, has ->
            card.background = bg(has)
            card.animate().scaleX(if (has) 1.1f else 1f).scaleY(if (has) 1.1f else 1f).setDuration(160).start()
            card.elevation = if (has) px(24).toFloat() else 0f
            name.animate().translationY(if (has) px(12).toFloat() else 0f).setDuration(160).start()
            name.setTextColor(if (has) ink else slate)
        }
        return col
    }

    // ---- atajos, uso reciente y tiempo ----
    private fun go(i: Intent) = startActivity(i, ActivityOptions.makeCustomAnimation(this, R.anim.noad_in, R.anim.noad_out).toBundle())

    private fun openAction(action: String, t: String) {
        try { go(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Exception) { Toast.makeText(this, "No se pudo abrir $t", Toast.LENGTH_SHORT).show() }
    }

    private fun openLiveTv() {
        try {
            go(Intent(Intent.ACTION_MAIN).setClassName("com.orange.es.stb", "com.orange.es.tv_core.presentation.ui.navigation.view.NavigationActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        } catch (e: Exception) { Toast.makeText(this, "No se pudo abrir la TV en vivo", Toast.LENGTH_SHORT).show() }
    }

    private fun chip(t: String, onClick: () -> Unit): TextView {
        // fondo único cuyo color se anima; sin escalar al enfocar (el recorte del contenedor deformaba el botón)
        val bg = GradientDrawable().apply { cornerRadius = px(30).toFloat(); setColor(Color.TRANSPARENT); setStroke(px(3), teal) }
        return TextView(this).apply {
            text = t; typeface = fLabel; setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(24)); setTextColor(ink); gravity = Gravity.CENTER
            setPadding(px(26), 0, px(26), 0); background = bg; isFocusable = true; isClickable = true
            setOnClickListener { onClick() }
            setOnFocusChangeListener { v, has ->
                val tv = v as TextView
                ValueAnimator.ofFloat(if (has) 0f else 1f, if (has) 1f else 0f).apply {
                    duration = 180; interpolator = easeOut
                    addUpdateListener {
                        val f = it.animatedValue as Float
                        bg.setColor(android.graphics.Color.argb((255 * f).toInt(), 245, 243, 240))
                        bg.setStroke(px(3), android.animation.ArgbEvaluator().evaluate(f, teal, ink) as Int)
                        tv.setTextColor(android.animation.ArgbEvaluator().evaluate(f, ink, ground) as Int)
                    }
                }.start()
            }
            setOnKeyListener { v, k, e ->
                if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) {
                    if (e.action == KeyEvent.ACTION_DOWN) v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start()
                    else if (e.action == KeyEvent.ACTION_UP) v.animate().scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(easeOut).start()
                }
                false
            }
        }
    }

    private fun recordRecent(pkg: String) {
        val cur = prefs.getString("recent", "")!!.split(",").filter { it.isNotEmpty() && it != pkg }.toMutableList()
        cur.add(0, pkg)
        prefs.edit().putString("recent", cur.take(40).joinToString(",")).apply()
    }

    private lateinit var wTemp: TextView
    private lateinit var wCond: TextView
    private lateinit var wRange: TextView
    private var lastTry = 0L

    private fun weatherPanel(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(30), px(20), px(30), px(20))
            background = wBg(false)
            isFocusable = true; isClickable = true
            setOnClickListener { showForecast() }
            setOnFocusChangeListener { v, has -> v.background = wBg(has); v.animate().scaleX(if (has) 1.03f else 1f).scaleY(if (has) 1.03f else 1f).setDuration(140).start() }
        }
        wBox = box
        box.addView(TextView(this).apply { text = "MADRID · 7 DÍAS"; typeface = fLabel; setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(24)); letterSpacing = 0.22f; setTextColor(slate) })
        wTemp = TextView(this).apply { typeface = fDisplay; setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(88)); letterSpacing = -0.04f; setTextColor(ink); includeFontPadding = false }
        wCond = TextView(this).apply { typeface = fDisplay; setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(32)); setTextColor(ink) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        row.addView(wTemp, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(wCond, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = px(22); bottomMargin = px(10) })
        box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(6) })
        wRange = TextView(this).apply { typeface = fLabel; setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(26)); setTextColor(slate) }
        box.addView(wRange, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(4) })
        return box
    }

    private fun wmo(c: Int) = when (c) {
        0 -> "Despejado"; 1 -> "Casi despejado"; 2 -> "Parcialmente nublado"; 3 -> "Nublado"
        45, 48 -> "Niebla"; in 51..57 -> "Llovizna"; in 61..67 -> "Lluvia"; in 71..77 -> "Nieve"
        in 80..82 -> "Chubascos"; 85, 86 -> "Chubascos de nieve"; in 95..99 -> "Tormenta"; else -> "Tiempo variable"
    }

    private fun renderWeather() {
        if (!::wTemp.isInitialized) return
        if (!prefs.contains("w_temp")) { wTemp.text = "--°"; wCond.text = "Sin datos"; wRange.text = "Comprobando el tiempo…"; return }
        wTemp.text = "${prefs.getInt("w_temp", 0)}°"
        wCond.text = wmo(prefs.getInt("w_code", -1))
        wRange.text = "Mín ${prefs.getInt("w_min", 0)}° · Máx ${prefs.getInt("w_max", 0)}° · Lluvia ${prefs.getInt("w_pop", 0)}%"
    }

    private fun fetchWeather() {
        val now = System.currentTimeMillis()
        val fresh = now - prefs.getLong("w_time", 0L) < 30 * 60_000L && prefs.contains("w7")
        if (fresh || now - lastTry < 5 * 60_000L) return
        lastTry = now
        Thread {
            try {
                val url = URL("https://api.open-meteo.com/v1/forecast?latitude=40.4168&longitude=-3.7038&current=temperature_2m,weather_code" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=Europe%2FMadrid&forecast_days=7")
                val c = url.openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                val cur = j.getJSONObject("current"); val d = j.getJSONObject("daily")
                prefs.edit()
                    .putInt("w_temp", Math.round(cur.getDouble("temperature_2m")).toInt())
                    .putInt("w_code", cur.getInt("weather_code"))
                    .putInt("w_max", Math.round(d.getJSONArray("temperature_2m_max").getDouble(0)).toInt())
                    .putInt("w_min", Math.round(d.getJSONArray("temperature_2m_min").getDouble(0)).toInt())
                    .putInt("w_pop", d.getJSONArray("precipitation_probability_max").optInt(0))
                    .putString("w7", d.toString())
                    .putLong("w_time", System.currentTimeMillis()).apply()
                runOnUiThread { renderWeather() }
            } catch (e: Exception) {
                runOnUiThread { if (!prefs.contains("w_temp")) { wCond.text = "Sin conexión"; wRange.text = "No se pudo cargar el tiempo" } }
            }
        }.start()
    }

    private lateinit var wBox: View
    private fun wBg(f: Boolean) = GradientDrawable().apply { cornerRadius = px(24).toFloat(); setColor(panel); setStroke(px(3), if (f) ink else teal) }

    // ---- paneles con animación (tiempo a 7 días, datos geek) ----
    private var overlay: FrameLayout? = null
    private var panelCol: LinearLayout? = null
    private var prevFocus: View? = null
    private var sysTick: Runnable? = null
    private val easeOut = PathInterpolator(0.16f, 1f, 0.3f, 1f)

    private fun openPanel(title: String, build: (LinearLayout) -> List<View>) {
        if (overlay != null) return
        prevFocus = currentFocus
        val frame = window.decorView.findViewById<ViewGroup>(android.R.id.content)
        val scrim = FrameLayout(this).apply { setBackgroundColor(Color.argb(255, 15, 20, 25)); isFocusable = true; alpha = 0f }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(96), px(64), px(96), px(30)) }
        val head = FrameLayout(this)
        head.addView(TextView(this).apply { text = title.uppercase(Locale("es", "ES")); typeface = fLabel; setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(26)); letterSpacing = 0.22f; setTextColor(slate) },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START))
        head.addView(TextView(this).apply { text = "Atrás para cerrar"; typeface = fLabel; setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(24)); setTextColor(slate) },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END))
        col.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val items = build(col)
        scrim.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        scrim.setOnKeyListener { _, k, e ->
            if (e.action == KeyEvent.ACTION_UP && (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER)) { closePanel(); true } else false
        }
        frame.addView(scrim, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay = scrim; panelCol = col; scrim.requestFocus()
        // entrada: velo, panel que sube con expo-out y elementos escalonados
        scrim.animate().alpha(1f).setDuration(240).start()
        col.alpha = 0f; col.translationY = px(56).toFloat(); col.scaleX = 0.98f; col.scaleY = 0.98f
        col.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(460).setInterpolator(easeOut).start()
        items.forEachIndexed { i, v ->
            v.alpha = 0f; v.translationY = px(40).toFloat()
            v.animate().alpha(1f).translationY(0f).setStartDelay(100L + i * 45L).setDuration(520).setInterpolator(easeOut).start()
        }
    }

    private fun closePanel() {
        val o = overlay ?: return
        overlay = null
        sysTick?.let { handler.removeCallbacks(it) }; sysTick = null
        panelCol?.animate()?.alpha(0f)?.translationY(px(28).toFloat())?.setDuration(180)?.setInterpolator(AccelerateInterpolator())?.start()
        o.animate().alpha(0f).setStartDelay(40).setDuration(200).withEndAction { (o.parent as? ViewGroup)?.removeView(o) }.start()
        (prevFocus ?: if (::wBox.isInitialized) wBox else null)?.requestFocus()
    }

    private fun showForecast() {
        openPanel("Próximos 7 días · Madrid") { col ->
            val rows = ArrayList<View>()
            val raw = prefs.getString("w7", null)
            if (raw == null) {
                val t = TextView(this).apply { text = "Todavía no hay datos del tiempo."; typeface = fDisplay; setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(40)); setTextColor(ink) }
                col.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(40) })
                rows.add(t)
            } else {
                val d = JSONObject(raw); val days = d.getJSONArray("time")
                val tmax = d.getJSONArray("temperature_2m_max"); val tmin = d.getJSONArray("temperature_2m_min")
                val pop = d.getJSONArray("precipitation_probability_max"); val code = d.getJSONArray("weather_code")
                val parse = SimpleDateFormat("yyyy-MM-dd", Locale.US); val fmt = SimpleDateFormat("EEEE d", Locale("es", "ES"))
                for (i in 0 until days.length()) {
                    val name = when (i) { 0 -> "Hoy"; 1 -> "Mañana"; else -> fmt.format(parse.parse(days.getString(i))!!).replaceFirstChar { it.uppercase() } }
                    val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    fun cell(t: String, w: Int, f: Typeface, size: Int, color: Int, g: Int, weight: Float = 0f) {
                        val tv = TextView(this).apply { text = t; typeface = f; setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(size)); setTextColor(color); gravity = g }
                        row.addView(tv, if (weight > 0f) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight) else LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT))
                    }
                    cell(name, px(380), fDisplay, 36, ink, Gravity.START)
                    cell(wmo(code.getInt(i)), 0, fLabel, 30, slate, Gravity.START, 1f)
                    cell("${Math.round(tmin.getDouble(i))}° / ${Math.round(tmax.getDouble(i))}°", px(240), fDisplay, 36, ink, Gravity.END)
                    cell("Lluvia ${pop.optInt(i)}%", px(240), fLabel, 28, slate, Gravity.END)
                    wrap.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(92)))
                    wrap.addView(View(this).apply { setBackgroundColor(Color.argb(90, 27, 74, 92)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(2)))
                    col.addView(wrap, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    rows.add(wrap)
                }
            }
            rows
        }
    }

    // ---- datos geek del sistema ----
    private class SysCard(val value: TextView, val sub: TextView, val fill: View?, var shown: Double = 0.0)
    private val sys = LinkedHashMap<String, SysCard>()

    private fun gb(bytes: Long) = String.format(Locale("es", "ES"), "%.1f GB", bytes / 1073741824.0)

    private fun thermal(): Int? = try {
        File("/sys/class/thermal").listFiles()?.filter { it.name.startsWith("thermal_zone") }
            ?.mapNotNull { File(it, "temp").takeIf { f -> f.canRead() }?.readText()?.trim()?.toIntOrNull() }
            ?.map { if (it > 1000) it / 1000 else it }?.filter { it in 1..130 }?.maxOrNull()
    } catch (e: Exception) { null }

    private fun sysCard(key: String, title: String, bar: Boolean): Pair<View, SysCard> {
        val padH = px(26)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(padH, px(18), padH, px(18))
            background = GradientDrawable().apply { cornerRadius = px(22).toFloat(); setColor(panel); setStroke(px(3), teal) }
        }
        card.addView(TextView(this).apply { text = title.uppercase(Locale("es", "ES")); typeface = fLabel; setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(24)); letterSpacing = 0.18f; setTextColor(slate) })
        val v = TextView(this).apply { typeface = fDisplay; setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(44)); setTextColor(ink); maxLines = 1; includeFontPadding = false }
        card.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(8) })
        val sub = TextView(this).apply { typeface = fLabel; setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(24)); setTextColor(slate); maxLines = 1 }
        card.addView(sub, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(2) })
        var fill: View? = null
        if (bar) {
            val track = FrameLayout(this).apply { setBackgroundColor(Color.argb(90, 27, 74, 92)) }
            fill = View(this).apply { setBackgroundColor(ink) }
            track.addView(fill, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
            card.addView(track, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(6)).apply { topMargin = px(10) })
        }
        return card to SysCard(v, sub, fill)
    }

    private fun showSystem() {
        sys.clear()
        openPanel("Sistema") { col ->
            val defs = ArrayList<Triple<String, String, Boolean>>().apply {
                add(Triple("ram", "RAM libre", true)); add(Triple("disk", "Disco libre", true)); add(Triple("net", "Red local", false))
                add(Triple("up", "Encendida", false)); add(Triple("apps", "Apps", false)); add(Triple("model", "Equipo", false))
                add(Triple("android", "Android", false)); add(Triple("screen", "Pantalla", false)); add(Triple("cpu", "Procesador", false))
                if (thermal() != null) add(Triple("temp", "Temperatura", false))
            }
            val cards = ArrayList<View>()
            val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            defs.chunked(3).forEachIndexed { ri, chunk ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                chunk.forEachIndexed { ci, (k, t, b) ->
                    val (view, sc) = sysCard(k, t, b); sys[k] = sc; cards.add(view)
                    row.addView(view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (ci > 0) leftMargin = px(18) })
                }
                grid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (ri > 0) topMargin = px(18) })
            }
            col.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(34) })
            // actualización en vivo mientras el panel está abierto
            val t = object : Runnable { override fun run() { updateSys(); handler.postDelayed(this, 2000) } }
            sysTick = t; handler.postDelayed(t, 250)
            cards
        }
    }

    private fun setBar(c: SysCard, frac: Double) {
        val f = c.fill ?: return
        val track = f.parent as View
        track.post {
            val to = (track.width * frac.coerceIn(0.0, 1.0)).toInt()
            ValueAnimator.ofInt(f.layoutParams.width, to).apply {
                duration = 700; interpolator = easeOut
                addUpdateListener { f.layoutParams = (f.layoutParams as FrameLayout.LayoutParams).apply { width = it.animatedValue as Int }; f.requestLayout() }
            }.start()
        }
    }

    private fun updateSys() {
        if (overlay == null || sys.isEmpty()) return
        val mi = ActivityManager.MemoryInfo(); (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        sys["ram"]?.let { it.value.text = gb(mi.availMem); it.sub.text = "de ${gb(mi.totalMem)} · ${100 - Math.round(mi.availMem * 100.0 / mi.totalMem)}% en uso"; setBar(it, mi.availMem.toDouble() / mi.totalMem) }
        val st = StatFs(Environment.getDataDirectory().path)
        sys["disk"]?.let { it.value.text = gb(st.availableBytes); it.sub.text = "de ${gb(st.totalBytes)} · ${100 - Math.round(st.availableBytes * 100.0 / st.totalBytes)}% usado"; setBar(it, st.availableBytes.toDouble() / st.totalBytes) }
        val nif = localNet()
        sys["net"]?.let { it.value.text = nif?.second ?: "Sin red"; it.sub.text = nif?.first ?: "—" }
        val up = SystemClock.elapsedRealtime() / 60000
        sys["up"]?.let { it.value.text = if (up >= 1440) "${up / 1440} d ${up % 1440 / 60} h" else if (up >= 60) "${up / 60} h ${up % 60} min" else "$up min"; it.sub.text = "desde el último reinicio" }
        sys["apps"]?.let { it.value.text = "$lastAppCount"; it.sub.text = "visibles en este launcher" }
        sys["model"]?.let { it.value.text = Build.MODEL; it.sub.text = Build.MANUFACTURER }
        sys["android"]?.let { it.value.text = "Android ${Build.VERSION.RELEASE}"; it.sub.text = "API ${Build.VERSION.SDK_INT} · parche ${Build.VERSION.SECURITY_PATCH}" }
        sys["screen"]?.let {
            val dm = resources.displayMetrics
            it.value.text = "${dm.widthPixels}×${dm.heightPixels}"
            it.sub.text = "${dm.densityDpi} dpi · ${Math.round(windowManager.defaultDisplay.refreshRate)} Hz"
        }
        sys["cpu"]?.let { it.value.text = "${Runtime.getRuntime().availableProcessors()} núcleos"; it.sub.text = Build.SUPPORTED_ABIS.firstOrNull() ?: "" }
        sys["temp"]?.let { c -> thermal()?.let { c.value.text = "$it °C"; c.sub.text = "SoC (máx. de las zonas térmicas)" } }
    }

    private fun localNet(): Pair<String, String>? = try {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.firstNotNullOfOrNull { ni ->
            ni.inetAddresses.toList().firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }?.hostAddress?.let { ip ->
                (if (ni.name.startsWith("wlan")) "Wi-Fi" else if (ni.name.startsWith("eth")) "Ethernet" else ni.name) to ip
            }
        }
    } catch (e: Exception) { null }

    private var lastAppCount = 0

    // ---- vistas propias ----
    private inner class BgView(c: Context) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            canvas.drawColor(ground)
            p.shader = RadialGradient(w * 0.70f, h * 0.18f, w * 0.52f, Color.argb(115, 27, 74, 92), Color.argb(0, 27, 74, 92), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, p)
            p.shader = null; p.color = Color.argb(51, 27, 74, 92); p.strokeWidth = 2f * s
            var x = -60f * s; while (x < w) { canvas.drawLine(x, 0f, x, h, p); x += 120f * s }
            var y = 0f; while (y < h) { canvas.drawLine(0f, y, w, y, p); y += 120f * s }
            p.shader = RadialGradient(w * 0.42f, h * 0.46f, w * 0.75f, intArrayOf(Color.argb(0, 15, 20, 25), Color.argb(0, 15, 20, 25), Color.argb(237, 15, 20, 25)), floatArrayOf(0f, 0.30f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, p); p.shader = null
        }
    }

    private inner class SettingsButton(c: Context) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        init {
            isFocusable = true; isClickable = true
            setOnFocusChangeListener { _, _ -> invalidate() }
        }
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val c = w / 2f; val f = isFocused
            p.style = Paint.Style.FILL; p.color = if (f) ink else Color.TRANSPARENT; canvas.drawCircle(c, c, c - 2f, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 3f * s; p.color = if (f) ink else teal; canvas.drawCircle(c, c, c - 3f * s, p)
            p.color = if (f) ground else ink; p.strokeWidth = 4f * s; p.strokeCap = Paint.Cap.ROUND
            val a = w * 0.30f; val b = w * 0.70f
            for ((i, yy) in listOf(0.34f, 0.5f, 0.66f).withIndex()) {
                canvas.drawLine(a, w * yy, b, w * yy, p)
                val kx = if (i == 1) w * 0.62f else w * 0.40f
                p.style = Paint.Style.FILL; canvas.drawCircle(kx, w * yy, 5f * s, p); p.style = Paint.Style.STROKE
            }
        }
    }
}

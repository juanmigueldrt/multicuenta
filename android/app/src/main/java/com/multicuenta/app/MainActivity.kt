package com.multicuenta.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Franz/Rambox-style multi-account client for WhatsApp Web and Telegram Web.
 * Left: vertical rail with one round icon per account and a "+" button.
 * Right: the active account's WebView. Every account uses its own WebView
 * profile (cookies, storage, service workers), so sessions never mix.
 * Emojis use the system font, i.e. Android's Noto Color Emoji.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var store: AccountStore
    private val accounts = mutableListOf<Account>()
    private val views = mutableMapOf<String, View>() // WebView, or a message when it can't be isolated
    private val unread = mutableMapOf<String, Int>()
    private var activeId: String? = null

    private lateinit var rail: LinearLayout
    private lateinit var content: FrameLayout

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermission: PermissionRequest? = null

    private val isolationSupported by lazy {
        WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
    }

    private val fileChooser =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data))
            fileCallback = null
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            pendingPermission?.let { grantAllowed(it) }
            pendingPermission = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AccountStore(this)
        accounts += store.load()
        if (accounts.isEmpty()) {
            accounts += Account(store.newId(Service.WHATSAPP, accounts), Service.WHATSAPP, "Personal")
            store.save(accounts)
        }

        rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val railScroll = ScrollView(this).apply {
            setBackgroundColor(DARK)
            isVerticalScrollBarEnabled = false
            addView(rail)
        }
        content = FrameLayout(this).apply { setBackgroundColor(GREY) }

        val root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(railScroll, LinearLayout.LayoutParams(dp(72), MATCH_PARENT))
        root.addView(content, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = activeId?.let { views[it] } as? WebView
                if (wv != null && wv.canGoBack()) {
                    wv.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        show(store.activeId?.takeIf { id -> accounts.any { it.id == id } } ?: accounts.first().id)
    }

    override fun onDestroy() {
        views.values.filterIsInstance<WebView>().forEach { it.destroy() }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- accounts

    private fun show(id: String) {
        val acc = accounts.firstOrNull { it.id == id } ?: return
        activeId = id
        store.activeId = id
        val view = views.getOrPut(id) {
            createView(acc).also { content.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) }
        }
        views.values.forEach { it.visibility = if (it === view) View.VISIBLE else View.GONE }
        renderRail()
    }

    private fun createView(acc: Account): View {
        // Without profiles every WebView shares one cookie jar: a second account of the
        // same service would overwrite the first one's login, so refuse instead.
        val sameService = accounts.filter { it.service == acc.service }
        if (!isolationSupported && sameService.indexOf(acc) > 0) {
            return message(
                "Para usar varias cuentas de ${acc.service.label} a la vez, actualiza " +
                    "«Android System WebView» desde Google Play. Esta versión no admite perfiles aislados."
            )
        }

        val wv = WebView(this)
        if (isolationSupported) {
            // Must happen before any other call on this WebView
            ProfileStore.getInstance().getOrCreateProfile(acc.id)
            WebViewCompat.setProfile(wv, acc.id)
        }
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            // WhatsApp Web only serves desktop browsers; on mobile it redirects to the app store
            if (acc.service == Service.WHATSAPP) userAgentString = DESKTOP_UA
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (isServiceHost(request.url.host)) return false
                openExternal(request.url)
                return true
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) {
                unread[acc.id] = parseUnread(title.orEmpty())
                renderRail()
            }

            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams,
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    fileChooser.launch(params.createIntent())
                    true
                } catch (e: ActivityNotFoundException) {
                    fileCallback = null
                    false
                }
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                if (!isServiceHost(request.origin.host)) {
                    request.deny()
                    return
                }
                val missing = request.resources.mapNotNull { androidPermissionFor(it) }
                    .distinct().filterNot { hasPermission(it) }
                if (missing.isEmpty()) {
                    grantAllowed(request)
                } else {
                    pendingPermission?.deny()
                    pendingPermission = request
                    permissionLauncher.launch(missing.toTypedArray())
                }
            }
        }
        wv.loadUrl(acc.service.url)
        return wv
    }

    private fun showAddDialog() {
        val whatsapp = RadioButton(this).apply { id = View.generateViewId(); text = "WhatsApp"; isChecked = true }
        val telegram = RadioButton(this).apply { id = View.generateViewId(); text = "Telegram" }
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(whatsapp)
            addView(telegram)
        }
        val name = EditText(this).apply {
            hint = "Nombre de la cuenta (ej. Ventas)"
            setSingleLine()
            background = pill(GREY)
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(group)
            addView(name, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
        }
        AlertDialog.Builder(this)
            .setTitle("Añadir cuenta")
            .setView(box)
            .setPositiveButton("Añadir") { _, _ ->
                val svc = if (telegram.isChecked) Service.TELEGRAM else Service.WHATSAPP
                val label = name.text.toString().trim().ifEmpty { svc.label }
                val acc = Account(store.newId(svc, accounts), svc, label)
                accounts += acc
                store.save(accounts)
                show(acc.id)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showAccountMenu(acc: Account) {
        AlertDialog.Builder(this)
            .setTitle("${acc.name} · ${acc.service.label}")
            .setItems(arrayOf("Recargar", "Eliminar cuenta")) { _, which ->
                when (which) {
                    0 -> (views[acc.id] as? WebView)?.reload()
                    1 -> confirmDelete(acc)
                }
            }
            .show()
    }

    private fun confirmDelete(acc: Account) {
        AlertDialog.Builder(this)
            .setTitle("¿Eliminar ${acc.name}?")
            .setMessage(
                "Se cerrará la sesión de ${acc.service.label} de esta cuenta y se borrarán sus datos " +
                    "en este teléfono. No se puede deshacer."
            )
            .setPositiveButton("Eliminar") { _, _ -> deleteAccount(acc) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun deleteAccount(acc: Account) {
        views.remove(acc.id)?.let { v ->
            content.removeView(v)
            (v as? WebView)?.destroy()
        }
        unread.remove(acc.id)
        accounts.remove(acc)
        store.save(accounts)
        if (isolationSupported) {
            try {
                ProfileStore.getInstance().deleteProfile(acc.id)
            } catch (e: IllegalStateException) {
                // Profile still held by the destroyed WebView; its data stays until next launch
                Toast.makeText(this, "Los datos se borrarán al reiniciar la app", Toast.LENGTH_LONG).show()
            }
        }
        if (accounts.isEmpty()) {
            activeId = null
            renderRail()
            showAddDialog()
        } else {
            show(if (activeId == acc.id) accounts.first().id else activeId!!)
        }
    }

    // ---------------------------------------------------------------- rail UI

    private fun renderRail() {
        rail.removeAllViews()
        accounts.forEach { rail.addView(railItem(it)) }

        val plus = TextView(this).apply {
            text = "+"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(0xFFAEBAC1.toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2), 0xFF54656F.toInt())
            }
            contentDescription = "Añadir cuenta de WhatsApp o Telegram"
            setOnClickListener { showAddDialog() }
        }
        rail.addView(plus, LinearLayout.LayoutParams(dp(44), dp(44)).apply { topMargin = dp(8) })
    }

    private fun railItem(acc: Account): View {
        val active = acc.id == activeId
        val icon = FrameLayout(this)

        val avatar = TextView(this).apply {
            text = acc.name.take(1).uppercase()
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(acc.service.color)
                if (active) setStroke(dp(2), Color.WHITE)
            }
            alpha = if (active) 1f else 0.8f
        }
        icon.addView(avatar, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER))

        val badge = TextView(this).apply {
            text = acc.service.letter
            textSize = 8f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(acc.service.color)
                setStroke(dp(2), DARK)
            }
        }
        icon.addView(badge, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.BOTTOM or Gravity.END))

        val count = unread[acc.id] ?: 0
        if (count > 0) {
            val pillView = TextView(this).apply {
                text = if (count > 99) "99+" else count.toString()
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(dp(5), 0, dp(5), 0)
                minWidth = dp(18)
                background = pill(0xFF25D366.toInt()).apply { setStroke(dp(2), DARK) }
            }
            icon.addView(pillView, FrameLayout.LayoutParams(WRAP_CONTENT, dp(18), Gravity.TOP or Gravity.END))
        }

        val label = TextView(this).apply {
            text = acc.name
            textSize = 10f
            maxLines = 1
            gravity = Gravity.CENTER
            setTextColor(if (active) 0xFFD1D7DB.toInt() else 0xFF8696A0.toInt())
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
            addView(icon, LinearLayout.LayoutParams(dp(52), dp(52)))
            addView(label, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            contentDescription = "${acc.name}, ${acc.service.label}" + if (count > 0) ", $count sin leer" else ""
            setOnClickListener { show(acc.id) }
            setOnLongClickListener { showAccountMenu(acc); true }
        }
    }

    private fun message(text: String): View = TextView(this).apply {
        this.text = text
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(0xFF54656F.toInt())
        setPadding(dp(32), dp(32), dp(32), dp(32))
    }

    // ---------------------------------------------------------------- helpers

    private fun grantAllowed(request: PermissionRequest) {
        val allowed = request.resources.filter { r -> androidPermissionFor(r)?.let { hasPermission(it) } == true }
        if (allowed.isEmpty()) request.deny() else request.grant(allowed.toTypedArray())
    }

    private fun androidPermissionFor(resource: String): String? = when (resource) {
        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
        else -> null
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun isServiceHost(host: String?): Boolean {
        val h = host ?: return false
        return SERVICE_HOSTS.any { h == it || h.endsWith(".$it") }
    }

    private fun openExternal(uri: Uri) {
        if (uri.scheme != "http" && uri.scheme != "https") return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No hay navegador para abrir el enlace", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        cornerRadius = dp(24).toFloat()
        setColor(color)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val DARK = 0xFF0B141A.toInt()
        private const val GREY = 0xFFF0F2F5.toInt()
        private val SERVICE_HOSTS = listOf("whatsapp.com", "whatsapp.net", "telegram.org")
        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}

package com.multicuenta.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Html
import android.text.InputType
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/**
 * Multichat for WhatsApp Web and Telegram Web, following design/multichat-m3.figcraft (Material 3):
 * bottom navigation (Chats / Cuentas / Ajustes), account chips over the active account's web chat,
 * an accounts screen grouped by service, and a linking screen that shows each service's own login.
 * Every account runs in its own WebView profile, so logins never mix.
 */
class MainActivity : AppCompatActivity() {

    private enum class Screen { CHATS, ACCOUNTS, SETTINGS, LINK }
    private enum class Status { ACTIVE, ERROR }
    private enum class LinkMode { QR, PHONE }
    private enum class PhoneStage { FORM, WAITING, WEB }

    /** What the account's web page reports through [Bridge]: login state, QR image, pairing code. */
    private data class PageState(val logged: Boolean, val loginPage: Boolean, val qr: String?, val code: String?)

    private val pageState = mutableMapOf<String, PageState>()
    private val qrBitmaps = mutableMapOf<String, Bitmap>()
    private var linkMode = LinkMode.QR
    private var phoneStage = PhoneStage.FORM
    private var linkPhone = ""
    private var linkShowWeb = false

    private lateinit var store: AccountStore
    private val accounts = mutableListOf<Account>()
    private val views = mutableMapOf<String, View>() // WebView, or a message when it can't be isolated
    private val unread = mutableMapOf<String, Int>()
    private val status = mutableMapOf<String, Status>()
    private var activeId: String? = null
    private var linkingId: String? = null
    private var screen = Screen.CHATS

    private lateinit var topBar: LinearLayout
    private lateinit var chipsScroll: HorizontalScrollView
    private lateinit var chips: LinearLayout
    private lateinit var webHost: FrameLayout
    private lateinit var placeholder: FrameLayout // native view shown in place of a web view
    private lateinit var linkHint: TextView
    private lateinit var chatsPane: LinearLayout
    private lateinit var pagePane: FrameLayout
    private lateinit var bottomNav: LinearLayout

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
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) WebView.setWebContentsDebuggingEnabled(true)
        accounts += store.load()
        activeId = store.activeId?.takeIf { id -> accounts.any { it.id == id } } ?: accounts.firstOrNull()?.id

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(SURFACE)
        }
        chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(4), dp(16), dp(8))
        }
        chipsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(SURFACE)
            addView(chips)
        }
        linkHint = text("", 12f, ON_SURFACE_VARIANT).apply {
            setPadding(dp(16), dp(4), dp(16), dp(12))
            setBackgroundColor(SURFACE)
        }
        placeholder = FrameLayout(this).apply { setBackgroundColor(SURFACE); visibility = View.GONE }
        webHost = FrameLayout(this).apply {
            setBackgroundColor(SURFACE)
            addView(placeholder, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        chatsPane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(chipsScroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(linkHint, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(webHost, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        pagePane = FrameLayout(this).apply { setBackgroundColor(SURFACE) }
        val body = FrameLayout(this).apply {
            addView(chatsPane, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(pagePane, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        bottomNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(SURFACE_CONTAINER)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SURFACE)
            addView(topBar, LinearLayout.LayoutParams(MATCH_PARENT, dp(64)))
            addView(body, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(bottomNav, LinearLayout.LayoutParams(MATCH_PARENT, dp(80)))
        }
        setContentView(root)

        // Edge-to-edge (Android 15): pad by bars, cutout and keyboard; dark icons on the light surface
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (screen) {
                    Screen.LINK -> linkBack()
                    Screen.ACCOUNTS, Screen.SETTINGS -> go(Screen.CHATS)
                    Screen.CHATS -> {
                        val wv = activeId?.let { views[it] } as? WebView
                        if (wv != null && wv.canGoBack()) {
                            wv.goBack()
                        } else {
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                            isEnabled = true
                        }
                    }
                }
            }
        })

        go(Screen.CHATS)
    }

    override fun onDestroy() {
        views.values.filterIsInstance<WebView>().forEach { it.destroy() }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- navigation

    private fun go(target: Screen) {
        screen = target
        // The link screen is native; the web page only shows when the user asks for it
        val showWeb = target == Screen.CHATS || (target == Screen.LINK && linkShowWeb)
        chatsPane.visibility = if (showWeb) View.VISIBLE else View.GONE
        pagePane.visibility = if (showWeb) View.GONE else View.VISIBLE
        bottomNav.visibility = if (target == Screen.LINK) View.GONE else View.VISIBLE
        chipsScroll.visibility = if (target == Screen.CHATS && accounts.size > 1) View.VISIBLE else View.GONE
        linkHint.visibility = if (target == Screen.LINK) View.VISIBLE else View.GONE

        when (target) {
            Screen.CHATS -> {
                renderTopBar("Chats")
                showWeb(activeId)
                when {
                    accounts.isEmpty() -> showEmptyChats()
                    pageState[activeId]?.let { it.loginPage && !it.logged } == true -> showUnlinkedChats()
                }
            }
            Screen.LINK -> {
                val acc = accounts.firstOrNull { it.id == linkingId } ?: return go(Screen.ACCOUNTS)
                renderTopBar("Vincular ${acc.service.label}", back = true, logo = logoRes(acc.service))
                if (linkShowWeb) {
                    linkHint.text = "Página de inicio de sesión de ${acc.service.label}. Pulsa ← para volver."
                    showWeb(acc.id)
                } else {
                    ensureView(acc) // the page keeps running hidden and feeds the native screen
                    renderLink(acc)
                }
            }
            Screen.ACCOUNTS -> {
                renderTopBar("Cuentas")
                renderAccounts()
            }
            Screen.SETTINGS -> {
                renderTopBar("Ajustes")
                renderSettings()
            }
        }
        renderBottomNav()
    }

    private fun ensureView(acc: Account): View = views.getOrPut(acc.id) {
        createView(acc).also { webHost.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) }
    }

    /** Shows [id]'s web view (created on first use) and hides every other one. */
    private fun showWeb(id: String?) {
        val acc = accounts.firstOrNull { it.id == id }
        val view = acc?.let { ensureView(it) }
        views.values.forEach { it.visibility = if (it === view) View.VISIBLE else View.GONE }
        placeholder.visibility = View.GONE
        if (screen == Screen.CHATS && acc != null) {
            activeId = acc.id
            store.activeId = acc.id
        }
        renderChips()
    }

    // ---------------------------------------------------------------- bars

    private fun renderTopBar(title: String, back: Boolean = false, logo: Int? = null) {
        topBar.removeAllViews()
        topBar.setPadding(dp(if (back) 4 else 16), 0, dp(4), 0)
        if (back) topBar.addView(iconButton(R.drawable.ic_arrow_back, "Volver") { linkBack() })
        if (logo != null) {
            topBar.addView(ImageView(this).apply { setImageResource(logo) },
                LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(12) })
        }
        topBar.addView(text(title, 22f, ON_SURFACE), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (screen == Screen.CHATS && accounts.isNotEmpty()) {
            topBar.addView(iconButton(R.drawable.ic_more_vert, "Más opciones") { anchor ->
                PopupMenu(this, anchor).apply {
                    menu.add("Recargar")
                    menu.add("Cuentas")
                    setOnMenuItemClickListener { item ->
                        if (item.title == "Recargar") (activeId?.let { views[it] } as? WebView)?.reload()
                        else go(Screen.ACCOUNTS)
                        true
                    }
                }.show()
            })
        }
    }

    private fun renderBottomNav() {
        bottomNav.removeAllViews()
        val totalUnread = unread.values.sum()
        listOf(
            Triple(Screen.CHATS, "Chats", R.drawable.ic_chat_outline to R.drawable.ic_chat),
            Triple(Screen.ACCOUNTS, "Cuentas", R.drawable.ic_manage_accounts to R.drawable.ic_manage_accounts_fill),
            Triple(Screen.SETTINGS, "Ajustes", R.drawable.ic_settings to R.drawable.ic_settings),
        ).forEach { (target, label, icons) ->
            val selected = screen == target
            val indicator = FrameLayout(this).apply {
                background = if (selected) rounded(SECONDARY_CONTAINER, 16) else null
                addView(icon(if (selected) icons.second else icons.first, ON_SURFACE),
                    FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
                if (target == Screen.CHATS && totalUnread > 0) {
                    addView(badge(totalUnread), FrameLayout.LayoutParams(WRAP_CONTENT, dp(16), Gravity.TOP or Gravity.END)
                        .apply { topMargin = dp(1); marginEnd = dp(10) })
                }
            }
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(indicator, LinearLayout.LayoutParams(dp(64), dp(32)))
                addView(text(label, 12f, if (selected) ON_SURFACE else ON_SURFACE_VARIANT, medium = true).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(0, dp(4), 0, 0)
                })
                contentDescription = label
                setOnClickListener { go(target) }
            }
            bottomNav.addView(item, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        }
    }

    /** Filter-chip row: one chip per account; the selected one shows the check icon. */
    private fun renderChips() {
        chips.removeAllViews()
        accounts.forEach { acc ->
            val selected = acc.id == activeId
            val count = unread[acc.id] ?: 0
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), 0, dp(12), 0)
                background = if (selected) rounded(SECONDARY_CONTAINER, 8)
                else rounded(SURFACE, 8, stroke = OUTLINE_VARIANT)
                addView(
                    if (selected) icon(R.drawable.ic_check, ON_SECONDARY_CONTAINER, 18)
                    else ImageView(this@MainActivity).apply { setImageResource(logoRes(acc.service)) },
                    LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) },
                )
                addView(text(chipLabel(acc), 14f, if (selected) ON_SECONDARY_CONTAINER else ON_SURFACE_VARIANT, medium = true))
                if (count > 0) addView(badge(count), LinearLayout.LayoutParams(WRAP_CONTENT, dp(16)).apply { marginStart = dp(8) })
                contentDescription = "${acc.service.label} ${acc.name}" + if (count > 0) ", $count sin leer" else ""
                setOnClickListener { showWeb(acc.id) }
                setOnLongClickListener { accountMenu(acc, this); true }
            }
            chips.addView(chip, LinearLayout.LayoutParams(WRAP_CONTENT, dp(32)).apply { marginEnd = dp(8) })
        }
    }

    // ---------------------------------------------------------------- pages

    private fun showEmptyChats() {
        pagePane.removeAllViews()
        chatsPane.visibility = View.GONE
        pagePane.visibility = View.VISIBLE
        pagePane.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), 0, dp(32), 0)
            addView(text("Aún no hay cuentas", 22f, ON_SURFACE))
            addView(text("Vincula WhatsApp o Telegram para ver tus chats aquí.", 14f, ON_SURFACE_VARIANT).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(24))
            })
            addView(filledButton("Vincular cuenta") { go(Screen.ACCOUNTS) })
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    /** Chats tab for an account that is not linked yet: native prompt instead of the web login page. */
    private fun showUnlinkedChats() {
        val acc = accounts.firstOrNull { it.id == activeId } ?: return
        views[acc.id]?.visibility = View.GONE
        placeholder.removeAllViews()
        placeholder.visibility = View.VISIBLE
        placeholder.bringToFront()
        placeholder.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), 0, dp(32), 0)
            addView(ImageView(this@MainActivity).apply { setImageResource(logoRes(acc.service)) },
                LinearLayout.LayoutParams(dp(56), dp(56)).apply { bottomMargin = dp(16) })
            addView(text("${acc.service.label} · ${acc.name} sin vincular", 22f, ON_SURFACE).apply { gravity = Gravity.CENTER })
            addView(text("Vincula la cuenta para ver aquí sus chats.", 14f, ON_SURFACE_VARIANT).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(24))
            })
            addView(filledButton("Vincular ${acc.service.label}", brandButton(acc.service)) {
                startLink(acc)
            })
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private fun startLink(acc: Account) {
        linkingId = acc.id
        linkMode = LinkMode.QR
        phoneStage = PhoneStage.FORM
        linkShowWeb = false
        go(Screen.LINK)
    }

    private fun linkBack() {
        if (linkShowWeb) {
            linkShowWeb = false
            go(Screen.LINK)
        } else {
            go(Screen.ACCOUNTS)
        }
    }

    // ---------------------------------------------------------------- native link screen

    /** Native "Vincular" screen (design: Vincular · QR / Número / Código / Hecho). */
    private fun renderLink(acc: Account) {
        val state = pageState[acc.id]
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        linkHint.visibility = View.GONE
        pagePane.setPadding(0, 0, 0, 0)

        if (state?.logged == true) {
            col.addView(successView(acc))
        } else {
            col.addView(segmented(acc), LinearLayout.LayoutParams(MATCH_PARENT, dp(40)).apply { bottomMargin = dp(24) })
            when {
                state?.code != null -> col.addView(codeView(acc, state.code))
                linkMode == LinkMode.QR -> col.addView(qrView(acc))
                phoneStage == PhoneStage.WAITING -> col.addView(waitingView("Pidiendo el código a ${acc.service.label}…"))
                else -> col.addView(phoneForm(acc))
            }
            col.addView(textButton("Ver la página de inicio de sesión") {
                linkShowWeb = true
                go(Screen.LINK)
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(16) })
        }
        pagePane.removeAllViews()
        pagePane.addView(ScrollView(this).apply { addView(col) }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    /** M3 segmented button: Código QR | Número. */
    private fun segmented(acc: Account) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = rounded(SURFACE, 20, stroke = OUTLINE)
        listOf(LinkMode.QR to "Código QR", LinkMode.PHONE to "Número").forEachIndexed { i, (mode, label) ->
            val selected = linkMode == mode
            val half = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                if (selected) {
                    background = GradientDrawable().apply {
                        setColor(SECONDARY_CONTAINER)
                        val r = dp(20).toFloat()
                        cornerRadii = if (i == 0) floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
                        else floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
                    }
                    addView(icon(R.drawable.ic_check, ON_SECONDARY_CONTAINER, 18),
                        LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) })
                }
                addView(text(label, 14f, if (selected) ON_SECONDARY_CONTAINER else ON_SURFACE, medium = true))
                contentDescription = label
                setOnClickListener {
                    if (linkMode != mode) {
                        linkMode = mode
                        if (mode == LinkMode.QR && phoneStage != PhoneStage.FORM) {
                            // Back to QR after starting the phone flow: reload the login page
                            phoneStage = PhoneStage.FORM
                            (views[acc.id] as? WebView)?.reload()
                        }
                        renderLink(acc)
                    }
                }
            }
            addView(half, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
            if (i == 0) addView(View(this@MainActivity).apply { setBackgroundColor(OUTLINE) },
                LinearLayout.LayoutParams(dp(1), MATCH_PARENT))
        }
    }

    private fun qrView(acc: Account) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val card = FrameLayout(this@MainActivity).apply {
            background = rounded(SURFACE_LOWEST, 28)
            elevation = dp(2).toFloat()
            val bmp = qrBitmaps[acc.id]
            if (bmp != null) {
                addView(ImageView(this@MainActivity).apply {
                    setImageBitmap(bmp)
                    contentDescription = "Código QR para vincular ${acc.service.label}"
                }, FrameLayout.LayoutParams(dp(224), dp(224), Gravity.CENTER))
            } else {
                addView(ProgressBar(this@MainActivity), FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
            }
        }
        addView(card, LinearLayout.LayoutParams(dp(256), dp(256)).apply { topMargin = dp(24); bottomMargin = dp(24) })
        addView(html(when (acc.service) {
            Service.WHATSAPP -> "En WhatsApp, abre <b>Dispositivos vinculados</b> y escanea este código."
            Service.TELEGRAM -> "En Telegram, abre <b>Ajustes › Dispositivos › Vincular dispositivo</b> y escanea este código."
        }))
        addView(text("¿Es este mismo móvil? Usa «Número».", 12f, ON_SURFACE_VARIANT).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })
    }

    private fun phoneForm(acc: Account) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val prefix = field("+34", InputType.TYPE_CLASS_PHONE)
        val number = field("", InputType.TYPE_CLASS_PHONE).apply { hint = "612 345 678" }
        if (linkPhone.isNotEmpty()) {
            prefix.setText(linkPhone.substringBefore(' '))
            number.setText(linkPhone.substringAfter(' ', ""))
        }
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(labeled("Prefijo", prefix), LinearLayout.LayoutParams(dp(80), WRAP_CONTENT).apply { marginEnd = dp(8) })
            addView(labeled("Número de teléfono", number), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        })
        val label = if (acc.service == Service.WHATSAPP) "Obtener código" else "Continuar"
        addView(filledButton(label, brandButton(acc.service)) {
            val digits = number.text.toString().filter { it.isDigit() }
            val pre = "+" + prefix.text.toString().filter { it.isDigit() }
            if (digits.length < 6 || pre.length < 2) {
                Toast.makeText(this@MainActivity, "Escribe el prefijo y el número", Toast.LENGTH_SHORT).show()
                return@filledButton
            }
            linkPhone = "$pre $digits"
            startPhoneLink(acc, pre + digits)
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)).apply { topMargin = dp(16) })
    }

    private fun codeView(acc: Account, code: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(text("Código para $linkPhone", 14f, ON_SURFACE_VARIANT).apply { setPadding(0, dp(24), 0, dp(16)) })
        val chars = code.filter { it.isLetterOrDigit() }
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            chars.forEachIndexed { i, c ->
                if (i == 4 && chars.length == 8) addView(text("–", 22f, ON_SURFACE_VARIANT).apply { setPadding(dp(4), 0, dp(4), 0) })
                addView(text(c.toString(), 22f, ON_PRIMARY_CONTAINER, medium = true).apply {
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    background = rounded(PRIMARY_CONTAINER, 8)
                }, LinearLayout.LayoutParams(dp(34), dp(44)).apply { marginEnd = dp(4) })
            }
        })
        addView(html(
            "En ${acc.service.label}, abre <b>Dispositivos vinculados › Vincular con número</b> e introduce el código."
        ).apply { setPadding(0, dp(24), 0, 0) })
        addView(textButton("Cambiar número") {
            phoneStage = PhoneStage.FORM
            pageState[acc.id] = PageState(false, true, null, null)
            (views[acc.id] as? WebView)?.reload()
            renderLink(acc)
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(16) })
    }

    private fun successView(acc: Account) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(120), 0, 0)
        addView(FrameLayout(this@MainActivity).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(SUCCESS_CONTAINER) }
            addView(icon(R.drawable.ic_check, ON_SUCCESS_CONTAINER, 32), FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(64), dp(64)))
        addView(text("Cuenta vinculada", 22f, ON_SURFACE).apply { setPadding(0, dp(16), 0, dp(8)) })
        addView(text("${acc.service.label} · ${acc.name} ya aparece en tus chats.", 14f, ON_SURFACE_VARIANT).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(24))
        })
        addView(filledButton("Hecho", PRIMARY) {
            activeId = acc.id
            go(Screen.CHATS)
        })
    }

    private fun waitingView(message: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(48), 0, 0)
        addView(ProgressBar(this@MainActivity), LinearLayout.LayoutParams(dp(40), dp(40)))
        addView(text(message, 14f, ON_SURFACE_VARIANT).apply { setPadding(0, dp(16), 0, 0) })
    }

    /** Drives the web page's own phone-number login; the code comes back through [Bridge]. */
    private fun startPhoneLink(acc: Account, phone: String) {
        val wv = views[acc.id] as? WebView ?: return
        phoneStage = PhoneStage.WAITING
        renderLink(acc)
        wv.evaluateJavascript("window.__mcPhone && window.__mcPhone(${JSONObject.quote(phone)});", null)
    }

    // ---------------------------------------------------------------- page bridge

    /** Receives the page state posted by [PAGE_JS]; runs on a WebView thread. */
    private inner class Bridge(private val id: String) {
        @JavascriptInterface
        fun post(json: String) = runOnUiThread { onPageState(id, json) }

        @JavascriptInterface
        fun phoneFailed(step: String) = runOnUiThread { onPhoneFailed(id, step) }
    }

    private fun onPageState(id: String, json: String) {
        val o = try { JSONObject(json) } catch (e: org.json.JSONException) { return }
        val next = PageState(
            o.optBoolean("logged"),
            o.optBoolean("loginPage"),
            o.optString("qr").takeIf { it.startsWith("data:image") },
            o.optString("code").takeIf { it.isNotBlank() && it != "null" },
        )
        val prev = pageState[id]
        if (prev == next) return
        pageState[id] = next
        if (next.qr != null && next.qr != prev?.qr) {
            val bytes = Base64.decode(next.qr.substringAfter(','), Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { qrBitmaps[id] = it }
        }
        if (next.logged != prev?.logged) setStatus(id, if (next.logged) Status.ACTIVE else null)
        val acc = accounts.firstOrNull { it.id == id } ?: return
        when {
            screen == Screen.LINK && linkingId == id && !linkShowWeb -> renderLink(acc)
            screen == Screen.CHATS && activeId == id &&
                (next.logged != prev?.logged || next.loginPage != prev?.loginPage) -> go(Screen.CHATS)
        }
    }

    private fun onPhoneFailed(id: String, step: String) {
        if (linkingId != id || screen != Screen.LINK) return
        // The page changed and the automation can't find its controls: let the user finish in the page
        phoneStage = PhoneStage.WEB
        linkShowWeb = true
        Toast.makeText(this, "Termina la vinculación en la página ($step)", Toast.LENGTH_LONG).show()
        go(Screen.LINK)
    }

    private fun renderAccounts() {
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(24))
        }
        Service.entries.forEach { svc ->
            val ofService = accounts.filter { it.service == svc }
            if (ofService.isEmpty()) return@forEach
            list.addView(sectionLabel(svc.label))
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(SURFACE_LOWEST, 16)
            }
            ofService.forEachIndexed { i, acc ->
                if (i > 0) card.addView(View(this).apply { setBackgroundColor(SURFACE_CONTAINER) },
                    LinearLayout.LayoutParams(MATCH_PARENT, dp(1)))
                card.addView(accountRow(acc))
            }
            list.addView(card)
        }
        list.addView(sectionLabel("Añadir cuenta"))
        Service.entries.forEach { svc ->
            list.addView(linkButton(svc), LinearLayout.LayoutParams(MATCH_PARENT, dp(52)).apply { bottomMargin = dp(8) })
        }
        pagePane.removeAllViews()
        pagePane.addView(ScrollView(this).apply { addView(list) }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private fun accountRow(acc: Account): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(12), dp(4), dp(12))
        addView(ImageView(this@MainActivity).apply { setImageResource(logoRes(acc.service)) },
            LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(16) })
        val count = unread[acc.id] ?: 0
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(acc.name, 16f, ON_SURFACE))
            addView(text(if (count > 0) "$count sin leer" else "Sesión aislada", 14f, ON_SURFACE_VARIANT))
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        when (status[acc.id]) {
            Status.ACTIVE -> addView(statusChip("Activa", R.drawable.ic_check, SUCCESS_CONTAINER, ON_SUCCESS_CONTAINER))
            Status.ERROR -> addView(statusChip("Reconectar", R.drawable.ic_sync_problem, ERROR_CONTAINER, ON_ERROR_CONTAINER)
                .apply { setOnClickListener { (views[acc.id] as? WebView)?.reload() } })
            null -> if (pageState[acc.id]?.loginPage == true) {
                addView(statusChip("Vincular", R.drawable.ic_sync_problem, SECONDARY_CONTAINER, ON_SECONDARY_CONTAINER)
                    .apply { setOnClickListener { startLink(acc) } })
            }
        }
        addView(iconButton(R.drawable.ic_more_vert, "Opciones de ${acc.name}") { anchor -> accountMenu(acc, anchor) })
        setOnClickListener { activeId = acc.id; go(Screen.CHATS) }
    }

    private fun renderSettings() {
        val webViewVersion = WebViewCompat.getCurrentWebViewPackage(this)?.versionName ?: "desconocida"
        val appVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: PackageManager.NameNotFoundException) {
            ""
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(SURFACE_LOWEST, 16)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(settingRow("Perfiles aislados",
                if (isolationSupported) "Activos: cada cuenta tiene sus propias cookies y datos"
                else "No disponibles: actualiza «Android System WebView» para usar varias cuentas del mismo servicio"))
            addView(settingRow("Android System WebView", webViewVersion))
            addView(settingRow("Cuentas", "${accounts.count { it.service == Service.WHATSAPP }} de WhatsApp · " +
                "${accounts.count { it.service == Service.TELEGRAM }} de Telegram"))
            addView(settingRow("Versión de Multicuenta", appVersion))
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(24))
            addView(sectionLabel("General"))
            addView(card)
        }
        pagePane.removeAllViews()
        pagePane.addView(ScrollView(this).apply { addView(list) }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    // ---------------------------------------------------------------- accounts

    private fun addAccount(svc: Service) {
        val existing = accounts.count { it.service == svc }
        val acc = Account(store.newId(svc, accounts), svc, if (existing == 0) "Personal" else "Cuenta ${existing + 1}")
        accounts += acc
        store.save(accounts)
        if (activeId == null) activeId = acc.id
        startLink(acc)
    }

    private fun accountMenu(acc: Account, anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 0, 0, "Abrir chats")
            menu.add(0, 1, 1, "Vincular de nuevo")
            menu.add(0, 2, 2, "Recargar")
            menu.add(0, 3, 3, "Renombrar")
            menu.add(0, 4, 4, "Eliminar cuenta")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    0 -> { activeId = acc.id; go(Screen.CHATS) }
                    1 -> startLink(acc)
                    2 -> (views[acc.id] as? WebView)?.reload()
                    3 -> rename(acc)
                    4 -> confirmDelete(acc)
                }
                true
            }
        }.show()
    }

    private fun rename(acc: Account) {
        val field = EditText(this).apply {
            setText(acc.name)
            setSingleLine()
            setSelection(text.length)
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(field)
        }
        AlertDialog.Builder(this)
            .setTitle("Renombrar cuenta")
            .setView(box)
            .setPositiveButton("Guardar") { _, _ ->
                val name = field.text.toString().trim()
                if (name.isNotEmpty()) {
                    val i = accounts.indexOfFirst { it.id == acc.id }
                    if (i >= 0) accounts[i] = acc.copy(name = name)
                    store.save(accounts)
                    go(screen)
                }
            }
            .setNegativeButton("Cancelar", null)
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
            webHost.removeView(v)
            (v as? WebView)?.destroy()
        }
        unread.remove(acc.id)
        status.remove(acc.id)
        accounts.removeAll { it.id == acc.id }
        store.save(accounts)
        if (isolationSupported) {
            try {
                ProfileStore.getInstance().deleteProfile(acc.id)
            } catch (e: IllegalStateException) {
                // Profile still held by the destroyed WebView; its data stays until next launch
                Toast.makeText(this, "Los datos se borrarán al reiniciar la app", Toast.LENGTH_LONG).show()
            }
        }
        if (activeId == acc.id) {
            activeId = accounts.firstOrNull()?.id
            store.activeId = activeId
        }
        go(if (screen == Screen.LINK) Screen.ACCOUNTS else screen)
    }

    // ---------------------------------------------------------------- web views

    private fun createView(acc: Account): View {
        // Without profiles every WebView shares one cookie jar: a second account of the
        // same service would overwrite the first one's login, so refuse instead.
        val sameService = accounts.filter { it.service == acc.service }
        if (!isolationSupported && sameService.indexOf(acc) > 0) {
            return text(
                "Para usar varias cuentas de ${acc.service.label} a la vez, actualiza " +
                    "«Android System WebView» desde Google Play. Esta versión no admite perfiles aislados.",
                15f, ON_SURFACE_VARIANT,
            ).apply {
                gravity = Gravity.CENTER
                setPadding(dp(32), dp(32), dp(32), dp(32))
            }
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

            override fun onPageFinished(view: WebView, url: String?) {
                // WhatsApp's desktop page uses width=device-width and gets cut off on a phone.
                // A fixed layout width lets the wide viewport zoom the whole page to fit.
                if (acc.service == Service.WHATSAPP) view.evaluateJavascript(FIT_DESKTOP_JS, null)
                view.evaluateJavascript("($PAGE_JS)(${JSONObject.quote(acc.service.id)});", null)
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                if (status[acc.id] == Status.ERROR) setStatus(acc.id, null)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) setStatus(acc.id, Status.ERROR)
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) {
                val n = parseUnread(title.orEmpty())
                if (unread[acc.id] != n) {
                    unread[acc.id] = n
                    renderChips()
                    renderBottomNav()
                    if (screen == Screen.ACCOUNTS) renderAccounts()
                }
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
        wv.addJavascriptInterface(Bridge(acc.id), "MulticuentaBridge")
        wv.loadUrl(acc.service.url)
        return wv
    }

    private fun setStatus(id: String, s: Status?) {
        if (status[id] == s) return
        if (s == null) status.remove(id) else status[id] = s
        if (screen == Screen.ACCOUNTS) renderAccounts()
    }

    // ---------------------------------------------------------------- M3 building blocks

    private fun text(s: String, sp: Float, color: Int, medium: Boolean = false) = TextView(this).apply {
        text = s
        textSize = sp
        setTextColor(color)
        typeface = if (medium) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
    }

    private fun icon(res: Int, tint: Int, sizeDp: Int = 24) = ImageView(this).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
        layoutParams = ViewGroup.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun iconButton(res: Int, description: String, onClick: (View) -> Unit) = FrameLayout(this).apply {
        val out = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, out, true)
        setBackgroundResource(out.resourceId)
        addView(icon(res, ON_SURFACE_VARIANT), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        contentDescription = description
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        setOnClickListener { onClick(it) }
    }

    private fun badge(count: Int) = text(if (count > 99) "99+" else count.toString(), 11f, 0xFFFFFFFF.toInt(), medium = true).apply {
        gravity = Gravity.CENTER
        minWidth = dp(16)
        setPadding(dp(4), 0, dp(4), 0)
        background = rounded(PRIMARY, 8)
    }

    private fun sectionLabel(s: String) = text(s, 14f, PRIMARY, medium = true).apply {
        setPadding(0, dp(20), 0, dp(8))
    }

    private fun statusChip(label: String, res: Int, bg: Int, fg: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), 0, dp(10), 0)
        background = rounded(bg, 8)
        addView(icon(res, fg, 16), LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(4) })
        addView(text(label, 12f, fg, medium = true))
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, dp(26))
    }

    private fun linkButton(svc: Service) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), 0, dp(12), 0)
        background = rounded(SURFACE, 12, stroke = OUTLINE_VARIANT)
        addView(ImageView(this@MainActivity).apply { setImageResource(logoRes(svc)) },
            LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(16) })
        addView(text("Vincular ${svc.label}", 16f, ON_SURFACE), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(icon(R.drawable.ic_chevron_right, ON_SURFACE_VARIANT))
        contentDescription = "Vincular cuenta de ${svc.label}"
        setOnClickListener { addAccount(svc) }
    }

    private fun filledButton(label: String, color: Int = PRIMARY, onClick: () -> Unit) =
        text(label, 14f, 0xFFFFFFFF.toInt(), medium = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(12), dp(24), dp(12))
            background = rounded(color, 24)
            setOnClickListener { onClick() }
        }

    private fun textButton(label: String, onClick: () -> Unit) = text(label, 14f, PRIMARY, medium = true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setOnClickListener { onClick() }
    }

    private fun html(s: String) = TextView(this).apply {
        text = Html.fromHtml(s, Html.FROM_HTML_MODE_COMPACT)
        textSize = 14f
        setTextColor(ON_SURFACE_VARIANT)
        gravity = Gravity.CENTER
    }

    /** M3 outlined text field (radius 4). */
    private fun field(value: String, type: Int) = EditText(this).apply {
        setText(value)
        inputType = type
        setSingleLine()
        textSize = 16f
        setTextColor(ON_SURFACE)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = rounded(SURFACE, 4, stroke = OUTLINE)
    }

    private fun labeled(label: String, field: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(label, 12f, ON_SURFACE_VARIANT).apply { setPadding(dp(4), 0, 0, dp(4)) })
        addView(field, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun brandButton(svc: Service) = if (svc == Service.WHATSAPP) WHATSAPP_DARK else PRIMARY

    private fun settingRow(title: String, value: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(10), 0, dp(10))
        addView(text(title, 16f, ON_SURFACE))
        addView(text(value, 14f, ON_SURFACE_VARIANT))
    }

    private fun rounded(color: Int, radiusDp: Int, stroke: Int? = null) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun logoRes(svc: Service) = when (svc) {
        Service.WHATSAPP -> R.drawable.logo_whatsapp
        Service.TELEGRAM -> R.drawable.logo_telegram
    }

    private fun chipLabel(acc: Account) =
        (if (acc.service == Service.WHATSAPP) "WA " else "TG ") + acc.name

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

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        // Material 3 palette from design/multichat-m3.figcraft (Fundamentos)
        private const val SURFACE = 0xFFF8F9FF.toInt()
        private const val SURFACE_CONTAINER = 0xFFECEEF4.toInt()
        private const val SURFACE_LOWEST = 0xFFFFFFFF.toInt()
        private const val ON_SURFACE = 0xFF191C20.toInt()
        private const val ON_SURFACE_VARIANT = 0xFF43474E.toInt()
        private const val PRIMARY = 0xFF36618E.toInt()
        private const val SECONDARY_CONTAINER = 0xFFD7E3F7.toInt()
        private const val ON_SECONDARY_CONTAINER = 0xFF101C2B.toInt()
        private const val OUTLINE_VARIANT = 0xFFC3C7CF.toInt()
        private const val OUTLINE = 0xFF73777F.toInt()
        private const val PRIMARY_CONTAINER = 0xFFD1E4FF.toInt()
        private const val ON_PRIMARY_CONTAINER = 0xFF001D36.toInt()
        private const val WHATSAPP_DARK = 0xFF103928.toInt()
        private const val SUCCESS_CONTAINER = 0xFFC8F0D2.toInt()
        private const val ON_SUCCESS_CONTAINER = 0xFF00210C.toInt()
        private const val ERROR_CONTAINER = 0xFFFFDAD6.toInt()
        private const val ON_ERROR_CONTAINER = 0xFF410002.toInt()

        private val SERVICE_HOSTS = listOf("whatsapp.com", "whatsapp.net", "telegram.org")
        /**
         * Runs inside each service page (call with the service id). Every 1.5 s it posts to
         * MulticuentaBridge: whether the chat list is there (logged in), whether the login page
         * is showing, the login QR as a PNG data URL, and WhatsApp's pairing code (data-link-code).
         * window.__mcPhone(number) drives the page's own "log in with phone number" form.
         */
        private const val PAGE_JS = """function(svc){
  if (window.__mcPoll) return;
  function q(s){ return document.querySelector(s); }
  function byText(re){
    var els = document.querySelectorAll('[role=button], button, a, span, div');
    for (var i = els.length - 1; i >= 0; i--) {
      var e = els[i];
      if (e.children.length <= 2 && re.test((e.textContent || '').trim())) return e;
    }
    return null;
  }
  function setValue(el, v){
    if (el.isContentEditable) { el.textContent = v; }
    else { Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(el, v); }
    el.dispatchEvent(new Event('input', { bubbles: true }));
  }
  window.__mcPhone = function(num){
    var step = 0, tries = 0;
    var t = setInterval(function(){
      if (++tries > 40) { clearInterval(t); MulticuentaBridge.phoneFailed('paso ' + step); return; }
      if (step === 0) {
        var l = byText(/n[uú]mero de tel[eé]fono|phone number/i);
        if (l) {
          l.click(); step = 1;
          if (svc !== 'whatsapp') { clearInterval(t); MulticuentaBridge.phoneFailed('Telegram'); }
        }
      } else if (step === 1) {
        var inp = q('input[type=text], input[type=tel], input:not([type])');
        if (inp) { inp.focus(); setValue(inp, num); step = 2; }
      } else if (step === 2) {
        var b = byText(/^(siguiente|next)$/i);
        if (b) { b.click(); clearInterval(t); }
      }
    }, 500);
  };
  window.__mcPoll = setInterval(function(){
    try {
      var s = { logged: false, loginPage: false, qr: null, code: null };
      var c = q('[data-link-code]');
      if (c) s.code = c.getAttribute('data-link-code');
      var cv = q('canvas');
      if (!s.code && cv && cv.width >= 100 && !q('#pane-side, #side, #column-left .chatlist')) {
        s.qr = cv.toDataURL('image/png');
      }
      s.loginPage = !!(s.qr || s.code || (svc !== 'whatsapp' && q('#auth-pages .tabs-tab.active')));
      s.logged = !s.loginPage && !!(svc === 'whatsapp' ? q('#pane-side, #side') : q('#column-left'));
      MulticuentaBridge.post(JSON.stringify(s));
    } catch (e) {}
  }, 1500);
}"""
        private const val FIT_DESKTOP_JS = """(function(){
  var m = document.querySelector('meta[name=viewport]');
  if (!m) { m = document.createElement('meta'); m.name = 'viewport'; document.head.appendChild(m); }
  m.content = 'width=900';
})();"""
        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}

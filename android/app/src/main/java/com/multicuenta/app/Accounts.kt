package com.multicuenta.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class Service(
    val id: String,
    val label: String,
    val slug: String,
    val letter: String,
    val color: Int,
    val url: String,
) {
    WHATSAPP("whatsapp", "WhatsApp", "wa", "W", 0xFF00A884.toInt(), "https://web.whatsapp.com/"),
    TELEGRAM("telegram", "Telegram", "tg", "T", 0xFF2AABEE.toInt(), "https://web.telegram.org/k/");

    companion object {
        fun from(id: String): Service = entries.firstOrNull { it.id == id } ?: WHATSAPP
    }
}

/** One isolated account. [id] is also the name of its WebView profile. */
data class Account(val id: String, val service: Service, val name: String)

/** Accounts persisted as a JSON array in private SharedPreferences. */
class AccountStore(context: Context) {
    private val prefs = context.getSharedPreferences("accounts", Context.MODE_PRIVATE)

    fun load(): List<Account> {
        val raw = prefs.getString("list", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Account(o.getString("id"), Service.from(o.getString("service")), o.getString("name"))
            }
        } catch (e: org.json.JSONException) {
            emptyList()
        }
    }

    fun save(accounts: List<Account>) {
        val arr = JSONArray()
        accounts.forEach {
            arr.put(JSONObject().put("id", it.id).put("service", it.service.id).put("name", it.name))
        }
        prefs.edit().putString("list", arr.toString()).apply()
    }

    var activeId: String?
        get() = prefs.getString("active", null)
        set(value) = prefs.edit().putString("active", value).apply()

    /** Never reuses an id: a reused id would reopen a deleted account's profile. */
    fun newId(service: Service, existing: List<Account>): String {
        var n = prefs.getInt("next_${service.slug}", existing.size + 1)
        while (existing.any { it.id == "${service.slug}_$n" }) n++
        prefs.edit().putInt("next_${service.slug}", n + 1).apply()
        return "${service.slug}_$n"
    }
}

/** First "(N)" in a page title: "(3) WhatsApp" -> 3, no number -> 0. */
fun parseUnread(title: String): Int =
    Regex("\\((\\d+)\\)").find(title)?.groupValues?.get(1)?.toIntOrNull() ?: 0

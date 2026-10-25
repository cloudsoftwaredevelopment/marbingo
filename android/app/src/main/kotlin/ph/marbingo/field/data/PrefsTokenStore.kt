package ph.marbingo.field.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ph.marbingo.core.model.UserDto
import ph.marbingo.core.net.ApiClient
import ph.marbingo.core.net.TokenStore

/** Session in private SharedPreferences ("marbingo_session"). [changes] ticks on every write so the session Flow can follow. */
class PrefsTokenStore(context: Context) : TokenStore {
    private val prefs = context.applicationContext.getSharedPreferences("marbingo_session", Context.MODE_PRIVATE)
    private val userAdapter = ApiClient.moshi.adapter(UserDto::class.java)
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes

    private fun bump() { _changes.value = _changes.value + 1 }

    override var accessToken: String?
        get() = prefs.getString(KEY_ACCESS, null)
        set(v) { prefs.edit().putString(KEY_ACCESS, v).apply(); bump() }

    override var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH, null)
        set(v) { prefs.edit().putString(KEY_REFRESH, v).apply(); bump() }

    override var user: UserDto?
        get() = prefs.getString(KEY_USER, null)?.let { runCatching { userAdapter.fromJson(it) }.getOrNull() }
        set(v) { prefs.edit().putString(KEY_USER, v?.let { userAdapter.toJson(it) }).apply(); bump() }

    var shopName: String?
        get() = prefs.getString(KEY_SHOP_NAME, null)
        set(v) { prefs.edit().putString(KEY_SHOP_NAME, v).apply(); bump() }

    var shopMark: String?
        get() = prefs.getString(KEY_SHOP_MARK, null)
        set(v) { prefs.edit().putString(KEY_SHOP_MARK, v).apply(); bump() }

    /** The account whose data is in the local DB; survives [clear] so a token expiry doesn't orphan queued orders. */
    var dataOwnerId: Int?
        get() = if (prefs.contains(KEY_OWNER)) prefs.getInt(KEY_OWNER, -1) else null
        set(v) { prefs.edit().apply { if (v == null) remove(KEY_OWNER) else putInt(KEY_OWNER, v) }.apply() }

    /** Drops the session but keeps [dataOwnerId]. */
    override fun clear() {
        prefs.edit().remove(KEY_ACCESS).remove(KEY_REFRESH).remove(KEY_USER).remove(KEY_SHOP_NAME).remove(KEY_SHOP_MARK).apply()
        bump()
    }

    private companion object {
        const val KEY_ACCESS = "access"
        const val KEY_REFRESH = "refresh"
        const val KEY_USER = "user"
        const val KEY_SHOP_NAME = "shopName"
        const val KEY_SHOP_MARK = "shopMark"
        const val KEY_OWNER = "dataOwnerId"
    }
}

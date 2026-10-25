package ph.marbingo.core.net

import ph.marbingo.core.model.UserDto

/** Where the session lives. :app provides a SharedPreferences-backed implementation; tests use [InMemoryTokenStore]. */
interface TokenStore {
    var accessToken: String?
    var refreshToken: String?
    var user: UserDto?
    fun clear()
}

class InMemoryTokenStore : TokenStore {
    override var accessToken: String? = null
    override var refreshToken: String? = null
    override var user: UserDto? = null
    override fun clear() { accessToken = null; refreshToken = null; user = null }
}

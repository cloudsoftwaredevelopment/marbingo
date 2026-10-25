package ph.marbingo.field.ui

import android.net.Uri

object Routes {
    const val SIGN_IN = "signin"
    const val HOME = "home"
    const val STORES = "stores"
    const val ORDERS = "orders"
    const val SETTINGS = "settings"
    const val SCAN = "scan"

    const val NEW_ORDER = "neworder?store={store}"
    const val STORE = "store/{uuid}"
    const val ORDER = "order/{uuid}"
    const val RECEIPT = "receipt/{uuid}"

    const val SCAN_RESULT_KEY = "scanned_code"

    fun newOrder(storeUuid: String? = null) = if (storeUuid == null) "neworder" else "neworder?store=${Uri.encode(storeUuid)}"
    fun store(uuid: String) = "store/${Uri.encode(uuid)}"
    fun order(uuid: String) = "order/${Uri.encode(uuid)}"
    fun receipt(uuid: String) = "receipt/${Uri.encode(uuid)}"
}

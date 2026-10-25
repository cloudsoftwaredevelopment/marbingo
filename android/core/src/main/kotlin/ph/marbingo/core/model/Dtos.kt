package ph.marbingo.core.model

// Wire format for the Marbingo API — see docs/field-api.md. Money is integer centavos;
// timestamps are ISO-8601 UTC strings.

data class UserDto(
    val id: Int,
    val firstName: String,
    val lastName: String,
    val email: String,
    val role: String,
    val employeeId: String? = null,
    val photoUrl: String? = null,
)

data class LoginRequest(val email: String, val password: String)
data class LoginResponse(val accessToken: String, val refreshToken: String, val user: UserDto)
data class RefreshRequest(val refreshToken: String)
data class TokenPair(val accessToken: String, val refreshToken: String)
data class MeResponse(val user: UserDto)
data class SettingsDto(val shopName: String, val shopMark: String? = null)

data class ItemDto(
    val id: Int,
    val name: String,
    val sku: String?,
    val unit: String,
    val categoryId: Int?,
    val category: String?,
    val sellingPrice: Long,
    val wholesalePrice: Long?,
    val fieldPrice: Long,
    val qtyOnHand: Int,
    val imageUrl: String?,
    val updatedAt: String,
)

data class CategoryDto(val id: Int, val name: String)

data class CustomerDto(
    val id: Int,
    val clientUuid: String,
    val name: String,
    val ownerName: String?,
    val phone: String?,
    val address: String?,
    val notes: String?,
    val active: Boolean,
    val createdAt: String?,
    val updatedAt: String?,
)

data class CustomerRefDto(val id: Int, val clientUuid: String, val name: String)
data class PersonDto(val id: Int, val firstName: String, val lastName: String)

data class OrderLineDto(
    val itemId: Int,
    val name: String,
    val unit: String?,
    val qty: Int,
    val unitPrice: Long,
    val lineTotal: Long,
    val deliveredQty: Int?,
)

data class OrderDto(
    val id: Int,
    val clientUuid: String,
    val status: String, // pending | delivered | cancelled
    val customer: CustomerRefDto,
    val agent: PersonDto?,
    val orderedAt: String,
    val syncedAt: String?,
    val updatedAt: String?,
    val deliveredAt: String?,
    val deliveredBy: PersonDto?,
    val cancelledReason: String?,
    val notes: String?,
    val total: Long,
    val saleId: Int?,
    val lines: List<OrderLineDto>,
)

data class SyncPullResponse(
    val serverTime: String,
    val items: List<ItemDto>,
    val categories: List<CategoryDto>,
    val customers: List<CustomerDto>,
    val orders: List<OrderDto>,
)

data class CustomerInput(
    val clientUuid: String,
    val name: String,
    val ownerName: String? = null,
    val phone: String? = null,
    val address: String? = null,
    val notes: String? = null,
)

data class OrderLineInput(val itemId: Int, val qty: Int)

data class OrderInput(
    val clientUuid: String,
    val customerClientUuid: String? = null,
    val customerId: Int? = null,
    val orderedAt: String,
    val notes: String? = null,
    val lines: List<OrderLineInput>,
)

data class OrderBatchRequest(val customers: List<CustomerInput>, val orders: List<OrderInput>)
data class CustomerIdResult(val clientUuid: String, val id: Int)
data class OrderResult(val clientUuid: String, val ok: Boolean, val order: OrderDto? = null, val error: String? = null)
data class OrderBatchResponse(val customers: List<CustomerIdResult>, val results: List<OrderResult>)

data class CustomerResponse(val customer: CustomerDto)
data class OrderResponse(val order: OrderDto)
data class CancelRequest(val reason: String)
data class ApiErrorBody(val error: String?, val fields: Map<String, String>?)

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
    val qtyOnHand: Int,
    val imageUrl: String?,
    val updatedAt: String,
    /** Wholesale bundles (docs/pricing.md), sorted by size descending. Empty from older servers. */
    val packs: List<PackDto> = emptyList(),
)

data class PackDto(val id: Int? = null, val packTypeId: Int, val name: String, val size: Int, val price: Long)

data class PackTypeDto(val id: Int, val name: String, val defaultSize: Int? = null, val sort: Int? = null, val active: Boolean? = null)

/** One row of a price breakdown: a pack (name != null) or the loose units (packTypeId and name null, size 1). */
data class PricePartDto(
    val packTypeId: Int?,
    val name: String?,
    val size: Int,
    val count: Int,
    val price: Long,
    val amount: Long,
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
    // Store location (docs/tracking-api.md). Nullable with defaults so older servers still parse.
    val lat: Double? = null,
    val lng: Double? = null,
    val locationSource: String? = null, // gps | manual | null
    val locationAccuracy: Double? = null,
    val locatedAt: String? = null,
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
    val breakdown: List<PricePartDto>? = null,
    val deliveredBreakdown: List<PricePartDto>? = null,
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
    val packTypes: List<PackTypeDto> = emptyList(),
    val trackingSchedule: TrackingScheduleDto? = null,
    val trackingConsentAt: String? = null,
)

data class CustomerInput(
    val clientUuid: String,
    val name: String,
    val ownerName: String? = null,
    val phone: String? = null,
    val address: String? = null,
    val notes: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val locationAccuracy: Double? = null,
)

/** PUT /api/customers/:id: only the location fields, sent when the agent fixes an existing store's pin. */
data class CustomerLocationInput(val lat: Double, val lng: Double, val locationAccuracy: Double? = null)

// ---- Work-hours tracking (docs/tracking-api.md) ----

/** days are ISO weekdays (1 = Mon … 7 = Sun); start/end are Manila "HH:MM". */
data class TrackingScheduleDto(val days: List<Int>, val start: String, val end: String)

data class TrackingPointDto(
    val recordedAt: String,
    val lat: Double,
    val lng: Double,
    val accuracy: Double? = null,
    val speed: Double? = null,
    val bearing: Double? = null,
    val battery: Int? = null,
)

data class TrackingPointsRequest(val points: List<TrackingPointDto>)
data class TrackingPointsResponse(val accepted: Int = 0, val duplicates: Int = 0, val rejected: Int = 0)
data class ConsentResponse(val trackingConsentAt: String? = null)

data class OrderLineInput(val itemId: Int, val qty: Int)

data class OrderInput(
    val clientUuid: String,
    val customerClientUuid: String? = null,
    val customerId: Int? = null,
    val orderedAt: String,
    val notes: String? = null,
    /** Set when the agent cancelled the order before it synced (server cancels or records it as cancelled). */
    val cancelReason: String? = null,
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

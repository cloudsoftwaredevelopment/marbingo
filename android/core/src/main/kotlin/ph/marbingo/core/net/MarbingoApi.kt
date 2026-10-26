package ph.marbingo.core.net

import ph.marbingo.core.model.*
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/** Retrofit interface for the endpoints the field app uses (docs/field-api.md). Paths are relative to the base URL. */
interface MarbingoApi {
    @POST("api/auth/login") suspend fun login(@Body body: LoginRequest): LoginResponse
    @POST("api/auth/refresh") suspend fun refresh(@Body body: RefreshRequest): TokenPair
    @GET("api/auth/me") suspend fun me(): MeResponse
    @GET("api/settings") suspend fun settings(): SettingsDto

    @GET("api/sync/pull") suspend fun pull(@Query("since") since: String?): SyncPullResponse
    @POST("api/orders/batch") suspend fun pushOrders(@Body body: OrderBatchRequest): OrderBatchResponse
    @POST("api/customers") suspend fun upsertCustomer(@Body body: CustomerInput): CustomerResponse
    @PUT("api/customers/{id}") suspend fun updateCustomerLocation(@Path("id") id: Int, @Body body: CustomerLocationInput): CustomerResponse

    @GET("api/settings/tracking-schedule") suspend fun trackingSchedule(): TrackingScheduleDto
    @POST("api/tracking/points") suspend fun postTrackingPoints(@Body body: TrackingPointsRequest): TrackingPointsResponse
    @POST("api/tracking/consent") suspend fun giveTrackingConsent(): ConsentResponse

    @POST("api/orders/{id}/cancel") suspend fun cancelOrder(@Path("id") id: Int, @Body body: CancelRequest): OrderResponse
}

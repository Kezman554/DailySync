package com.personalmorningalarm.data.remote

import com.personalmorningalarm.data.model.CalendarEventsDto
import com.personalmorningalarm.data.model.ChalkboardTaskDto
import com.personalmorningalarm.data.model.InboxCaptureDto
import com.personalmorningalarm.data.model.ScheduleTaskDto
import com.personalmorningalarm.data.model.ShoppingItemDto
import com.personalmorningalarm.data.model.ShoppingListDetailDto
import com.personalmorningalarm.data.model.ShoppingListSummaryDto
import com.personalmorningalarm.data.model.WeekScheduleDto
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The Alfred Vault API. One endpoint per content screen that reads from Alfred;
 * add new endpoints here rather than building a second client.
 *
 * The chalkboard writes return [Response] rather than a body so a 404 — a stale
 * targeting line — reaches [AlfredRepository] as data instead of an exception:
 * its error body carries the current list, which is how the app resyncs.
 */
interface AlfredApiService {

    /** Today's schedule. No completion state and no timestamps — display only. */
    @GET("daily-schedule")
    suspend fun getDailySchedule(): List<ScheduleTaskDto>

    /**
     * The whole plan week, every day keyed by ISO date. Used only by the week
     * screen — the alarm content screen and the Today screen stay on
     * [getDailySchedule], which is all a wake-up reminder needs.
     */
    @GET("daily-schedule/week")
    suspend fun getWeekSchedule(): WeekScheduleDto

    /**
     * The family calendar between [start] (inclusive) and [end] (exclusive), both
     * plain ISO dates. One call covers a whole visible week — callers bucket the
     * events by day themselves rather than asking per day.
     *
     * Read-only: the app never writes to the household diary.
     */
    @GET("calendar/events")
    suspend fun getCalendarEvents(
        @Query("start") start: String,
        @Query("end") end: String
    ): CalendarEventsDto

    /**
     * The rolling to-do, including items ticked but not yet swept by the Pi's
     * overnight housekeeping. Each item carries its raw vault [ChalkboardTaskDto.line],
     * the targeting key for the writes below.
     */
    @GET("chalkboard")
    suspend fun getChalkboard(): List<ChalkboardTaskDto>

    /** Adds an item. The API appends "- [ ] text (today's date)" itself — no date is sent. */
    @POST("chalkboard")
    suspend fun addChalkboardItem(@Body body: ChalkboardAddRequest): Response<Unit>

    /** Flips the targeted item to done. It stays in the list, ticked, until the sweep. */
    @POST("chalkboard/tick")
    suspend fun tickChalkboardItem(@Body body: ChalkboardLineRequest): Response<Unit>

    /** Removes the targeted item as no longer relevant (the API archives it as dropped). */
    @POST("chalkboard/drop")
    suspend fun dropChalkboardItem(@Body body: ChalkboardLineRequest): Response<Unit>

    /** Every active shopping list in the vault — the list-picker menu's discovery source. */
    @GET("shopping")
    suspend fun getShoppingLists(): List<ShoppingListSummaryDto>

    /** One list's items, ticked included; each carries its raw targeting [ShoppingItemDto.line]. */
    @GET("shopping/{listId}")
    suspend fun getShoppingList(@Path("listId") listId: String): ShoppingListDetailDto

    /** Scaffolds a new active shopping list. 409 (via [Response]) if that name already exists. */
    @POST("shopping")
    suspend fun createShoppingList(@Body body: ShoppingCreateRequest): Response<ShoppingListSummaryDto>

    /** Takes a list out of discovery. 404 (via [Response]) if it's already gone, with the current lists. */
    @DELETE("shopping/{listId}")
    suspend fun deleteShoppingList(@Path("listId") listId: String): Response<ShoppingDeleteResponse>

    /** Appends an unticked item to the targeted list. */
    @POST("shopping/{listId}")
    suspend fun addShoppingItem(
        @Path("listId") listId: String,
        @Body body: ShoppingAddRequest
    ): Response<Unit>

    /** Flips the targeted item to bought. It stays listed, ticked, until the sweep. */
    @POST("shopping/{listId}/tick")
    suspend fun tickShoppingItem(
        @Path("listId") listId: String,
        @Body body: ShoppingLineRequest
    ): Response<Unit>

    /** Removes the targeted item as no longer wanted (the API archives it as dropped). */
    @POST("shopping/{listId}/drop")
    suspend fun dropShoppingItem(
        @Path("listId") listId: String,
        @Body body: ShoppingLineRequest
    ): Response<Unit>

    /** The vault's current `0-inbox/` captures, newest first. Read-only — triage happens in the vault. */
    @GET("inbox")
    suspend fun getInbox(): List<InboxCaptureDto>

    /**
     * Creates a new inbox capture. The body is the text itself as text/plain, not
     * JSON — hence the raw [RequestBody]: Gson would serialise a String to a quoted
     * JSON scalar and send it as application/json, which this endpoint doesn't take.
     * Build it with [AlfredRepository.capturePlainText].
     */
    @POST("inbox")
    suspend fun capture(@Body body: RequestBody): Response<Unit>
}

/** What DELETE /shopping/{listId} answers: the lists that remain. */
data class ShoppingDeleteResponse(val lists: List<ShoppingListSummaryDto>?)

/** Body for POST /chalkboard — the task text alone; Alfred adds the checkbox and date. */
data class ChalkboardAddRequest(val text: String)

/** Body for tick/drop — the item's raw vault line, sent back verbatim as the target. */
data class ChalkboardLineRequest(val line: String)

/** Body for POST /shopping/{listId} — the item text alone; Alfred adds the checkbox. */
data class ShoppingAddRequest(val text: String)

/** Body for a shopping tick/drop — the item's raw vault line, sent back verbatim as the target. */
data class ShoppingLineRequest(val line: String)

/** Body for POST /shopping — the list's display name; Alfred slugifies it into a vault file id. */
data class ShoppingCreateRequest(val name: String)

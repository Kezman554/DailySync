package com.personalmorningalarm.data.remote

import com.personalmorningalarm.data.model.CalendarCreateRequest
import com.personalmorningalarm.data.model.CalendarEventsDto
import com.personalmorningalarm.data.model.ChalkboardTaskDto
import com.personalmorningalarm.data.model.InboxCaptureDto
import com.personalmorningalarm.data.model.NoteDto
import com.personalmorningalarm.data.model.NoteSummaryDto
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
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Tag

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
     * The one write is [createCalendarEvent], from the Add Event screen.
     */
    @GET("calendar/events")
    suspend fun getCalendarEvents(
        @Query("start") start: String,
        @Query("end") end: String
    ): CalendarEventsDto

    /**
     * Adds one event to the family calendar. 201 answers GET's shape with the one
     * event in it; 400 for an unknown who, 422 for anything else refused. Not
     * idempotent: every call makes an event, so it is never queued or retried, and
     * the body is one-shot (AlfredClient). [sent] is marked once the request starts
     * going out, which is how a failure tells "never sent" from "sent, no answer".
     */
    @POST("calendar/events")
    suspend fun createCalendarEvent(
        @Body body: CalendarCreateRequest,
        @Tag sent: SendMarker
    ): Response<CalendarEventsDto>

    /**
     * One chalkboard list ([list] is a [com.personalmorningalarm.data.model.ChalkboardList]
     * wire name, always sent). With [includeTicked] the items ticked but not yet
     * swept come back too, every item marked [ChalkboardTaskDto.ticked]; without it,
     * unticked items only. Each item carries its raw vault [ChalkboardTaskDto.line],
     * the targeting key for the writes below.
     */
    @GET("chalkboard")
    suspend fun getChalkboard(
        @Query("list") list: String,
        @Query("include_ticked") includeTicked: Boolean
    ): List<ChalkboardTaskDto>

    /** Adds an item to [list]. The API appends "- [ ] text (today's date)" itself — no date is sent. */
    @POST("chalkboard")
    suspend fun addChalkboardItem(@Query("list") list: String, @Body body: ChalkboardAddRequest): Response<Unit>

    /** Flips the targeted item to done. It stays in the file, ticked, until the sweep. */
    @POST("chalkboard/tick")
    suspend fun tickChalkboardItem(@Query("list") list: String, @Body body: ChalkboardLineRequest): Response<Unit>

    /** Removes the targeted item as no longer relevant (the API archives it as dropped). */
    @POST("chalkboard/drop")
    suspend fun dropChalkboardItem(@Query("list") list: String, @Body body: ChalkboardLineRequest): Response<Unit>

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

    /** Sets a list's shared flag. 404 (via [Response]) if the list is gone. */
    @POST("shopping/{listId}/share")
    suspend fun setShoppingShared(
        @Path("listId") listId: String,
        @Body body: ShoppingShareRequest
    ): Response<Unit>

    /**
     * Makes a new SHARED list from a phone's copy, ticks kept, in one commit. Not
     * idempotent: a repeat makes a second list ("-2"), so a lost answer is never
     * simply retried (see ShoppingShare).
     */
    @POST("shopping/upload")
    suspend fun uploadShoppingList(@Body body: ShoppingUploadRequest): Response<ShoppingUploadResponse>

    /**
     * Returns the list's items and deletes it from the hub, in one commit. 404 if it's
     * gone, 409 if it isn't one the hub can remove (not in 6-life/shopping/).
     */
    @POST("shopping/{listId}/pull-back")
    suspend fun pullBackShoppingList(@Path("listId") listId: String): Response<ShoppingPullBackResponse>

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

    /** Every note as id, title, updated and preview, newest update first. */
    @GET("notes")
    suspend fun getNotes(): List<NoteSummaryDto>

    /** One note's exact text. The list's preview is short, so this is what the editor opens. */
    @GET("notes/{noteId}")
    suspend fun getNote(@Path("noteId") noteId: String): NoteDto

    /** Creates or replaces a note. Safe to repeat; 422 (via [Response]) if the text is refused. */
    @PUT("notes/{noteId}")
    suspend fun saveNote(@Path("noteId") noteId: String, @Body body: NoteSaveRequest): Response<Unit>

    /** Removes a note. Succeeds even if it's already gone, so it's safe to repeat. */
    @DELETE("notes/{noteId}")
    suspend fun deleteNote(@Path("noteId") noteId: String): Response<Unit>
}

/** Body for PUT /notes/{noteId} — the note's text, exactly as typed. */
data class NoteSaveRequest(val text: String)

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

/** Body for POST /shopping/{listId}/share. */
data class ShoppingShareRequest(val shared: Boolean)

/** Body for POST /shopping/upload: the list's title, whose it is, and every item with its tick. */
data class ShoppingUploadRequest(val title: String, val owner: String, val items: List<ShoppingUploadItem>)

data class ShoppingUploadItem(val text: String, val ticked: Boolean)

/** What POST /shopping/upload answers: the new list's id, the handle every other route takes. */
data class ShoppingUploadResponse(val id: String?)

/** What POST /shopping/{listId}/pull-back answers: the items as they were, ticks included. */
data class ShoppingPullBackResponse(val items: List<ShoppingItemDto>?)

/** Body for POST /shopping — the list's display name; Alfred slugifies it into a vault file id. */
data class ShoppingCreateRequest(val name: String)

package com.personalmorningalarm.data.remote

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.personalmorningalarm.data.model.CalendarCreateRequest
import com.personalmorningalarm.data.model.CalendarEventsDto
import com.personalmorningalarm.data.model.ChalkboardList
import com.personalmorningalarm.data.model.ChalkboardTaskDto
import com.personalmorningalarm.data.model.InboxCaptureDto
import com.personalmorningalarm.data.model.NoteDto
import com.personalmorningalarm.data.model.NoteSummaryDto
import com.personalmorningalarm.data.model.Notes
import com.personalmorningalarm.data.model.ScheduleTaskDto
import com.personalmorningalarm.data.model.ShoppingItemDto
import com.personalmorningalarm.data.model.ShoppingListDetailDto
import com.personalmorningalarm.data.model.ShoppingListSummaryDto
import com.personalmorningalarm.data.model.WeekScheduleDto
import com.personalmorningalarm.data.profile.Profile
import com.personalmorningalarm.data.profile.ProfileSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.RequestBody
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.lang.reflect.Type
import java.time.LocalDate

/**
 * Single entry point to the Alfred Vault API. Every endpoint goes through [fetch],
 * which adds the behaviour each Alfred screen needs: on success the response is
 * cached; when Alfred is unreachable the cached response is served instead, and
 * only if there's nothing cached does the caller get [AlfredResult.Unavailable].
 * Every result carries when its data was fetched, cached or not.
 *
 * Every call also tells [AlfredReachability] whether Alfred answered — the same
 * unreachable verdict the offline write queues act on, so there is one answer to
 * "can we see Alfred" across the app.
 *
 * Alfred being down is an ordinary morning, not an error — no call here throws.
 */
class AlfredRepository(
    private val settings: AlfredSettings,
    private val cache: AlfredResponseCache,
    /** Without a context to read the profile from, shopping requests name Nick: the app as it was. */
    private val serviceProvider: (AlfredSettings) -> AlfredApiService = { AlfredClient.service(it) { Profile.NICK } },
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Whose phone this is, for the chalkboard lists a profile may not use
     * ([Profile.mayUse]): such a list is never asked for, whoever calls.
     */
    private val profile: () -> Profile? = { Profile.NICK }
) : ShoppingItemSource {

    /** The real client, naming this phone's profile on every shopping request. */
    constructor(context: Context) : this(
        AlfredSettings(context),
        AlfredResponseCache(context),
        ProfileSettings(context).let { profiles -> { settings -> AlfredClient.service(settings, profiles::profile) } },
        profile = ProfileSettings(context)::profile
    )

    private val gson = Gson()

    /** Today's schedule from Alfred, falling back to the last one it served. */
    suspend fun getDailySchedule(): AlfredResult<List<ScheduleTaskDto>> =
        fetch(
            endpoint = ENDPOINT_DAILY_SCHEDULE,
            type = object : TypeToken<List<ScheduleTaskDto>>() {}.type
        ) { it.getDailySchedule() }

    /** The whole plan week, falling back to the last one Alfred served. */
    suspend fun getWeekSchedule(): AlfredResult<WeekScheduleDto> =
        fetch(
            endpoint = ENDPOINT_WEEK_SCHEDULE,
            type = WeekScheduleDto::class.java
        ) { it.getWeekSchedule() }

    /**
     * Whether today is a work day, falling back to the last answer served. That
     * answer names its own date, so a copy saved on another day says nothing about
     * today: [com.personalmorningalarm.challenge.WorkStep] checks it.
     */
    suspend fun getWorkDay(): AlfredResult<com.personalmorningalarm.data.model.WorkDayDto> =
        fetch(
            endpoint = ENDPOINT_WORK_DAY,
            type = com.personalmorningalarm.data.model.WorkDayDto::class.java
        ) { it.getWorkDay() }

    /**
     * The family calendar for the whole visible week — one call, bucketed by day
     * client-side. [endExclusive] follows the endpoint: pass the day after the last
     * day you want.
     */
    suspend fun getCalendarWeek(start: LocalDate, endExclusive: LocalDate): AlfredResult<CalendarEventsDto> =
        fetchCalendar(ENDPOINT_CALENDAR_WEEK, start, endExclusive)

    /**
     * Just [date]'s family calendar, for the alarm's wake-up schedule page.
     *
     * Cached under its own key rather than the week's: the two surfaces ask for
     * different ranges, and a narrow morning fetch must not overwrite the week the
     * tile falls back on when Alfred is unreachable.
     */
    suspend fun getCalendarDay(date: LocalDate): AlfredResult<CalendarEventsDto> =
        fetchCalendar(ENDPOINT_CALENDAR_DAY, date, date.plusDays(1))

    /**
     * The family calendar for the Calendar screen's weeks ahead, cached apart from
     * the week and the day so none of the three overwrites another's fallback.
     */
    suspend fun getCalendarAgenda(start: LocalDate, endExclusive: LocalDate): AlfredResult<CalendarEventsDto> =
        fetchCalendar(ENDPOINT_CALENDAR_AGENDA, start, endExclusive)

    private suspend fun fetchCalendar(
        endpoint: String,
        start: LocalDate,
        endExclusive: LocalDate
    ): AlfredResult<CalendarEventsDto> =
        fetch(
            endpoint = endpoint,
            type = CalendarEventsDto::class.java
        ) { it.getCalendarEvents(start.toString(), endExclusive.toString()) }

    /**
     * Adds one event to the Young Family calendar, once. Never queued and never
     * retried here: the endpoint makes a new event on every call, so after a lost
     * answer only the person, having looked at the calendar, can say whether to send
     * it again. The created event is announced on [CalendarChanges] for the Week
     * screen.
     */
    suspend fun createCalendarEvent(request: CalendarCreateRequest): CalendarCreateResult =
        withContext(Dispatchers.IO) {
            val marker = SendMarker()
            try {
                val response = serviceProvider(settings).createCalendarEvent(request, marker)
                val code = response.code()
                reportAnswer(true)
                when {
                    response.isSuccessful -> {
                        val event = response.body()?.events?.firstOrNull()
                        event?.let(CalendarChanges::announce)
                        CalendarCreateResult.Created(event)
                    }
                    code in 400..499 -> {
                        Log.w(TAG, "Calendar event refused: HTTP $code")
                        CalendarCreateResult.Refused(errorDetail(response))
                    }
                    else -> {
                        // A fault on the Pi or upstream in HA, which may have got as far
                        // as making the event before failing.
                        Log.w(TAG, "Calendar event failed on the hub: HTTP $code")
                        CalendarCreateResult.Unconfirmed
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                reportAnswer(false)
                if (marker.sent) {
                    Log.d(TAG, "Calendar event sent, no answer (${e.javaClass.simpleName})")
                    CalendarCreateResult.Unconfirmed
                } else {
                    Log.d(TAG, "Alfred unreachable for calendar event (${e.javaClass.simpleName})")
                    CalendarCreateResult.NotSent
                }
            }
        }

    /** FastAPI's `{"detail": "..."}`, when the detail is a plain sentence. */
    private data class ErrorBody(val detail: Any?)

    private fun errorDetail(response: Response<*>): String? = try {
        (gson.fromJson(response.errorBody()?.string(), ErrorBody::class.java)?.detail as? String)
            ?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    /**
     * One chalkboard list from Alfred, ticked items included (greyed until the
     * sweep), falling back to the last copy served for that list. Nick's by
     * default: the alarm's content screen reads it this way.
     */
    suspend fun getChalkboard(list: ChalkboardList = ChalkboardList.NICK): AlfredResult<List<ChalkboardTaskDto>> =
        if (!mayUse(list)) {
            // Not even the saved copy: a list this profile hasn't got has nothing to show.
            AlfredResult.Unavailable
        } else fetch(
            endpoint = list.cacheKey,
            type = CHALKBOARD_TYPE
        ) { it.getChalkboard(list.wireName, includeTicked = true) }

    /**
     * Adds an item to [list]. Alfred appends the checkbox and today's date. [key] is
     * the add's [AddKey], the same on every attempt: a lost answer is sent again.
     */
    suspend fun addChalkboardItem(list: ChalkboardList, task: String, key: String): AlfredWriteResult =
        if (!mayUse(list)) notThisProfiles(list, "add") else write(list, "add", keyed = true) { service ->
            sendKeyed { sent -> service.addChalkboardItem(list.wireName, ChalkboardAddRequest(task), key, sent) }
        }

    /** Ticks the item on [list] whose raw vault line is [line]. It stays listed until the sweep. */
    suspend fun tickChalkboardItem(list: ChalkboardList, line: String): AlfredWriteResult =
        if (!mayUse(list)) notThisProfiles(list, "tick") else write(list, "tick") { it.tickChalkboardItem(list.wireName, ChalkboardLineRequest(line)) }

    /** Drops the item on [list] whose raw vault line is [line] — no longer relevant, not done. */
    suspend fun dropChalkboardItem(list: ChalkboardList, line: String): AlfredWriteResult =
        if (!mayUse(list)) notThisProfiles(list, "drop") else write(list, "drop") { it.dropChalkboardItem(list.wireName, ChalkboardLineRequest(line)) }

    /** Every active shopping list from Alfred, falling back to the last one it served. */
    suspend fun getShoppingLists(): AlfredResult<List<ShoppingListSummaryDto>> =
        fetch(
            endpoint = ENDPOINT_SHOPPING,
            type = SHOPPING_LISTS_TYPE
        ) { it.getShoppingLists() }

    /** One shopping list's items from Alfred, falling back to the last copy served for [listId]. */
    override suspend fun getShoppingList(listId: String): AlfredResult<ShoppingListDetailDto> =
        fetch(
            endpoint = shoppingListEndpoint(listId),
            type = ShoppingListDetailDto::class.java
        ) { it.getShoppingList(listId) }

    /**
     * Scaffolds a new shopping list. Online-only by design (the card's contract): a
     * queued create can't know whether the name collides until it actually reaches
     * Alfred, so there is nothing sensible to do with it offline.
     */
    suspend fun createShoppingList(name: String): ShoppingCreateResult = withContext(Dispatchers.IO) {
        try {
            val response = serviceProvider(settings).createShoppingList(ShoppingCreateRequest(name))
            reportAnswer(response.isSuccessful || response.code() == 409)
            when {
                response.isSuccessful -> {
                    val body = response.body()
                    if (body == null) {
                        Log.w(TAG, "Create shopping list: 2xx with no body")
                        ShoppingCreateResult.Unreachable
                    } else {
                        ShoppingCreateResult.Created(body)
                    }
                }
                response.code() == 409 -> ShoppingCreateResult.Conflict
                else -> {
                    Log.w(TAG, "Create shopping list rejected: HTTP ${response.code()}")
                    ShoppingCreateResult.Unreachable
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Log.d(TAG, "Alfred unreachable for create shopping list (${e.javaClass.simpleName})")
            reportAnswer(false)
            ShoppingCreateResult.Unreachable
        }
    }

    /**
     * Deletes the list [listId]. Online-only, as [createShoppingList] is. A 404 with
     * its list of lists means the list is already gone, which is what was asked for;
     * a 404 without one is just a failed call, and the list is left alone.
     */
    suspend fun deleteShoppingList(listId: String): ShoppingDeleteResult = withContext(Dispatchers.IO) {
        try {
            val response = serviceProvider(settings).deleteShoppingList(listId)
            val remaining = when {
                response.isSuccessful -> response.body()?.lists ?: cachedShoppingLists(listId)
                response.code() == 404 -> listsFromStaleBody(response)
                else -> null
            }
            reportAnswer(remaining != null)
            if (remaining == null) {
                Log.w(TAG, "Delete shopping list rejected: HTTP ${response.code()}")
                ShoppingDeleteResult.Unreachable
            } else {
                // Both saved copies, so an offline screen can't bring the list back.
                cache.put(ENDPOINT_SHOPPING, gson.toJson(remaining), clock())
                cache.clear(shoppingListEndpoint(listId))
                ShoppingDeleteResult.Deleted(remaining)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Log.d(TAG, "Alfred unreachable for delete shopping list (${e.javaClass.simpleName})")
            reportAnswer(false)
            ShoppingDeleteResult.Unreachable
        }
    }

    /** Shape of a list-level 404: {"detail":{"error":…,"lists":[…]}}. */
    private data class StaleListsBody(val detail: Detail?) {
        data class Detail(val lists: List<ShoppingListSummaryDto>?)
    }

    private fun listsFromStaleBody(response: Response<*>): List<ShoppingListSummaryDto>? = try {
        gson.fromJson(response.errorBody()?.string(), StaleListsBody::class.java)?.detail?.lists
    } catch (e: Exception) {
        null
    }

    /** The saved list of lists without [listId], for a success that came with no lists. */
    private fun cachedShoppingLists(listId: String): List<ShoppingListSummaryDto> {
        val saved: List<ShoppingListSummaryDto>? = try {
            cache.get(ENDPOINT_SHOPPING)?.let { gson.fromJson(it.json, SHOPPING_LISTS_TYPE) }
        } catch (e: Exception) {
            null
        }
        return saved.orEmpty().filterNot { it.id == listId }
    }

    /** Adds an item to the shopping list [listId], keyed by [key] as [addChalkboardItem] is. */
    override suspend fun addShoppingItem(listId: String, text: String, key: String): ShoppingWriteResult =
        writeShopping(listId, "add", keyed = true) { service ->
            sendKeyed { sent -> service.addShoppingItem(listId, ShoppingAddRequest(text), key, sent) }
        }

    /**
     * Ticks the item whose raw vault line is [line] in list [listId]. Stays listed until
     * the sweep. With a [key] a resend after a lost answer lands once, on the same row.
     */
    override suspend fun tickShoppingItem(listId: String, line: String, key: String?): ShoppingWriteResult =
        writeShopping(listId, "tick", keyed = key != null) {
            it.tickShoppingItem(listId, ShoppingLineRequest(line), key)
        }

    /** Unticks the bought item whose raw (ticked) vault line is [line], keyed as [tickShoppingItem]. */
    suspend fun untickShoppingItem(listId: String, line: String, key: String): ShoppingWriteResult =
        writeShopping(listId, "untick", keyed = true) {
            it.untickShoppingItem(listId, ShoppingLineRequest(line), key)
        }

    /**
     * Clears [listId]'s bought items. Online-only, as delete is. A resend is safe: once
     * cleared there's nothing ticked, and the hub makes no change.
     */
    suspend fun clearShoppingBought(listId: String): ShoppingWriteResult =
        writeShopping(listId, "clear-bought") { it.clearShoppingBought(listId) }

    /** Sets [listId]'s Keep ticked on the hub. Online-only: the answer is the hub's now. */
    suspend fun setShoppingKeepTicked(listId: String, keep: Boolean): ShareFlagResult =
        shareCall("keep-ticked") {
            val response = it.setShoppingKeepTicked(listId, ShoppingKeepTickedRequest(keep))
            when {
                response.isSuccessful -> ShareFlagResult.Done
                response.code() == 404 -> ShareFlagResult.Gone
                else -> null
            }
        } ?: ShareFlagResult.Unreachable

    /** Drops the item whose raw vault line is [line] in list [listId] — no longer wanted. */
    override suspend fun dropShoppingItem(listId: String, line: String): ShoppingWriteResult =
        writeShopping(listId, "drop") { it.dropShoppingItem(listId, ShoppingLineRequest(line)) }

    // Sharing (Session BB). Each answers with what the hub holds now — never a saved
    // copy — because a move between the phone and the hub is decided on it.

    /** Sets [listId]'s shared flag on the hub. */
    suspend fun setShoppingShared(listId: String, shared: Boolean): ShareFlagResult =
        shareCall("share") {
            val response = it.setShoppingShared(listId, ShoppingShareRequest(shared))
            when {
                response.isSuccessful -> ShareFlagResult.Done
                response.code() == 404 -> ShareFlagResult.Gone
                else -> null
            }
        } ?: ShareFlagResult.Unreachable

    /** Uploads a phone list as a new shared hub list owned by [owner] (a hub name, e.g. "jess"). */
    suspend fun uploadShoppingList(title: String, owner: String, items: List<ShoppingUploadItem>): UploadResult =
        shareCall("upload") {
            val response = it.uploadShoppingList(ShoppingUploadRequest(title, owner, items))
            val id = response.body()?.id
            when {
                response.isSuccessful && !id.isNullOrBlank() -> UploadResult.Uploaded(id)
                // A 2xx with no id: it may well have made the list, so it's not a refusal.
                response.isSuccessful -> null
                response.code() in 400..499 -> UploadResult.Refused(response.code())
                else -> null
            }
        } ?: UploadResult.Unreachable

    /**
     * Takes [listId] off the hub, returning its items. On success both saved copies
     * forget it, so an offline screen can't bring it back.
     */
    suspend fun pullBackShoppingList(listId: String): PullBackResult =
        (shareCall("pull-back") {
            val response = it.pullBackShoppingList(listId)
            val items = response.body()?.items
            when {
                response.isSuccessful && items != null -> PullBackResult.PulledBack(items)
                response.isSuccessful -> null
                response.code() == 404 -> PullBackResult.Gone
                response.code() in 400..499 -> PullBackResult.Refused(response.code())
                else -> null
            }
        } ?: PullBackResult.Unreachable).also { result ->
            if (result is PullBackResult.PulledBack || result is PullBackResult.Gone) forgetShoppingList(listId)
        }

    /** [listId]'s items as the hub has them now: found, gone (404), or no answer. */
    suspend fun liveShoppingList(listId: String): LiveListResult =
        shareCall("read") {
            try {
                it.getShoppingList(listId).items?.let(LiveListResult::Found)
            } catch (e: HttpException) {
                if (e.code() == 404) LiveListResult.Gone else null
            }
        } ?: LiveListResult.Unreachable

    /** The hub's list of lists right now, also saved as discovery's copy; null with no answer. */
    suspend fun liveShoppingLists(): List<ShoppingListSummaryDto>? =
        (getShoppingLists() as? AlfredResult.Fresh)?.data

    private fun forgetShoppingList(listId: String) {
        cache.put(ENDPOINT_SHOPPING, gson.toJson(cachedShoppingLists(listId)), clock())
        cache.clear(shoppingListEndpoint(listId))
    }

    /**
     * One sharing call. [handle] turns a response into a result, or null for an answer
     * that settles nothing (a 5xx, a body that can't be read): that reads as no answer.
     */
    private suspend fun <T : Any> shareCall(verb: String, handle: suspend (AlfredApiService) -> T?): T? =
        withContext(Dispatchers.IO) {
            try {
                val result = handle(serviceProvider(settings))
                reportAnswer(result != null)
                if (result == null) Log.w(TAG, "Shopping $verb: no usable answer")
                result
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                Log.d(TAG, "Alfred unreachable for shopping $verb (${e.javaClass.simpleName})")
                reportAnswer(false)
                null
            }
        }

    /** The vault's current `0-inbox/` captures, falling back to the last set Alfred served. */
    suspend fun getInbox(): AlfredResult<List<InboxCaptureDto>> =
        fetch(
            endpoint = ENDPOINT_INBOX,
            type = INBOX_TYPE
        ) { it.getInbox() }

    /**
     * Captures [text] as a new file in `0-inbox/`. Unlike every other write here
     * there's no targeting key and so no stale case: a 4xx means Alfred read the
     * text and refused it, which no amount of retrying will change, so it comes back
     * as [InboxWriteResult.Rejected] rather than being retried forever. A 409 is
     * one of those: [key] (the capture's [AddKey]) was used for other text.
     */
    suspend fun captureToInbox(text: String, key: String): InboxWriteResult = withContext(Dispatchers.IO) {
        try {
            val service = serviceProvider(settings)
            val response = sendKeyed { sent -> service.capture(capturePlainText(text), key, sent) }
            reportAnswer(response.isSuccessful || response.code() in 400..499)
            when {
                response.isSuccessful -> InboxWriteResult.Done
                response.code() == 409 -> {
                    Log.e(TAG, "Inbox capture: Idempotency-Key reused for other text (a bug) — not retried")
                    InboxWriteResult.Rejected
                }
                response.code() in 400..499 -> {
                    Log.w(TAG, "Inbox capture refused: HTTP ${response.code()}")
                    InboxWriteResult.Rejected
                }
                else -> {
                    Log.w(TAG, "Inbox capture rejected: HTTP ${response.code()}")
                    InboxWriteResult.Unreachable
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Log.d(TAG, "Alfred unreachable for inbox capture (${e.javaClass.simpleName})")
            reportAnswer(false)
            InboxWriteResult.Unreachable
        }
    }

    /** Every note, newest first, falling back to the last list Alfred served. */
    suspend fun getNotes(): AlfredResult<List<NoteSummaryDto>> =
        fetch(
            endpoint = ENDPOINT_NOTES,
            type = NOTES_TYPE
        ) { it.getNotes() }

    /** One note's text, falling back to the last copy this phone saw. */
    suspend fun getNote(noteId: String): AlfredResult<NoteDto> =
        fetch(
            endpoint = noteEndpoint(noteId),
            type = NoteDto::class.java
        ) { it.getNote(noteId) }

    /** The saved list as it stands, for a screen to pick up what a delivered write changed. */
    fun savedNotes(): List<NoteSummaryDto>? = try {
        cache.get(ENDPOINT_NOTES)?.let { gson.fromJson<List<NoteSummaryDto>>(it.json, NOTES_TYPE) }
    } catch (e: Exception) {
        null
    }

    /**
     * Saves [text] as note [noteId]. On success the saved copies follow, so a note
     * written on this phone can always be reopened offline, and a list Alfred can't
     * refresh right now doesn't lose the note the moment its queue row goes.
     */
    suspend fun saveNote(noteId: String, text: String): NoteWriteResult =
        writeNote("save", { it.saveNote(noteId, NoteSaveRequest(text)) }) {
            cache.put(noteEndpoint(noteId), gson.toJson(NoteDto(id = noteId, text = text)), clock())
            patchSavedNotes { notes -> listOf(Notes.summary(noteId, text)) + notes.filterNot { it.id == noteId } }
        }

    /** Deletes note [noteId], and its saved copies, so an offline screen can't bring it back. */
    suspend fun deleteNote(noteId: String): NoteWriteResult =
        writeNote("delete", { it.deleteNote(noteId) }) {
            cache.clear(noteEndpoint(noteId))
            patchSavedNotes { notes -> notes.filterNot { it.id == noteId } }
        }

    /**
     * Changes the saved list and leaves its timestamp alone: this is one note's news,
     * not a fresh list, so the rest of it is exactly as old as it was.
     */
    private fun patchSavedNotes(change: (List<NoteSummaryDto>) -> List<NoteSummaryDto>) {
        val saved = cache.get(ENDPOINT_NOTES) ?: return
        val notes = savedNotes() ?: return
        cache.put(ENDPOINT_NOTES, gson.toJson(change(notes)), saved.cachedAtMillis)
    }

    private suspend fun writeNote(
        verb: String,
        request: suspend (AlfredApiService) -> Response<Unit>,
        onDone: () -> Unit
    ): NoteWriteResult = withContext(Dispatchers.IO) {
        try {
            val response = request(serviceProvider(settings))
            reportAnswer(response.isSuccessful || response.code() == 422)
            when {
                response.isSuccessful -> {
                    onDone()
                    NoteWriteResult.Done
                }
                response.code() == 422 -> {
                    Log.w(TAG, "Note $verb refused: HTTP 422")
                    NoteWriteResult.Rejected
                }
                else -> {
                    Log.w(TAG, "Note $verb rejected: HTTP ${response.code()}")
                    NoteWriteResult.Unreachable
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Log.d(TAG, "Alfred unreachable for note $verb (${e.javaClass.simpleName})")
            reportAnswer(false)
            NoteWriteResult.Unreachable
        }
    }

    private fun mayUse(list: ChalkboardList): Boolean = Profile.mayUse(profile(), list)

    /**
     * A write to a list this profile hasn't got (one queued before the phone changed
     * hands, say): Refused, so a queued one is kept as failed and never resent.
     */
    private fun notThisProfiles(list: ChalkboardList, verb: String): AlfredWriteResult {
        Log.w(TAG, "Chalkboard ${list.wireName} $verb: not this profile's list — not sent")
        return AlfredWriteResult.Refused
    }

    /**
     * Runs a chalkboard write. A 404 means the targeting line went stale; its body
     * carries the list's open items, handed back so the caller can resync at once.
     */
    private suspend fun write(
        list: ChalkboardList,
        verb: String,
        keyed: Boolean = false,
        request: suspend (AlfredApiService) -> Response<Unit>
    ): AlfredWriteResult = withContext(Dispatchers.IO) {
        try {
            val response = request(serviceProvider(settings))
            reportAnswer(response.isSuccessful || response.code() == 404 || (keyed && response.code() == 409))
            when {
                response.isSuccessful -> AlfredWriteResult.Done
                keyed && response.code() == 409 -> {
                    Log.e(TAG, "Chalkboard $verb: Idempotency-Key reused for other text (a bug) — not retried")
                    AlfredWriteResult.Refused
                }
                response.code() == 404 -> staleTarget(list, verb, response)
                else -> {
                    Log.w(TAG, "Chalkboard $verb rejected: HTTP ${response.code()}")
                    AlfredWriteResult.Unreachable
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Log.d(TAG, "Alfred unreachable for chalkboard $verb (${e.javaClass.simpleName})")
            reportAnswer(false)
            AlfredWriteResult.Unreachable
        }
    }

    /**
     * Sends a keyed add, and sends it again at once, under the same key, if the
     * connection dropped after it went out: the answer was lost, and the hub answers
     * a repeated key with the first add rather than adding again. OkHttp's own retry
     * covers a dropped pooled connection; this covers the rest (a read timeout while
     * the Pi was busy, say). A failure before anything went out is thrown at once:
     * the hub isn't there, and the caller queues the add.
     */
    private suspend fun <T> sendKeyed(call: suspend (SendMarker) -> Response<T>): Response<T> {
        repeat(LOST_ANSWER_RESENDS) {
            val sent = SendMarker()
            try {
                return call(sent)
            } catch (e: IOException) {
                if (!sent.sent) throw e
                Log.d(TAG, "Keyed add sent, answer lost (${e.javaClass.simpleName}) — sending again")
            }
        }
        return call(SendMarker())
    }

    /** Shape of a tick/drop 404 (FastAPI wraps it): {"detail":{"error":…,"items":[…]}}. */
    private data class StaleTargetBody(val detail: Detail?) {
        data class Detail(val items: List<ChalkboardTaskDto>?)
    }

    /**
     * The 404's list holds unticked items only (a write has no include_ticked), so
     * it isn't saved over the list's copy, which still has the greyed ticked rows:
     * the caller refetches to bring those back.
     */
    private fun staleTarget(list: ChalkboardList, verb: String, response: Response<Unit>): AlfredWriteResult {
        val current: List<ChalkboardTaskDto>? = try {
            gson.fromJson(response.errorBody()?.string(), StaleTargetBody::class.java)
                ?.detail?.items
        } catch (e: Exception) {
            null
        }
        // A 404 whose body isn't the promised list is just a failed write.
        if (current == null) {
            Log.w(TAG, "Chalkboard $verb 404 without a usable list body")
            return AlfredWriteResult.Unreachable
        }
        Log.d(TAG, "Chalkboard ${list.wireName} $verb hit a stale line — ${current.size} items open")
        return AlfredWriteResult.StaleTarget(current)
    }

    /** Same shape as [write], for the per-list shopping endpoints. */
    private suspend fun writeShopping(
        listId: String,
        verb: String,
        keyed: Boolean = false,
        request: suspend (AlfredApiService) -> Response<Unit>
    ): ShoppingWriteResult = withContext(Dispatchers.IO) {
        try {
            val response = request(serviceProvider(settings))
            reportAnswer(response.isSuccessful || response.code() == 404 || (keyed && response.code() == 409))
            when {
                response.isSuccessful -> ShoppingWriteResult.Done
                keyed && response.code() == 409 -> {
                    Log.e(TAG, "Shopping $verb: Idempotency-Key reused for other text (a bug) — not retried")
                    ShoppingWriteResult.Refused
                }
                response.code() == 404 -> staleShoppingTarget(listId, verb, response)
                else -> {
                    Log.w(TAG, "Shopping $verb rejected: HTTP ${response.code()}")
                    ShoppingWriteResult.Unreachable
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Log.d(TAG, "Alfred unreachable for shopping $verb (${e.javaClass.simpleName})")
            reportAnswer(false)
            ShoppingWriteResult.Unreachable
        }
    }

    /** Shape of a shopping tick/drop 404: {"detail":{"error":…,"list_id":…,"items":[…]}}. */
    private data class ShoppingStaleTargetBody(val detail: Detail?) {
        data class Detail(val items: List<ShoppingItemDto>?)
    }

    private fun staleShoppingTarget(listId: String, verb: String, response: Response<Unit>): ShoppingWriteResult {
        val current: List<ShoppingItemDto>? = try {
            gson.fromJson(response.errorBody()?.string(), ShoppingStaleTargetBody::class.java)
                ?.detail?.items
        } catch (e: Exception) {
            null
        }
        if (current == null) {
            Log.w(TAG, "Shopping $verb 404 without a usable list body")
            return ShoppingWriteResult.Unreachable
        }
        Log.d(TAG, "Shopping $verb hit a stale line — resynced ${current.size} items")
        // The 404 body has no list summary, only items — carry the previous cache's
        // summary forward so this endpoint's cache stays the same shape (a full
        // ShoppingListDetailDto) as every ordinary fetch caches under this key.
        val previousList = try {
            cache.get(shoppingListEndpoint(listId))
                ?.let { gson.fromJson(it.json, ShoppingListDetailDto::class.java) }
                ?.list
        } catch (e: Exception) {
            null
        }
        cache.put(
            shoppingListEndpoint(listId),
            gson.toJson(ShoppingListDetailDto(list = previousList, items = current)),
            clock()
        )
        return ShoppingWriteResult.StaleTarget(current)
    }

    /**
     * Calls [request], caching its response under [endpoint]. [type] is how the
     * cached JSON is read back, so it must match [request]'s return type.
     */
    private suspend fun <T : Any> fetch(
        endpoint: String,
        type: Type,
        request: suspend (AlfredApiService) -> T
    ): AlfredResult<T> = withContext(Dispatchers.IO) {
        try {
            val data = request(serviceProvider(settings))
            val fetchedAt = clock()
            cache.put(endpoint, gson.toJson(data), fetchedAt)
            reportAnswer(true)
            AlfredResult.Fresh(data, fetchedAt)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            // Any failed read — no route, timeout, an HTTP error, a body we can't
            // parse — leaves the screen on its saved copy, so it counts as
            // unreachable the same way a failed write does.
            Log.d(TAG, "Alfred unreachable for $endpoint (${e.javaClass.simpleName}) — trying cache")
            reportAnswer(false)
            readCache(endpoint, type)
        }
    }

    /**
     * Whether Alfred answered, for [AlfredReachability]. "Answered" matches what
     * the write paths treat as not-unreachable: a 404 stale target or a 4xx
     * refusal is Alfred reading the request and replying.
     */
    private fun reportAnswer(answered: Boolean) = AlfredReachability.report(answered)

    private fun <T : Any> readCache(endpoint: String, type: Type): AlfredResult<T> {
        val cached = cache.get(endpoint) ?: return AlfredResult.Unavailable
        return try {
            val data = gson.fromJson<T>(cached.json, type)
                ?: return AlfredResult.Unavailable
            Log.d(TAG, "Serving cached $endpoint from ${cached.cachedAtMillis}")
            AlfredResult.Stale(data, cached.cachedAtMillis)
        } catch (e: Exception) {
            // A cache we can't parse is worse than none — drop it so it can't
            // poison every future morning.
            Log.w(TAG, "Cached $endpoint unreadable — discarding", e)
            cache.clear(endpoint)
            AlfredResult.Unavailable
        }
    }

    companion object {
        private const val TAG = "PMA"

        /** How many times a keyed add whose answer was lost is sent again at once, before it's queued. */
        private const val LOST_ANSWER_RESENDS = 1

        const val ENDPOINT_DAILY_SCHEDULE = "daily-schedule"
        const val ENDPOINT_WEEK_SCHEDULE = "daily-schedule/week"
        const val ENDPOINT_WORK_DAY = "daily-schedule/work-day"

        /** All hit GET /calendar/events; they cache apart because their ranges differ. */
        const val ENDPOINT_CALENDAR_WEEK = "calendar/events/week"
        const val ENDPOINT_CALENDAR_DAY = "calendar/events/day"
        const val ENDPOINT_CALENDAR_AGENDA = "calendar/events/agenda"

        const val ENDPOINT_SHOPPING = "shopping"
        const val ENDPOINT_INBOX = "inbox"
        const val ENDPOINT_NOTES = "notes"

        private val CHALKBOARD_TYPE: Type =
            object : TypeToken<List<ChalkboardTaskDto>>() {}.type

        private val SHOPPING_LISTS_TYPE: Type =
            object : TypeToken<List<ShoppingListSummaryDto>>() {}.type

        private val INBOX_TYPE: Type =
            object : TypeToken<List<InboxCaptureDto>>() {}.type

        private val NOTES_TYPE: Type =
            object : TypeToken<List<NoteSummaryDto>>() {}.type

        private val PLAIN_TEXT: MediaType? = MediaType.parse("text/plain; charset=utf-8")

        /** POST /inbox takes the capture as a plain-text body, not JSON. */
        fun capturePlainText(text: String): RequestBody = RequestBody.create(PLAIN_TEXT, text)

        /** Each list caches independently — a slash is a fine SharedPreferences key. */
        fun shoppingListEndpoint(listId: String): String = "$ENDPOINT_SHOPPING/$listId"

        /** Each note's text caches on its own, as each shopping list does. */
        fun noteEndpoint(noteId: String): String = "$ENDPOINT_NOTES/$noteId"
    }
}

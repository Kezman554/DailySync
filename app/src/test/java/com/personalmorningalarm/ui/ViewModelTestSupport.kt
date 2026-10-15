package com.personalmorningalarm.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personalmorningalarm.data.AlarmRepository
import com.personalmorningalarm.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before

/**
 * Shared harness for ViewModel tests: an in-memory Room-backed repository and a
 * test Main dispatcher for viewModelScope.
 *
 * Deterministic by construction. Main is an [UnconfinedTestDispatcher] on a
 * virtual-time [scheduler], so viewModelScope work runs on the calling thread and
 * every delay (WhileSubscribed's stop timeout included) waits on virtual time
 * rather than a real thread. Room runs its queries and invalidation callbacks
 * inline on the caller too, so a write and the flow emissions it triggers have
 * all happened by the time the call returns.
 *
 * ViewModels made through [track] are cancelled in teardown before Main is reset:
 * left running, a WhileSubscribed timer or an in-flight call could touch Main at
 * the same moment resetMain swaps it out ("Dispatchers.Main is used concurrently
 * with setting it"), which is what made these tests flaky.
 *
 * ViewModels whose repositories hop to the real Dispatchers.IO (presence, Alfred)
 * still finish on another thread; for those, [awaitValue] keeps a wall-clock
 * bound. Room-only ViewModels never need it — the value is already there.
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class ViewModelTestSupport {

    protected val scheduler = TestCoroutineScheduler()
    protected lateinit var db: AppDatabase
    protected lateinit var repo: AlarmRepository
    private lateinit var collectorScope: CoroutineScope
    private val viewModels = mutableListOf<ViewModel>()

    @Before
    fun baseSetUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        repo = AlarmRepository(db)
        collectorScope = CoroutineScope(Dispatchers.Main + Job())
    }

    @After
    fun baseTearDown() {
        // Stop everything that could still use Main before swapping Main out.
        collectorScope.cancel()
        viewModels.forEach { it.viewModelScope.cancel() }
        viewModels.clear()
        db.close()
        Dispatchers.resetMain()
    }

    /** Registers [vm] so teardown cancels its scope before Main is reset. */
    protected fun <T : ViewModel> track(vm: T): T = vm.also { viewModels += it }

    /** Holds the given WhileSubscribed flows hot for the duration of the test. */
    protected fun keepSubscribed(vararg flows: Flow<*>) {
        flows.forEach { flow -> collectorScope.launch { flow.collect { } } }
    }

    /**
     * [flow]'s value once the work due now has run, failing at once if it doesn't
     * satisfy [predicate]. For Room-only ViewModels, where nothing runs elsewhere.
     */
    protected fun <T> settledValue(flow: StateFlow<T>, predicate: (T) -> Boolean): T {
        scheduler.runCurrent()
        val current = flow.value
        if (!predicate(current)) throw AssertionError("Flow value doesn't satisfy predicate: $current")
        return current
    }

    /**
     * Polls [flow].value until [predicate] holds or the timeout elapses — only for
     * ViewModels whose work finishes on a real IO thread.
     */
    protected fun <T> awaitValue(
        flow: StateFlow<T>,
        timeoutMs: Long = 3_000,
        predicate: (T) -> Boolean
    ): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            scheduler.runCurrent()
            val current = flow.value
            if (predicate(current)) return current
            Thread.sleep(15)
        }
        throw AssertionError("Flow never satisfied predicate; last value = ${flow.value}")
    }
}

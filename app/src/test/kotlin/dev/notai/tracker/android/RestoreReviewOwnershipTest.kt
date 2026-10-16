package dev.notai.tracker.android

import android.app.AlertDialog
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.viewModelScope
import dev.notai.tracker.*
import dev.notai.tracker.android.ui.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

/** Real host fixture files and Robolectric lifecycle callbacks; no physical store/device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RestoreReviewOwnershipTest {
    private val oldSync = DirectoryDurability.sync;private val oldRename = DirectoryDurability.rename
    private val oldOpen = UiBackends.open
    private val models = mutableListOf<TrackerViewModel>()
    @Before fun files() {
        DirectoryDurability.sync = { java.nio.channels.FileChannel.open(it.toPath(),java.nio.file.StandardOpenOption.READ).use { fd -> fd.force(true) } }
        DirectoryDurability.rename = { a,b -> java.nio.file.Files.move(a.toPath(),b.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);Unit }
        android.provider.Settings.Global.putInt(RuntimeEnvironment.getApplication().contentResolver,"boot_count",1)
    }
    @After fun close() {
        models.forEach { it.viewModelScope.cancel() };UiBackends.open = oldOpen
        DirectoryDurability.sync = oldSync;DirectoryDurability.rename = oldRename
    }
    private class Store: StateStore {
        @Volatile private var value = Dataset();override val changes = MutableStateFlow(value)
        @Volatile var beforeCommit: (() -> Unit)? = null
        override fun read() = value
        @Synchronized override fun <T> transaction(block: (Dataset) -> Pair<Dataset,T>): T {
            val (next,result) = block(value);validate(next)
            if(next != value) { beforeCommit?.invoke();value = next;changes.value = next }
            return result
        }
    }
    private class Backend(override val tracker: Tracker,override val backups: Backups): UiBackend {
        override val focusDue = FocusDueSignals();override val runtime: TrackerRuntime? = null
        override fun prepareCatalog(): CatalogPreview = error("Unused fixture")
        override fun applyCatalog(preview: CatalogPreview): Tracker.Change = error("Unused fixture")
        override fun submitShortcut(action: SurfaceAction) = CompletableDeferred<Result<Tracker.Change>>(Result.failure(IllegalStateException("Unused fixture")))
    }
    private fun model(t: Tracker,backups: Backups): TrackerViewModel {
        UiBackends.open = { Backend(t,backups) };val vm = TrackerViewModel(RuntimeEnvironment.getApplication());models += vm
        waitFor { vm.boot.value is TrackerViewModel.Boot.Ready };return vm
    }
    private fun waitFor(predicate: () -> Boolean) {
        val end = System.nanoTime()+5_000_000_000L
        while(!predicate()) { shadowOf(Looper.getMainLooper()).idle();if(System.nanoTime() > end) throw AssertionError("Ownership fixture timed out");Thread.sleep(5) }
    }
    private fun raw(clock: FakeClock) = Backups.encode(Dataset(cards = listOf(Card("restored-card","Restored"))),clock.now())
    private fun references() = PrivateFileMaintenance.forContext(RuntimeEnvironment.getApplication()).state().references
        .filter { it.blocker == CopyBlocker.RESTORE_SOURCE_IN_USE }.map { it.token }.toSet()

    @Test fun cancelBothPreviewKindsReleasesExactDurableLeasesAndKeepsSourceBytes() {
        val context = RuntimeEnvironment.getApplication();val life = CopyLifetimes.get(context);val clock = FakeClock();val t = Tracker(Store(),clock)
        val bytes = raw(clock);val before = t.data()
        for(rescue in listOf(false,true)) {
            val source = life.stage(bytes.byteInputStream());val owner = RestoreReviewOwner<AutoCloseable>()
            val preview: AutoCloseable = if(rescue) DatabaseRecovery.prepare(context,source) else Backups(t,MemoryRollback(),life).prepare { source.inputStream() }
            val tokens = references();assertTrue(tokens.isNotEmpty())
            owner.prepared(owner.begin()!!,preview);owner.discard(preview);owner.close()
            assertTrue(references().intersect(tokens).isEmpty());assertEquals(bytes,source.readText());assertEquals(before,t.data())
        }
    }
    @Test fun viewModelLatePrepareAfterDisposeAndCancellationReleasesItsLease() = runBlocking {
        val context = RuntimeEnvironment.getApplication();val life = CopyLifetimes.get(context);val clock = FakeClock();val t = Tracker(Store(),clock)
        val entered = CountDownLatch(1);val release = CountDownLatch(1);val closed = CountDownLatch(1)
        val lifecycle = object: RestoreLifecycle by life {
            override fun acquire(sourceSha256: String): RestoreLease? {
                val lease = life.acquire(sourceSha256)!!;entered.countDown();check(release.await(5,TimeUnit.SECONDS))
                return object: RestoreLease { override fun <T> protecting(block: () -> T) = lease.protecting(block);override fun close() { lease.close();closed.countDown() } }
            }
        }
        val vm = model(t,Backups(t,MemoryRollback(),lifecycle));val owner = RestoreReviewOwner<RestorePreview>(vm::releaseRestorePreviewOnIo)
        val source = java.io.File(context.cacheDir,"late-review-source.json").apply { writeText(raw(clock)) };val before = t.data()
        val preparing = async(Dispatchers.IO) { vm.prepareRestore(Uri.fromFile(source),owner) }
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS));assertTrue(references().isNotEmpty())
            owner.close();preparing.cancel();release.countDown();preparing.join()
            assertTrue(closed.await(5,TimeUnit.SECONDS));waitFor { references().isEmpty() }
            assertEquals(before,t.data());assertEquals(rawDataCardId(),Backups.decode(source.readText()).data.cards.single().id)
        } finally { release.countDown();owner.close();preparing.cancelAndJoin() }
    }
    private fun rawDataCardId() = "restored-card"

    @Test fun claimedApplySurvivesOwnerDisposalAndClosesOnlyAfterCompletion() {
        val context = RuntimeEnvironment.getApplication();val life = CopyLifetimes.get(context);val clock = FakeClock();val store = Store();val t = Tracker(store,clock)
        val vm = model(t,Backups(t,MemoryRollback(),life));val ownerReleases = AtomicInteger()
        val owner = RestoreReviewOwner<RestorePreview> { ownerReleases.incrementAndGet();vm.releaseRestorePreviewOnIo(it) }
        val source = java.io.File(context.cacheDir,"active-review-source.json").apply { writeText(raw(clock)) }
        val preview = runBlocking { vm.prepareRestore(Uri.fromFile(source),owner) }!!
        val tokens = references();val before = t.data();val entered = CountDownLatch(1);val release = CountDownLatch(1)
        store.beforeCommit = { entered.countDown();check(release.await(5,TimeUnit.SECONDS)) }
        try {
            vm.applyRestore(preview,owner);assertTrue(entered.await(5,TimeUnit.SECONDS))
            owner.discard(preview);owner.close();assertEquals(0,ownerReleases.get())
            assertTrue(references().containsAll(tokens));assertEquals(before,t.data())
            release.countDown();waitFor { t.data().cards.any { it.id == "restored-card" } && references().intersect(tokens).isEmpty() }
            assertEquals(0,ownerReleases.get());assertTrue(source.exists())
        } finally { release.countDown();owner.close() }
    }
    @Test fun cancelledApplyBeforeCoroutineStartStillClosesClaimedPreview() {
        val clock = FakeClock();val t = Tracker(Store(),clock);val closed = CountDownLatch(1)
        val backups = Backups(t,MemoryRollback(),RestoreLifecycle { object: RestoreLease { override fun close() { closed.countDown() } } })
        val vm = model(t,backups);val owner = RestoreReviewOwner<RestorePreview>(vm::releaseRestorePreviewOnIo)
        val preview = backups.prepare(raw(clock));owner.prepared(owner.begin()!!,preview)
        val before = t.data();vm.viewModelScope.cancel();vm.applyRestore(preview,owner);owner.close()
        assertTrue(closed.await(5,TimeUnit.SECONDS));assertEquals(before,t.data())
    }
    private class Probe: AutoCloseable { val closed = CountDownLatch(1);val count = AtomicInteger();override fun close() { count.incrementAndGet();closed.countDown() } }
    @Suppress("UNCHECKED_CAST")
    @Test fun technicalDialogCancelBackReplacementAndRecreationReleasePendingOwnership() {
        TrackerRuntime.beginRecovery() // Prevent synthetic Activity startup from creating a real tracker fixture.
        val controller = Robolectric.buildActivity(TechnicalActivity::class.java).create().start().resume()
        try {
            val activity = controller.get()
            val ownerField = TechnicalActivity::class.java.getDeclaredField("restoreReviews").apply { isAccessible = true }
            val owner = ownerField.get(activity) as RestoreReviewOwner<AutoCloseable>
            val show = TechnicalActivity::class.java.declaredMethods.single { it.name == "showRestoreReview" }.apply { isAccessible = true }
            val unused: suspend () -> Unit = { error("Cancel must not apply") }
            for(back in listOf(false,true)) {
                val p = Probe();owner.prepared(owner.begin()!!,p);show.invoke(activity,p,"QA review","Synthetic counts","Apply",unused)
                val dialog = ShadowAlertDialog.getLatestAlertDialog()
                if(back) dialog.cancel() else dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                shadowOf(Looper.getMainLooper()).idle();assertTrue(p.closed.await(5,TimeUnit.SECONDS));assertEquals(1,p.count.get())
            }
            val old = Probe();owner.prepared(owner.begin()!!,old)
            val nextRequest = owner.begin()!!;assertTrue(old.closed.await(5,TimeUnit.SECONDS))
            controller.recreate()
            val late = Probe();assertNull(owner.prepared(nextRequest,late));assertTrue(late.closed.await(5,TimeUnit.SECONDS));assertEquals(1,late.count.get())
        } finally { controller.pause().stop().destroy();TrackerRuntime.endRecovery() }
    }
}

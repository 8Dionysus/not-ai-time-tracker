package dev.notai.tracker.android

import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.widget.*
import dev.notai.tracker.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36],qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotificationCompactVisualTest {
    private val ids = intArrayOf(R.id.notification_favorite_0,R.id.notification_favorite_1,R.id.notification_favorite_2,R.id.notification_favorite_3)
    private fun tracker(): Tracker {
        val names = listOf("Письмо","Работа","Силовая","Учёба","Чтение")
        val roots = listOf("area.writing","area.work","area.movement","area.learning","area.reading")
        val initial = Dataset(types = roots.mapIndexed { i,id -> ActivityType(id,names[i]) },
            favorites = names.mapIndexed { i,title -> Favorite("f$i",title,Classification(name=title,activityTypeId=roots[i]),ChoicePersonalization(order=i)) })
        val store = object: StateStore {
            private var value = initial
            override val changes = MutableStateFlow(value)
            override fun read() = value
            @Synchronized override fun <T> transaction(block: (Dataset)->Pair<Dataset,T>): T {
                val (next,result) = block(value);validate(next);value=next;changes.value=next;return result
            }
        }
        return Tracker(store,FakeClock())
    }
    private fun measured(context: Context,views: RemoteViews,width: Int): View = views.apply(context,FrameLayout(context)).apply {
        measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        layout(0,0,measuredWidth,measuredHeight)
    }
    private fun write(bitmap: Bitmap,name: String) {
        val directory = File(System.getProperty("tracker.artifacts"),"c-visual").apply { mkdirs() }
        File(directory,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    @Test fun compactFavoritesFitConservativeWidthsRetainLabelsAndStalePayloads() {
        for(width in listOf(220,280,320,400)) {
            RuntimeEnvironment.setQualifiers("w${width}dp-h800dp-mdpi")
            val c = RuntimeEnvironment.getApplication();val t = tracker();val before = t.data()
            val (small,big) = SurfaceViews.notificationViews(c,t.view())
            val rendered = measured(c,small,width-96)
            val limit = SurfaceViews.compactFavoriteLimit(c)
            assertTrue("Collapsed row stays within 48dp at default text size",rendered.measuredHeight <= 48)
            assertEquals(View.GONE,rendered.findViewById<View>(R.id.surface_status).visibility)
            val visible = ids.map { rendered.findViewById<ImageButton>(it) }.filter { it.visibility == View.VISIBLE }
            assertEquals(limit,visible.size)
            assertEquals(listOf("Письмо","Работа","Силовая","Учёба").take(limit).map { "Начать: $it" },visible.map { it.contentDescription.toString() })
            visible.forEach { icon ->
                assertTrue(icon.right <= rendered.findViewById<View>(R.id.notification_favorites).width)
                assertTrue(icon.height > 0)
                val tag = c.resources.getIdentifier("pending_intent_tag","id","android")
                val pending = icon.getTag(tag) as PendingIntent
                assertTrue(shadowOf(pending).isImmutable)
                val payload = shadowOf(pending).savedIntent
                val route = SurfaceChoices.parse(payload)!!
                assertEquals(t.snapshot().timerTarget,route.target)
                val reference = codec.decodeFromString(ChoiceReference.serializer(),payload.getStringExtra(QuickChoiceReceiver.EXTRA_CHOICE)!!)
                assertEquals(t.data().datasetId,reference.datasetId)
            }
            val expanded = measured(c,requireNotNull(big),width-96)
            assertEquals("Письмо",expanded.findViewById<TextView>(R.id.surface_chip_0_text).text.toString())
            val widget = SurfaceViews.widget(c,t.view(),true).apply(c,null)
            assertEquals("Занятие выберешь потом",widget.findViewById<TextView>(R.id.surface_status).text.toString())
            assertNull(widget.findViewById<View>(R.id.notification_favorites))
            assertEquals(before,t.data())
        }
    }
    @Test fun activePausedRecoveryAndEnlargedTextKeepTheirControlsAndRenderPreviews() {
        for(night in listOf(false,true)) {
            RuntimeEnvironment.setQualifiers("w400dp-h800dp-${if(night) "night" else "notnight"}-mdpi")
            val c = RuntimeEnvironment.getApplication();val t = tracker()
            val canvasView = LinearLayout(c).apply { orientation=LinearLayout.VERTICAL;setPadding(16,16,16,16);setBackgroundColor(if(night) Color.rgb(11,15,21) else Color.rgb(230,233,229)) }
            fun add(title: String,pair: Pair<RemoteViews,RemoteViews?>,active: Boolean = false,recovery: Boolean = false) {
                canvasView.addView(TextView(c).apply { text=title;textSize=12f;setTextColor(if(night) Color.LTGRAY else Color.DKGRAY);setPadding(0,12,0,8) })
                val row = measured(c,pair.first,304)
                if(active) { assertEquals(View.VISIBLE,row.findViewById<View>(R.id.surface_chrono).visibility);assertEquals(View.VISIBLE,row.findViewById<View>(R.id.surface_secondary).visibility) }
                if(recovery) { assertEquals(View.GONE,row.findViewById<View>(R.id.surface_primary).visibility);assertEquals(View.VISIBLE,row.findViewById<View>(R.id.surface_status).visibility) }
                if(active || recovery) assertEquals(View.GONE,row.findViewById<View>(R.id.notification_favorites).visibility)
                canvasView.addView(FrameLayout(c).apply {
                    setBackgroundColor(if(night) Color.rgb(30,36,44) else Color.WHITE);addView(row)
                },LinearLayout.LayoutParams(304,-2))
            }
            add("Idle, icons without visible labels",SurfaceViews.notificationViews(c,t.view()))
            val expanded = requireNotNull(SurfaceViews.notificationViews(c,t.view()).second)
            canvasView.addView(TextView(c).apply { text="Expanded, labels retained";setTextColor(if(night) Color.LTGRAY else Color.DKGRAY) })
            canvasView.addView(measured(c,expanded,304))
            t.start(t.snapshot().token,Classification(name="Работа",activityTypeId="area.work"));(t.clock as FakeClock).advance(125000)
            add("Running",SurfaceViews.notificationViews(c,t.view()),active=true)
            t.pause(t.snapshot().token);add("Paused",SurfaceViews.notificationViews(c,t.view()),active=true)
            t.resume(t.snapshot().token);(t.clock as FakeClock).boot="new-boot"
            assertEquals(Mode.RECOVERY,t.snapshot().mode)
            add("Recovery",SurfaceViews.notificationViews(c,t.view()),recovery=true)
            canvasView.measure(View.MeasureSpec.makeMeasureSpec(336,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
            canvasView.layout(0,0,336,canvasView.measuredHeight)
            val bitmap=Bitmap.createBitmap(336,canvasView.height,Bitmap.Config.ARGB_8888);canvasView.draw(Canvas(bitmap));write(bitmap,"notification-${if(night) "dark" else "light"}")
        }
        val c=RuntimeEnvironment.getApplication();val configuration=android.content.res.Configuration(c.resources.configuration).apply { fontScale=1.5f }
        val large=c.createConfigurationContext(configuration)
        val row=SurfaceViews.notificationViews(large,tracker().view()).first.apply(large,null)
        assertEquals(0,SurfaceViews.compactFavoriteLimit(large))
        assertEquals(View.VISIBLE,row.findViewById<View>(R.id.surface_primary).visibility)
        assertEquals(View.GONE,row.findViewById<View>(R.id.notification_favorites).visibility)
    }
    @Test fun launcherPalettesRetainTheBrandAndProduceAHostPreview() {
        val c=RuntimeEnvironment.getApplication()
        val resources=listOf(R.mipmap.ic_launcher,R.mipmap.ic_launcher_amber,R.mipmap.ic_launcher_mint,R.mipmap.ic_launcher_violet)
        val bitmap=Bitmap.createBitmap(512,160,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);canvas.drawColor(Color.rgb(35,38,43))
        resources.forEachIndexed { i,id -> c.getDrawable(id)!!.apply { bounds=Rect(i*128+16,16,i*128+112,112);draw(canvas) } }
        val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color=Color.WHITE;textSize=14f }
        listOf("Default","Amber","Mint","Violet").forEachIndexed { i,name -> canvas.drawText(name,i*128+20f,140f,paint) }
        write(bitmap,"launcher-palettes")
        assertEquals(listOf("Системный вариант","Янтарный","Мятный","Фиолетовый"),LauncherIconController.options.map { it.label(c) })
    }
}

package com.subhub.app.studio;

import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withTagValue;
import static org.hamcrest.Matchers.is;
import static com.subhub.app.NativeUiActions.revealAboveNavigation;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import android.widget.ImageView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.R;
import com.subhub.app.pack.SubHubPack;
import com.subhub.app.pack.SubHubPackManager;
import com.subhub.app.security.ControllerPinManager;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;

public final class PackCoverAndroidTest {
    private Context context;
    private SubHubPackManager manager;
    private final List<String> ids = new ArrayList<>();
    private boolean wasDom;

    @Before public void setup() {
        InstrumentationRegistry.getInstrumentation().getUiAutomation();
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        context = ApplicationProvider.getApplicationContext();
        manager = new SubHubPackManager(context);
        wasDom = ControllerPinManager.isDomModeActive();
        ControllerPinManager.enterDomMode();
    }

    @After public void cleanup() {
        ControllerPinManager.enterDomMode();
        for (String id : ids) { manager.deleteDraft(id); manager.deleteLibrary(id); }
        if (wasDom) ControllerPinManager.enterDomMode(); else ControllerPinManager.enterSubMode();
    }

    @Test public void coverSurvivesExportImportAndAppearsInLibraryDraftAndReview() throws Exception {
        SubHubPack pack = manager.createBlank();
        ids.add(pack.getId());
        byte[] image = artwork();
        pack.putAsset("assets/cover/art.png", image);
        File export = manager.exportForShare(pack);
        SubHubPack imported = manager.importPack(Uri.fromFile(export));
        assertArrayEquals(image, imported.getAsset("assets/cover/art.png"));
        manager.saveDraft(imported);
        try (ActivityScenario<StudioActivity> scenario = ActivityScenario.launch(StudioActivity.class)) {
            awaitCover(scenario, R.id.library_list, "pack_cover:" + pack.getId());
            onView(org.hamcrest.Matchers.allOf(withTagValue(is("pack_cover:" + pack.getId())),
                    androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA(withId(R.id.library_list))))
                    .perform(revealAboveNavigation());
            retain(scenario, "pack-cover-library.png");
            onView(withId(R.id.tab_drafts)).perform(revealAboveNavigation(), click());
            awaitCover(scenario, R.id.drafts_list, "pack_cover:" + pack.getId());
            onView(org.hamcrest.Matchers.allOf(withTagValue(is("pack_cover:" + pack.getId())),
                    androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA(withId(R.id.drafts_list))))
                    .perform(revealAboveNavigation());
            retain(scenario, "pack-cover-draft.png");
            scenario.onActivity(activity -> invoke(activity, "openDraft", new Class<?>[]{SubHubPack.class}, imported));
            awaitCover(scenario, android.R.id.content, R.id.preview_cover);
            scenario.onActivity(activity -> invoke(activity, "showEditorStep", new Class<?>[]{int.class}, 3));
            onView(withId(R.id.preview_cover)).perform(revealAboveNavigation());
            retain(scenario, "pack-cover-review.png");
            scenario.onActivity(activity -> {
                imported.removeAsset("assets/cover/art.png");
                invoke(activity, "openDraft", new Class<?>[]{SubHubPack.class}, imported);
                assertEquals("Removing cover immediately removes the review slot", View.GONE,
                        activity.findViewById(R.id.preview_cover).getVisibility());
            });
        } finally { assertTrue(export.delete()); }
    }

    @Test public void missingOrInvalidCoverHasNoEmptyCardSlot() throws Exception {
        SubHubPack plain = manager.createBlank();
        SubHubPack invalid = manager.createBlank();
        ids.add(plain.getId()); ids.add(invalid.getId());
        invalid.putAsset("assets/cover/invalid.png", new byte[]{1, 2, 3});
        manager.addToLibrary(plain); manager.addToLibrary(invalid);
        try (ActivityScenario<StudioActivity> scenario = ActivityScenario.launch(StudioActivity.class)) {
            awaitCard(scenario, plain.getId()); awaitCard(scenario, invalid.getId());
            scenario.onActivity(activity -> {
                for (String id : ids) {
                    View card = activity.findViewById(R.id.library_list).findViewWithTag("pack_card:" + id);
                    assertNotNull(card);
                    assertNull(card.findViewWithTag("pack_cover:" + id));
                }
            });
        }
        assertNull(PackCoverArtwork.decode(new byte[]{1, 2, 3}, 256));
    }

    @Test public void largeCoverDecodeIsBoundedAndKeepsItsAspectRatio() {
        Bitmap thumbnail = PackCoverArtwork.decode(artwork(), 256);
        assertNotNull(thumbnail);
        try {
            assertTrue(thumbnail.getWidth() <= 256 && thumbnail.getHeight() <= 256);
            assertEquals(1025f / 769f, thumbnail.getWidth() / (float) thumbnail.getHeight(), .02f);
            assertEquals(Color.MAGENTA, thumbnail.getPixel(10, 10));
            assertEquals(Color.CYAN, thumbnail.getPixel(thumbnail.getWidth() - 10, thumbnail.getHeight() - 10));
        } finally { thumbnail.recycle(); }
    }

    private static byte[] artwork() {
        Bitmap image = Bitmap.createBitmap(1025, 769, Bitmap.Config.ARGB_8888);
        try {
            image.eraseColor(Color.MAGENTA);
            android.graphics.Paint paint = new android.graphics.Paint(); paint.setColor(Color.CYAN);
            new Canvas(image).drawRect(512, 384, 1025, 769, paint);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, bytes));
            return bytes.toByteArray();
        } finally { image.recycle(); }
    }

    private static void awaitCover(ActivityScenario<StudioActivity> scenario, int container, Object target) {
        await(scenario, activity -> {
            View view = target instanceof Integer ? activity.findViewById((Integer) target)
                    : activity.findViewById(container).findViewWithTag(target);
            return view instanceof ImageView && ((ImageView) view).getDrawable() instanceof BitmapDrawable;
        });
    }

    private static void awaitCard(ActivityScenario<StudioActivity> scenario, String id) {
        await(scenario, activity -> activity.findViewById(R.id.library_list).findViewWithTag("pack_card:" + id) != null);
    }

    private static void await(ActivityScenario<StudioActivity> scenario, java.util.function.Predicate<StudioActivity> ready) {
        long end = SystemClock.uptimeMillis() + 10000;
        AtomicBoolean done = new AtomicBoolean();
        while (SystemClock.uptimeMillis() < end) {
            scenario.onActivity(activity -> done.set(ready.test(activity)));
            if (done.get()) return;
            SystemClock.sleep(25);
        }
        fail("Native cover fixture did not become ready");
    }

    private static void invoke(StudioActivity activity, String name, Class<?>[] types, Object... arguments) {
        try {
            Method method = StudioActivity.class.getDeclaredMethod(name, types); method.setAccessible(true);
            method.invoke(activity, arguments);
        } catch (ReflectiveOperationException failed) { throw new AssertionError(failed); }
    }

    private void retain(ActivityScenario<StudioActivity> scenario, String filename) {
        scenario.onActivity(activity -> {
            View view = activity.findViewById(android.R.id.content);
            Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
            try (FileOutputStream stream = new FileOutputStream(new File(context.getExternalFilesDir(null), filename))) {
                Canvas canvas = new Canvas(bitmap);
                android.graphics.drawable.Drawable background = activity.getWindow().getDecorView().getBackground();
                if (background == null) canvas.drawColor(context.getColor(R.color.background));
                else {
                    android.graphics.Rect original = new android.graphics.Rect(background.getBounds());
                    background.setBounds(0, 0, view.getWidth(), view.getHeight());
                    background.draw(canvas);
                    background.setBounds(original);
                }
                view.draw(canvas);
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
            } catch (java.io.IOException failed) { throw new AssertionError(failed); }
            finally { bitmap.recycle(); }
        });
    }
}

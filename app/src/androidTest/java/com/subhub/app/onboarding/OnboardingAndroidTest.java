package com.subhub.app.onboarding;

import android.content.*;
import android.graphics.*;
import android.view.View;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import com.subhub.app.MainActivity;
import com.subhub.app.R;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.*;
import java.io.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;

@RunWith(AndroidJUnit4.class)
public class OnboardingAndroidTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    @Before public void freshFixture(){
        new com.subhub.app.appmode.AppModeManager(context).setArmed(false);
        context.getSharedPreferences("subhub_onboarding",0).edit().clear().commit();
        context.getSharedPreferences("subhub_controller_auth",0).edit().clear().commit();
        context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).edit().remove("controller_pin_salt").remove("controller_pin_hash").remove("controller_keyholder_optional").remove("has_seen_onboarding").commit();
        ControllerPinManager.enterSubMode();
    }
    @After public void restore(){OnboardingState.complete(context);ControllerPinManager.setPin(context,"2468");ControllerPinManager.enterSubMode();}
    @Test public void firstLaunchStartsTourAndSkipIsPersistentWithoutForcingAKey(){
        assertTrue(OnboardingState.shouldStart(context));
        UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        Intent home=new Intent(context,MainActivity.class).setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(home);
        assertTrue(device.wait(Until.hasObject(By.res("com.subhub.app","tour_next")),8000));
        onView(withId(R.id.tour_skip)).perform(click());
        assertTrue(device.wait(Until.hasObject(By.res("com.subhub.app","nav_home")),8000));
        assertTrue(OnboardingState.completed(context));assertFalse(OnboardingState.shouldStart(context));
        assertFalse(ControllerPinManager.hasCredentials(context));assertTrue(ControllerPinManager.allowsUnkeyedAccess(context));
        assertFalse(new com.subhub.app.appmode.AppModeManager(context).isArmed());
    }
    @Test public void selectedAppsAndStyleSurviveRecreationAndFinishWithoutStartingService(){
        new FeatureModuleManager(context).save(true,true,true,false);
        try(ActivityScenario<OnboardingActivity> tour=ActivityScenario.launch(OnboardingActivity.class)){
            onView(withId(R.id.tour_next)).perform(click());
            onView(withText(R.string.style_blur)).perform(scrollTo(),click());tour.recreate();
            onView(withText(R.string.style_blur)).check(matches(isSelected()));
            tour.onActivity(a->capture(a,"tour-appearance.png"));
            onView(withId(R.id.tour_next)).perform(click());
            onView(withId(R.id.tour_step_title)).check(matches(withText(R.string.tour_wallet_rules)));
            onView(withId(R.id.tour_next)).perform(click());
            onView(withId(R.id.tour_step_title)).check(matches(withText(R.string.tour_select_apps)));
            tour.onActivity(a->capture(a,"tour-apps.png"));
            onView(withId(R.id.tour_next)).perform(click());
            onView(withId(R.id.tour_next)).perform(click());
            onView(withId(R.id.tour_step_title)).check(matches(withText(R.string.tour_permissions)));
            onView(withId(R.id.tour_next)).check(matches(withText(R.string.tour_finish)));
            onView(withId(R.id.tour_next)).perform(click());
        }
        assertTrue(new FeatureModuleManager(context).isWalletEnabled());
        assertEquals(CensorAppearance.Type.BLUR,new SettingsRepository(context).loadAppearance().getType());
        assertFalse(new com.subhub.app.appmode.AppModeManager(context).isArmed());
        assertFalse(ControllerPinManager.hasCredentials(context));
    }
    @Test public void replayDoesNotResetSavedChoices(){
        new FeatureModuleManager(context).save(false,true,false,true);ControllerPinManager.useWithoutKeyholder(context);OnboardingState.complete(context);
        new SettingsRepository(context).saveAppearance(CensorAppearance.Type.GLITCH,71,false,false);
        try(ActivityScenario<OnboardingActivity> tour=ActivityScenario.launch(new Intent(context,OnboardingActivity.class).putExtra(OnboardingActivity.REPLAY,true))){
            onView(withId(R.id.tour_next)).perform(click());
            onView(withText(R.string.style_blur)).perform(scrollTo(),click());
            onView(withId(R.id.tour_skip)).perform(click());
        }
        FeatureModuleManager modules=new FeatureModuleManager(context);assertFalse(modules.isCensorEnabled());assertTrue(modules.isLimitsEnabled());assertTrue(modules.isWalletEnabled());assertTrue(modules.isSubliminalEnabled());
        assertEquals(CensorAppearance.Type.GLITCH,new SettingsRepository(context).loadAppearance().getType());
    }
    @Test public void incompleteSetupResumesAfterCredentialsAreConfigured() {
        new FeatureModuleManager(context).save(true,true,true,false);
        try(ActivityScenario<OnboardingActivity> tour=ActivityScenario.launch(OnboardingActivity.class)) {
            onView(withId(R.id.tour_next)).perform(click());onView(withText(R.string.style_blur)).perform(scrollTo(),click());
        }
        ControllerPinManager.setPin(context,"2468");assertTrue(OnboardingState.shouldStart(context));
        try(ActivityScenario<OnboardingActivity> resumed=ActivityScenario.launch(OnboardingActivity.class)) {
            onView(withId(R.id.tour_progress)).check(matches(withText(context.getString(R.string.tour_progress,2,6))));
            onView(withText(R.string.style_blur)).perform(scrollTo()).check(matches(isSelected()));
            onView(withId(R.id.tour_skip)).perform(click());
        }
        assertTrue(new FeatureModuleManager(context).isWalletEnabled());assertEquals(CensorAppearance.Type.BLUR,new SettingsRepository(context).loadAppearance().getType());assertFalse(OnboardingState.inProgress(context));
    }
    @Test public void allSixStepsExplainTheirActionsAndKeepTheFooterOutsideTheScroll() {
        try(ActivityScenario<OnboardingActivity> tour=ActivityScenario.launch(OnboardingActivity.class)) {
            for(int i=0;i<6;i++) {
                final int current=i;
                tour.onActivity(a->{
                    android.graphics.Rect footer=new android.graphics.Rect(),scroll=new android.graphics.Rect();
                    a.findViewById(R.id.tour_next).getGlobalVisibleRect(footer);a.findViewById(R.id.tour_scroll).getGlobalVisibleRect(scroll);
                    assertTrue("Footer must not overlap step content",footer.top>=scroll.bottom);
                    assertEquals(a.getString(R.string.tour_progress,current+1,6),((android.widget.TextView)a.findViewById(R.id.tour_progress)).getText().toString());
                    capture(a,"tour-quality-"+current+".png");
                });
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                if(i<5)onView(withId(R.id.tour_next)).perform(click());
            }
            onView(withId(R.id.tour_skip)).perform(click());
        }
    }
    private static void capture(android.app.Activity a,String name){
        View root=a.getWindow().getDecorView();root.post(()->{Bitmap bitmap=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);root.draw(new Canvas(bitmap));
            try(FileOutputStream out=new FileOutputStream(new File(a.getFilesDir(),name))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}catch(IOException error){throw new AssertionError(error);}finally{bitmap.recycle();}});
    }
}

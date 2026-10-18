package com.subhub.app.censor.android;

import static org.junit.Assert.*;
import android.content.Context;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import com.subhub.app.service.ScreenshotAccessibilityService;
import com.subhub.app.update.UpdateScheduler;
import com.subhub.app.update.UpdateStateStore;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Synthetic setup only in a newly created guest. Never run against an existing guest. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion=34)
public final class FirstVisibleGuestSetupAndroidTest {
    @Test public void newSyntheticGuestHasStableInactiveQualificationBaseline() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue(ProductCensorQualification.supported());assertEquals("true",InstrumentationRegistry.getArguments().getString("new_first_visible_guest"));
        String namespace=InstrumentationRegistry.getArguments().getString("product_first_visible_run");
        assertNotNull(namespace);assertTrue(namespace.matches("first_visible_[a-f0-9]{32}"));
        File directory=new File(context.getFilesDir(),"first-visible-setup/"+namespace);assertFalse(directory.exists());assertTrue(directory.mkdirs());
        assertNull(ProductCensorQualification.current());assertFalse(ScreenshotAccessibilityService.isRunning());
        assertNull(Settings.Secure.getString(context.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES));
        assertEquals("0",Settings.Secure.getString(context.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED));
        JSONArray observations=new JSONArray();Throwable error=null;boolean passed=false;
        long began=SystemClock.uptimeMillis(),deadline=began+60000;
        try {
            // These stores belong exclusively to this newly created synthetic guest.
            for(String name:Arrays.asList("betablocker_settings","betablocker_stats","subhub_penance","betablocker_achievements",
                    "subhub_hardcore_auto_pay","paypal_sandbox_credentials","detector_provider_cache","censor_product_qualification")) {
                Map<String,?> current=context.getSharedPreferences(name,Context.MODE_PRIVATE).getAll();
                if(name.equals("subhub_hardcore_auto_pay")||name.equals("paypal_sandbox_credentials"))assertTrue(current.isEmpty());
                if(current.isEmpty())assertTrue(context.getSharedPreferences(name,Context.MODE_PRIVATE).edit().clear().commit());
            }
            UpdateStateStore updates=new UpdateStateStore(context);updates.setAutomaticChecks(false);assertFalse(updates.automaticChecks());
            UpdateScheduler.synchronize(context);
            WorkManager work=WorkManager.getInstance(context);long stableAt=-1;String previous=null;
            while(SystemClock.uptimeMillis()<deadline) {
                JSONArray rows=new JSONArray();boolean terminal=true;
                for(String name:Arrays.asList("subhub-release-check","subhub-release-check-now")) {
                    long remaining=deadline-SystemClock.uptimeMillis();assertTrue(remaining>0);
                    List<WorkInfo> infos=work.getWorkInfosForUniqueWork(name).get(Math.min(remaining,5000),TimeUnit.MILLISECONDS);
                    JSONArray records=new JSONArray();
                    for(WorkInfo info:infos){terminal&=info.getState().isFinished();records.put(new JSONObject().put("id",info.getId().toString()).put("state",info.getState().name()));}
                    rows.put(new JSONObject().put("name",name).put("work",records));
                }
                Map<String,Map<String,?>> maps=allMaps(context);Map<String,Integer> components=components(context);
                String signature=maps.toString()+components.toString();long at=SystemClock.uptimeMillis();
                if(!terminal){stableAt=-1;previous=null;}
                else if(stableAt<0||!signature.equals(previous)){stableAt=at;previous=signature;}
                observations.put(new JSONObject().put("atMillis",at).put("namedWorkTerminal",terminal).put("work",rows)
                        .put("preferenceNamespaces",new JSONArray(maps.keySet())).put("components",new JSONObject(components)).put("stableForMillis",stableAt<0?-1:at-stableAt));
                if(terminal&&stableAt>=0&&at-stableAt>=10000){passed=true;break;}
                Thread.sleep(2000);
            }
            assertTrue("Relevant updater work and persisted setup must settle within the original setup budget",passed);
        }catch(Exception|Error failure){error=failure;throw failure;}
        finally{
            JSONObject value=new JSONObject().put("schemaVersion",1).put("namespace",namespace).put("uid",Process.myUid())
                    .put("beganAtMillis",began).put("deadlineMillis",deadline).put("passed",passed).put("failure",error==null?JSONObject.NULL:error.getClass().getName())
                    .put("observations",observations).put("modelsCreatedBySetup",false).put("ordinaryStartupMayHaveCheckedUpdates",true)
                    .put("oldGuestModified",false).put("physicalAcceptance",false);
            Files.write(new File(directory,"setup.json").toPath(),value.toString().getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
        }
    }
    private static Map<String,Map<String,?>> allMaps(Context context){
        TreeMap<String,Map<String,?>> result=new TreeMap<>();File[] files=new File(context.getDataDir(),"shared_prefs").listFiles();assertNotNull(files);
        for(File file:files){assertTrue(file.isFile());assertTrue(file.getName().matches("[A-Za-z0-9_.-]+\\.xml"));String name=file.getName().substring(0,file.getName().length()-4);
            result.put(name,new TreeMap<>(context.getSharedPreferences(name,Context.MODE_PRIVATE).getAll()));}
        return result;
    }
    private static Map<String,Integer> components(Context context)throws Exception{
        android.content.pm.PackageManager manager=context.getPackageManager();
        android.content.pm.PackageInfo info=manager.getPackageInfo(context.getPackageName(),android.content.pm.PackageManager.GET_ACTIVITIES
                |android.content.pm.PackageManager.GET_RECEIVERS|android.content.pm.PackageManager.GET_SERVICES
                |android.content.pm.PackageManager.GET_PROVIDERS|android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS);
        TreeMap<String,Integer> result=new TreeMap<>();result.put("package",manager.getApplicationEnabledSetting(context.getPackageName()));
        for(android.content.pm.ComponentInfo[] group:new android.content.pm.ComponentInfo[][]{info.activities,info.receivers,info.services,info.providers}){
            if(group==null)continue;
            for(android.content.pm.ComponentInfo item:group){assertEquals(context.getPackageName(),item.packageName);assertEquals(Process.myUid(),item.applicationInfo.uid);
                int state=manager.getComponentEnabledSetting(new android.content.ComponentName(item.packageName,item.name));assertTrue(state>=0&&state<=2);assertNull(result.put(item.name,state));}
        }
        return result;
    }
}

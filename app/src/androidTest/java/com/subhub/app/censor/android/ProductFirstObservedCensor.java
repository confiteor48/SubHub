package com.subhub.app.censor.android;

import static org.junit.Assert.*;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.content.FileProvider;
import androidx.test.filters.SdkSuppress;
import com.subhub.app.censor.engine.CensorEngine;
import com.subhub.app.censor.engine.SceneEngine;
import com.subhub.app.censor.engine.ScrollMotionLedger;
import com.subhub.app.detection.DetectorConfig;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Passive literal-first-request record. No replacement capture, model, retry or presentation path. */
@SdkSuppress(minSdkVersion = 34)
final class ProductFirstObservedCensor implements CensorProductSession.Observer {
    static final String TARGET = "com.subhub.app.test";
    static final String INPUT_SHA = "88431cd9653ccd539741b555fb0a46b61558b301d4110412b5bc28b5e3ea6cb5";
    static final String MODEL_SHA = "e41c73a468045acd9f52c33430c5c5dc16aad948782a10b4f55d2ae3cd7905b7";
    private final Context context;
    private final android.app.UiAutomation ui;
    private final File output;
    private final boolean scrollingTarget;
    private final List<JSONObject> rows = new ArrayList<>();
    private long firstRequest = -1, firstEpoch = -1, firstAt = -1, frozenAt = -1, freezeOrdinal = -1;
    private String stopReason;
    private int diagnosticFailures;
    private File sharedInput;
    private Uri inputUri;
    private boolean launched;
    private volatile boolean targetRetired;
    private volatile long retirementAt = -1;
    private int submissionCallbacks, submissionOverflow, endpointFailures;
    private boolean submissionLeasesReleased = true, submissionsOffMain = true;

    ProductFirstObservedCensor(Context context, android.app.UiAutomation ui, String namespace) throws Exception {
        this(context, ui, namespace, false);
    }
    ProductFirstObservedCensor(Context context, android.app.UiAutomation ui, String namespace, boolean scrollingTarget) throws Exception {
        this.context = context; this.ui = ui;
        this.scrollingTarget = scrollingTarget;
        assertNotNull(namespace); assertTrue(namespace.matches("first_visible_[a-f0-9]{32}"));
        output = new File(context.getFilesDir(), "product-first-observed-censor/" + namespace);
        assertFalse("Exclusive diagnostic namespace", output.exists()); assertTrue(output.mkdirs());
        write("started.json", new JSONObject().put("schemaVersion", 1).put("namespace", namespace)
                .put("pid", Process.myPid()).put("inputSha256", INPUT_SHA).put("modelSha256", MODEL_SHA)
                .put("changedRuntime", false).put("pixelAcceptance", false).put("scrollingTarget", scrollingTarget));
    }

    void prepareInput() throws Exception {
        File original = new File(context.getFilesDir(), "first-visible-input.png");
        assertEquals(791555, original.length()); assertEquals(INPUT_SHA, digest(new FileInputStream(original)));
        assertEquals(MODEL_SHA, digest(context.getAssets().open("320n_fp16.onnx")));
        File directory = new File(context.getCacheDir(), "censor-lab/exports");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        sharedInput = new File(directory, "visual-reentry-" + UUID.randomUUID().toString().replace("-", "") + ".png");
        assertTrue(sharedInput.createNewFile());
        try (InputStream input = new FileInputStream(original); FileOutputStream stream = new FileOutputStream(sharedInput)) {
            byte[] buffer = new byte[8192]; int total = 0, count;
            while ((count = input.read(buffer)) != -1) {
                total += count; assertTrue(total <= 791555); stream.write(buffer, 0, count);
            }
            assertEquals(791555, total);
        }
        assertEquals(INPUT_SHA, digest(new FileInputStream(sharedInput)));
        inputUri = FileProvider.getUriForFile(context, context.getPackageName() + ".updates", sharedInput);
        context.grantUriPermission(TARGET, inputUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    private Bitmap sourcePhoto;
    private volatile SnapshotCensorSurface.Post latestPost;
    private volatile AccessibilityWindowReader.Window latestWindow;
    private final java.util.List<SceneEngine.Batch> acceptedBatches = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.List<CensorEngine.EvidenceReceipt> acceptedEvidence = new java.util.concurrent.CopyOnWriteArrayList<>();
    private boolean visible;
    private long visibleAt=-1;

    void launchAndAwaitTarget() throws Exception {
        long launchedAt=now(), deadline=launchedAt+15000;
        launched=true;
        context.startActivity(new Intent().setComponent(new ComponentName(TARGET,ProductTargetFixtureActivity.class.getName()))
                .setDataAndType(inputUri,"image/png")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_NO_ANIMATION|Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra("positiveVisualFixture",true).putExtra("positiveVisualScrollFixture",scrollingTarget).putExtra("visualCommand",1L));
        sourcePhoto=android.graphics.BitmapFactory.decodeFile(sharedInput.getAbsolutePath());assertNotNull(sourcePhoto);
        JSONArray attempts=new JSONArray();boolean passed=false,firstFailed=false;
        try {
            while(now()<deadline) {
                JSONObject before=PositivePhotoWitness.inspect(ui);
                if(before==null){Thread.sleep(10);continue;}
                assertNull(ProductCensorQualification.current());
                long readStart=now();Bitmap pixels=ui.takeScreenshot();long readEnd=now();
                if(pixels==null){attempts.put(new JSONObject().put("readStart",readStart).put("readEnd",readEnd).put("screenshot",false));continue;}
                boolean retained=false;
                JSONObject after=null;
                try {
                    after=PositivePhotoWitness.inspect(ui);
                    JSONObject result=after!=null&&PositivePhotoWitness.sameGeometry(before,after)?PositivePhotoWitness.evaluate(before,pixels,sourcePhoto):null;
                    long checked=now();passed=result!=null&&result.getBoolean("matches")&&checked<=deadline;
                    attempts.put(new JSONObject().put("readStart",readStart).put("readEnd",readEnd).put("checkedAt",checked)
                            .put("before",before).put("after",nullable(after)).put("screenshot",true).put("result",nullable(result)).put("passed",passed));
                    if(passed){savePixels("prebind-input-control.png",pixels);retained=true;break;}
                    if(!firstFailed){savePixels("prebind-first-failed.png",pixels);firstFailed=true;retained=true;}
                } catch(Exception|Error failure) {
                    attempts.put(new JSONObject().put("readStart",readStart).put("readEnd",readEnd).put("checkedAt",now())
                            .put("before",before).put("after",nullable(after)).put("screenshot",true).put("passed",false)
                            .put("failureType",failure.getClass().getName()));
                    if(!retained&&!firstFailed){savePixels("prebind-first-failed.png",pixels);firstFailed=true;}
                    throw failure;
                } finally {pixels.recycle();}
            }
        } finally {write("input-witness.json",new JSONObject().put("launchAt",launchedAt).put("deadline",deadline).put("passed",passed).put("attempts",attempts));}
        assertTrue("Actual five-patch photo support before product binding",passed);
        record("PREBIND_INPUT_CONTROL",row->row.put("productionCapture",false).put("qualifiedPhotoPatches",5));
    }

    @Override public void foreground(AccessibilityWindowReader.Window window){latestWindow=window;}
    synchronized boolean visible(){return visible;}
    File outputDirectory() { return output; }
    JSONObject scrollContext(CensorProductSession owner) throws Exception {
        SnapshotCensorSurface.Post post = latestPost;
        AccessibilityWindowReader.Window window = latestWindow;
        JSONObject value = new JSONObject().put("observedAtMillis", now())
                .put("ownerIdentity", owner == null ? -1 : System.identityHashCode(owner))
                .put("currentOwner", owner != null && owner == ProductCensorQualification.current())
                .put("windowId", window == null ? -1 : window.id)
                .put("windowPackage", window == null ? JSONObject.NULL : window.packageName)
                .put("compositorPostAttributionKnown", false).put("capturedFramePixelsKnown", false);
        if (post == null) return value.put("post", JSONObject.NULL);
        CensorEngine.PaintReceipt paint = post.acceptedPaint;
        JSONArray members = new JSONArray();
        if (paint != null) for (SceneEngine.Mask member : paint.members)
            members.put(new JSONObject().put("identity", member.identity).put("category", member.category)
                    .put("evidenceSource", source(member.evidenceSource)).put("geometrySource", source(member.geometrySource))
                    .put("displayRegion", region(member.displayRegion)));
        return value.put("post", new JSONObject().put("serial", post.postSerial)
                .put("accepted", paint != null).put("revision", paint == null ? JSONObject.NULL : paint.revision)
                .put("canvas", paint == null || paint.canvas == null ? JSONObject.NULL : source(paint.canvas))
                .put("scopeCurrent", paint != null && owner != null && paint.scope.same(owner.controller().activeScope()))
                .put("members", members));
    }

    void observeVisible(CensorProductSession owner) throws Exception {
        SnapshotCensorSurface.Post before=latestPost;
        if(owner==null||ProductCensorQualification.current()!=owner||before==null||before.acceptedPaint==null||before.acceptedPaint.members.isEmpty())return;
        CensorEngine.PaintReceipt paint=before.acceptedPaint;
        if(!paint.scope.same(owner.controller().activeScope()))return;
        AccessibilityWindowReader.Window window=latestWindow;
        JSONObject geometry=PositivePhotoWitness.inspect(ui);
        if(window==null||!TARGET.equals(window.packageName)||geometry==null||geometry.getInt("windowId")!=window.id)return;
        long start=now();Bitmap pixels=ui.takeScreenshot();long end=now();
        if(pixels==null)return;
        boolean saved=false;
        try {
            JSONObject afterGeometry=PositivePhotoWitness.inspect(ui);
            AccessibilityWindowReader.Window afterWindow=latestWindow;
            boolean logical=afterWindow!=null&&window.id==afterWindow.id&&TARGET.equals(afterWindow.packageName)
                    &&paint.scope.same(owner.controller().activeScope())&&ProductCensorQualification.current()==owner
                    &&afterGeometry!=null&&PositivePhotoWitness.sameGeometry(geometry,afterGeometry);
            boolean stable=before==latestPost&&window==afterWindow&&logical;
            JSONObject check=PositivePhotoWitness.evaluate(geometry,pixels,sourcePhoto);
            JSONArray photo=check.getJSONArray("witnesses");
            boolean background=photo.getJSONObject(1).getBoolean("matches")&&photo.getJSONObject(3).getBoolean("matches")&&photo.getJSONObject(4).getBoolean("matches");
            JSONArray probes=new JSONArray();SceneEngine.Mask winner=null;
            for(SceneEngine.Mask member:paint.members){
                SceneEngine.Region box=member.displayRegion;
                if(!"face".equals(member.category)||box.right-box.left<8||box.bottom-box.top<8)continue;
                int x=(int)Math.floor((box.left+box.right)/2),y=(int)Math.floor((box.top+box.bottom)/2);
                if(x<1||y<1||x+1>=pixels.getWidth()||y+1>=pixels.getHeight())continue;
                boolean magenta=true;JSONArray samples=new JSONArray();
                for(int py=y-1;py<=y+1;py++)for(int px=x-1;px<=x+1;px++){
                    int color=pixels.getPixel(px,py);
                    boolean match=android.graphics.Color.red(color)>=247&&android.graphics.Color.green(color)<=8&&Math.abs(android.graphics.Color.blue(color)-128)<=8;
                    magenta&=match;samples.put(new JSONObject().put("x",px).put("y",py).put("argb",color).put("matches",match));
                }
                boolean supported=false,batchSupported=false;
                for(CensorEngine.EvidenceReceipt evidence:acceptedEvidence)if(evidence.positiveInput&&sameSource(evidence.source,member.evidenceSource))
                    for(SceneEngine.AcceptedSubject subject:evidence.subjects)
                        if(subject.identity==member.identity&&subject.partition==member.partition&&member.category.equals(subject.category)
                                &&sameSource(subject.source,member.evidenceSource)&&sameRegion(subject.region,member.evidenceRegion)){
                            supported=true;
                            for(SceneEngine.Batch batch:acceptedBatches)if(sameSource(batch.source,subject.source)&&batch.lane==subject.evidenceLane)
                                for(SceneEngine.Observation observation:batch.observations)
                                    if(observation.partition==member.partition&&member.category.equals(observation.category)&&sameRegion(observation.region,subject.region))batchSupported=true;
                        }
                probes.put(new JSONObject().put("member",member.identity).put("source",source(member.evidenceSource))
                        .put("displayRegion",region(box)).put("batchSupported",batchSupported).put("evidenceSupported",supported).put("samples",samples));
                if(magenta&&supported&&batchSupported){winner=member;break;}
            }
            long checked=now();boolean inBudget;
            synchronized(this){inBudget=firstAt>=0&&checked<=firstAt+8000;}
            boolean passed=(scrollingTarget?logical:stable)&&background&&winner!=null&&inBudget;
            JSONObject candidate=winner==null?null:new JSONObject().put("identity",winner.identity).put("evidenceSource",source(winner.evidenceSource)).put("geometrySource",source(winner.geometrySource));
            record("PIXEL_OBSERVATION",row->row.put("readStartedAtMillis",start).put("readEndedAtMillis",end).put("checkedAtMillis",checked)
                    .put("stableMetadata",stable).put("logicalContextStable",logical)
                    .put("admissionEndpoint",scrollingTarget?"STATIONARY_MOTION_ADMISSION":"FIRST_OBSERVED_VISIBLE_CENSOR")
                    .put("backgroundMatches",background).put("withinBudget",inBudget).put("passed",passed)
                    .put("windowId",window.id).put("postSerial",before.postSerial).put("revision",paint.revision).put("appearanceGeneration",paint.appearanceGeneration)
                    .put("epoch",paint.scope.epoch).put("geometry",paint.scope.geometry).put("photoGeometry",geometry).put("photoResult",check)
                    .put("probes",probes).put("candidate",nullable(candidate)).put("compositorPostAttributionKnown",false).put("capturedFramePixelsKnown",false));
            if(passed){
                savePixels("first-observed-censor.png",pixels);saved=true;
                synchronized(this){visible=true;visibleAt=checked;freeze(scrollingTarget?"STATIONARY_MOTION_ADMITTED":"FIRST_OBSERVED_VISIBLE_CENSOR",checked);}
            }else if(!new File(output,"first-failed-censor.png").exists()){savePixels("first-failed-censor.png",pixels);saved=true;}
        }catch(Exception|Error failure){
            if(!saved&&!new File(output,"first-failed-censor.png").exists())savePixels("first-failed-censor.png",pixels);
            throw failure;
        }finally{pixels.recycle();}
    }
    private static boolean sameSource(SceneEngine.Source first,SceneEngine.Source second){
        return first.epoch==second.epoch&&first.sequence==second.sequence&&first.imageRequestIdentity==second.imageRequestIdentity&&first.kind==second.kind&&first.geometry==second.geometry;
    }
    private static boolean sameRegion(SceneEngine.Region first,SceneEngine.Region second){return first.left==second.left&&first.top==second.top&&first.right==second.right&&first.bottom==second.bottom;}
    private void savePixels(String name,Bitmap pixels)throws Exception{
        File file=new File(output,name);assertTrue(file.createNewFile());
        try(FileOutputStream stream=new FileOutputStream(file)){assertTrue(pixels.compress(Bitmap.CompressFormat.PNG,100,stream));}
    }

    VisualDetectorWorker initialize(Context app, SceneEngine.Lane lane, DetectorConfig config, long partition, String fallback) throws Exception {
        assertNotEquals(Looper.getMainLooper(), Looper.myLooper());
        long call = record("INITIALIZE_BEGIN", row -> row.put("lane", lane.name())
                .put("configuredThreads", config.getInferenceThreads()).put("configuredResolution", config.getInferenceResolution()));
        try {
            VisualDetectorWorker worker = VisualDetectorWorker.initialize(app, lane, config, partition, fallback);
            record("INITIALIZE_RETURN", row -> row.put("call", call).put("lane", lane.name())
                    .put("provider", worker.provider()).put("workerIdentity", System.identityHashCode(worker)));
            return worker;
        } catch (Exception | Error failure) {
            record("INITIALIZE_FAILED", row -> row.put("call", call).put("lane", lane.name()).put("failureType", failure.getClass().getName()));
            throw failure;
        }
    }

    @Override public synchronized void trace(CensorEngine.Trace value) {
        long ordinal = record("TRACE", row -> row.put("stage", value.stage.name()).put("epoch", value.epoch).put("request", value.source)
                .put("lane", value.lane == null ? JSONObject.NULL : value.lane.name()).put("atMillis", value.atMillis)
                .put("queueMillis", value.queueMillis).put("pixelAgeMillis", value.pixelAgeMillis)
                .put("work", value.work == null ? JSONObject.NULL : new JSONObject().put("preprocessMillis", value.work.preprocessMillis)
                        .put("runtimeMillis", value.work.runtimeMillis).put("postprocessMillis", value.work.postprocessMillis)));
        if (ordinal < 0) return;
        long observed = rows.get((int) ordinal - 1).optLong("observedAtMillis", -1);
        if (firstRequest < 0 && value.stage == CensorEngine.Stage.CAPTURE_DISPATCHED) {
            firstRequest = value.source; firstEpoch = value.epoch; firstAt = value.atMillis;
        }
    }
    @Override public void failed(long request, int code) {
        record("FAILED", row -> row.put("request", request).put("platformCode", code).put("rawFrameworkCallbackAtMillis", JSONObject.NULL));
    }
    @Override public void prepared(long request, PixelFrame.Kind kind, long nanos, int bytes) {
        record("PREPARED", row -> row.put("request", request).put("frameKind", kind.name()).put("preparationNanos", nanos)
                .put("bytes", bytes).put("sourcePixelAtMillis", JSONObject.NULL).put("rawFrameworkCallbackAtMillis", JSONObject.NULL));
    }
    void inputBoundary(int type, int contentTypes, int windowId, long sourceAt, long receivedAt, String route, boolean completed) {
        record("INPUT_BOUNDARY", row -> row.put("schemaVersion", 1).put("type", type).put("contentTypes", contentTypes)
                .put("windowId", windowId).put("sourceAtMillis", sourceAt).put("receivedAtMillis", receivedAt)
                .put("route", route).put("completed", completed));
    }
    @Override public void scrollReceived(ScrollMotionLedger.Receipt receipt) {
        record("SCROLL_RECEIVED", row -> {
            ScrollMotionLedger.Sample sample = receipt.sample;
            row.put("schemaVersion", 1).put("admission", receipt.admission.name()).put("refusal", receipt.refusal.name())
                    .put("generation", receipt.generation).put("admittedAtMillis", receipt.admittedAtMillis)
                    .put("producerCount", receipt.producerCount).put("intervalCount", receipt.intervalCount)
                    .put("sample", sample == null ? JSONObject.NULL : new JSONObject().put("epoch", sample.scope.epoch).put("geometry", sample.scope.geometry)
                            .put("windowId", sample.scope.windowId).put("protectionOrigin", sample.scope.protection.origin.name())
                            .put("tapScope", sample.scope.protection.tapScope).put("delivery", sample.deliveryIdentity)
                            .put("owner", sample.owner).put("producer", sample.producer).put("ownerKind", sample.ownerKind.name())
                            .put("sourceAtMillis", sample.sourceAtMillis).put("receivedAtMillis", sample.receivedAtMillis)
                            .put("readStartedAtMillis", sample.readStartedAtMillis).put("readFinishedAtMillis", sample.readFinishedAtMillis)
                            .put("x", coordinate(sample.x)).put("y", coordinate(sample.y))
                            .put("reportedViewport", sample.reportedViewport == null ? JSONObject.NULL : region(sample.reportedViewport)));
        });
    }
    @Override public void batchAccepted(SceneEngine.Batch batch) {
        acceptedBatches.add(batch);
        record("BATCH", row -> {
            JSONArray observations = new JSONArray();
            for (SceneEngine.Observation value : batch.observations) observations.put(new JSONObject().put("category", value.category)
                    .put("partition", value.partition).put("confidence", value.confidence).put("region", region(value.region)));
            row.put("source", source(batch.source)).put("lane", batch.lane.name()).put("observations", observations)
                    .put("inspectionCount", batch.inspections.size());
        });
    }
    @Override public void evidenceAccepted(CensorEngine.EvidenceReceipt receipt) {
        acceptedEvidence.add(receipt);
        record("EVIDENCE", row -> {
            JSONArray subjects = new JSONArray();
            for (SceneEngine.AcceptedSubject value : receipt.subjects) subjects.put(new JSONObject().put("identity", value.identity).put("partition",value.partition)
                    .put("source", source(value.source)).put("category", value.category).put("lane", value.evidenceLane.name())
                    .put("region", region(value.region)).put("validUntilMillis", value.validUntilMillis));
            row.put("source", source(receipt.source)).put("triggerLane", receipt.triggerLane.name())
                    .put("acceptedAtMillis", receipt.acceptedAtMillis).put("positiveInput", receipt.positiveInput).put("subjects", subjects);
        });
    }
    @Override public void posted(SnapshotCensorSurface.Post post) {
        record("POST", row -> {
            JSONArray members = new JSONArray(); CensorEngine.PaintReceipt paint = post.acceptedPaint;
            if (paint != null) for (SceneEngine.Mask value : paint.members) members.put(new JSONObject().put("identity", value.identity).put("partition",value.partition).put("category",value.category)
                    .put("evidenceSource", source(value.evidenceSource)).put("geometrySource", source(value.geometrySource))
                    .put("evidenceRegion", region(value.evidenceRegion)).put("displayRegion", region(value.displayRegion)));
            row.put("serial", post.postSerial).put("endpointGeneration", post.endpointGeneration).put("accepted", paint != null)
                    .put("recordedAtMillis", post.recordedAtMillis).put("postReturnedAtMillis", post.postReturnedAtMillis)
                    .put("uiAdmittedAtMillis", post.uiAdmittedAtMillis).put("compositorAtMillis", post.compositorAtMillis)
                    .put("queuedNanos", post.queuedNanos).put("lockNanos", post.lockNanos)
                    .put("recordingNanos", post.recordingNanos).put("postNanos", post.postNanos)
                    .put("renderThreadId", post.renderThreadId).put("mainThread", post.mainThread)
                    .put("revision", paint == null ? JSONObject.NULL : paint.revision)
                    .put("canvas", paint == null || paint.canvas == null ? JSONObject.NULL : source(paint.canvas)).put("members", members);
        });
        latestPost=post;
    }
    @Override public boolean collectSubmissions() { return scrollingTarget; }
    @Override public synchronized void submitted(SurfaceSubmission value) {
        CensorProductSession owner = ProductCensorQualification.current();
        SnapshotCensorSurface surface = owner == null ? null : owner.controller().surface();
        Integer users = surface == null ? null : surface.surfaceUseCount();
        Integer drawings = surface == null ? null : surface.renderer().activeDrawingCount();
        boolean onMain = Looper.myLooper() == Looper.getMainLooper();
        submissionLeasesReleased &= users != null && users == 0 && drawings != null && drawings == 0;
        submissionsOffMain &= !onMain && value.renderThreadId == Thread.currentThread().getId();
        if (++submissionCallbacks > 4096) { submissionOverflow++; return; }
        record("SUBMISSION", row -> row.put("schemaVersion", 1).put("value", submission(value))
                .put("surfaceUsers", nullable(users)).put("drawingLeases", nullable(drawings))
                .put("callbackMainThread", onMain).put("callbackThreadId", Thread.currentThread().getId()));
    }
    synchronized void submissionsClosed(CensorProductSession owner) {
        SnapshotCensorSurface surface = owner == null ? null : owner.controller().surface();
        record("SUBMISSION_SUMMARY", row -> row.put("schemaVersion", 1).put("metrics", surface == null ? JSONObject.NULL : new JSONObject()
                .put("schema", 3).put("mainThread", !submissionsOffMain).put("actualAccessibility", true).put("pixelAcceptance", false)
                .put("lockAttempts", surface.lockCount()).put("successfulPosts", surface.postCount())
                .put("postAttempts", surface.postAttempts()).put("postReturns", surface.postReturns())
                .put("droppedSuccessfulCompletions", surface.droppedSuccessfulCompletionCount()).put("endpointFailures", endpointFailures)
                .put("submissionCallbacks", submissionCallbacks).put("submissionOverflow", submissionOverflow)
                .put("submissionObserverFailures", surface.submissionObserverFailures()).put("allSubmissionLeasesReleased", submissionLeasesReleased)
                .put("submissionObservationNanos", surface.submissionObservationNanos())
                .put("maximumSubmissionObservationNanos", surface.maximumSubmissionObservationNanos())
                .put("submissionLabel", "product-long-scroll-recording").put("controlledPresentationTimeMillis", -1)));
        assertNotNull("Actual surface owner retained through final export", surface);
        assertTrue("Final owner drain is not a live callback lease check", owner.managedResourcesDrained());
        assertTrue("Existing opt-in reached both product bridges", submissionCallbacks > 0);
        assertEquals("No copied submission overflow", 0, submissionOverflow);
        assertEquals("Every actual attempt delivered", surface.postAttempts(), submissionCallbacks);
        assertEquals("No submission observer failure", 0, surface.submissionObserverFailures());
        assertTrue("Actual holder and drawing leases released at each callback", submissionLeasesReleased);
        assertTrue("Submission callbacks remained on their rendering thread", submissionsOffMain);
    }
    private static JSONObject submission(SurfaceSubmission value) throws Exception {
        Object paintJson = JSONObject.NULL;
        if (value.paint != null) {
            SurfaceSubmission.Paint paint = value.paint; JSONArray members = new JSONArray();
            for (SurfaceSubmission.Member member : paint.members) members.put(new JSONObject().put("identity", member.identity)
                    .put("geometrySource", submissionSource(member.geometryEpoch, member.geometry, member.geometrySequence, member.geometryRequest, member.geometryKind))
                    .put("evidenceSource", submissionSource(member.evidenceEpoch, member.evidenceGeometry, member.evidenceSequence, member.evidenceRequest, member.evidenceKind))
                    .put("destination", region(member.destination)).put("canvasClip", region(member.canvasClip))
                    .put("ownerClip", member.ownerClip == null ? JSONObject.NULL : region(member.ownerClip))
                    .put("clippedBounds", member.clippedBounds == null ? JSONObject.NULL : region(member.clippedBounds)));
            paintJson = new JSONObject().put("mode", paint.mode.name()).put("epoch", paint.epoch).put("geometry", paint.geometry)
                    .put("sceneRevision", paint.sceneRevision).put("presentationRevision", paint.presentationRevision).put("sampledUptimeMillis", paint.sampledAtMillis)
                    .put("rawMembers", paint.rawMembers).put("geometryComplete", paint.geometryComplete).put("members", members);
        }
        return new JSONObject().put("attempt", value.attempt).put("postSerial", value.postSerial).put("generation", value.endpointGeneration)
                .put("postEndedUptimeMillis", value.postEndedAtMillis).put("renderThreadId", value.renderThreadId)
                .put("queuedElapsedNanos", value.queuedNanos).put("lockElapsedNanos", value.lockNanos)
                .put("recordingElapsedNanos", value.recordingNanos).put("postElapsedNanos", value.postNanos)
                .put("surface", new JSONArray().put(value.surfaceX).put(value.surfaceY).put(value.surfaceWidth).put(value.surfaceHeight))
                .put("holderReturned", value.holderReturned).put("canvasClipValidated", value.canvasClipValidated).put("recordingFinished", value.recordingFinished)
                .put("cleanupSucceeded", value.cleanupSucceeded).put("compositorUptimeMillis", value.compositorAtMillis).put("paint", paintJson);
    }
    private static JSONObject submissionSource(long epoch, long geometry, long sequence, long request, SceneEngine.SourceKind kind) throws Exception {
        return new JSONObject().put("epoch", epoch).put("geometry", geometry).put("sequence", sequence).put("request", request).put("kind", kind.name());
    }
    @Override public void providerReady(SceneEngine.Lane lane, String provider, long millis) {
        record("PROVIDER_READY", row -> row.put("lane", lane.name()).put("provider", provider).put("reportedMillis", millis));
    }
    @Override public void modelUnavailable(SceneEngine.Lane lane) { record("UNAVAILABLE", row -> row.put("lane", lane.name())); }
    @Override public void sessionFailure(Throwable failure) { record("FAULT", row -> row.put("failureType", failure.getClass().getName())); }
    @Override public void resourceFailure() { record("RESOURCE_FAILURE", row -> { }); }
    @Override public synchronized void endpointFailure() { endpointFailures++; record("ENDPOINT_FAILURE", row -> { }); }
    @Override public void drained() { record("DRAIN_NOTICE", row -> row.put("provesExecutorTermination", false)); }
    @Override public void executorsTerminated(boolean disposed) { record("EXECUTORS_TERMINATED", row -> row.put("disposed", disposed)); }

    synchronized boolean firstDispatched() { return firstRequest >= 0; }
    synchronized boolean outcomeFrozen() {
        if (stopReason == null && firstAt >= 0 && now() >= firstAt + 8000) freeze("OBSERVATION_EXPIRED", now());
        return stopReason != null;
    }
    private void freeze(String reason, long observed) {
        stopReason = reason; frozenAt = observed; freezeOrdinal = rows.size();
    }
    void bound(int serviceIdentity, int pid, int capabilities, boolean legacyAbsent) {
        record("BOUND", row -> row.put("serviceIdentity", serviceIdentity).put("pid", pid)
                .put("capabilities", capabilities).put("legacyAbsent", legacyAbsent));
    }
    void closeRequested() { record("CLOSE_REQUESTED", row -> { }); }
    void managedDrained() { record("MANAGED_DRAINED", row -> { }); }

    synchronized void exportClosed(CensorProductSession owner, Throwable bodyFailure, Throwable closureFailure) throws Exception {
        JSONObject counts = new JSONObject();
        for (JSONObject row : rows) { String kind = row.getString("kind"); counts.put(kind, counts.optInt(kind) + 1); }
        write("closed.json", new JSONObject().put("schemaVersion", 1).put("pid", Process.myPid())
                .put("firstRequest", firstRequest).put("firstEpoch", firstEpoch).put("firstDispatchedAtMillis", firstAt)
                .put("deadlineMillis", firstAt < 0 ? -1 : firstAt + 8000).put("stopReason", nullable(stopReason))
                .put("frozenAtMillis", frozenAt).put("freezeOrdinal", freezeOrdinal)
                .put("bodyFailure", failure(bodyFailure)).put("closureFailure", failure(closureFailure))
                .put("managedDrain", owner != null && owner.managedResourcesDrained())
                .put("actualProductionService", owner != null && owner.service() instanceof com.subhub.app.service.ScreenshotAccessibilityService)
                .put("rawRowCount", rows.size()).put("rawKindCounts", counts).put("rows", new JSONArray(rows))
                .put("diagnosticFailures", diagnosticFailures).put("changedRuntime", false)
                .put("visible",visible).put("visibleObservedAtMillis",visibleAt).put("capturedFramePixelsKnown",false).put("compositorPostAttributionKnown",false)
                .put("admissionEndpoint",scrollingTarget?"STATIONARY_MOTION_ADMISSION":"FIRST_OBSERVED_VISIBLE_CENSOR")
                .put("firstVisibleLatencyClaimed",false).put("currentPaintPersistenceKnown",false)
                .put("frameworkCallbackReturnKnown", false).put("pixelClockKnown", false).put("performanceImprovement", false));
    }

    void retireTargetAndInput() throws Exception {
        if(sourcePhoto!=null){sourcePhoto.recycle();sourcePhoto=null;}
        Throwable error = null;
        try {
            if (launched) {
                android.os.ResultReceiver acknowledgement = new android.os.ResultReceiver(new android.os.Handler(Looper.getMainLooper())) {
                    @Override protected void onReceiveResult(int code, android.os.Bundle data) {
                        if (code == 1 && data != null && inputUri.toString().equals(data.getString("retiredUri"))
                                && INPUT_SHA.equals(data.getString("inputSha256")) && data.getBoolean("streamsClosed")) {
                            retirementAt = now(); targetRetired = true;
                        } else record("INVALID_RETIREMENT_ACK", row -> { });
                    }
                };
                context.startActivity(new Intent().setComponent(new ComponentName(TARGET, ProductTargetFixtureActivity.class.getName()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION).putExtra("finish", true)
                        .putExtra("visualRetireUri", inputUri.toString()).putExtra("visualRetirementReceiver", acknowledgement));
                await("Exact target onDestroy/stream acknowledgement", now() + 15000, () -> targetRetired);
            }
        } catch (Exception | Error failure) { error = failure; throw failure; }
        finally {
            boolean retired = !launched || targetRetired, revoked = false;
            if (inputUri != null && retired) { context.revokeUriPermission(inputUri, Intent.FLAG_GRANT_READ_URI_PERMISSION); revoked = true; }
            boolean removed = sharedInput == null || !sharedInput.exists();
            if (sharedInput != null && sharedInput.exists() && retired) removed = sharedInput.delete();
            write("fixture-cleanup.json", new JSONObject().put("schemaVersion", 1).put("launched", launched)
                    .put("targetRetired", targetRetired).put("retirementAtMillis", retirementAt).put("uriRevoked", revoked)
                    .put("ownedCopyRemoved", removed).put("retainedIfInputUseUnknown", !removed && !retired).put("failure", failure(error)));
            if (error == null) assertTrue("Only acknowledged retired input may be removed", retired && removed);
        }
    }
    void restored(boolean exact, Throwable failure) throws Exception {
        write("state-restored.json", new JSONObject().put("schemaVersion", 1).put("exact", exact).put("failure", failure(failure)).put("atMillis", now()));
    }
    void dailyStore(JSONObject value) throws Exception { write("daily-store.json", value); }

    private interface Fill { void apply(JSONObject row) throws Exception; }
    private synchronized long record(String kind, Fill fill) {
        try {
            JSONObject row = new JSONObject().put("ordinal", rows.size() + 1).put("kind", kind).put("observedAtMillis", now());
            fill.apply(row); rows.add(row); return rows.size();
        } catch (Exception failure) { diagnosticFailures++; return -1; }
    }
    private void write(String name, JSONObject value) throws Exception {
        Files.write(new File(output, name).toPath(), value.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
    }
    private static JSONObject source(SceneEngine.Source value) throws Exception {
        return new JSONObject().put("epoch", value.epoch).put("sequence", value.sequence).put("imageRequestIdentity", value.imageRequestIdentity)
                .put("kind", value.kind.name()).put("geometry", value.geometry).put("width", value.width).put("height", value.height)
                .put("pixelAtMillis", value.pixelAtMillis).put("receivedAtMillis", value.receivedAtMillis);
    }
    private static JSONObject coordinate(ScrollMotionLedger.Coordinate value) throws Exception {
        return new JSONObject().put("valid", value.valid).put("absoluteKnown", value.absoluteKnown).put("absolute", value.absolute)
                .put("deltaKnown", value.deltaKnown).put("delta", value.delta);
    }
    private static JSONArray region(SceneEngine.Region value) throws Exception { return new JSONArray(new double[]{value.left, value.top, value.right, value.bottom}); }
    private static Object nullable(Object value) { return value == null ? JSONObject.NULL : value; }
    private static Object failure(Throwable value) { return value == null ? JSONObject.NULL : value.getClass().getName(); }
    static long now() { return SystemClock.uptimeMillis(); }
    static void await(String label, long deadline, BooleanSupplier condition) throws Exception {
        while (!condition.getAsBoolean()) { assertTrue(label, now() < deadline); Thread.sleep(10); }
    }
    private static String digest(InputStream source) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream input = source) { byte[] buffer = new byte[8192]; int count; while ((count = input.read(buffer)) != -1) hash.update(buffer, 0, count); }
        StringBuilder result = new StringBuilder();
        for (byte item : hash.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", item & 255));
        return result.toString();
    }
}

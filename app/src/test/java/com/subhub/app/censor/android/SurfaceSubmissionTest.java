package com.subhub.app.censor.android;

import com.subhub.app.censor.engine.SceneEngine;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class SurfaceSubmissionTest {
    private static final SceneEngine.Region BOX = new SceneEngine.Region(1.25, 2.5, 101.75, 202.125);
    private static SurfaceSubmission.Member member(SceneEngine.Region canvas, SceneEngine.Region owner) {
        return new SurfaceSubmission.Member(7, new SceneEngine.Source(1, 2, 42, 400, 600, 100, -1),
                new SceneEngine.Source(3, 4, 43, 400, 600, 110, -1), BOX, canvas, owner);
    }
    @Test public void actualFloatDestinationAndBothSourceLineagesAreCopiedWithoutOwners() {
        SurfaceSubmission.Member value = member(new SceneEngine.Region(0, 0, 300, 300), null);
        assertNotSame(BOX, value.destination); assertEquals(1.25, value.destination.left, 0);
        assertEquals(202.125, value.destination.bottom, 0); assertEquals(1, value.geometryEpoch);
        assertEquals(42, value.geometry); assertEquals(2, value.geometrySequence); assertEquals(2, value.geometryRequest);
        assertEquals(3, value.evidenceEpoch); assertEquals(43, value.evidenceGeometry);
        assertEquals(4, value.evidenceSequence); assertEquals(4, value.evidenceRequest);
        assertEquals(SceneEngine.SourceKind.IMAGE, value.geometryKind); assertNull(value.ownerClip);
    }
    @Test public void emptyThreeWayIntersectionIsNotConfusedWithPairwiseIntersections() {
        SceneEngine.Region canvas = new SceneEngine.Region(0, 0, 300, 40), owner = new SceneEngine.Region(0, 60, 300, 300);
        assertNotNull(BOX.clippedTo(canvas)); assertNotNull(BOX.clippedTo(owner));
        assertNull(member(canvas, owner).clippedBounds);
    }
    @Test public void clippingKeepsOnePhysicalCoordinateBasisAndCopiesTheOwner() {
        SceneEngine.Region canvas = new SceneEngine.Region(10, 20, 90, 160), owner = new SceneEngine.Region(30, 40, 80, 170);
        SurfaceSubmission.Member value = member(canvas, owner);
        assertNotSame(canvas, value.canvasClip); assertNotSame(owner, value.ownerClip);
        assertEquals(30, value.clippedBounds.left, 0); assertEquals(40, value.clippedBounds.top, 0);
        assertEquals(80, value.clippedBounds.right, 0); assertEquals(160, value.clippedBounds.bottom, 0);
    }
    @Test public void missingOrCappedGeometryAndUnsupportedReverseHolesStayIncomplete() {
        List<SurfaceSubmission.Member> values = new ArrayList<>();
        for (int i = 0; i < SurfaceSubmission.MAXIMUM_MEMBERS; i++) values.add(member(BOX, null));
        assertFalse(SurfaceSubmission.Paint.drawn(1, 42, 1, 2, 100, values.size() + 1, values, false).geometryComplete);
        assertFalse(SurfaceSubmission.Paint.drawn(1, 42, 1, 2, 100, 0, Collections.emptyList(), true).geometryComplete);
        assertTrue(SurfaceSubmission.Paint.clear().geometryComplete);
        assertEquals(-1, SurfaceSubmission.Paint.clear().sceneRevision);
        values.add(member(BOX, null));
        try { SurfaceSubmission.Paint.drawn(1, 42, 1, 2, 100, values.size(), values, false); fail(); }
        catch (IllegalArgumentException expected) { }
    }
    @Test public void inventoriesAreImmutableAndUnknownEndpointOutcomeDoesNotBecomeClear() {
        List<SurfaceSubmission.Member> input = new ArrayList<>(); input.add(member(BOX, null));
        SurfaceSubmission.Paint paint = SurfaceSubmission.Paint.drawn(1, 42, 1, 2, 100, 1, input, false);
        input.clear(); assertEquals(1, paint.members.size()); assertTrue(paint.geometryComplete);
        try { paint.members.clear(); fail(); } catch (UnsupportedOperationException expected) { }
        SurfaceSubmission value = new SurfaceSubmission(1, 3, 4, 120, 5, 1, 2, 3, 4, 10, 20, 320, 320,
                false, true, true, true, paint);
        assertFalse(value.holderReturned); assertEquals(SurfaceSubmission.Mode.MEMBERS, value.paint.mode);
    }
    @Test public void retainedMetadataContainsNoRenderSessionSourceOrPixelReferences() {
        for (Class<?> type : new Class<?>[]{SurfaceSubmission.class, SurfaceSubmission.Paint.class, SurfaceSubmission.Member.class}) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                Class<?> value = field.getType();
                assertTrue(field.toString(), Modifier.isFinal(field.getModifiers()));
                assertTrue(field.toString(), value.isPrimitive() || value.isEnum() || value == SceneEngine.Region.class
                        || value == List.class || value == SurfaceSubmission.Paint.class);
            }
        }
    }
    @Test public void presentationRevisionPreservesTheEntireSignedOpaqueTokenDomain() {
        for (long revision : new long[]{Long.MIN_VALUE, -65536, -1, 0, Long.MAX_VALUE}) {
            SurfaceSubmission.Paint value = SurfaceSubmission.Paint.drawn(1, 42, 1, revision, 100, 0, Collections.emptyList(), false);
            assertEquals(revision, value.presentationRevision);
        }
    }
}

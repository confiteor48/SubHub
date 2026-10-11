package com.subhub.app.censor.android;

import com.subhub.app.censor.engine.CensorEngine;
import com.subhub.app.censor.engine.PaintMapping;
import com.subhub.app.censor.engine.SceneEngine;
import com.subhub.app.settings.CensorAppearance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Resource dependencies only. Displayed motion/scene revision are deliberately not cache keys. */
final class PaintResourceKey {
    final long epoch, generation, artworkRevision;
    final SceneEngine.Source canvas;
    final boolean canvasPixels;
    final List<Member> members;
    private static final class Member {
        final long identity;
        final SceneEngine.Source source;
        final SceneEngine.Region crop;
        Member(SceneEngine.Mask mask, SceneEngine.Region crop) { identity = mask.identity; source = mask.evidenceSource; this.crop = crop; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Member)) return false;
            Member value = (Member) other;
            return identity == value.identity && source == value.source && PreparedCensorPaint.same(crop, value.crop);
        }
        @Override public int hashCode() { return Objects.hash(identity, System.identityHashCode(source), crop.left, crop.top, crop.right, crop.bottom); }
    }
    private PaintResourceKey(long epoch, long generation, long revision, SceneEngine.Source canvas,
            boolean pixels, List<Member> members) {
        this.epoch = epoch; this.generation = generation; artworkRevision = revision;
        this.canvas = canvas; canvasPixels = pixels; this.members = Collections.unmodifiableList(members);
    }
    static PaintResourceKey of(CensorEngine<?>.RenderView view, CensorAppearance appearance,
            long generation, long artworkRevision) {
        CensorAppearance.Type type = PreparedCensorPaint.effectiveType(appearance);
        boolean pixels = type == CensorAppearance.Type.PIXELATE || type == CensorAppearance.Type.BLUR
                || type == CensorAppearance.Type.GLITCH || type == CensorAppearance.Type.TAPE;
        List<Member> members = new ArrayList<>(); SceneEngine.Source canvas = null; boolean canvasPixels = false;
        if (pixels && appearance.isReverseMode()) {
            canvas = view.canvasSource; canvasPixels = view.canvasPixels() != null;
        } else if (pixels) {
            for (SceneEngine.Group group : view.snapshot.groups) for (SceneEngine.Mask mask : group.members) {
                if (view.sourcePixels(mask.evidenceSource.sequence) != null) members.add(new Member(mask,
                        PaintMapping.padded(mask.evidenceRegion, appearance.getSizePadding(), PreparedCensorPaint.isText(mask))));
            }
            members.sort(Comparator.comparingLong(member -> member.identity));
        }
        return new PaintResourceKey(view.snapshot.destination.epoch, generation, artworkRevision, canvas, canvasPixels, members);
    }
    @Override public boolean equals(Object other) {
        if (!(other instanceof PaintResourceKey)) return false;
        PaintResourceKey value = (PaintResourceKey) other;
        return epoch == value.epoch && generation == value.generation && artworkRevision == value.artworkRevision
                && canvas == value.canvas && canvasPixels == value.canvasPixels && members.equals(value.members);
    }
    @Override public int hashCode() { return Objects.hash(epoch, generation, artworkRevision, System.identityHashCode(canvas), canvasPixels, members); }
}

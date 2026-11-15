package com.sora.splineroads.world;

import com.sora.splineroads.core.ElevatedAccess;
import com.sora.splineroads.core.LaneAdditions;
import com.sora.splineroads.core.LanePoints;
import com.sora.splineroads.core.LaneSections;
import com.sora.splineroads.core.RoadGeometry;
import java.util.ArrayList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

public final class LanePointCodec {
   public static CompoundTag position(RoadGeometry.V value) {
      return RoadRecord.writeNode(new RoadGeometry.Node(value, 0.0, 0.0));
   }

   public static RoadGeometry.V position(CompoundTag tag) {
      return RoadRecord.readNode(tag).position();
   }

   public static CompoundTag ref(LanePoints.Ref r) {
      CompoundTag t = new CompoundTag();
      if (r.junction() != null) {
         t.putUUID("Junction", r.junction());
      } else {
         t.putUUID("Road", r.road());
         t.putUUID("Point", r.point());
      }

      return t;
   }

   public static LanePoints.Ref ref(CompoundTag t) {
      return t.hasUUID("Junction") ? LanePoints.Ref.junction(t.getUUID("Junction")) : LanePoints.Ref.lane(t.getUUID("Road"), t.getUUID("Point"));
   }

   public static CompoundTag options(LanePoints.Options o) {
      CompoundTag t = new CompoundTag();
      t.putString("Path", o.path().name());
      t.putBoolean("SourceExtra", o.sourceExtra());
      t.putBoolean("TargetExtra", o.targetExtra());
      t.putDouble("Radius", o.radius());
      t.putDouble("Transition", o.transition());
      t.putString("Departure", o.departure().name());
      t.putString("Arrival", o.arrival().name());
      t.putString("Elevation", o.elevation().name());
      t.putString("Landing", o.landing().name());
      t.putBoolean("GradeOverride", o.gradeOverride());
      t.putBoolean("AllowTunnel", o.allowTunnel());
      if (o.lanes() != 1) {
         t.putInt("Lanes", o.lanes());
      }

      return t;
   }

   public static LanePoints.Options options(CompoundTag t) {
      return t.isEmpty()
         ? LanePoints.Options.DEFAULT
         : new LanePoints.Options(
            LanePoints.Path.valueOf(t.getString("Path")),
            t.contains("Departure")
               ? LanePoints.Departure.valueOf(t.getString("Departure"))
               : (t.getBoolean("SourceExtra") ? LanePoints.Departure.EXTRA : LanePoints.Departure.BRANCH),
            t.contains("Arrival")
               ? LanePoints.Arrival.valueOf(t.getString("Arrival"))
               : (t.getBoolean("TargetExtra") ? LanePoints.Arrival.EXTRA : LanePoints.Arrival.MERGE),
            t.getDouble("Radius"),
            t.getDouble("Transition"),
            t.contains("Elevation") ? LanePoints.Elevation.valueOf(t.getString("Elevation")) : LanePoints.Elevation.AUTO,
            t.contains("Landing") ? LanePoints.Landing.valueOf(t.getString("Landing")) : LanePoints.Landing.FLEXIBLE,
            t.getBoolean("GradeOverride"),
            t.getBoolean("AllowTunnel"),
            t.contains("Lanes") ? t.getInt("Lanes") : 1
         );
   }

   public static CompoundTag link(LanePoints.Link link) {
      CompoundTag t = new CompoundTag();
      t.put("From", ref(link.from()));
      t.put("To", ref(link.to()));
      t.put("Options", options(link.options()));
      t.putDouble("TargetOffset", link.targetOffset());
      t.putBoolean("ProtectedMerge", link.protectedMerge());
      t.putBoolean("RectangularClosure", link.rectangularClosure());
      if (link.junctionMouth() != null) {
         t.put("JunctionMouth", position(link.junctionMouth()));
      }

      return t;
   }

   public static LanePoints.Link link(CompoundTag t) {
      return new LanePoints.Link(
         ref(t.getCompound("From")),
         ref(t.getCompound("To")),
         options(t.getCompound("Options")),
         t.contains("JunctionMouth") ? position(t.getCompound("JunctionMouth")) : null,
         t.getDouble("TargetOffset"),
         t.getBoolean("ProtectedMerge"),
         t.getBoolean("RectangularClosure")
      );
   }

   public static CompoundTag write(LanePoints.Data data) {
      CompoundTag t = new CompoundTag();
      ListTag points = new ListTag();

      for (LanePoints.Point p : data.points()) {
         CompoundTag q = new CompoundTag();
         q.putUUID("Id", p.id());
         q.putString("Origin", p.origin().name());
         q.putInt("Lane", p.lane());
         q.putDouble("MergeLength", p.mergeLength());
         q.put("Position", position(p.position()));
         if (p.anchor() != null) {
            q.put("Anchor", position(p.anchor()));
         }

         points.add(q);
      }

      t.put("Points", points);
      t.putInt("PriorityDepth", data.priorityDepth());
      if (data.link() != null) {
         t.put("Link", link(data.link()));
      }

      ListTag openings = new ListTag();

      for (LanePoints.Opening o : data.openings()) {
         CompoundTag q = new CompoundTag();
         q.putUUID("Connection", o.connection());
         q.putDouble("HalfWidth", o.halfWidth());
         ListTag path = new ListTag();

         for (RoadGeometry.V v : o.centerline()) {
            path.add(position(v));
         }

         q.put("Path", path);
         openings.add(q);
      }

      t.put("Openings", openings);
      ListTag cuts = new ListTag();

      for (LaneSections.Cut c : data.cuts()) {
         CompoundTag q = new CompoundTag();
         q.putUUID("Connection", c.connection());
         q.putInt("Lane", c.lane());
         q.putInt("Sign", c.sign());
         q.putDouble("Begin", c.begin());
         q.putDouble("End", c.end());
         q.putDouble("Transition", c.transition());
         q.putBoolean("Temporary", c.temporary());
         q.putBoolean("ArrivalClosure", c.arrival());
         q.putBoolean("Rectangular", c.rectangular());
         q.putBoolean("Underpass", c.underpass());
         if (c.replacement() != null) {
            q.putUUID("Replacement", c.replacement());
         }

         cuts.add(q);
      }

      t.put("LaneCuts", cuts);
      ListTag additions = new ListTag();

      for (LaneAdditions.Addition a : data.additions()) {
         CompoundTag q = new CompoundTag();
         q.putUUID("Connection", a.connection());
         q.putInt("Slot", a.slot());
         q.putInt("Sign", a.sign());
         q.putDouble("Station", a.station());
         q.putDouble("Transition", a.transition());
         if (Double.isFinite(a.end())) {
            q.putDouble("End", a.end());
         }

         additions.add(q);
      }

      t.put("OuterLaneAdditions", additions);
      ListTag bands = new ListTag();

      for (LanePoints.Band b : data.bands()) {
         CompoundTag q = new CompoundTag();
         q.putDouble("Station", b.station());
         q.putDouble("Low", b.low());
         q.putDouble("High", b.high());
         bands.add(q);
      }

      if (!bands.isEmpty()) {
         t.put("MotorBands", bands);
      }

      ListTag access = new ListTag();

      for (ElevatedAccess.Reservation r : data.access()) {
         CompoundTag q = new CompoundTag();
         q.putUUID("Connection", r.connection());
         q.putUUID("Point", r.point());
         q.put("Lanes", write(LanePoints.Data.EMPTY.cuts(r.cuts()).additions(r.additions())));
         ListTag gaps = new ListTag();

         for (ElevatedAccess.Gap g : r.gaps()) {
            CompoundTag v = new CompoundTag();
            v.putDouble("From", g.from());
            v.putDouble("To", g.to());
            v.putDouble("Low", g.low());
            v.putDouble("High", g.high());
            gaps.add(v);
         }

         q.put("Gaps", gaps);
         access.add(q);
      }

      if (!access.isEmpty()) {
         t.put("ElevatedAccess", access);
      }

      return t;
   }

   public static LanePoints.Data read(CompoundTag t) {
      ArrayList<LanePoints.Point> points = new ArrayList<>();

      for (Tag value : t.getList("Points", 10)) {
         CompoundTag p = (CompoundTag)value;
         points.add(
            new LanePoints.Point(
               p.getUUID("Id"),
               LanePoints.Origin.valueOf(p.getString("Origin")),
               p.getInt("Lane"),
               position(p.getCompound("Position")),
               p.contains("Anchor", 10) ? position(p.getCompound("Anchor")) : null,
               p.getDouble("MergeLength")
            )
         );
      }

      ArrayList<LanePoints.Opening> openings = new ArrayList<>();

      for (Tag value : t.getList("Openings", 10)) {
         CompoundTag o = (CompoundTag)value;
         ArrayList<RoadGeometry.V> path = new ArrayList<>();

         for (Tag p : o.getList("Path", 10)) {
            path.add(position((CompoundTag)p));
         }

         openings.add(new LanePoints.Opening(o.getUUID("Connection"), path, o.getDouble("HalfWidth")));
      }

      ArrayList<LaneSections.Cut> cuts = new ArrayList<>();

      for (Tag v : t.getList("LaneCuts", 10)) {
         CompoundTag q = (CompoundTag)v;
         cuts.add(
            new LaneSections.Cut(
               q.getUUID("Connection"),
               q.getInt("Lane"),
               q.getInt("Sign"),
               q.getDouble("Begin"),
               q.getDouble("End"),
               q.getDouble("Transition"),
               q.hasUUID("Replacement") ? q.getUUID("Replacement") : null,
               q.getBoolean("Temporary"),
               q.getBoolean("ArrivalClosure"),
               q.getBoolean("Rectangular"),
               q.getBoolean("Underpass")
            )
         );
      }

      ArrayList<LaneAdditions.Addition> additions = new ArrayList<>();

      for (Tag v : t.getList("OuterLaneAdditions", 10)) {
         CompoundTag q = (CompoundTag)v;
         additions.add(
            new LaneAdditions.Addition(
               q.getUUID("Connection"),
               q.getInt("Slot"),
               q.getInt("Sign"),
               q.getDouble("Station"),
               q.getDouble("Transition"),
               q.contains("End") ? q.getDouble("End") : Double.NaN
            )
         );
      }

      ArrayList<LanePoints.Band> bands = new ArrayList<>();

      for (Tag v : t.getList("MotorBands", 10)) {
         CompoundTag q = (CompoundTag)v;
         bands.add(new LanePoints.Band(q.getDouble("Station"), q.getDouble("Low"), q.getDouble("High")));
      }

      ArrayList<ElevatedAccess.Reservation> access = new ArrayList<>();

      for (Tag value : t.getList("ElevatedAccess", 10)) {
         CompoundTag q = (CompoundTag)value;
         CompoundTag laneTag = q.getCompound("Lanes").copy();
         laneTag.remove("ElevatedAccess");
         LanePoints.Data laneData = read(laneTag);
         ArrayList<ElevatedAccess.Gap> gaps = new ArrayList<>();

         for (Tag item : q.getList("Gaps", 10)) {
            CompoundTag v = (CompoundTag)item;
            gaps.add(new ElevatedAccess.Gap(v.getDouble("From"), v.getDouble("To"), v.getDouble("Low"), v.getDouble("High")));
         }

         access.add(new ElevatedAccess.Reservation(q.getUUID("Connection"), q.getUUID("Point"), laneData.cuts(), laneData.additions(), gaps));
      }

      return new LanePoints.Data(
         points,
         t.contains("Link") ? link(t.getCompound("Link")) : null,
         openings,
         t.contains("PriorityDepth") ? t.getInt("PriorityDepth") : (t.contains("Link") ? 1 : 0),
         cuts,
         additions,
         bands,
         access
      );
   }

   private LanePointCodec() {
   }
}

package com.sora.splineroads.world;

import com.sora.splineroads.core.ElevatedAccess;
import com.sora.splineroads.core.LaneAdditions;
import com.sora.splineroads.core.LanePoints;
import com.sora.splineroads.core.LaneSections;
import com.sora.splineroads.core.RoadAttachments;
import com.sora.splineroads.core.RoadFurniture;
import com.sora.splineroads.core.RoadGantry;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadInfrastructure;
import com.sora.splineroads.core.RoadPlanner;
import com.sora.splineroads.core.RoadPoleModel;
import com.sora.splineroads.core.RoadProfile;
import com.sora.splineroads.core.RoadQueries;
import com.sora.splineroads.core.RoadSignals;
import com.sora.splineroads.core.RoadSlicing;
import com.sora.splineroads.core.RoadStreetscape;
import com.sora.splineroads.core.RoadStructures;
import com.sora.splineroads.core.RoadTransitions;
import com.sora.splineroads.core.RoadsideBypass;
import com.sora.splineroads.core.TollModels;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;

public final class RoadSplitGeometry {
   public static double station(RoadGeometry.Mesh mesh, RoadGeometry.V point) {
      return RoadQueries.project(mesh, point).sample().distance();
   }

   private static double horizontal(RoadGeometry.Mesh mesh, RoadGeometry.V point) {
      return RoadQueries.horizontal(mesh, point).sample().distance();
   }

   public static RoadGeometry.Node node(RoadGeometry.Mesh mesh, double d) {
      RoadGeometry.Sample at = RoadSlicing.at(mesh, d);
      RoadProfile.Layout layout = RoadProfile.layout(mesh, at);
      RoadGeometry.V p = at.at(layout.medianCenter(), 0.0);
      RoadGeometry.Sample a = RoadSlicing.at(mesh, Math.max(0.0, d - 0.01));
      RoadGeometry.Sample b = RoadSlicing.at(mesh, Math.min(mesh.length(), d + 0.01));
      RoadGeometry.V delta = b.center().sub(a.center());
      return new RoadGeometry.Node(p, RoadPlanner.yaw(at.left().left().mul(-1.0)), delta.y() / Math.max(1.0E-9, delta.horizontalLength()));
   }

   public static List<RoadSplitGeometry.Range> protection(RoadRecord road) {
      RoadGeometry.Mesh mesh = road.mesh();
      RoadGeometry.Mesh raw = road.rawMesh();
      double length = mesh.length();
      ArrayList<RoadSplitGeometry.Range> ranges = new ArrayList<>();
      if (RoadInfrastructure.customBridge(mesh)
         && Set.of(RoadInfrastructure.Bridge.ARCH, RoadInfrastructure.Bridge.CABLE, RoadInfrastructure.Bridge.SUSPENSION)
            .contains(road.settings().options().infrastructure().bridge())) {
         ranges.add(new RoadSplitGeometry.Range(0.0, length, "此桥型属于需要整体维护的桥跨"));
      }

      ranges.add(new RoadSplitGeometry.Range(0.0, 2.0, "距原起点过近"));
      ranges.add(new RoadSplitGeometry.Range(length - 2.0, length, "距原终点过近"));
      RoadTransitions.Ends e = road.settings().options().ends();
      double span = RoadTransitions.span(road.settings(), length + e.trimmedStart() + e.trimmedEnd());
      if (e.start() != null && !e.start().equals(RoadTransitions.Section.of(road.settings())) || road.settings().startWidth() != road.settings().width()) {
         ranges.add(new RoadSplitGeometry.Range(0.0, Math.max(0.0, span - e.trimmedStart()), "这里是起端断面渐变段"));
      }

      if (e.end() != null && !e.end().equals(RoadTransitions.Section.of(road.settings())) || road.settings().endWidth() != road.settings().width()) {
         ranges.add(new RoadSplitGeometry.Range(Math.min(length, length - span + e.trimmedEnd()), length, "这里是末端断面渐变段"));
      }

      LanePoints.Data lanes = road.settings().options().lanePoints();

      for (LaneSections.Cut c : lanes.cuts()) {
         if (c.temporary()) {
            protectedRange(ranges, c.begin(), c.end(), length, "这里是车道分离、封闭或恢复区间");
         } else {
            double cutSpan = Math.min(c.transition(), Math.abs(c.end() - c.begin()) / 2.0);
            protectedRange(ranges, c.begin(), c.begin() + (double)c.sign() * cutSpan, length, "这里是车道分离渐变段");
            protectedRange(ranges, c.end() - (double)c.sign() * cutSpan, c.end(), length, "这里是车道恢复渐变段");
         }
      }

      for (LaneAdditions.Addition a : lanes.additions()) {
         ranges.add(new RoadSplitGeometry.Range(a.station() - (double)a.sign() * a.transition(), a.station(), "这里是汇入增车道渐变段"));
      }

      for (LanePoints.Point p : lanes.points()) {
         if (p.mergeLength() != 0.0) {
            double d = horizontal(raw, p.anchor() == null ? p.position() : p.anchor());
            int sign = LanePoints.lane(raw, p).sign();
            ranges.add(new RoadSplitGeometry.Range(d, d + (double)sign * Math.abs(p.mergeLength()), "这里是车道点合流渐变段"));
         }
      }

      for (LanePoints.Opening opening : lanes.openings()) {
         double lo = Double.POSITIVE_INFINITY;
         double hi = -lo;

         for (RoadGeometry.V px : opening.centerline()) {
            double d = horizontal(mesh, px);
            lo = Math.min(lo, d - opening.halfWidth() - 0.4);
            hi = Math.max(hi, d + opening.halfWidth() + 0.4);
         }

         if (Double.isFinite(lo)) {
            ranges.add(new RoadSplitGeometry.Range(lo, hi, "这里是匝道开口及护栏衔接范围"));
         }
      }

      List<RoadAttachments.Point> controls = road.settings()
         .options()
         .attachments()
         .points()
         .stream()
         .filter(RoadAttachments.Point::controlled)
         .sorted(Comparator.comparingDouble(px -> horizontal(raw, px.anchor())))
         .toList();

      for (int i = 0; i < controls.size(); i++) {
         ranges.add(
            new RoadSplitGeometry.Range(
               i == 0 ? 0.0 : horizontal(raw, controls.get(i - 1).anchor()),
               i + 1 == controls.size() ? length : horizontal(raw, controls.get(i + 1).anchor()),
               "这里是附属控制点调形的实际作用区间"
            )
         );
      }

      for (RoadsideBypass.Span cx : road.settings().options().streetscape().crossings()) {
         ranges.add(new RoadSplitGeometry.Range(cx.start(), cx.end(), "这里是慢行通道的立体转换范围"));
      }

      for (int slot : RoadGantry.slots(mesh)) {
         List<RoadStructures.Part> members = RoadGantry.members(mesh, slot, road.structures());
         if (!members.isEmpty()) {
            double lo = Double.POSITIVE_INFINITY;
            double hi = -lo;

            for (RoadStructures.Part px : members) {
               for (RoadGeometry.V v : px.base()) {
                  double d = horizontal(mesh, v);
                  lo = Math.min(lo, d);
                  hi = Math.max(hi, d);
               }
            }

            ranges.add(new RoadSplitGeometry.Range(lo, hi, "这里穿过整座龙门架，请在架体外分割"));
         }
      }

      HashSet<RoadStructures.Part> supportMembers = new HashSet<>(supports(mesh, road.structures()));

      for (RoadStructures.Part px : road.structures()) {
         if (!linear(px) || supportMembers.contains(px)) {
            double lo = Double.POSITIVE_INFINITY;
            double hi = -lo;

            for (RoadGeometry.V v : px.base()) {
               double d = horizontal(mesh, v);
               lo = Math.min(lo, d);
               hi = Math.max(hi, d);
            }

            ranges.add(new RoadSplitGeometry.Range(lo, hi, "这里穿过桥柱、灯具或不可拆整体构件"));
         }
      }

      return List.copyOf(ranges);
   }

   private static void protectedRange(List<RoadSplitGeometry.Range> ranges, double a, double b, double length, String reason) {
      double from = Math.max(0.0, Math.min(a, b));
      double to = Math.min(length, Math.max(a, b));
      if (from <= to) {
         ranges.add(new RoadSplitGeometry.Range(from, to, reason));
      }
   }

   public static RoadSplitGeometry.Pair split(RoadRecord road, double d, RoadSlicing.Connection mode, UUID second) {
      if (road.junction() != null || road.toll() != null || road.settings().style().ramp() || road.settings().options().lanePoints().link() != null) {
         throw new IllegalArgumentException("匝道本体、路口及收费站不能直接分割");
      } else if (road.mesh().closed()) {
         throw new IllegalArgumentException("闭合道路暂不支持分割");
      } else if (Double.isFinite(d) && !(d <= 0.0) && !(d >= road.mesh().length())) {
         List<RoadSplitGeometry.Range> protection = protection(road);
         requireClear(protection, d, d);
         RoadGeometry.Node node = node(road.mesh(), d);
         BlockPos pos = BlockPos.containing(node.position().x(), node.position().y(), node.position().z());
         if (!pos.equals(road.a()) && !pos.equals(road.b())) {
            return new RoadSplitGeometry.Pair(
               range(road, 0.0, d, road.id(), road.a(), pos, road.start(), node, mode),
               range(road, d, road.mesh().length(), second, pos, road.b(), node, road.end(), mode),
               node,
               pos,
               d,
               protection
            );
         } else {
            throw new IllegalArgumentException("新端点与已有端点方块重叠");
         }
      } else {
         throw new IllegalArgumentException("未命中道路内部");
      }
   }

   public static void requireClear(List<RoadSplitGeometry.Range> ranges, double from, double to) {
      for (RoadSplitGeometry.Range r : ranges) {
         if (r.intersects(from, to)) {
            throw new IllegalArgumentException(r.reason() + String.format(Locale.ROOT, "（%.2f–%.2f 格）", r.from(), r.to()));
         }
      }
   }

   public static RoadRecord range(
      RoadRecord road, double from, double to, UUID id, BlockPos a, BlockPos b, RoadGeometry.Node start, RoadGeometry.Node end, RoadSlicing.Connection mode
   ) {
      return range(road, from, to, id, a, b, start, end, mode, false);
   }

   static RoadRecord junctionRange(
      RoadRecord road, double from, double to, UUID id, BlockPos a, BlockPos b, RoadGeometry.Node start, RoadGeometry.Node end, RoadSlicing.Connection mode
   ) {
      return range(road, from, to, id, a, b, start, end, mode, true);
   }

   private static RoadRecord range(
      RoadRecord road,
      double from,
      double to,
      UUID id,
      BlockPos a,
      BlockPos b,
      RoadGeometry.Node start,
      RoadGeometry.Node end,
      RoadSlicing.Connection mode,
      boolean rebuildFurniture
   ) {
      RoadGeometry.Mesh mesh = road.mesh();
      RoadGeometry.Mesh raw = road.rawMesh();
      double length = to - from;
      RoadGeometry.Settings s = road.settings();
      RoadProfile.Options o = s.options();
      LanePoints.Data lanes = o.lanePoints();
      List<LanePoints.Point> points = lanes.points()
         .stream()
         .filter(px -> owns(horizontal(raw, px.anchor() == null ? px.position() : px.anchor()), from, to, raw.length()))
         .toList();
      List<LaneSections.Cut> cuts = lanes.cuts()
         .stream()
         .filter(c -> Math.max(c.begin(), c.end()) > from && Math.min(c.begin(), c.end()) < to)
         .map(
            c -> new LaneSections.Cut(
                  c.connection(),
                  c.lane(),
                  c.sign(),
                  c.begin() - from,
                  c.end() - from,
                  c.transition(),
                  c.replacement(),
                  c.temporary(),
                  c.arrival(),
                  c.rectangular(),
                  c.underpass()
               )
         )
         .toList();
      List<LaneAdditions.Addition> additions = lanes.additions()
         .stream()
         .filter(
            v -> v.fraction(from) > 0.0
                  || v.fraction(to) > 0.0
                  || Double.isFinite(v.end()) && Math.max(v.station(), v.end()) >= from && Math.min(v.station(), v.end()) <= to
         )
         .map(v -> new LaneAdditions.Addition(v.connection(), v.slot(), v.sign(), v.station() - from, v.transition(), v.end() - from))
         .toList();
      List<LanePoints.Opening> openings = lanes.openings()
         .stream()
         .filter(v -> v.centerline().stream().anyMatch(px -> owns(horizontal(raw, px), from, to, raw.length())))
         .toList();
      ArrayList<ElevatedAccess.Reservation> access = new ArrayList<>();

      for (ElevatedAccess.Reservation r : lanes.access()) {
         double lo = r.cuts().stream().mapToDouble(c -> Math.min(c.begin(), c.end())).min().orElseThrow();
         double hi = r.cuts().stream().mapToDouble(c -> Math.max(c.begin(), c.end())).max().orElseThrow();

         for (LaneAdditions.Addition extra : r.additions()) {
            lo = Math.min(lo, Math.min(extra.station(), extra.end()) - extra.transition());
            hi = Math.max(hi, Math.max(extra.station(), extra.end()) + extra.transition());
         }

         if (!(hi <= from) && !(lo >= to)) {
            if (lo < from - 1.0E-6 || hi > to + 1.0E-6) {
               throw new IllegalArgumentException("分割范围穿过高架出入口，请先删除该连接");
            }

            access.add(ElevatedAccess.shifted(r, -from));
         }
      }

      LanePoints.Data laneData = new LanePoints.Data(
         points,
         lanes.link(),
         openings,
         lanes.priorityDepth(),
         cuts,
         additions,
         lanes.bands()
            .stream()
            .filter(v -> v.station() >= from && v.station() <= to)
            .map(v -> new LanePoints.Band(v.station() - from, v.low(), v.high()))
            .toList(),
         access
      );
      RoadAttachments.Data attached = o.attachments();
      List<RoadAttachments.Point> control = attached.points().stream().filter(px -> owns(horizontal(raw, px.position()), from, to, raw.length())).toList();
      ArrayList<RoadAttachments.Span> spans = new ArrayList<>();

      for (RoadAttachments.Span v : attached.spans()) {
         double x = horizontal(raw, v.start());
         double y = horizontal(raw, v.end());
         double lo = Math.max(from, Math.min(x, y));
         double hi = Math.min(to, Math.max(x, y));
         if (!(hi <= lo)) {
            RoadGeometry.V low = lo == Math.min(x, y) ? (x < y ? v.start() : v.end()) : RoadSlicing.at(raw, lo).center();
            RoadGeometry.V high = hi == Math.max(x, y) ? (x < y ? v.end() : v.start()) : RoadSlicing.at(raw, hi).center();
            spans.add(new RoadAttachments.Span(x < y ? low : high, x < y ? high : low, v.paint()));
         }
      }

      RoadAttachments.Data attachments = new RoadAttachments.Data(control, spans);
      ArrayList<RoadGantry.Edit> edits = new ArrayList<>();

      for (int slot : RoadGantry.slots(mesh)) {
         RoadGantry.Station st = RoadGantry.station(mesh, slot);
         if (owns(st.distance(), from, to, mesh.length())) {
            RoadGantry.Edit e = st.edit();
            edits.add(
               new RoadGantry.Edit(
                  slot,
                  0.0,
                  e.clearance(),
                  e.kind(),
                  e.reverse(),
                  e.independent(),
                  Math.max(0.0, Math.min(1.0, (st.distance() - from) / length)),
                  true,
                  (e.automatic() || o.infrastructure().gantryEdits().stream().noneMatch(vx -> vx.slot() == slot))
                     && RoadGantry.members(mesh, slot, road.structures()).isEmpty()
               )
            );
         }
      }

      RoadStreetscape.Config streetscape = o.streetscape();
      ArrayList<RoadStreetscape.Span> raised = new ArrayList<>();

      for (RoadStreetscape.Span vx : streetscape.raisedSpans()) {
         if (vx.to() > from && vx.from() < to) {
            raised.add(new RoadStreetscape.Span(Math.max(from, vx.from()) - from, Math.min(to, vx.to()) - from));
         }
      }

      List<RoadStreetscape.Span> tunnels = streetscape.tunnels()
         .stream()
         .filter(vxx -> vxx.to() > from && vxx.from() < to)
         .map(vxx -> new RoadStreetscape.Span(Math.max(from, vxx.from()) - from, Math.min(to, vxx.to()) - from))
         .toList();
      List<RoadsideBypass.Span> crossings = streetscape.crossings()
         .stream()
         .filter(vxx -> vxx.end() > from && vxx.start() < to)
         .map(vxx -> new RoadsideBypass.Span(vxx.side(), vxx.start() - from, vxx.from() - from, vxx.to() - from, vxx.end() - from, vxx.delta()))
         .toList();
      s = s.options(
         o.ends(o.ends().trim(from, mesh.length() - to))
            .lanePoints(laneData)
            .attachments(attachments)
            .infrastructure(o.infrastructure().fixedStations(edits))
            .streetscape(streetscape.raisedSpans(raised).sideStructures(streetscape.sides().slice(from, to)).tunnels(tunnels).crossings(crossings))
      );
      RoadSlicing.Slice rawSlice = RoadSlicing.slice(raw, from, to, s);
      RoadSlicing.Slice shapedSlice = RoadSlicing.slice(mesh, from, to, s);
      RoadSlicing.Fragment previous = road.fragment();
      RoadSlicing.Fragment fragment = new RoadSlicing.Fragment(
         previous == null ? road.id() : previous.source(),
         (previous == null ? 0.0 : previous.origin()) + from,
         previous == null ? mesh.length() : previous.total(),
         mode,
         attachments,
         shapedSlice.geometry()
      );
      ArrayList<RoadStructures.Part> parts = new ArrayList<>();

      for (RoadStructures.Part p : rebuildFurniture ? supports(mesh, road.structures()) : road.structures()) {
         RoadStructures.Part clipped = part(mesh, p, from, to);
         if (clipped != null) {
            parts.add(clipped);
         }
      }

      fragment = new RoadSlicing.Fragment(
         fragment.source(), fragment.origin(), fragment.total(), fragment.connection(), fragment.applied(), fragment.geometry(), supports(mesh, parts)
      );
      int caps = (from == 0.0 ? road.endCaps() & 1 : 0) | (to == mesh.length() ? road.endCaps() & 2 : 0);
      return new RoadRecord(
         id,
         road.owner(),
         a,
         b,
         start,
         end,
         s,
         road.automatic(),
         road.clearance(),
         parts,
         caps,
         road.buildVersion(),
         road.assembly(),
         rawSlice.mesh().samples(),
         (road.furniturePhase() == null ? RoadFurniture.Phase.DEFAULT : road.furniturePhase()).shift(from),
         null,
         null,
         fragment
      );
   }

   static List<RoadStructures.Part> supports(RoadGeometry.Mesh mesh, List<RoadStructures.Part> parts) {
      List<RoadStructures.Part> candidates = parts.stream()
         .filter(
            px -> px.material() == RoadStructures.Material.CONCRETE
                  && px.model().isEmpty()
                  && (px.pier() || Math.abs(horizontal(mesh, px.a()) - horizontal(mesh, px.b())) <= Math.max(3.0, px.width() * 1.5))
                  && supportBelowDeck(mesh, px)
         )
         .toList();
      List<RoadStructures.Part> shafts = candidates.stream().filter(px -> px.pier() && px.height() > 1.0).toList();
      ArrayList<RoadStructures.Part> out = new ArrayList<>();

      for (RoadStructures.Part p : candidates) {
         if (shafts.contains(p)) {
            out.add(p);
         } else {
            double[] box = box(p);

            for (RoadStructures.Part shaft : shafts) {
               if (touches(box, box(shaft))) {
                  out.add(p);
                  break;
               }
            }
         }
      }

      return List.copyOf(out);
   }

   private static boolean supportBelowDeck(RoadGeometry.Mesh mesh, RoadStructures.Part part) {
      List<RoadGeometry.V> top = part.top();
      RoadGeometry.V center = new RoadGeometry.V(0.0, 0.0, 0.0);

      for (RoadGeometry.V v : top) {
         center = center.add(v);
      }

      center = center.mul(1.0 / (double)top.size());
      RoadGeometry.Sample at = RoadQueries.horizontal(mesh, center).sample();
      return center.y() <= at.center().y() - mesh.settings().thickness() + 0.03;
   }

   private static double[] box(RoadStructures.Part p) {
      ArrayList<RoadGeometry.V> q = new ArrayList<>(p.base());
      q.addAll(p.top());
      return new double[]{
         q.stream().mapToDouble(RoadGeometry.V::x).min().orElseThrow(),
         q.stream().mapToDouble(RoadGeometry.V::y).min().orElseThrow(),
         q.stream().mapToDouble(RoadGeometry.V::z).min().orElseThrow(),
         q.stream().mapToDouble(RoadGeometry.V::x).max().orElseThrow(),
         q.stream().mapToDouble(RoadGeometry.V::y).max().orElseThrow(),
         q.stream().mapToDouble(RoadGeometry.V::z).max().orElseThrow()
      };
   }

   private static boolean touches(double[] a, double[] b) {
      for (int i = 0; i < 3; i++) {
         if (a[i] > b[i + 3] + 0.05 || b[i] > a[i + 3] + 0.05) {
            return false;
         }
      }

      return true;
   }

   private static boolean owns(double d, double from, double to, double total) {
      return d >= from - 1.0E-7 && (d < to - 1.0E-7 || to >= total - 1.0E-7 && d <= to + 1.0E-7);
   }

   static boolean linear(RoadStructures.Part p) {
      if (p.material().name().contains("LOG")
         || p.material().name().contains("LEAVES")
         || p.material().name().startsWith("FLOWER")
         || p.pier()
         || RoadSignals.signal(p)
         || RoadPoleModel.support(p)
         || TollModels.supports(p)
         || p.model().startsWith("sr:gantry/")
         || p.a().sub(p.b()).horizontalLength() < 1.0E-5) {
         return false;
      } else if (!p.model().isEmpty() && !Set.of("sr:cycle_wall", "sr:cycle_bypass", "sr:planting_soil", "sr:cycle_planter").contains(p.model())) {
         return false;
      } else {
         List<RoadGeometry.V> q = p.base();
         RoadGeometry.V n = cross(q.get(1).sub(q.get(0)), q.get(2).sub(q.get(0)));
         return Math.abs(n.dot(q.get(3).sub(q.get(0)))) > 1.0E-7 * Math.max(1.0, n.distance(new RoadGeometry.V(0.0, 0.0, 0.0))) ? false : p.heights() == null;
      }
   }

   private static RoadGeometry.V cross(RoadGeometry.V a, RoadGeometry.V b) {
      return new RoadGeometry.V(a.y() * b.z() - a.z() * b.y(), a.z() * b.x() - a.x() * b.z(), a.x() * b.y() - a.y() * b.x());
   }

   static RoadStructures.Part part(RoadGeometry.Mesh mesh, RoadStructures.Part p, double from, double to) {
      double lo = Double.POSITIVE_INFINITY;
      double hi = -lo;

      for (RoadGeometry.V v : p.base()) {
         double d = horizontal(mesh, v);
         lo = Math.min(lo, d);
         hi = Math.max(hi, d);
      }

      if (hi < from - 1.0E-7 || lo > to + 1.0E-7) {
         return null;
      } else if (lo >= from - 1.0E-7 && hi <= to + 1.0E-7) {
         double d = horizontal(mesh, p.a().add(p.b()).mul(0.5));
         return owns(d, from, to, mesh.length()) ? p : null;
      } else if (!linear(p)) {
         throw new IllegalArgumentException("切口穿过不可拆的整体构件");
      } else {
         RoadStructures.Part result = p;
         if (from > 0.0) {
            result = clip(p, RoadSlicing.at(mesh, from), true);
         }

         if (result != null && to < mesh.length()) {
            result = clip(result, RoadSlicing.at(mesh, to), false);
         }

         return result;
      }
   }

   private static RoadStructures.Part clip(RoadStructures.Part p, RoadGeometry.Sample cut, boolean after) {
      RoadGeometry.V forward = cut.left().left().mul(after ? -1.0 : 1.0);
      List<RoadGeometry.V> q = p.base();
      double[] d = new double[4];

      for (int i = 0; i < 4; i++) {
         d[i] = q.get(i).sub(cut.center()).dot(forward);
      }

      if (Arrays.stream(d).allMatch(v -> v >= -1.0E-8)) {
         return p;
      } else if (Arrays.stream(d).allMatch(v -> v <= 1.0E-8)) {
         return null;
      } else if (d[0] >= 0.0 == d[1] >= 0.0 && d[2] >= 0.0 == d[3] >= 0.0) {
         RoadGeometry.V left = q.get(0).add(q.get(3).sub(q.get(0)).mul(d[0] / (d[0] - d[3])));
         RoadGeometry.V right = q.get(1).add(q.get(2).sub(q.get(1)).mul(d[1] / (d[1] - d[2])));
         RoadGeometry.V center = left.add(right).mul(0.5);
         RoadGeometry.V frame = left.sub(right).mul(0.5);
         RoadGeometry.V oldA = q.get(0).add(q.get(1)).mul(0.5);
         RoadGeometry.V oldB = q.get(2).add(q.get(3)).mul(0.5);
         return d[0] < 0.0
            ? new RoadStructures.Part(center, oldB, p.width(), p.height(), false, p.material(), frame, q.get(3).sub(q.get(2)).mul(0.5), p.model())
            : new RoadStructures.Part(oldA, center, p.width(), p.height(), false, p.material(), q.get(0).sub(q.get(1)).mul(0.5), frame, p.model());
      } else {
         throw new IllegalArgumentException("该构件无法沿当前横断面无损裁分，请在构件外分割");
      }
   }

   private RoadSplitGeometry() {
   }

   public static record Pair(
      RoadRecord first, RoadRecord second, RoadGeometry.Node node, BlockPos pos, double station, List<RoadSplitGeometry.Range> protection
   ) {
   }

   public static record Range(double from, double to, String reason) {
      public Range(double from, double to, String reason) {
         if (from > to) {
            double t = from;
            from = to;
            to = t;
         }

         this.from = from;
         this.to = to;
         this.reason = reason;
      }

      public boolean intersects(double a, double b) {
         return a < this.to + 1.0E-6 && b > this.from - 1.0E-6;
      }
   }
}

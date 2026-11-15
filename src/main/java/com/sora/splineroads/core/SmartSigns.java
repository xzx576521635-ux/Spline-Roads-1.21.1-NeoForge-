package com.sora.splineroads.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class SmartSigns {
   public static final int VERSION = 7;
   public static final int MAX_TEXT = 80;
   public static final int MAX_DRAFTS = 8;
   public static final int MAX_FIELDS = 96;
   public static final List<SmartSigns.Mode> STYLES = List.of(
      SmartSigns.Mode.EMPTY, SmartSigns.Mode.EXIT_SIGN, SmartSigns.Mode.SPEED_LIMIT, SmartSigns.Mode.IC_JCT, SmartSigns.Mode.LANE_INDICATOR
   );
   public static final List<String> FIELDS = List.of("main", "sub", "route", "road", "roadSub", "exit", "distance");
   public static final List<String> SPEED_FIELDS = List.of("vehicle", "vehicleSub", "maxSpeed", "minSpeed");
   public static final int GREEN = 1870390;
   public static final int WHITE = 16448238;
   public static final int DARK_GREEN = 9239;
   public static final int RED = 14296109;
   public static final int BLUE = 1201840;
   public static final double BACK = 0.1;
   public static final double FACE = 0.33;
   public static final double INK = 0.355;
   public static final double TEXT = 0.332;
   public static final int DIAGRAM_GREEN = 1870390;

   public static boolean freestanding(SmartSigns.Mode mode) {
      return mode == SmartSigns.Mode.ROADSIDE || mode == SmartSigns.Mode.JUNCTION_ADVANCE || mode == SmartSigns.Mode.ROAD_NAME;
   }

   public static String styleLabel(SmartSigns.Mode mode) {
      return switch (mode) {
         case EXIT_AUTO, EXIT_SIGN -> "出口预告龙门架";
         case IC_JCT -> "JCT/IC预告龙门架";
         default -> "空龙门架";
         case SPEED_LIMIT -> "限速龙门架";
         case LANE_INDICATOR -> "车道指示龙门架";
      };
   }

   public static List<Integer> exitLaneMapping(List<Integer> upstream, List<Integer> downstream, Set<Integer> exit, int side) {
      if (!upstream.isEmpty() && !downstream.isEmpty()) {
         LinkedHashSet<Integer> mapped = new LinkedHashSet<>();

         for (int i = 0; i < downstream.size(); i++) {
            if (exit.contains(downstream.get(i))) {
               int rank = side < 0 ? i : downstream.size() - 1 - i;
               int index = side < 0 ? Math.min(rank, upstream.size() - 1) : Math.max(0, upstream.size() - 1 - rank);
               mapped.add(upstream.get(index));
            }
         }

         return List.copyOf(mapped);
      } else {
         return List.of();
      }
   }

   public static List<SmartSigns.Branch> roadsideOrder(SmartSigns.State state) {
      int exitSide = state.branches().stream().filter(b -> !b.id().equals("through")).mapToInt(SmartSigns.Branch::side).findFirst().orElse(1);
      return state.branches()
         .stream()
         .sorted(Comparator.<SmartSigns.Branch>comparingInt(b -> b.id().equals("through") ? -exitSide : b.side()).thenComparing(SmartSigns.Branch::id))
         .toList();
   }

   public static boolean headerMode(SmartSigns.Mode mode) {
      return mode == SmartSigns.Mode.EXIT_AUTO || mode == SmartSigns.Mode.EXIT_SIGN || mode == SmartSigns.Mode.ROADSIDE || mode == SmartSigns.Mode.IC_JCT;
   }

   public static Set<String> fieldIds(List<SmartSigns.Branch> branches) {
      LinkedHashSet<String> out = new LinkedHashSet<>();

      for (SmartSigns.Branch b : branches) {
         for (String f : FIELDS) {
            out.add(b.id() + "/" + f);
         }
      }

      return out;
   }

   public static Set<String> fieldIds(SmartSigns.Mode mode, List<SmartSigns.Branch> branches) {
      if (mode == SmartSigns.Mode.LANE_INDICATOR) {
         return Set.of();
      } else if (mode != SmartSigns.Mode.JUNCTION_ADVANCE && mode != SmartSigns.Mode.ROAD_NAME) {
         if (mode != SmartSigns.Mode.SPEED_LIMIT) {
            return fieldIds(branches);
         } else {
            LinkedHashSet<String> out = new LinkedHashSet<>();

            for (SmartSigns.Branch b : branches) {
               if (!b.id().equals("emergency")) {
                  for (String f : SPEED_FIELDS) {
                     out.add(b.id() + "/" + f);
                  }
               }
            }

            return out;
         }
      } else {
         LinkedHashSet<String> out = new LinkedHashSet<>();

         for (SmartSigns.Branch bx : branches) {
            if (mode != SmartSigns.Mode.JUNCTION_ADVANCE || !bx.path().isEmpty()) {
               for (String f : List.of("main", "sub")) {
                  out.add(bx.id() + "/" + f);
               }
            }
         }

         return out;
      }
   }

   public static String label(String id) {
      if (id.equals("road/main")) {
         return "当前道路名称";
      } else if (id.equals("road/sub")) {
         return "道路名称副文字";
      } else if (id.equals("junction/main")) {
         return "路口预告主文字";
      } else if (id.equals("junction/sub")) {
         return "路口预告副文字";
      } else {
         String var1 = id.substring(id.lastIndexOf(47) + 1);

         return switch (var1) {
            case "vehicle" -> "车型主文字";
            case "vehicleSub" -> "车型副文字";
            case "maxSpeed" -> "最高时速";
            case "minSpeed" -> "最低时速";
            case "main" -> "目的地主文字";
            case "sub" -> "目的地副文字";
            case "route" -> "道路编号";
            case "road" -> "道路名称";
            case "roadSub" -> "道路名称副文字";
            case "exit" -> "出口号／立交名";
            default -> "距离（含单位请手填）";
         };
      }
   }

   public static SmartSigns.Layout layout(RoadGeometry.Mesh mesh, SmartSigns.State state) {
      if (state.mode() != SmartSigns.Mode.JUNCTION_ADVANCE && state.mode() != SmartSigns.Mode.ROAD_NAME) {
         boolean roadside = state.mode() == SmartSigns.Mode.ROADSIDE && state.support() != null;
         RoadGantry.Station st = roadside ? null : RoadGantry.station(mesh, state.key().slot());
         RoadGeometry.V right = roadside ? state.support().right() : st.sample().left().mul((double)state.key().flow());
         RoadGeometry.V front = right.left();
         double frameFront = st != null && st.kind() == RoadInfrastructure.Gantry.FRAME ? 0.5 : 0.225;
         double mount = frameFront + 0.035 - 0.1;
         double mainHeight = state.mode() == SmartSigns.Mode.SPEED_LIMIT ? 2.65 : 3.4;
         RoadGeometry.V origin = roadside
            ? state.support().foot().add(new RoadGeometry.V(0.0, 5.2, 0.0))
            : new RoadGeometry.V(
                  st.sample().center().x(),
                  state.mode() == SmartSigns.Mode.IC_JCT
                     ? st.sample().center().y() + st.edit().clearance()
                     : RoadGantry.upperBeamCenterY(st) - mainHeight / 2.0,
                  st.sample().center().z()
               )
               .add(front.mul(mount));
         ArrayList<SmartSigns.Board> boards = new ArrayList<>();
         ArrayList<SmartSigns.Field> fields = new ArrayList<>();
         ArrayList<RoadSurface.Face> faces = new ArrayList<>();
         if (state.target() != null && state.diagnostic().isEmpty() && !state.branches().isEmpty()) {
            if (state.mode() == SmartSigns.Mode.LANE_INDICATOR) {
               return indicatorLayout(mesh, state, st, right, front, mount);
            } else if (state.mode() == SmartSigns.Mode.SPEED_LIMIT) {
               return speedLayout(mesh, state, st, origin, right, front, frameFront, mount);
            } else {
               List<LanePoints.Lane> live = roadside
                  ? List.of()
                  : LaneSections.live(mesh, st.distance()).lanes().stream().filter(l -> l.sign() == state.key().flow()).toList();
               double min = live.stream().mapToDouble(l -> l.position().sub(st.sample().center()).dot(right) - l.width() / 2.0).min().orElse(0.0);
               double max = live.stream().mapToDouble(l -> l.position().sub(st.sample().center()).dot(right) + l.width() / 2.0).max().orElse(0.0);
               if (!roadside && max - min < 2.8) {
                  return new SmartSigns.Layout(origin, right, front, List.of(), List.of(), List.of());
               } else {
                  boolean ic = state.mode() == SmartSigns.Mode.IC_JCT;
                  double total = max - min - 0.4;
                  double center = (min + max) / 2.0;
                  if (ic) {
                     return diagramLayout(state, st, origin, right, front, carriagewayCenter(mesh, st.sample(), state.key().flow(), center), frameFront, mount);
                  } else {
                     if (roadside) {
                        List<SmartSigns.Branch> ordered = roadsideOrder(state);

                        for (int i = 0; i < ordered.size(); i++) {
                           SmartSigns.Branch branch = ordered.get(i);
                           int side = branch.id().equals("through") ? -state.branches().get(state.branches().size() - 1).side() : branch.side();
                           int count = ordered.size();
                           int cantilever = state.support().cantileverSide();
                           double x = cantilever != 0
                              ? ((double)i - (double)(count - 1) / 2.0) * 4.7 + (double)(cantilever * count) * 2.35
                              : (count == 1 ? (double)side * 2.35 : ((double)i - (double)(count - 1) / 2.0) * 4.7);
                           double width = 4.15;
                           double span = state.support().gantrySpan();
                           if (span != 0.0) {
                              double spacing = Math.min(4.7, Math.abs(span) / (double)count);
                              width = Math.min(width, spacing - 0.25);
                              x = span / 2.0 + ((double)i - (double)(count - 1) / 2.0) * spacing;
                              if (count == 1 && branch.id().equals("elevated")) {
                                 x = Math.min(0.0, span) + width / 2.0 + 0.125;
                              }
                           }

                           boards.add(new SmartSigns.Board(branch.id(), x, width, 3.4));
                           accessoryBoard(boards, state, branch.id(), x, Math.min(3.95, width - 0.15), 3.53);
                        }
                     } else {
                        for (SmartSigns.Branch branch : state.branches()) {
                           List<LanePoints.Lane> lanes = live.stream().filter(l -> branch.lanes().contains(l.index())).toList();
                           if (!lanes.isEmpty()) {
                              double lo = lanes.stream()
                                 .mapToDouble(l -> l.position().sub(st.sample().center()).dot(right) - l.width() / 2.0)
                                 .min()
                                 .orElseThrow();
                              double hi = lanes.stream()
                                 .mapToDouble(l -> l.position().sub(st.sample().center()).dot(right) + l.width() / 2.0)
                                 .max()
                                 .orElseThrow();
                              double width = Math.min(7.8, Math.max(1.2, hi - lo - 0.25));
                              double x = (lo + hi) / 2.0;
                              boards.add(new SmartSigns.Board(branch.id(), x, width, 3.4));
                              accessoryBoard(boards, state, branch.id(), x, Math.min(width - 0.2, 5.4), 3.53);
                           }
                        }
                     }

                     List<SmartSigns.Board> panels = boards;
                     if (roadside
                        && state.branches().size() == 2
                        && state.branches().stream().anyMatch(b -> b.id().equals("elevated"))
                        && state.branches().stream().anyMatch(b -> b.id().equals("surface"))) {
                        List<SmartSigns.Board> columns = boards.stream().filter(b -> !b.branch().endsWith("/header")).toList();
                        double left = columns.stream().mapToDouble(b -> b.x() - b.width() / 2.0).min().orElseThrow();
                        double rightEdge = columns.stream().mapToDouble(b -> b.x() + b.width() / 2.0).max().orElseThrow();
                        panels = new ArrayList<>(boards.stream().filter(b -> b.branch().endsWith("/header")).toList());
                        panels.add(new SmartSigns.Board("elevated-access", (left + rightEdge) / 2.0, rightEdge - left, 3.4));
                     }

                     SmartSigns.Layout transform = new SmartSigns.Layout(origin, right, front, panels, fields, faces);

                     for (SmartSigns.Board board : panels) {
                        shell(faces, transform, board);
                        rounded(
                           faces, transform, board.x() - board.width() / 2.0, board.bottom(), board.x() + board.width() / 2.0, board.top(), 0.1, 0.33, 16448238
                        );
                        rounded(
                           faces,
                           transform,
                           board.x() - board.width() / 2.0 + 0.065,
                           board.bottom() + 0.065,
                           board.x() + board.width() / 2.0 - 0.065,
                           board.top() - 0.065,
                           0.065,
                           0.336,
                           1870390
                        );
                        if (!board.branch().endsWith("/header")) {
                           if (roadside) {
                              sideMount(faces, transform, board);
                           } else {
                              mounting(faces, transform, board, st, frameFront, mount);
                           }
                        }
                     }

                     if (roadside) {
                        if (state.support().gantrySpan() != 0.0) {
                           halfGantry(transform, state.support().gantrySpan()).forEach(p -> faces.addAll(p.faces()));
                        } else {
                           pole(faces, transform, panels);
                        }
                     }

                     for (int i = 0; i < state.branches().size(); i++) {
                        SmartSigns.Branch branchx = state.branches().get(i);
                        SmartSigns.Board boardx = ic ? boards.get(0) : boards.stream().filter(b -> b.branch().equals(branchx.id())).findFirst().orElse(null);
                        if (boardx != null) {
                           boolean elevatedThrough = branchx.id().equals("elevated") && state.branches().size() == 1;
                           double w = boardx.width();
                           double x = boardx.x();
                           double side = !elevatedThrough && branchx.side() != -1 ? 1.0 : -1.0;
                           double arrowWidth = Math.min(1.5, w * 0.3);
                           double textWidth = w - arrowWidth - 0.38;
                           double textX = x - side * (arrowWidth / 2.0 + 0.02);
                           if (state.headerVisible(branchx.id())) {
                              header(faces, fields, transform, branchx.id(), textX, 2.78, textWidth, 0.78);
                           }

                           fields.add(new SmartSigns.Field(branchx.id() + "/main", textX, 1.88, textWidth, 0.78, 2));
                           fields.add(new SmartSigns.Field(branchx.id() + "/sub", textX, 1.11, textWidth, 0.45, 2));
                           SmartSigns.Board top = boards.stream().filter(b -> b.branch().equals(branchx.id() + "/header")).findFirst().orElse(null);
                           if (top != null) {
                              boolean exit = state.accessoryVisible(branchx.id(), "exit");
                              boolean distance = state.accessoryVisible(branchx.id(), "distance");
                              if (exit) {
                                 fields.add(
                                    new SmartSigns.Field(
                                       branchx.id() + "/exit",
                                       top.x() - (distance ? top.width() * 0.145 : 0.0),
                                       3.84,
                                       distance ? top.width() * 0.65 - 0.16 : top.width() - 0.22,
                                       0.4,
                                       2
                                    )
                                 );
                              }

                              if (distance) {
                                 fields.add(
                                    new SmartSigns.Field(
                                       branchx.id() + "/distance",
                                       top.x() + (exit ? top.width() * 0.335 : 0.0),
                                       3.84,
                                       exit ? top.width() * 0.28 - 0.12 : top.width() - 0.22,
                                       0.32,
                                       1
                                    )
                                 );
                              }
                           }

                           double ax = x + side * (w / 2.0 - arrowWidth / 2.0 - 0.12);
                           double ay = 0.73;
                           boolean through = elevatedThrough || branchx.id().equals("through") || branchx.id().equals("surface");
                           if (through) {
                              boldArrow(faces, transform, ax, ay, ax, 2.85, 0.17, 0.355);
                           } else {
                              double reach = arrowWidth * 0.68;
                              boldArrow(faces, transform, ax - side * reach * 0.45, ay, ax + side * reach * 0.45, 2.47, 0.17, 0.355);
                           }

                           for (double bx : new double[]{x - w / 2.0 + 0.16, x + w / 2.0 - 0.16}) {
                              for (double by : new double[]{0.16, 3.24}) {
                                 quad(faces, transform, bx - 0.025, by - 0.025, bx + 0.025, by + 0.025, 0.339, 13622741);
                              }
                           }
                        }
                     }

                     return new SmartSigns.Layout(origin, right, front, List.copyOf(panels), List.copyOf(fields), List.copyOf(faces));
                  }
               }
            }
         } else {
            return new SmartSigns.Layout(origin, right, front, List.of(), List.of(), List.of());
         }
      } else {
         return junctionLayout(state);
      }
   }

   private static void accessoryBoard(List<SmartSigns.Board> boards, SmartSigns.State state, String branch, double x, double width, double bottom) {
      boolean exit = state.accessoryVisible(branch, "exit");
      boolean distance = state.accessoryVisible(branch, "distance");
      if (exit || distance) {
         boards.add(new SmartSigns.Board(branch + "/header", x, exit && distance ? width : Math.max(1.0, width * (exit ? 0.72 : 0.34)), 0.62, bottom));
      }
   }

   private static SmartSigns.Board accessoryBoard(SmartSigns.Layout t, SmartSigns.Board main) {
      return t.boards().stream().filter(b -> b.branch().equals(main.branch() + "/header")).findFirst().orElse(null);
   }

   public static List<SmartSigns.Layout> views(SmartSigns.Layout front, SmartSigns.Mode mode) {
      if (mode != SmartSigns.Mode.ROAD_NAME) {
         return List.of(front);
      } else {
         SmartSigns.Layout back = new SmartSigns.Layout(
            front.origin().add(front.front().mul(0.42200000000000004)),
            front.right().mul(-1.0),
            front.front().mul(-1.0),
            front.boards().stream().map(b -> new SmartSigns.Board(b.branch(), -b.x(), b.width(), b.height(), b.bottom())).toList(),
            front.fields()
               .stream()
               .map(fx -> new SmartSigns.Field(fx.id(), -fx.x(), fx.y(), fx.width(), fx.height(), fx.lines(), fx.color(), fx.literal()))
               .toList(),
            List.of()
         );
         ArrayList<RoadSurface.Face> faces = new ArrayList<>();

         for (RoadSurface.Face f : front.faces()) {
            if (SmartSignFaces.front(f, front)) {
               ArrayList<RoadGeometry.V> points = f.points().stream().map(p -> {
                  RoadGeometry.V d = p.sub(front.origin());
                  return back.at(-d.dot(front.right()), d.y(), d.dot(front.front()));
               }).collect(Collectors.toCollection(ArrayList::new));
               Collections.reverse(points);
               faces.add(new RoadSurface.Face(points, f.color()));
            }
         }

         return List.of(front, new SmartSigns.Layout(back.origin(), back.right(), back.front(), back.boards(), back.fields(), List.copyOf(faces)));
      }
   }

   private static SmartSigns.Layout junctionLayout(SmartSigns.State state) {
      SmartSigns.Support mount = state.support();
      if (mount == null) {
         return new SmartSigns.Layout(
            new RoadGeometry.V(0.0, 0.0, 0.0), new RoadGeometry.V(1.0, 0.0, 0.0), new RoadGeometry.V(0.0, 0.0, 1.0), List.of(), List.of(), List.of()
         );
      } else {
         boolean advance = state.mode() == SmartSigns.Mode.JUNCTION_ADVANCE;
         boolean ring = state.entry().startsWith("roundabout");
         double base = advance ? 5.2 : 2.1;
         double width = advance ? 3.0 : 2.4;
         double height = advance ? 2.0 : 0.65;
         double x = advance
            ? (double)(mount.paired() ^ state.branches().stream().anyMatch(bx -> bx.id().equals("__median__")) ? 1 : -1) * (width / 2.0 + 0.32)
            : 0.0;
         RoadGeometry.V origin = mount.foot().add(new RoadGeometry.V(0.0, base, 0.0));
         List<SmartSigns.Board> boards = List.of(new SmartSigns.Board(advance ? "junction" : "road", x, width, height));
         ArrayList<SmartSigns.Field> fields = new ArrayList<>();
         ArrayList<RoadSurface.Face> faces = new ArrayList<>();
         SmartSigns.Layout t = new SmartSigns.Layout(origin, mount.right(), mount.front(), boards, fields, faces);
         if (state.target() != null && state.diagnostic().isEmpty()) {
            SmartSigns.Board b = boards.get(0);
            shell(faces, t, b);
            rounded(faces, t, x - width / 2.0, 0.0, x + width / 2.0, height, 0.055, 0.33, 16448238);
            rounded(faces, t, x - width / 2.0 + 0.035, 0.035, x + width / 2.0 - 0.035, height - 0.035, 0.035, 0.336, 1656702);
            tube(faces, t, 0.0, -base, 0.0, advance ? 1.55 : 0.045, advance ? 0.095 : 0.065, advance ? -0.03 : 0.21500000000000002, 12437449);
            box(faces, t, -0.15, -base, 0.15, -base + 0.12, advance ? -0.17 : 0.06500000000000003, advance ? 0.13 : 0.365, 8753554);
            if (advance) {
               for (double y : new double[]{0.55, 1.45}) {
                  tube(faces, t, Math.min(0.0, x - width / 2.0 + 0.14), y, Math.max(0.0, x + width / 2.0 - 0.14), y, 0.05, -0.03, 13226192);

                  for (double dx : new double[]{-0.85, 0.85}) {
                     box(faces, t, x + dx - 0.065, y - 0.1, x + dx + 0.065, y + 0.1, -0.08, 0.12000000000000001, 10925234);
                  }
               }
            }

            if (!advance) {
               quad(faces, t, x - width / 2.0 + 0.035, 0.035, x + width / 2.0 - 0.035, 0.19, 0.355, 16448238);
               fields.add(new SmartSigns.Field("road/main", x, (0.19 + height - 0.035) / 2.0, width - 0.16, 0.34, 1));
               fields.add(new SmartSigns.Field("road/sub", x, 0.12, width - 0.16, 0.12, 1, 1382684));
            } else {
               double cy = ring && state.branches().stream().anyMatch(v -> !v.path().isEmpty() && v.path().get(v.path().size() - 1).z() < -0.15) ? 0.82 : 0.52;
               double r = 0.25;
               stroke(faces, t, x, ring ? cy - r - 0.24 : cy - 0.28, x, ring ? cy - r : cy, 0.065, 0.355, 16448238);
               if (ring) {
                  ArrayList<RoadGeometry.V> inner = new ArrayList<>();
                  ArrayList<RoadGeometry.V> outer = new ArrayList<>();

                  for (int i = 0; i < 64; i++) {
                     double a = (Math.PI * 2) * (double)i / 64.0;
                     inner.add(t.at(x + (r - 0.03) * Math.cos(a), cy + (r - 0.03) * Math.sin(a), 0.355));
                     outer.add(t.at(x + (r + 0.03) * Math.cos(a), cy + (r + 0.03) * Math.sin(a), 0.355));
                  }

                  for (int i = 0; i < 64; i++) {
                     int j = (i + 1) % 64;
                     faces.add(new RoadSurface.Face(List.of(inner.get(i), outer.get(i), outer.get(j), inner.get(j)), 16448238));
                  }
               }

               ArrayList<SmartSigns.Branch> ordered = new ArrayList<>(state.branches().stream().filter(v -> !v.path().isEmpty()).toList());
               ordered.sort(Comparator.comparingDouble(v -> v.path().get(v.path().size() - 1).x()));
               int leftCount = (int)ordered.stream().filter(v -> v.side() < 0).count();
               int rightCount = (int)ordered.stream().filter(v -> v.side() > 0).count();
               int li = 0;
               int ri = 0;

               for (SmartSigns.Branch branch : ordered) {
                  RoadGeometry.V d = branch.path().get(branch.path().size() - 1);
                  double dx = d.x();
                  double dy = d.z();
                  double length = Math.hypot(dx, dy);
                  if (!(length < 0.01)) {
                     dx /= length;
                     dy /= length;
                     if (!ring) {
                        if (branch.side() == 0) {
                           dx = 0.0;
                           dy = 1.0;
                        } else {
                           dx = (double)branch.side();
                           dy = 0.0;
                        }
                     }

                     double sx = x + dx * (ring ? r : 0.0);
                     double sy = cy + dy * (ring ? r : 0.0);
                     double reach = Math.min(
                        1.24 / Math.max(0.001, Math.abs(dx)), (dy >= 0.0 ? Math.min(0.83, 1.4 - cy) : cy - 0.12) / Math.max(0.001, Math.abs(dy))
                     );
                     double tx = x + dx * reach;
                     double ty = cy + dy * reach;
                     continuousArrow(faces, t, List.of(new RoadGeometry.V(sx, sy, 0.0), new RoadGeometry.V(tx, ty, 0.0)), 0.065, 0.365);
                     double fx;
                     double fy;
                     double fw;
                     if (branch.side() == 0) {
                        fx = x;
                        fy = 1.75;
                        fw = 1.22;
                     } else {
                        int count = branch.side() < 0 ? leftCount : rightCount;
                        int index = branch.side() < 0 ? li++ : ri++;
                        fx = x + (double)branch.side() * 1.04;
                        fy = count == 1
                           ? (ring && cy > 0.8 && dy > 0.15 ? 0.46 : Math.max(1.05, cy + 0.58))
                           : 1.68 - (double)index * (1.2 / (double)Math.max(1, count - 1));
                        fw = 0.78;
                     }

                     fields.add(new SmartSigns.Field(branch.id() + "/main", fx, fy, fw, 0.19, 1));
                     fields.add(new SmartSigns.Field(branch.id() + "/sub", fx, fy - 0.18, fw, 0.12, 1));
                  }
               }
            }

            return new SmartSigns.Layout(origin, mount.right(), mount.front(), boards, List.copyOf(fields), List.copyOf(faces));
         } else {
            return new SmartSigns.Layout(origin, mount.right(), mount.front(), List.of(), List.of(), List.of());
         }
      }
   }

   public static double carriagewayCenter(RoadGeometry.Mesh mesh, RoadGeometry.Sample at, int flow, double fallback) {
      RoadProfile.Layout profile = RoadProfile.layout(mesh, at);
      if (!profile.catalog().twoWay()) {
         return fallback;
      } else {
         int side = flow * profile.outside();
         double inner = profile.medianEdge(side);
         double outer = profile.outer(side);
         double closure = RoadTransitions.dropBoundary(mesh, at);
         if (Double.isFinite(closure)) {
            outer = (double)side * Math.min(Math.abs(outer), closure);
         }

         return (inner + outer) * 0.5 * (double)flow;
      }
   }

   private static String diagramHeaderField(SmartSigns.State state, String field) {
      return state.branches().stream().filter(b -> b.id().equals("through")).findFirst().orElse(state.branches().get(0)).id() + "/" + field;
   }

   public static SmartSigns.State unifiedHeader(SmartSigns.State state) {
      if (state.mode() == SmartSigns.Mode.IC_JCT && !state.branches().isEmpty()) {
         HashMap<String, SmartSigns.Text> words = new HashMap<>(state.text());

         for (String suffix : List.of("exit", "distance")) {
            String primary = diagramHeaderField(state, suffix);
            if (words.getOrDefault(primary, SmartSigns.Text.EMPTY).value().isEmpty()) {
               for (SmartSigns.Branch branch : state.branches()) {
                  SmartSigns.Text old = words.get(branch.id() + "/" + suffix);
                  if (old != null && !old.value().isEmpty()) {
                     words.put(primary, old);
                     break;
                  }
               }
            }
         }

         return words.equals(state.text())
            ? state
            : new SmartSigns.State(
               state.key(),
               state.mode(),
               state.target(),
               state.diagnostic(),
               state.revision() + 1L,
               state.branches(),
               words,
               state.drafts(),
               state.entry(),
               state.support(),
               state.hiddenHeaders(),
               state.hiddenAccessories(),
               state.closedLanes()
            );
      } else {
         return state;
      }
   }

   private static SmartSigns.Layout diagramLayout(
      SmartSigns.State state,
      RoadGantry.Station st,
      RoadGeometry.V origin,
      RoadGeometry.V right,
      RoadGeometry.V front,
      double center,
      double frameFront,
      double mount
   ) {
      int columns = Math.max(
         1, (int)Math.max(state.branches().stream().filter(bx -> bx.side() < 0).count(), state.branches().stream().filter(bx -> bx.side() > 0).count())
      );
      double width = 4.0 + (double)Math.max(0, columns - 1) * 3.9;
      double scale = width / 4.0;
      ArrayList<SmartSigns.Board> boards = new ArrayList<>();
      boards.add(new SmartSigns.Board("diagram", center, width, 4.0));
      String primary = diagramHeaderField(state, "exit");
      primary = primary.substring(0, primary.lastIndexOf(47));
      boolean exit = state.accessoryVisible(primary, "exit");
      boolean distance = state.accessoryVisible(primary, "distance");
      if (exit || distance) {
         boards.add(new SmartSigns.Board("diagram/header", center, exit && distance ? 3.4 : (exit ? 2.35 : 1.1), 0.62, 4.13));
      }

      ArrayList<RoadSurface.Face> faces = new ArrayList<>();
      ArrayList<SmartSigns.Field> fields = new ArrayList<>();
      SmartSigns.Layout t = new SmartSigns.Layout(origin, right, front, boards, fields, faces);

      for (SmartSigns.Board b : boards) {
         shell(faces, t, b);
         rounded(faces, t, b.x() - b.width() / 2.0, b.bottom(), b.x() + b.width() / 2.0, b.top(), 0.1, 0.33, 16448238);
         rounded(faces, t, b.x() - b.width() / 2.0 + 0.065, b.bottom() + 0.065, b.x() + b.width() / 2.0 - 0.065, b.top() - 0.065, 0.065, 0.336, 1870390);
      }

      mounting(faces, t, boards.get(0), st, frameFront, mount);
      if (exit || distance) {
         double offset = exit && distance ? 0.9 : boards.get(1).width() * 0.29;

         for (double x : new double[]{center - offset, center + offset}) {
            box(faces, t, x - 0.045, 3.8, x + 0.045, 4.65, 0.025, 0.1, 10925234);
         }
      }

      if (exit) {
         fields.add(new SmartSigns.Field(diagramHeaderField(state, "exit"), center - (distance ? 0.46 : 0.0), 4.44, 2.1, 0.4, 2));
      }

      if (distance) {
         fields.add(new SmartSigns.Field(diagramHeaderField(state, "distance"), center + (exit ? 1.12 : 0.0), 4.44, 0.86, 0.32, 1));
      }

      HashMap<Integer, Integer> counts = new HashMap<>();

      for (SmartSigns.Branch branch : state.branches()) {
         boolean through = branch.id().equals("through") || branch.side() == 0;
         if (through) {
            fields.add(new SmartSigns.Field(branch.id() + "/main", center - 0.94, 3.56, 1.65, 0.38, 1));
            fields.add(new SmartSigns.Field(branch.id() + "/sub", center - 0.94, 3.22, 1.65, 0.24, 1));
            if (state.headerVisible(branch.id())) {
               double x = center + 0.94;
               double w = 1.65;
               double y = 3.52;
               double h = 0.58;
               double badge = 0.62;
               double left = x - w / 2.0;
               rounded(faces, t, left, y - h / 2.0, left + badge, y + h / 2.0, 0.035, 0.355, 16448238);
               rounded(faces, t, left + 0.035, y - h / 2.0 + 0.035, left + badge - 0.035, y + h / 2.0 - 0.035, 0.025, 0.359, 1870390);
               fields.add(new SmartSigns.Field(branch.id() + "/route", left + badge / 2.0, y, badge - 0.1, h - 0.12, 1));
               fields.add(new SmartSigns.Field(branch.id() + "/road", x + badge / 2.0, y + 0.12, w - badge - 0.12, 0.28, 1));
               fields.add(new SmartSigns.Field(branch.id() + "/roadSub", x + badge / 2.0, y - 0.18, w - badge - 0.12, 0.18, 1));
            }
         } else {
            int count = (int)state.branches().stream().filter(bx -> bx.side() == branch.side()).count();
            int column = counts.merge(branch.side(), 1, Integer::sum) - 1;
            double x = center + (double)branch.side() * (1.06 + (double)column * 1.95);
            double w = count == 1 ? 1.58 : 1.75;
            if (state.headerVisible(branch.id())) {
               header(faces, fields, t, branch.id(), x, 2.63, w, 0.52, 1870390, 1870390);
            }

            fields.add(new SmartSigns.Field(branch.id() + "/main", x, 2.17, w, 0.34, 1));
            fields.add(new SmartSigns.Field(branch.id() + "/sub", x, 1.85, w, 0.22, 1));
         }

         List<RoadGeometry.V> points = branch.path().stream().map(p -> new RoadGeometry.V(center + p.x() * 1.74 * scale, 0.22 + p.z() * 2.65, 0.0)).toList();
         continuousArrow(faces, t, points, 0.095, 0.375);
      }

      return new SmartSigns.Layout(origin, right, front, boards, List.copyOf(fields), List.copyOf(faces));
   }

   private static void continuousArrow(List<RoadSurface.Face> out, SmartSigns.Layout t, List<RoadGeometry.V> path, double width, double depth) {
      ArrayList<RoadGeometry.V> points = new ArrayList<>();

      for (RoadGeometry.V p : path) {
         if (points.isEmpty() || p.sub(points.get(points.size() - 1)).distance(new RoadGeometry.V(0.0, 0.0, 0.0)) > 1.0E-7) {
            points.add(p);
         }
      }

      if (points.size() >= 2) {
         RoadGeometry.V tip = points.get(points.size() - 1);
         RoadGeometry.V direction = tip.sub(points.get(points.size() - 2));
         double n = direction.distance(new RoadGeometry.V(0.0, 0.0, 0.0));
         direction = direction.mul(1.0 / n);
         double trim = 0.22;

         while (points.size() > 1) {
            int last = points.size() - 1;
            RoadGeometry.V end = points.get(last);
            RoadGeometry.V before = points.get(last - 1);
            double length = end.sub(before).distance(new RoadGeometry.V(0.0, 0.0, 0.0));
            if (!(length <= trim)) {
               points.set(last, end.add(before.sub(end).mul(trim / length)));
               break;
            }

            trim -= length;
            points.remove(last);
         }

         if (points.size() >= 2) {
            ArrayList<RoadGeometry.V> offsets = new ArrayList<>();

            for (int i = 0; i < points.size(); i++) {
               RoadGeometry.V before = points.get(Math.max(0, i - 1));
               RoadGeometry.V at = points.get(i);
               RoadGeometry.V after = points.get(Math.min(points.size() - 1, i + 1));
               RoadGeometry.V a = at.sub(before);
               RoadGeometry.V b = after.sub(at);
               if (a.distance(new RoadGeometry.V(0.0, 0.0, 0.0)) < 1.0E-7) {
                  a = b;
               }

               if (b.distance(new RoadGeometry.V(0.0, 0.0, 0.0)) < 1.0E-7) {
                  b = a;
               }

               a = a.mul(1.0 / a.distance(new RoadGeometry.V(0.0, 0.0, 0.0)));
               b = b.mul(1.0 / b.distance(new RoadGeometry.V(0.0, 0.0, 0.0)));
               RoadGeometry.V na = new RoadGeometry.V(-a.y(), a.x(), 0.0);
               RoadGeometry.V nb = new RoadGeometry.V(-b.y(), b.x(), 0.0);
               RoadGeometry.V sum = na.add(nb);
               double length = sum.distance(new RoadGeometry.V(0.0, 0.0, 0.0));
               RoadGeometry.V normal = length < 1.0E-7 ? nb : sum.mul(1.0 / length);
               double divisor = normal.dot(nb);
               offsets.add(normal.mul(Math.min(width * 1.25, width / 2.0 / Math.max(0.4, divisor))));
            }

            for (int i = 1; i < points.size(); i++) {
               RoadGeometry.V ax = points.get(i - 1);
               RoadGeometry.V bx = points.get(i);
               RoadGeometry.V oa = offsets.get(i - 1);
               RoadGeometry.V ob = offsets.get(i);
               out.add(
                  new RoadSurface.Face(
                     List.of(
                        t.at(ax.x() - oa.x(), ax.y() - oa.y(), depth),
                        t.at(bx.x() - ob.x(), bx.y() - ob.y(), depth),
                        t.at(bx.x() + ob.x(), bx.y() + ob.y(), depth),
                        t.at(ax.x() + oa.x(), ax.y() + oa.y(), depth)
                     ),
                     16448238
                  )
               );
            }
         }

         arrowTip(out, t, tip.x(), tip.y(), direction.x(), direction.y(), 0.29, 0.17, depth + 0.001);
      }
   }

   public static List<RoadSurface.Face> solidFaces(SmartSigns.Layout layout) {
      return SmartSignFaces.compose(layout).stream().filter(f -> !SmartSignFaces.front(f, layout)).toList();
   }

   public static List<RoadSurface.Face> artworkFaces(SmartSigns.Layout layout) {
      return SmartSignFaces.compose(layout).stream().filter(f -> SmartSignFaces.front(f, layout)).toList();
   }

   public static RoadGeometry.V anchor(RoadGeometry.Mesh mesh, SmartSigns.State s) {
      return s.support() != null ? s.support().foot() : RoadGantry.station(mesh, s.key().slot()).sample().center();
   }

   public static boolean valid(RoadGeometry.Mesh mesh, SmartSigns.State s) {
      return freestanding(s.mode()) ? s.support() != null : RoadGantry.exists(mesh, s.key().slot());
   }

   private static SmartSigns.Layout speedLayout(
      RoadGeometry.Mesh mesh,
      SmartSigns.State state,
      RoadGantry.Station st,
      RoadGeometry.V origin,
      RoadGeometry.V right,
      RoadGeometry.V front,
      double frameFront,
      double mount
   ) {
      ArrayList<SmartSigns.Board> boards = new ArrayList<>();
      ArrayList<SmartSigns.Field> fields = new ArrayList<>();
      ArrayList<RoadSurface.Face> faces = new ArrayList<>();
      List<LanePoints.Lane> live = LaneSections.live(mesh, st.distance()).lanes().stream().filter(l -> l.sign() == state.key().flow()).toList();

      for (LanePoints.Lane lane : live) {
         boards.add(
            new SmartSigns.Board(
               "lane:" + lane.index(), lane.position().sub(st.sample().center()).dot(right), Math.min(3.4, Math.max(1.2, lane.width() - 0.22)), 2.65
            )
         );
      }

      RoadProfile.Layout profile = RoadProfile.layout(mesh, st.sample());
      if (profile.shoulderWidth() > 0.25 && !live.isEmpty()) {
         int side = profile.catalog().twoWay() ? (mesh.settings().options().leftTraffic() ? -state.key().flow() : state.key().flow()) : profile.outside();
         double x = (profile.outer(side) + (double)side * profile.shoulderWidth() / 2.0) * (double)state.key().flow();
         boards.add(new SmartSigns.Board("emergency", x, Math.max(1.2, profile.shoulderWidth() - 0.16), 2.65));
      }

      SmartSigns.Layout t = new SmartSigns.Layout(origin, right, front, boards, fields, faces);

      for (SmartSigns.Board b : boards) {
         shell(faces, t, b);
         rounded(faces, t, b.x() - b.width() / 2.0, 0.0, b.x() + b.width() / 2.0, b.height(), 0.1, 0.33, 16448238);
         rounded(faces, t, b.x() - b.width() / 2.0 + 0.06, 0.06, b.x() + b.width() / 2.0 - 0.06, b.height() - 0.06, 0.06, 0.336, 1870390);
         mounting(faces, t, b, st, frameFront, mount);
         double x = b.x();
         double w = b.width();
         if (b.branch().equals("emergency")) {
            fields.add(new SmartSigns.Field("fixed/emergency", x, 2.12, w - 0.24, 0.38, 1, 16448238, "应急车道"));
            fields.add(new SmartSigns.Field("fixed/emergencySub", x, 1.68, w - 0.24, 0.24, 1, 16448238, "Emergency Lane"));
            double r = Math.min(0.44, w * 0.22);
            disc(faces, t, x, 0.84, r, 0.355, 14296109);
            disc(faces, t, x, 0.84, r * 0.77, 0.357, 1201840);
            double a = r * 0.57;
            stroke(faces, t, x - a, 0.84 - a, x + a, 0.84 + a, 0.09, 0.361, 14296109);
            stroke(faces, t, x - a, 0.84 + a, x + a, 0.84 - a, 0.09, 0.361, 14296109);
         } else {
            fields.add(new SmartSigns.Field(b.branch() + "/vehicle", x, 2.12, w - 0.24, 0.4, 1));
            fields.add(new SmartSigns.Field(b.branch() + "/vehicleSub", x, 1.66, w - 0.24, 0.28, 1));
            double r = Math.min(0.47, w * 0.18);
            double dx = w * 0.29;
            disc(faces, t, x - dx, 0.77, r, 0.355, 14296109);
            disc(faces, t, x - dx, 0.77, r * 0.79, 0.357, 16448238);
            disc(faces, t, x + dx, 0.77, r, 0.355, 1201840);
            discCap(faces, t, x + dx, 0.77, r, -0.62, 0.359, 16448238);
            fields.add(new SmartSigns.Field(b.branch() + "/maxSpeed", x - dx, 0.77, r * 1.35, r * 0.9, 1, 1054744));
            fields.add(new SmartSigns.Field(b.branch() + "/minSpeed", x + dx, 0.77, r * 1.35, r * 0.9, 1));
            boldArrow(faces, t, x, 1.04, x, 0.37, Math.min(0.15, w * 0.04), 0.355);
         }
      }

      return new SmartSigns.Layout(origin, right, front, List.copyOf(boards), List.copyOf(fields), List.copyOf(faces));
   }

   private static SmartSigns.Layout indicatorLayout(
      RoadGeometry.Mesh mesh, SmartSigns.State state, RoadGantry.Station st, RoadGeometry.V right, RoadGeometry.V front, double mount
   ) {
      double height = 1.2;
      double drop = 0.2;
      double frameFront = st.kind() == RoadInfrastructure.Gantry.FRAME ? 0.5 : 0.225;
      RoadGeometry.V origin = st.sample()
         .center()
         .add(new RoadGeometry.V(0.0, RoadGantry.lowerBeamY(st) - st.sample().center().y() - drop - height, 0.0))
         .add(front.mul(mount));
      ArrayList<SmartSigns.Board> boards = new ArrayList<>();
      ArrayList<RoadSurface.Face> faces = new ArrayList<>();
      List<LanePoints.Lane> live = LaneSections.live(mesh, st.distance()).lanes().stream().filter(l -> l.sign() == state.key().flow()).toList();

      for (LanePoints.Lane lane : live) {
         boards.add(
            new SmartSigns.Board("lane:" + lane.index(), lane.position().sub(st.sample().center()).dot(right), Math.min(1.3, lane.width() - 0.2), height)
         );
      }

      RoadProfile.Layout profile = RoadProfile.layout(mesh, st.sample());
      if (profile.shoulderWidth() > 0.25 && !live.isEmpty()) {
         int side = profile.catalog().twoWay() ? (mesh.settings().options().leftTraffic() ? -state.key().flow() : state.key().flow()) : profile.outside();
         boards.add(
            new SmartSigns.Board("emergency", (profile.outer(side) + (double)side * profile.shoulderWidth() / 2.0) * (double)state.key().flow(), 1.2, height)
         );
      }

      SmartSigns.Layout layout = new SmartSigns.Layout(origin, right, front, boards, List.of(), faces);

      for (SmartSigns.Board b : boards) {
         shell(faces, layout, b);
         rounded(faces, layout, b.x() - b.width() / 2.0, 0.0, b.x() + b.width() / 2.0, height, 0.055, 0.33, 1186329);

         for (double x : new double[]{b.x() - 0.3, b.x() + 0.3}) {
            box(faces, layout, x - 0.045, height - 0.1, x + 0.045, height + drop + 0.1, frameFront - mount - 0.06, 0.1, 10925234);
         }

         if (b.branch().equals("emergency")) {
            disc(faces, layout, b.x(), height / 2.0, 0.43, 0.355, 14296109);
            disc(faces, layout, b.x(), height / 2.0, 0.32, 0.357, 1186329);
            stroke(faces, layout, b.x() - 0.29, height / 2.0, b.x() + 0.29, height / 2.0, 0.105, 0.359, 14296109);
         } else if (state.closedLanes().contains(b.branch())) {
            stroke(faces, layout, b.x() - 0.32, 0.28, b.x() + 0.32, 0.92, 0.15, 0.355, 14296109);
            stroke(faces, layout, b.x() - 0.32, 0.92, b.x() + 0.32, 0.28, 0.15, 0.355, 14296109);
         } else {
            boldArrowColor(faces, layout, b.x(), 0.99, b.x(), 0.22, 0.16, 0.355, 3538790);
         }
      }

      return new SmartSigns.Layout(origin, right, front, List.copyOf(boards), List.of(), List.copyOf(faces));
   }

   private static void boldArrowColor(
      List<RoadSurface.Face> out, SmartSigns.Layout t, double x0, double y0, double x1, double y1, double width, double z, int color
   ) {
      ArrayList<RoadSurface.Face> temporary = new ArrayList<>();
      boldArrow(temporary, t, x0, y0, x1, y1, width, z);

      for (RoadSurface.Face face : temporary) {
         out.add(new RoadSurface.Face(face.points(), color));
      }
   }

   private static void discCap(List<RoadSurface.Face> out, SmartSigns.Layout t, double x, double y, double r, double fraction, double z, int color) {
      double start = Math.PI - Math.asin(fraction);
      double end = (Math.PI * 2) + Math.asin(fraction);
      ArrayList<RoadGeometry.V> points = new ArrayList<>();

      for (int i = 0; i <= 24; i++) {
         double a = start + (end - start) * (double)i / 24.0;
         points.add(t.at(x + r * Math.cos(a), y + r * Math.sin(a), z));
      }

      out.add(new RoadSurface.Face(points, color));
   }

   private static void disc(List<RoadSurface.Face> out, SmartSigns.Layout t, double x, double y, double r, double z, int color) {
      ArrayList<RoadGeometry.V> points = new ArrayList<>();

      for (int i = 0; i < 48; i++) {
         double a = (Math.PI * 2) * (double)i / 48.0;
         points.add(t.at(x + r * Math.cos(a), y + r * Math.sin(a), z));
      }

      out.add(new RoadSurface.Face(points, color));
   }

   private static void sideMount(List<RoadSurface.Face> out, SmartSigns.Layout t, SmartSigns.Board b) {
      SmartSigns.Board top = accessoryBoard(t, b);
      boolean full = top != null && top.width() >= b.width() * 0.9;

      for (double x : new double[]{b.x() - b.width() * 0.29, b.x() + b.width() * 0.29}) {
         box(out, t, x - 0.06, 0.2, x + 0.06, full ? 4.15 : b.top() - 0.18, -0.12, 0.1, 12175558);

         for (double y : new double[]{1.1, 2.1}) {
            box(out, t, x - 0.12, y - 0.09, x + 0.12, y + 0.09, -0.3, 0.1, 8425871);
         }
      }

      if (top != null && !full) {
         for (double x : new double[]{top.x() - top.width() * 0.29, top.x() + top.width() * 0.29}) {
            box(out, t, x - 0.06, b.top() - 0.18, x + 0.06, top.top(), -0.12, 0.1, 12175558);
         }
      }
   }

   public static List<RoadStructures.Part> mountParts(RoadGeometry.Mesh mesh, SmartSigns.State state) {
      if (freestanding(state.mode()) && state.support() != null) {
         SmartSigns.Layout t = layout(mesh, state);
         if (t.boards().isEmpty()) {
            return List.of();
         } else {
            boolean roadside = state.mode() == SmartSigns.Mode.ROADSIDE;
            boolean advance = state.mode() == SmartSigns.Mode.JUNCTION_ADVANCE;
            double depth = roadside ? -0.08 : (advance ? -0.03 : 0.21500000000000002);
            double height = roadside ? 7.87 : (advance ? 6.85 : 2.145);
            RoadStructures.Material material = RoadStructures.Material.STEEL;
            ArrayList<RoadStructures.Part> out = new ArrayList<>();
            RoadGeometry.V foot = state.support().foot().add(t.front().mul(depth));
            boolean gantry = roadside && state.support().gantrySpan() != 0.0;
            if (gantry) {
               out.addAll(halfGantry(t, state.support().gantrySpan()));
            } else {
               out.add(new RoadStructures.Part(foot, foot, 0.3, height, true, material));
            }

            for (SmartSigns.Board b : t.boards()) {
               out.add(
                  new RoadStructures.Part(
                     t.at(b.x() - b.width() / 2.0, b.bottom(), 0.21500000000000002),
                     t.at(b.x() + b.width() / 2.0, b.bottom(), 0.21500000000000002),
                     0.23,
                     b.height(),
                     false,
                     material
                  )
               );
            }

            if (roadside && !gantry || advance) {
               double lo = Math.min(0.0, t.boards().stream().mapToDouble(bx -> bx.x() - bx.width() / 2.0 + 0.14).min().orElse(0.0));
               double hi = Math.max(0.0, t.boards().stream().mapToDouble(bx -> bx.x() + bx.width() / 2.0 - 0.14).max().orElse(0.0));

               for (double y : roadside ? new double[]{1.1, 2.1} : new double[]{0.55, 1.45}) {
                  out.add(
                     new RoadStructures.Part(
                        t.at(lo, y - 0.085, roadside ? -0.22 : -0.03), t.at(hi, y - 0.085, roadside ? -0.22 : -0.03), 0.17, 0.17, false, material
                     )
                  );
               }
            }

            return List.copyOf(out);
         }
      } else {
         return List.of();
      }
   }

   private static List<RoadStructures.Part> halfGantry(SmartSigns.Layout t, double span) {
      ArrayList<RoadStructures.Part> out = new ArrayList<>();
      RoadStructures.Material steel = RoadStructures.Material.STEEL;

      for (double x : new double[]{0.0, span}) {
         RoadGeometry.V foot = t.at(x, -5.2, -0.22);
         out.add(new RoadStructures.Part(foot, foot, 0.34, 7.47, true, steel));
      }

      for (double y : new double[]{1.0, 2.1}) {
         out.add(new RoadStructures.Part(t.at(0.0, y, -0.22), t.at(span, y, -0.22), 0.26, 0.17, false, steel));
      }

      int bays = (int)Math.ceil(Math.abs(span) / 2.0);

      for (int i = 0; i < bays; i++) {
         RoadGeometry.V a = t.at(span * (double)i / (double)bays, i % 2 == 0 ? 1.16 : 2.1, -0.22);
         RoadGeometry.V b = t.at(span * (double)(i + 1) / (double)bays, i % 2 == 0 ? 2.1 : 1.16, -0.22);
         out.add(new RoadStructures.Part(a, b, 0.12, 0.12, false, steel));
      }

      return out;
   }

   private static void pole(List<RoadSurface.Face> out, SmartSigns.Layout t, List<SmartSigns.Board> boards) {
      tube(out, t, 0.0, -5.2, 0.0, 2.55, 0.12, -0.08, 12109765);
      box(out, t, -0.15, -5.2, 0.15, -5.03, -0.23, 0.07, 7569280);

      for (double x : new double[]{-0.11, 0.11}) {
         for (double z : new double[]{-0.19, 0.03}) {
            box(out, t, x - 0.022, -5.03, x + 0.022, -4.97, z - 0.022, z + 0.022, 13950938);
         }
      }

      double lo = boards.stream().mapToDouble(b -> b.x() - b.width() / 2.0 + 0.25).min().orElse(0.0);
      double hi = boards.stream().mapToDouble(b -> b.x() + b.width() / 2.0 - 0.25).max().orElse(0.0);

      for (double y : new double[]{1.1, 2.1}) {
         tube(out, t, Math.min(0.0, lo), y, Math.max(0.0, hi), y, 0.085, -0.22, 12438473);
      }
   }

   private static void tube(List<RoadSurface.Face> out, SmartSigns.Layout t, double x0, double y0, double x1, double y1, double r, double z, int color) {
      double n = Math.hypot(x1 - x0, y1 - y0);
      double nx = -(y1 - y0) / n;
      double ny = (x1 - x0) / n;
      ArrayList<RoadGeometry.V> a = new ArrayList<>();
      ArrayList<RoadGeometry.V> b = new ArrayList<>();

      for (int i = 0; i < 12; i++) {
         double v = (Math.PI * 2) * (double)i / 12.0;
         a.add(t.at(x0 + nx * r * Math.cos(v), y0 + ny * r * Math.cos(v), z + r * Math.sin(v)));
         b.add(t.at(x1 + nx * r * Math.cos(v), y1 + ny * r * Math.cos(v), z + r * Math.sin(v)));
      }

      for (int i = 0; i < 12; i++) {
         int j = (i + 1) % 12;
         out.add(new RoadSurface.Face(List.of(a.get(i), a.get(j), b.get(j), b.get(i)), color));
      }

      out.add(new RoadSurface.Face(a, color));
      Collections.reverse(b);
      out.add(new RoadSurface.Face(b, color));
   }

   private static void header(
      List<RoadSurface.Face> out, List<SmartSigns.Field> fields, SmartSigns.Layout t, String id, double x, double y, double width, double height
   ) {
      header(out, fields, t, id, x, y, width, height, 1870390, 9239);
   }

   private static void header(
      List<RoadSurface.Face> out,
      List<SmartSigns.Field> fields,
      SmartSigns.Layout t,
      String id,
      double x,
      double y,
      double width,
      double height,
      int badgeColor,
      int roadColor
   ) {
      double badge = Math.min(0.97, width * 0.28);
      double left = x - width / 2.0;
      rounded(out, t, left, y - height / 2.0, x + width / 2.0, y + height / 2.0, 0.035, 0.355, 16448238);
      rounded(out, t, left + 0.045, y - height / 2.0 + 0.045, left + badge, y + height / 2.0 - 0.045, 0.025, 0.359, badgeColor);
      fields.add(new SmartSigns.Field(id + "/route", left + (badge + 0.045) / 2.0, y, badge - 0.1, height - 0.15, 1));
      double tx = (left + badge + x + width / 2.0) / 2.0;
      double tw = width - badge - 0.1;
      fields.add(new SmartSigns.Field(id + "/road", tx, y + height * 0.14, tw, height * 0.45, 1, roadColor));
      fields.add(new SmartSigns.Field(id + "/roadSub", tx, y - height * 0.29, tw, height * 0.25, 1, roadColor));
   }

   private static void mounting(List<RoadSurface.Face> out, SmartSigns.Layout t, SmartSigns.Board b, RoadGantry.Station st, double frameFront, double mount) {
      double beam = RoadGantry.lowerBeamY(st) - t.origin().y();

      for (double x : new double[]{b.x() - b.width() * 0.29, b.x() + b.width() * 0.29}) {
         box(out, t, x - 0.055, 0.2, x + 0.055, b.top() - 0.18, 0.015, 0.1, 10925234);

         for (double y : new double[]{beam, beam + (st.kind() == RoadInfrastructure.Gantry.FRAME ? 0.82 : 0.7)}) {
            if (y < b.top() - 0.15) {
               box(out, t, x - 0.12, y - 0.03, x + 0.12, y + 0.2, frameFront - mount - 0.035, 0.1, 8228235);
            }
         }

         if (!b.branch().equals("diagram") && !b.branch().startsWith("lane:") && !b.branch().equals("emergency")) {
            SmartSigns.Board top = accessoryBoard(t, b);
            if (top != null) {
               double sx = Math.abs(top.width() - Math.min(b.width() - 0.2, 5.4)) < 1.0E-7
                  ? x
                  : Math.max(top.x() - top.width() * 0.4, Math.min(top.x() + top.width() * 0.4, x));
               box(out, t, sx - 0.045, b.top() - 0.18, sx + 0.045, b.top() + 0.7, 0.025, 0.1, 10925234);
            }
         }
      }
   }

   private static List<double[]> outline(double x0, double y0, double x1, double y1, double radius) {
      double r = Math.min(radius, Math.min(x1 - x0, y1 - y0) / 2.0);
      ArrayList<double[]> points = new ArrayList<>();

      for (int corner = 0; corner < 4; corner++) {
         double cx = corner != 0 && corner != 3 ? x0 + r : x1 - r;
         double cy = corner < 2 ? y1 - r : y0 + r;

         for (int step = 0; step <= 3; step++) {
            double a = Math.toRadians((double)(corner * 90 + step * 30));
            points.add(new double[]{cx + Math.cos(a) * r, cy + Math.sin(a) * r});
         }
      }

      return points;
   }

   private static void rounded(List<RoadSurface.Face> out, SmartSigns.Layout t, double x0, double y0, double x1, double y1, double radius, double z, int color) {
      out.add(new RoadSurface.Face(outline(x0, y0, x1, y1, radius).stream().map(p -> t.at(p[0], p[1], z)).toList(), color));
   }

   private static void shell(List<RoadSurface.Face> out, SmartSigns.Layout t, SmartSigns.Board b) {
      List<double[]> rim = outline(b.x() - b.width() / 2.0, b.bottom(), b.x() + b.width() / 2.0, b.top(), 0.1);
      ArrayList<RoadGeometry.V> back = new ArrayList<>();

      for (double[] p : rim) {
         back.add(t.at(p[0], p[1], 0.1));
      }

      Collections.reverse(back);
      out.add(new RoadSurface.Face(back, 8886163));

      for (int i = 0; i < rim.size(); i++) {
         double[] a = rim.get(i);
         double[] z = rim.get((i + 1) % rim.size());
         out.add(new RoadSurface.Face(List.of(t.at(a[0], a[1], 0.1), t.at(z[0], z[1], 0.1), t.at(z[0], z[1], 0.33), t.at(a[0], a[1], 0.33)), 11714746));
      }
   }

   private static void box(List<RoadSurface.Face> out, SmartSigns.Layout t, double x0, double y0, double x1, double y1, double z0, double z1, int color) {
      quad(out, t, x0, y0, x1, y1, z1, color);
      out.add(new RoadSurface.Face(List.of(t.at(x1, y0, z0), t.at(x0, y0, z0), t.at(x0, y1, z0), t.at(x1, y1, z0)), color));

      for (double x : new double[]{x0, x1}) {
         ArrayList<RoadGeometry.V> p = new ArrayList<>(List.of(t.at(x, y0, z0), t.at(x, y0, z1), t.at(x, y1, z1), t.at(x, y1, z0)));
         if (x == x1) {
            Collections.reverse(p);
         }

         out.add(new RoadSurface.Face(p, color));
      }

      for (double y : new double[]{y0, y1}) {
         ArrayList<RoadGeometry.V> p = new ArrayList<>(List.of(t.at(x0, y, z0), t.at(x1, y, z0), t.at(x1, y, z1), t.at(x0, y, z1)));
         if (y == y1) {
            Collections.reverse(p);
         }

         out.add(new RoadSurface.Face(p, color));
      }
   }

   private static void arrowTip(
      List<RoadSurface.Face> out, SmartSigns.Layout t, double x, double y, double dx, double dy, double length, double width, double z
   ) {
      double n = Math.hypot(dx, dy);
      if (!(n < 1.0E-7)) {
         dx /= n;
         dy /= n;
         out.add(
            new RoadSurface.Face(
               List.of(
                  t.at(x, y, z),
                  t.at(x - dx * length - dy * width, y - dy * length + dx * width, z),
                  t.at(x - dx * length + dy * width, y - dy * length - dx * width, z)
               ),
               16448238
            )
         );
      }
   }

   private static void boldArrow(List<RoadSurface.Face> out, SmartSigns.Layout t, double x0, double y0, double x1, double y1, double width, double depth) {
      double n = Math.hypot(x1 - x0, y1 - y0);
      double dx = (x1 - x0) / n;
      double dy = (y1 - y0) / n;
      stroke(out, t, x0, y0, x1 - dx * 0.3, y1 - dy * 0.3, width, depth, 16448238);
      arrowTip(out, t, x1, y1, dx, dy, 0.4, width * 1.65, depth);
   }

   private static void quad(List<RoadSurface.Face> out, SmartSigns.Layout t, double x0, double y0, double x1, double y1, double z, int color) {
      out.add(new RoadSurface.Face(List.of(t.at(x0, y0, z), t.at(x1, y0, z), t.at(x1, y1, z), t.at(x0, y1, z)), color));
   }

   private static void stroke(List<RoadSurface.Face> out, SmartSigns.Layout t, double x0, double y0, double x1, double y1, double width, double z, int color) {
      double n = Math.hypot(x1 - x0, y1 - y0);
      if (!(n < 1.0E-6)) {
         double dx = -(y1 - y0) / n * width / 2.0;
         double dy = (x1 - x0) / n * width / 2.0;
         out.add(
            new RoadSurface.Face(List.of(t.at(x0 - dx, y0 - dy, z), t.at(x1 - dx, y1 - dy, z), t.at(x1 + dx, y1 + dy, z), t.at(x0 + dx, y0 + dy, z)), color)
         );
      }
   }

   private SmartSigns() {
   }

   public static record Board(String branch, double x, double width, double height, double bottom) {
      public Board(String branch, double x, double width, double height) {
         this(branch, x, width, height, 0.0);
      }

      public double top() {
         return this.bottom + this.height;
      }
   }

   public static record Branch(String id, List<Integer> lanes, int side, boolean dedicated, List<RoadGeometry.V> path) {
      public Branch(String id, List<Integer> lanes, int side, boolean dedicated, List<RoadGeometry.V> path) {
         lanes = List.copyOf(lanes);
         path = List.copyOf(path);
         if (id != null && id.length() <= 100 && lanes.size() <= 32 && Math.abs(side) <= 1 && path.size() <= 128) {
            this.id = id;
            this.lanes = lanes;
            this.side = side;
            this.dedicated = dedicated;
            this.path = path;
         } else {
            throw new IllegalArgumentException("无效的分支");
         }
      }
   }

   public static record Field(String id, double x, double y, double width, double height, int lines, int color, String literal) {
      public Field(String id, double x, double y, double width, double height, int lines, int color) {
         this(id, x, y, width, height, lines, color, "");
      }

      public Field(String id, double x, double y, double width, double height, int lines) {
         this(id, x, y, width, height, lines, 16448238);
      }

      public boolean editable() {
         return this.literal.isEmpty();
      }
   }

   public static record Key(UUID road, int slot, int flow) {
      public Key(UUID road, int slot, int flow) {
         if (road != null && slot >= 0 && slot < 512 && Math.abs(flow) == 1) {
            this.road = road;
            this.slot = slot;
            this.flow = flow;
         } else {
            throw new IllegalArgumentException("无效的龙门架标识");
         }
      }

      public UUID id() {
         return UUID.nameUUIDFromBytes((this.road + ":" + this.slot + ":" + this.flow).getBytes(StandardCharsets.UTF_8));
      }
   }

   public static record Layout(
      RoadGeometry.V origin,
      RoadGeometry.V right,
      RoadGeometry.V front,
      List<SmartSigns.Board> boards,
      List<SmartSigns.Field> fields,
      List<RoadSurface.Face> faces
   ) {
      public RoadGeometry.V at(double x, double y, double depth) {
         return this.origin.add(this.right.mul(x)).add(new RoadGeometry.V(0.0, y, 0.0)).add(this.front.mul(depth));
      }

      public double top() {
         return this.boards.stream().mapToDouble(SmartSigns.Board::top).max().orElse(0.0);
      }
   }

   public static enum Mode {
      NORMAL,
      EXIT_AUTO,
      IC_JCT,
      EMPTY,
      SPEED_LIMIT,
      ROADSIDE,
      EXIT_SIGN,
      JUNCTION_ADVANCE,
      ROAD_NAME,
      LANE_INDICATOR;
   }

   public static record State(
      SmartSigns.Key key,
      SmartSigns.Mode mode,
      UUID target,
      String diagnostic,
      long revision,
      List<SmartSigns.Branch> branches,
      Map<String, SmartSigns.Text> text,
      Map<String, Map<String, SmartSigns.Text>> drafts,
      String entry,
      SmartSigns.Support support,
      Set<String> hiddenHeaders,
      Set<String> hiddenAccessories,
      Set<String> closedLanes
   ) {
      public State(
         SmartSigns.Key key,
         SmartSigns.Mode mode,
         UUID target,
         String diagnostic,
         long revision,
         List<SmartSigns.Branch> branches,
         Map<String, SmartSigns.Text> text,
         Map<String, Map<String, SmartSigns.Text>> drafts,
         String entry,
         SmartSigns.Support support,
         Set<String> hiddenHeaders,
         Set<String> hiddenAccessories
      ) {
         this(key, mode, target, diagnostic, revision, branches, text, drafts, entry, support, hiddenHeaders, hiddenAccessories, Set.of());
      }

      public State(
         SmartSigns.Key key,
         SmartSigns.Mode mode,
         UUID target,
         String diagnostic,
         long revision,
         List<SmartSigns.Branch> branches,
         Map<String, SmartSigns.Text> text,
         Map<String, Map<String, SmartSigns.Text>> drafts,
         String entry,
         SmartSigns.Support support,
         Set<String> hiddenHeaders
      ) {
         this(key, mode, target, diagnostic, revision, branches, text, drafts, entry, support, hiddenHeaders, Set.of());
      }

      public State(
         SmartSigns.Key key,
         SmartSigns.Mode mode,
         UUID target,
         String diagnostic,
         long revision,
         List<SmartSigns.Branch> branches,
         Map<String, SmartSigns.Text> text,
         Map<String, Map<String, SmartSigns.Text>> drafts,
         String entry,
         SmartSigns.Support support
      ) {
         this(key, mode, target, diagnostic, revision, branches, text, drafts, entry, support, Set.of());
      }

      public State(
         SmartSigns.Key key,
         SmartSigns.Mode mode,
         UUID target,
         String diagnostic,
         long revision,
         List<SmartSigns.Branch> branches,
         Map<String, SmartSigns.Text> text,
         Map<String, Map<String, SmartSigns.Text>> drafts,
         String entry
      ) {
         this(key, mode, target, diagnostic, revision, branches, text, drafts, entry, null);
      }

      public State(
         SmartSigns.Key key,
         SmartSigns.Mode mode,
         UUID target,
         String diagnostic,
         long revision,
         List<SmartSigns.Branch> branches,
         Map<String, SmartSigns.Text> text,
         Map<String, Map<String, SmartSigns.Text>> drafts
      ) {
         this(key, mode, target, diagnostic, revision, branches, text, drafts, Integer.toString(key.flow()));
      }

      public State(
         SmartSigns.Key key,
         SmartSigns.Mode mode,
         UUID target,
         String diagnostic,
         long revision,
         List<SmartSigns.Branch> branches,
         Map<String, SmartSigns.Text> text,
         Map<String, Map<String, SmartSigns.Text>> drafts,
         String entry,
         SmartSigns.Support support,
         Set<String> hiddenHeaders,
         Set<String> hiddenAccessories,
         Set<String> closedLanes
      ) {
         closedLanes = Set.copyOf(closedLanes);
         if (closedLanes.size() <= 32 && !closedLanes.stream().anyMatch(v -> !v.matches("lane:[0-9]{1,2}"))) {
            hiddenAccessories = Set.copyOf(hiddenAccessories);
            if (hiddenAccessories.size() <= 96
               && !hiddenAccessories.stream().anyMatch(v -> v == null || v.length() > 112 || !v.endsWith("/exit") && !v.endsWith("/distance"))) {
               hiddenHeaders = Set.copyOf(hiddenHeaders);
               if (hiddenHeaders.size() <= 96 && !hiddenHeaders.stream().anyMatch(v -> v == null || v.length() > 100)) {
                  branches = List.copyOf(branches);
                  text = Map.copyOf(text);
                  LinkedHashMap<String, Map<String, SmartSigns.Text>> copy = new LinkedHashMap<>();
                  drafts.forEach((k, v) -> copy.put(k, Map.copyOf((Map<? extends String, ? extends SmartSigns.Text>)v)));
                  drafts = Collections.unmodifiableMap(copy);
                  if (entry != null && entry.length() <= 100 && branches.size() <= 12 && text.size() <= 96 && drafts.size() <= 8) {
                     this.key = key;
                     this.mode = mode;
                     this.target = target;
                     this.diagnostic = diagnostic;
                     this.revision = revision;
                     this.branches = branches;
                     this.text = text;
                     this.drafts = drafts;
                     this.entry = entry;
                     this.support = support;
                     this.hiddenHeaders = hiddenHeaders;
                     this.hiddenAccessories = hiddenAccessories;
                     this.closedLanes = closedLanes;
                  } else {
                     throw new IllegalArgumentException("标牌内容超过上限");
                  }
               } else {
                  throw new IllegalArgumentException("道路标识区状态无效");
               }
            } else {
               throw new IllegalArgumentException("附属牌显隐状态无效");
            }
         } else {
            throw new IllegalArgumentException("车道指示状态无效");
         }
      }

      public static SmartSigns.State empty(SmartSigns.Key k) {
         return new SmartSigns.State(k, SmartSigns.Mode.NORMAL, null, "", 0L, List.of(), Map.of(), Map.of());
      }

      public String targetKey() {
         return this.target == null ? "ordinary" : this.target + ":" + this.entry;
      }

      public SmartSigns.State layout(SmartSigns.Mode next, UUID goal, List<SmartSigns.Branch> bs, String reason) {
         return this.layout(next, goal, bs, reason, Integer.toString(this.key.flow()));
      }

      public SmartSigns.State layout(SmartSigns.Mode next, UUID goal, List<SmartSigns.Branch> bs, String reason, String approach) {
         if (this.mode == next
            && Objects.equals(this.target, goal)
            && this.entry.equals(approach)
            && this.branches.equals(bs)
            && this.diagnostic.equals(reason)) {
            return this;
         } else {
            LinkedHashMap<String, Map<String, SmartSigns.Text>> history = new LinkedHashMap<>(this.drafts);
            HashMap<String, SmartSigns.Text> words = new HashMap<>(this.text);
            if (!Objects.equals(this.target, goal) || !this.entry.equals(approach)) {
               if (!words.isEmpty()) {
                  history.put(this.targetKey(), Map.copyOf(words));
               }

               words.clear();
            }

            Set<String> valid = SmartSigns.fieldIds(next, bs);
            HashMap<String, SmartSigns.Text> lost = new HashMap<>();
            words.entrySet().removeIf(e -> {
               if (!valid.contains(e.getKey())) {
                  lost.put(e.getKey(), e.getValue());
                  return true;
               } else {
                  return false;
               }
            });
            if (!lost.isEmpty()) {
               HashMap<String, SmartSigns.Text> archived = new HashMap<>(history.getOrDefault(this.targetKey(), Map.of()));
               archived.putAll(lost);
               history.put(this.targetKey(), archived);
            }

            while (history.size() > 8) {
               history.remove(history.keySet().iterator().next());
            }

            return new SmartSigns.State(
               this.key,
               next,
               goal,
               reason,
               this.revision + 1L,
               bs,
               words,
               history,
               approach,
               SmartSigns.freestanding(next) ? this.support : null,
               this.hiddenHeaders,
               this.hiddenAccessories,
               this.closedLanes
            );
         }
      }

      public SmartSigns.State supported(SmartSigns.Support value) {
         return Objects.equals(this.support, value)
            ? this
            : new SmartSigns.State(
               this.key,
               this.mode,
               this.target,
               this.diagnostic,
               this.revision + 1L,
               this.branches,
               this.text,
               this.drafts,
               this.entry,
               value,
               this.hiddenHeaders,
               this.hiddenAccessories,
               this.closedLanes
            );
      }

      public SmartSigns.State edit(String field, SmartSigns.Text value) {
         if (!SmartSigns.fieldIds(this.mode, this.branches).contains(field)) {
            throw new IllegalArgumentException("该文字区域已失效，请重新选择");
         } else {
            HashMap<String, SmartSigns.Text> words = new HashMap<>(this.text);
            if (value.value().isEmpty()) {
               words.remove(field);
            } else {
               words.put(field, value);
            }

            return new SmartSigns.State(
               this.key,
               this.mode,
               this.target,
               this.diagnostic,
               this.revision + 1L,
               this.branches,
               words,
               this.drafts,
               this.entry,
               this.support,
               this.hiddenHeaders,
               this.hiddenAccessories,
               this.closedLanes
            );
         }
      }

      public boolean headerVisible(String branch) {
         return !this.hiddenHeaders.contains(branch);
      }

      public SmartSigns.State header(String branch, boolean visible) {
         if (SmartSigns.headerMode(this.mode) && !this.branches.stream().noneMatch(b -> b.id().equals(branch))) {
            HashSet<String> hidden = new HashSet<>(this.hiddenHeaders);
            if (visible) {
               hidden.remove(branch);
            } else {
               hidden.add(branch);
            }

            return hidden.equals(this.hiddenHeaders)
               ? this
               : new SmartSigns.State(
                  this.key,
                  this.mode,
                  this.target,
                  this.diagnostic,
                  this.revision + 1L,
                  this.branches,
                  this.text,
                  this.drafts,
                  this.entry,
                  this.support,
                  hidden,
                  this.hiddenAccessories,
                  this.closedLanes
               );
         } else {
            throw new IllegalArgumentException("当前分支没有道路编号及名称区");
         }
      }

      private String accessoryKey(String branch, String part) {
         if (!part.equals("exit") && !part.equals("distance")) {
            throw new IllegalArgumentException("无效的附属牌类型");
         } else {
            return this.mode == SmartSigns.Mode.IC_JCT && !this.branches.isEmpty() ? SmartSigns.diagramHeaderField(this, part) : branch + "/" + part;
         }
      }

      public boolean accessoryVisible(String branch, String part) {
         return !this.hiddenAccessories.contains(this.accessoryKey(branch, part));
      }

      public SmartSigns.State accessory(String branch, String part, boolean visible) {
         if (SmartSigns.headerMode(this.mode) && !this.branches.stream().noneMatch(b -> b.id().equals(branch))) {
            String id = this.accessoryKey(branch, part);
            HashSet<String> hidden = new HashSet<>(this.hiddenAccessories);
            if (visible) {
               hidden.remove(id);
            } else {
               hidden.add(id);
            }

            return hidden.equals(this.hiddenAccessories)
               ? this
               : new SmartSigns.State(
                  this.key,
                  this.mode,
                  this.target,
                  this.diagnostic,
                  this.revision + 1L,
                  this.branches,
                  this.text,
                  this.drafts,
                  this.entry,
                  this.support,
                  this.hiddenHeaders,
                  hidden,
                  this.closedLanes
               );
         } else {
            throw new IllegalArgumentException("当前主牌没有出口编号或距离附属牌");
         }
      }

      public SmartSigns.State indicators(Set<String> closed) {
         return this.closedLanes.equals(closed)
            ? this
            : new SmartSigns.State(
               this.key,
               this.mode,
               this.target,
               this.diagnostic,
               this.revision + 1L,
               this.branches,
               this.text,
               this.drafts,
               this.entry,
               this.support,
               this.hiddenHeaders,
               this.hiddenAccessories,
               closed
            );
      }

      public SmartSigns.State indicator(String field, boolean closed) {
         if (this.mode == SmartSigns.Mode.LANE_INDICATOR && field.endsWith("/status")) {
            String lane = field.substring(0, field.length() - 7);
            if (lane.startsWith("lane:") && !this.branches.stream().noneMatch(b -> b.id().equals(lane))) {
               HashSet<String> values = new HashSet<>(this.closedLanes);
               if (closed) {
                  values.add(lane);
               } else {
                  values.remove(lane);
               }

               return this.indicators(values);
            } else {
               throw new IllegalArgumentException("该车道已变化，请重新选择指示牌");
            }
         } else {
            throw new IllegalArgumentException("该车道指示牌已失效，请重新选择");
         }
      }

      public SmartSigns.State rekey(SmartSigns.Key value) {
         return new SmartSigns.State(
            value,
            this.mode,
            this.target,
            this.diagnostic,
            this.revision + 1L,
            this.branches,
            this.text,
            this.drafts,
            this.entry,
            this.support,
            this.hiddenHeaders,
            this.hiddenAccessories,
            this.closedLanes
         );
      }

      public SmartSigns.State restore() {
         Map<String, SmartSigns.Text> saved = this.drafts.get(this.targetKey());
         if (saved == null) {
            throw new IllegalArgumentException("当前目标没有可恢复的草稿");
         } else {
            HashMap<String, SmartSigns.Text> words = new HashMap<>(this.text);
            saved.forEach((k, v) -> {
               if (SmartSigns.fieldIds(this.mode, this.branches).contains(k)) {
                  words.putIfAbsent(k, v);
               }
            });
            return new SmartSigns.State(
               this.key,
               this.mode,
               this.target,
               this.diagnostic,
               this.revision + 1L,
               this.branches,
               words,
               this.drafts,
               this.entry,
               this.support,
               this.hiddenHeaders,
               this.hiddenAccessories,
               this.closedLanes
            );
         }
      }
   }

   public static record Support(RoadGeometry.V foot, RoadGeometry.V right, RoadGeometry.V front, boolean paired, int cantileverSide, double gantrySpan) {
      public Support(RoadGeometry.V foot, RoadGeometry.V right, RoadGeometry.V front, boolean paired, int cantileverSide) {
         this(foot, right, front, paired, cantileverSide, 0.0);
      }

      public Support(RoadGeometry.V foot, RoadGeometry.V right, RoadGeometry.V front, boolean paired) {
         this(foot, right, front, paired, 0);
      }

      public Support(RoadGeometry.V foot, RoadGeometry.V right, RoadGeometry.V front, boolean paired, int cantileverSide, double gantrySpan) {
         if (Double.isFinite(gantrySpan)
            && !(Math.abs(gantrySpan) > 256.0)
            && cantileverSide >= -1
            && cantileverSide <= 1
            && foot != null
            && right != null
            && front != null
            && RoadGeometry.finite(foot.x(), foot.y(), foot.z(), right.x(), right.y(), right.z(), front.x(), front.y(), front.z())
            && !(Math.abs(right.horizontalLength() - 1.0) > 0.001)
            && !(Math.abs(front.horizontalLength() - 1.0) > 0.001)
            && !(Math.abs(right.dot(front)) > 0.001)
            && !(Math.abs(right.y()) > 0.001)
            && !(Math.abs(front.y()) > 0.001)) {
            this.foot = foot;
            this.right = right;
            this.front = front;
            this.paired = paired;
            this.cantileverSide = cantileverSide;
            this.gantrySpan = gantrySpan;
         } else {
            throw new IllegalArgumentException("无效的路杆安装位置");
         }
      }
   }

   public static record Text(String value, int size, boolean wrap) {
      public static final SmartSigns.Text EMPTY = new SmartSigns.Text("", 1, true);

      public Text(String value, int size, boolean wrap) {
         if (value != null
            && value.codePointCount(0, value.length()) <= 80
            && !value.codePoints().anyMatch(c -> Character.isISOControl(c) && c != 10 || c == 167)
            && size >= 0
            && size <= 2) {
            this.value = value;
            this.size = size;
            this.wrap = wrap;
         } else {
            throw new IllegalArgumentException("文字最多 80 字，除换行外不支持控制符；字号 0–2");
         }
      }
   }
}

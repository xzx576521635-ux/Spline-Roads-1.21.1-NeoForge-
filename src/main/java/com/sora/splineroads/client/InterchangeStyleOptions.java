package com.sora.splineroads.client;

import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadInfrastructure;
import com.sora.splineroads.core.RoadLanes;
import com.sora.splineroads.core.RoadPlantings;
import com.sora.splineroads.core.RoadProfile;
import com.sora.splineroads.core.RoadSidewalks;
import com.sora.splineroads.core.RoadStreetscape;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;

final class InterchangeStyleOptions {
   static final String[] TITLES = new String[]{"基本选项", "人行道", "桥梁 / 跨线桥", "地下通道", "龙门架 / 隔音板", "道路 / 人行道路灯", "绿化 / 包边", "隔离带植树"};
   static final List<RoadInfrastructure.Bridge> BRIDGES = List.of(
      RoadInfrastructure.Bridge.STANDARD, RoadInfrastructure.Bridge.BEAM, RoadInfrastructure.Bridge.OVERPASS, RoadInfrastructure.Bridge.VIADUCT
   );
   private final InterchangeScreen screen;
   private final int x;
   private final int y;
   private final int w;
   private final int row;

   private InterchangeStyleOptions(InterchangeScreen screen, int x, int y, int w, int row) {
      this.screen = screen;
      this.x = x;
      this.y = y;
      this.w = w;
      this.row = row;
   }

   private RoadGeometry.Settings settings() {
      return this.screen.mainSettings();
   }

   private void options(UnaryOperator<RoadProfile.Options> edit) {
      this.screen.changeExtras(edit.apply(this.settings().options()));
   }

   private void infrastructure(UnaryOperator<RoadInfrastructure.Config> edit) {
      this.options(o -> o.infrastructure(edit.apply(o.infrastructure())));
   }

   private void street(UnaryOperator<RoadStreetscape.Config> edit) {
      this.options(o -> o.streetscape(edit.apply(o.streetscape())));
   }

   private void walk(UnaryOperator<RoadSidewalks.Config> edit) {
      this.options(o -> o.sidewalk(edit.apply(o.sidewalk())));
   }

   private void button(String text, int slot, Runnable action, boolean active) {
      this.screen.styleButton(text, this.x, this.y + slot * this.row, this.w, action).active = active;
   }

   private void field(String label, String key, double value, int slot) {
      this.screen.styleField(label, key, value, this.x, this.y + slot * this.row, this.w);
   }

   static void build(InterchangeScreen screen, int page, int x, int y, int w, int available) {
      new InterchangeStyleOptions(screen, x, y, w, Math.min(27, Math.max(23, available / 6))).build(page);
   }

   private void build(int page) {
      RoadGeometry.Settings s = this.settings();
      RoadProfile.Options o = s.options();
      RoadProfile.Catalog c = RoadProfile.catalog(s);
      RoadInfrastructure.Config infra = o.infrastructure();
      RoadStreetscape.Config street = o.streetscape();
      RoadSidewalks.Config walk = o.sidewalk();
      boolean ordinary = c.type() == RoadProfile.Type.ORDINARY;
      boolean walkAllowed = ordinary && s.structure() != RoadGeometry.Structure.TUNNEL;
      switch (page) {
         case 0:
            this.button(this.screen.leftTraffic() ? "☑ 靠左行驶" : "☐ 靠左行驶（默认靠右）", 0, this.screen::toggleTraffic, true);
            this.button(
               RoadProfile.medianName(c.median()) + " ▸",
               1,
               () -> {
                  RoadGeometry.Settings now = this.settings();
                  RoadProfile.Catalog profile = RoadProfile.catalog(now);
                  List<RoadProfile.Median> choices = profile.type() == RoadProfile.Type.HIGHWAY
                     ? List.of(RoadProfile.Median.RAIL, RoadProfile.Median.GREEN)
                     : List.of(RoadProfile.Median.DOUBLE_YELLOW, RoadProfile.Median.DASHED_YELLOW, RoadProfile.Median.RAIL, RoadProfile.Median.GREEN);
                  this.screen
                     .changeStyle(
                        RoadLanes.carrier(profile.type(), RoadLanes.counts(now), choices.get((choices.indexOf(profile.median()) + 1) % choices.size()))
                     );
               },
               c.twoWay()
            );
            this.button(
               o.cycleFinish().label + " ▸",
               2,
               () -> this.options(
                     v -> v.cycleFinish(
                           RoadProfile.Options.CycleFinish.values()[(v.cycleFinish().ordinal() + 1)
                              % (
                                 s.structure() == RoadGeometry.Structure.TUNNEL
                                    ? RoadProfile.Options.CycleFinish.values().length - 1
                                    : RoadProfile.Options.CycleFinish.values().length
                              )]
                        )
                  ),
               ordinary
            );
            this.button(
               "非机动车分隔：" + street.separator().label + " ▸",
               3,
               () -> this.street(v -> v.separator(RoadStreetscape.Separator.values()[(v.separator().ordinal() + 1) % 3])),
               ordinary && o.cycle() && !street.parking() && s.structure() != RoadGeometry.Structure.TUNNEL
            );
            this.button((o.curb() ? "☑" : "☐") + " 最外侧马路牙子", 4, () -> this.options(v -> v.extras(v.cycle(), v.cycleRail(), !v.curb())), ordinary);
            this.button("高度与线形：由立交布局控制", 5, () -> {
            }, false);
            break;
         case 1:
            this.button((walk.enabled() ? "☑" : "☐") + " 智能人行道", 0, () -> this.walk(v -> v.enabled(!v.enabled())), walkAllowed);
            this.button(
               "铺设位置：" + walk.side().label + " ▸", 1, () -> this.walk(v -> v.side(RoadSidewalks.Side.values()[(v.side().ordinal() + 1) % 3])), walkAllowed
            );
            this.screen.styleButton("−", this.x, this.y + 2 * this.row, 26, () -> this.walk(v -> v.width(Math.max(1, v.width() - 1)))).active = walkAllowed
               && walk.width() > 1;
            this.screen.styleButton("宽度 " + walk.width() + " 格", this.x + 30, this.y + 2 * this.row, this.w - 60, () -> {
            }).active = false;
            this.screen.styleButton("+", this.x + this.w - 26, this.y + 2 * this.row, 26, () -> this.walk(v -> v.width(Math.min(15, v.width() + 1)))).active = walkAllowed
               && walk.width() < 15;
            this.button(
               "材质：" + RoadSidewalks.Finish.of(walk.material()).label + " ▸",
               3,
               () -> this.walk(
                     v -> v.material(
                           RoadSidewalks.Finish.values()[(RoadSidewalks.Finish.of(v.material()).ordinal() + 1) % RoadSidewalks.Finish.values().length].id
                        )
                  ),
               walkAllowed
            );
            this.button(
               "盲道：" + (walk.tactile() ? "开启" : "关闭"), 4, () -> this.walk(v -> v.tactile(!v.tactile())), walkAllowed && walk.enabled() && walk.width() >= 2
            );
            break;
         case 2:
            this.button(
               s.structure() == RoadGeometry.Structure.BRIDGE ? "☑ 启用桥梁" : "启用桥梁",
               0,
               () -> this.screen
                     .mainSettings(
                        this.settings().structure(s.structure() == RoadGeometry.Structure.BRIDGE ? RoadGeometry.Structure.AUTO : RoadGeometry.Structure.BRIDGE)
                     ),
               true
            );
            this.button("桥型：" + infra.bridge().label + " ▸", 1, () -> {
               this.infrastructure(v -> v.bridge(BRIDGES.get((BRIDGES.indexOf(v.bridge()) + 1) % BRIDGES.size())));
               this.screen.mainSettings(this.settings().structure(RoadGeometry.Structure.BRIDGE));
            }, true);
            this.field("桥墩跨度 / 格", "bridgeSpan", infra.span(), 2);
            this.button("主路高度：由端点调整页控制", 4, () -> {
            }, false);
            break;
         case 3:
            this.button(
               s.structure() == RoadGeometry.Structure.TUNNEL ? "☑ 启用隧道" : "启用隧道",
               0,
               () -> this.screen
                     .mainSettings(
                        this.settings().structure(s.structure() == RoadGeometry.Structure.TUNNEL ? RoadGeometry.Structure.AUTO : RoadGeometry.Structure.TUNNEL)
                     ),
               true
            );
            this.button(infra.tunnel().label + " ▸", 1, () -> {
               this.infrastructure(v -> v.tunnel(RoadInfrastructure.Tunnel.values()[(v.tunnel().ordinal() + 1) % 2]));
               this.screen.mainSettings(this.settings().structure(RoadGeometry.Structure.TUNNEL));
            }, true);
            this.field("边缘净高 / 格", "tunnelHeight", infra.headroom(), 2);
            this.button("主路高度：由端点调整页控制", 4, () -> {
            }, false);
            break;
         case 4:
            this.button(
               "龙门架：" + (infra.gantry() == RoadInfrastructure.Gantry.OFF ? "关闭" : "开启"),
               0,
               () -> this.infrastructure(
                     v -> v.gantry(v.gantry() == RoadInfrastructure.Gantry.OFF ? RoadInfrastructure.Gantry.FRAME : RoadInfrastructure.Gantry.OFF)
                  ),
               true
            );
            this.field("布设间距 / 格", "gantrySpacing", infra.spacing(), 1);
            this.button(
               "高架外侧：" + o.outerRail().label + " ▸",
               3,
               () -> this.options(v -> v.outerRail(RoadProfile.OuterRail.values()[(v.outerRail().ordinal() + 1) % RoadProfile.OuterRail.values().length])),
               true
            );
            break;
         case 5:
            this.field("道路路灯间距 / 8–96 格", "lampSpacing", street.lampSpacing(), 0);
            this.button(
               "人行道路灯：" + (street.walkLamps() ? "开启" : "关闭"),
               2,
               () -> this.street(v -> v.walkLamps(!v.walkLamps())),
               RoadStreetscape.walkLampAllowed(o, s.structure(), s.style()) && ordinary
            );
            this.field("人行道灯距 / 8–96 格", "walkLampSpacing", street.walkLampSpacing(), 3);
            break;
         case 6:
            this.button(
               "人行道绿化：" + street.planting().label + " ▸",
               0,
               () -> this.street(v -> v.planting(RoadStreetscape.Planting.values()[(v.planting().ordinal() + 1) % RoadStreetscape.Planting.values().length])),
               walkAllowed && walk.enabled()
            );
            this.field("绿化间距 / 8–64 格", "plantingSpacing", street.plantingSpacing(), 1);
            this.button(
               "道路包边：" + street.apron().label + " ▸", 3, () -> this.street(v -> v.apron(RoadStreetscape.Apron.values()[(v.apron().ordinal() + 1) % 3])), true
            );
            break;
         case 7:
            RoadPlantings plants = street.plantings();
            this.button(
               "中央：" + plants.medianTrees().label + " ▸",
               0,
               () -> this.street(
                     v -> v.plantings(v.plantings().medianTrees(RoadStreetscape.Planting.values()[(v.plantings().medianTrees().ordinal() + 1) % 5]))
                  ),
               c.twoWay() && c.median() == RoadProfile.Median.GREEN && plants.greenWidth() >= 1.5
            );
            this.field("植树间距 / 8–64 格", "medianTreeSpacing", plants.medianSpacing(), 1);
            this.button(
               "非机动车隔离：" + plants.separatorTrees().label + " ▸",
               3,
               () -> this.street(
                     v -> v.plantings(v.plantings().separatorTrees(RoadStreetscape.Planting.values()[(v.plantings().separatorTrees().ordinal() + 1) % 5]))
                  ),
               ordinary && o.cycle() && !street.parking() && street.separator() == RoadStreetscape.Separator.GREEN
            );
            this.field("植树间距 / 8–64 格", "separatorTreeSpacing", plants.separatorSpacing(), 4);
      }
   }

   static RoadProfile.Options capture(RoadProfile.Options o, BiFunction<String, Double, Double> number) {
      RoadInfrastructure.Config i = o.infrastructure();
      i = i.span(number.apply("bridgeSpan", i.span())).headroom(number.apply("tunnelHeight", i.headroom())).spacing(number.apply("gantrySpacing", i.spacing()));
      RoadStreetscape.Config c = o.streetscape();
      RoadPlantings p = c.plantings();
      p = p.railGap(number.apply("railGap", p.railGap()))
         .greenWidth(number.apply("greenWidth", p.greenWidth()))
         .medianSpacing(number.apply("medianTreeSpacing", p.medianSpacing()))
         .separatorSpacing(number.apply("separatorTreeSpacing", p.separatorSpacing()));
      c = c.plantings(p)
         .lampSpacing(number.apply("lampSpacing", c.lampSpacing()))
         .walkLampSpacing(number.apply("walkLampSpacing", c.walkLampSpacing()))
         .plantingSpacing(number.apply("plantingSpacing", c.plantingSpacing()));
      return o.infrastructure(i).streetscape(c);
   }
}

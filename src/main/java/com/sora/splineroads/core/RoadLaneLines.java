package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class RoadLaneLines {
   public static List<RoadLaneLines.Line> lines(RoadGeometry.Mesh m) {
      RoadGeometry.Sample s = RoadStructures.sample(m, m.length() / 2.0);
      RoadProfile.Layout l = RoadProfile.layout(m, s);
      ArrayList<RoadLaneLines.Line> out = new ArrayList<>();

      for (int side : new int[]{-1, 1}) {
         out.add(new RoadLaneLines.Line("edge:" + side, side < 0 ? "左侧边缘线" : "右侧边缘线", (double)side * (s.halfWidth() - 0.2)));
      }

      if (l.catalog().median() == RoadProfile.Median.DOUBLE_YELLOW && l.median() < 0.12) {
         out.add(new RoadLaneLines.Line("center:-1", "中央黄线（左）", l.medianCenter() - 0.14));
         out.add(new RoadLaneLines.Line("center:1", "中央黄线（右）", l.medianCenter() + 0.14));
      } else if (l.catalog().median() == RoadProfile.Median.DASHED_YELLOW) {
         out.add(new RoadLaneLines.Line("center:0", "中央虚线", l.medianCenter()));
      } else if (l.catalog().twoWay()) {
         for (int side : new int[]{-1, 1}) {
            out.add(new RoadLaneLines.Line("median:" + side, side < 0 ? "中央隔离左边线" : "中央隔离右边线", l.medianCenter() + (double)side * (l.median() / 2.0 + 0.12)));
         }
      }

      List<Double> d = l.dividers();

      for (int i = 0; i < d.size(); i++) {
         out.add(new RoadLaneLines.Line("divider:" + i, "车道分界线 " + (i + 1), d.get(i)));
      }

      for (int side : l.outsideSides()) {
         if (l.shoulderWidth() > 0.0 || l.cycleWidth() > 0.0) {
            out.add(new RoadLaneLines.Line("shoulder:" + side, side < 0 ? "左侧路肩/非机动车线" : "右侧路肩/非机动车线", l.outer(side)));
         }
      }

      out.sort(Comparator.comparingDouble(RoadLaneLines.Line::offset));
      return List.copyOf(out);
   }

   public static RoadLaneLines.Edit get(RoadGeometry.Settings s, String key) {
      return s.options()
         .laneLines()
         .stream()
         .filter(e -> e.key().equals(key))
         .findFirst()
         .orElse(new RoadLaneLines.Edit(key, RoadLaneLines.Pattern.DEFAULT, 0.12));
   }

   public static List<RoadLaneLines.Edit> with(List<RoadLaneLines.Edit> old, RoadLaneLines.Edit edit) {
      ArrayList<RoadLaneLines.Edit> out = new ArrayList<>(old);
      out.removeIf(e -> e.key().equals(edit.key()));
      if (edit.pattern() != RoadLaneLines.Pattern.DEFAULT) {
         out.add(edit);
      }

      out.sort(Comparator.comparing(RoadLaneLines.Edit::key));
      return List.copyOf(out);
   }

   private RoadLaneLines() {
   }

   public static record Edit(String key, RoadLaneLines.Pattern pattern, double width) {
      public Edit(String key, RoadLaneLines.Pattern pattern, double width) {
         if (key != null
            && key.matches("(edge|center|median|shoulder|divider):-?[0-9]{1,2}")
            && pattern != null
            && Double.isFinite(width)
            && !(width < 0.06)
            && !(width > 0.4)) {
            this.key = key;
            this.pattern = pattern;
            this.width = width;
         } else {
            throw new IllegalArgumentException("线宽须为 0.06–0.4 格");
         }
      }
   }

   public static record Line(String key, String label, double offset) {
   }

   public static enum Pattern {
      DEFAULT("默认"),
      NONE("隐藏"),
      WHITE_SOLID("白实线"),
      WHITE_DASHED("白虚线"),
      YELLOW_SOLID("黄实线"),
      YELLOW_DASHED("黄虚线"),
      DOUBLE_WHITE("双白实线"),
      DOUBLE_YELLOW("双黄实线"),
      LEFT_SOLID_RIGHT_DASHED("左实右虚（白）"),
      LEFT_DASHED_RIGHT_SOLID("左虚右实（白）"),
      YELLOW_LEFT_SOLID_RIGHT_DASHED("左实右虚（黄）"),
      YELLOW_LEFT_DASHED_RIGHT_SOLID("左虚右实（黄）");

      public final String label;

      private Pattern(String s) {
         this.label = s;
      }

      public boolean yellow() {
         return this == YELLOW_SOLID
            || this == YELLOW_DASHED
            || this == DOUBLE_YELLOW
            || this == YELLOW_LEFT_SOLID_RIGHT_DASHED
            || this == YELLOW_LEFT_DASHED_RIGHT_SOLID;
      }

      public boolean dashed() {
         return this == WHITE_DASHED || this == YELLOW_DASHED;
      }

      public boolean mixed() {
         return this == LEFT_SOLID_RIGHT_DASHED
            || this == LEFT_DASHED_RIGHT_SOLID
            || this == YELLOW_LEFT_SOLID_RIGHT_DASHED
            || this == YELLOW_LEFT_DASHED_RIGHT_SOLID;
      }

      public boolean dashedSide(int side) {
         return this != LEFT_SOLID_RIGHT_DASHED && this != YELLOW_LEFT_SOLID_RIGHT_DASHED
            ? (this == LEFT_DASHED_RIGHT_SOLID || this == YELLOW_LEFT_DASHED_RIGHT_SOLID) && side < 0
            : side > 0;
      }

      public boolean doubled() {
         return this == DOUBLE_WHITE || this == DOUBLE_YELLOW || this.mixed();
      }
   }
}

package com.sora.splineroads.core;

import java.util.List;

public record RailbedProfile(List<RailbedPath.Path> paths, double requestedWidth, RailbedOptions fittings) {
   public static final RailbedProfile NONE = new RailbedProfile(List.of(), 0.0, RailbedOptions.DEFAULT);

   public RailbedProfile(List<RailbedPath.Path> paths, double requestedWidth, RailbedOptions fittings) {
      paths = List.copyOf(paths);
      if (paths.size() <= 8 && Double.isFinite(requestedWidth) && !(requestedWidth < 0.0) && !(requestedWidth > 64.0) && fittings != null) {
         this.paths = paths;
         this.requestedWidth = requestedWidth;
         this.fittings = fittings;
      } else {
         throw new IllegalArgumentException("无效的铁路断面设置");
      }
   }

   public boolean active() {
      return !this.paths.isEmpty();
   }
}

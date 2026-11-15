package com.sora.splineroads.world;

import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadStructures;
import com.sora.splineroads.core.StreetLampClearance;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.LongConsumer;
import java.util.function.Predicate;
import net.minecraft.world.level.ChunkPos;

final class RoadSignSightlines {
   private final RoadIndex index;
   private final Set<UUID> requested;
   private final BiFunction<RoadIndex.Built, List<RoadStructures.Part>, Set<Long>> apply;
   private final LongConsumer repair;
   private final RoadSignSightlines.Source source;
   private final Set<Long> bases = new HashSet<>();
   private final Set<Long> queried = new HashSet<>();
   private final Set<Long> repairs = new LinkedHashSet<>();
   private final Set<UUID> candidates = new LinkedHashSet<>();
   private final Set<UUID> changes = new LinkedHashSet<>();
   private final List<RoadIndex.Built> roads = new ArrayList<>();
   private final List<Predicate<RoadGeometry.V>> prepared = new ArrayList<>();
   private List<Predicate<RoadGeometry.V>> signs = List.of();
   private final Iterator<UUID> requests;
   private Iterator<Long> chunks = Collections.emptyIterator();
   private Iterator<Long> changeChunks = Collections.emptyIterator();
   private Iterator<UUID> neighbors = Collections.emptyIterator();
   private Iterator<UUID> candidateIterator;
   private int phase;
   private int dx = 7;
   private int dz;
   private int baseX;
   private int baseZ;
   private int sourceAt;
   private int roadAt;
   private boolean invalidated;
   private long revision;
   private long steps;
   private long chunkQueries;
   private long signSources;
   private long partVisits;
   private long signTests;
   private long keyBuilds;
   private long commits;
   private long repairCount;
   private StreetLampClearance.Removal scan;

   RoadSignSightlines(RoadIndex index, Set<UUID> requested, BiFunction<RoadIndex.Built, List<RoadStructures.Part>, Set<Long>> apply, LongConsumer repair) {
      this(index, requested, apply, repair, JunctionSignRoads::lampClearance);
   }

   RoadSignSightlines(
      RoadIndex index,
      Set<UUID> requested,
      BiFunction<RoadIndex.Built, List<RoadStructures.Part>, Set<Long>> apply,
      LongConsumer repair,
      RoadSignSightlines.Source source
   ) {
      this.index = index;
      this.requested = Collections.unmodifiableSet(new LinkedHashSet<>(requested));
      this.requests = this.requested.iterator();
      this.apply = apply;
      this.repair = repair;
      this.source = source;
      this.revision = index.contentRevision();
   }

   Set<UUID> requested() {
      return this.requested;
   }

   void changed(UUID id) {
      this.changes.add(id);
   }

   void invalidate() {
      this.invalidated = true;
   }

   boolean invalidated() {
      return this.invalidated;
   }

   boolean done() {
      return this.repairs.isEmpty() && (this.invalidated || this.phase == 4);
   }

   RoadSignSightlines.Cost cost() {
      return new RoadSignSightlines.Cost(
         this.steps,
         this.chunkQueries,
         (long)this.roads.size(),
         this.signSources,
         this.partVisits,
         this.signTests,
         this.keyBuilds,
         this.commits,
         this.repairCount
      );
   }

   int advance(long deadline, int maxSteps) {
      int used = 0;

      while (used < maxSteps && !this.done() && System.nanoTime() < deadline) {
         this.step();
         used++;
         this.steps++;
      }

      return used;
   }

   private void step() {
      if (!this.repairs.isEmpty()) {
         long chunk = this.repairs.iterator().next();
         this.repair.accept(chunk);
         this.repairs.remove(chunk);
         this.repairCount++;
      } else if (this.changeChunks.hasNext()) {
         if (this.queried.contains(this.changeChunks.next())) {
            this.invalidated = true;
         }
      } else if (!this.changes.isEmpty()) {
         UUID id = this.changes.iterator().next();
         this.changes.remove(id);
         if (!this.requested.contains(id) && !this.candidates.contains(id)) {
            RoadIndex.Built road = this.index.roads.get(id);
            if (road != null) {
               this.changeChunks = road.chunks.iterator();
            }

            this.revision = this.index.contentRevision();
         } else {
            this.invalidated = true;
         }
      } else if (this.index.contentRevision() != this.revision) {
         this.invalidated = true;
      } else if (this.phase == 0) {
         if (this.neighbors.hasNext()) {
            this.candidates.add(this.neighbors.next());
         } else if (this.dx <= 6) {
            long key = ChunkPos.asLong(this.baseX + this.dx, this.baseZ + this.dz);
            if (++this.dz > 6) {
               this.dz = -6;
               this.dx++;
            }

            if (this.queried.add(key)) {
               this.chunkQueries++;
               this.neighbors = List.copyOf(this.index.inChunk(key)).iterator();
            }
         } else if (this.chunks.hasNext()) {
            long keyx = this.chunks.next();
            if (this.bases.add(keyx)) {
               ChunkPos at = new ChunkPos(keyx);
               this.baseX = at.x;
               this.baseZ = at.z;
               this.dx = this.dz = -6;
            }
         } else if (this.requests.hasNext()) {
            RoadIndex.Built road = this.index.roads.get(this.requests.next());
            if (road != null) {
               this.chunks = road.chunks.iterator();
            }
         } else {
            this.candidateIterator = this.candidates.iterator();
            this.phase = 1;
         }
      } else if (this.phase == 1) {
         if (this.candidateIterator.hasNext()) {
            RoadIndex.Built road = this.index.roads.get(this.candidateIterator.next());
            if (road != null) {
               this.roads.add(road);
            }
         } else {
            this.phase = 2;
         }
      } else if (this.phase == 2) {
         if (this.sourceAt < this.roads.size()) {
            Predicate<RoadGeometry.V> sign = this.source.prepare(this.roads.get(this.sourceAt++), Collections.unmodifiableList(this.roads));
            if (sign != null) {
               this.prepared.add(sign);
               this.signSources++;
            }
         } else {
            this.signs = List.copyOf(this.prepared);
            this.phase = this.signs.isEmpty() ? 4 : 3;
         }
      } else if (this.roadAt == this.roads.size()) {
         this.phase = 4;
      } else {
         RoadIndex.Built road = this.roads.get(this.roadAt);
         if (this.scan == null) {
            this.scan = new StreetLampClearance.Removal(road.record.structures(), this.signs);
         } else if (!this.scan.done()) {
            this.scan.advance(1);
         } else {
            List<RoadStructures.Part> removed = this.scan.removed();
            this.partVisits = this.partVisits + this.scan.partVisits();
            this.signTests = this.signTests + this.scan.signTests();
            this.keyBuilds = this.keyBuilds + this.scan.keyBuilds();
            if (!removed.isEmpty()) {
               Set<Long> cleanup = this.apply.apply(road, removed);
               if (cleanup == null) {
                  this.invalidated = true;
                  return;
               }

               this.repairs.addAll(cleanup);
               this.commits++;
               this.revision = this.index.contentRevision();
            }

            this.scan = null;
            this.roadAt++;
         }
      }
   }

   static record Cost(long steps, long chunkQueries, long roads, long signSources, long partVisits, long signTests, long keyBuilds, long commits, long repairs) {
   }

   @FunctionalInterface
   interface Source {
      Predicate<RoadGeometry.V> prepare(RoadIndex.Built var1, List<RoadIndex.Built> var2);
   }
}

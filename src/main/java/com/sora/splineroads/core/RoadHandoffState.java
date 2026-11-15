package com.sora.splineroads.core;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Predicate;

public final class RoadHandoffState<S> {
   private final Map<RoadTerrainMesh.Section, RoadHandoffState.Source<S>> sources = new HashMap<>();
   private final Map<RoadTerrainMesh.Section, RoadHandoffState.Resident> resident = new HashMap<>();
   private final WeakHashMap<Object, RoadHandoffState.OwnerGeneration> generations = new WeakHashMap<>();
   private final Map<RoadTerrainMesh.Section, RoadHandoffState.SectionGeneration> sectionGenerations = new HashMap<>();
   private final Predicate<? super S> emptySource;
   private long serial;
   private long revision;
   private long mode;
   private volatile long ownershipRevision;
   private boolean terrain;
   private long accepted;
   private long rejected;

   public RoadHandoffState() {
      this(data -> false);
   }

   public RoadHandoffState(Predicate<? super S> emptySource) {
      this.emptySource = Objects.requireNonNull(emptySource);
   }

   public synchronized void publish(RoadTerrainMesh.Section section, S data, Set<UUID> owners) {
      this.publish(section, data, owners, Set.of());
   }

   public synchronized void publish(RoadTerrainMesh.Section section, S data, Set<UUID> owners, Set<UUID> replaces) {
      this.sources
         .put(
            section,
            new RoadHandoffState.Source<>(++this.revision, data, Set.copyOf(owners), Set.copyOf(replaces), owners.isEmpty() && this.emptySource.test(data))
         );
      this.pruneEmpty(section);
   }

   public synchronized void mode(boolean next) {
      if (this.terrain != next) {
         this.terrain = next;
         this.mode++;
      }
   }

   public synchronized RoadHandoffState.Ticket<S> begin(Object owner, RoadTerrainMesh.Section section) {
      long generation = ++this.serial;
      RoadHandoffState.OwnerGeneration previous = this.generations.put(owner, new RoadHandoffState.OwnerGeneration(section, generation));
      if (previous != null && !previous.section().equals(section)) {
         this.removeOwnerSection(owner, previous.section());
      }

      this.sectionGenerations.put(section, new RoadHandoffState.SectionGeneration(new WeakReference<>(owner), generation, true));
      RoadHandoffState.Source<S> source = this.sources.get(section);
      return new RoadHandoffState.Ticket<>(
         section,
         source,
         this.terrain,
         this.terrain && source != null && !source.owners().isEmpty() || this.resident.containsKey(section),
         generation,
         this.mode,
         owner
      );
   }

   public synchronized boolean current(RoadHandoffState.Ticket<S> ticket) {
      Object owner = ticket.owner();
      RoadHandoffState.SectionGeneration section = this.sectionGenerations.get(ticket.section);
      RoadHandoffState.OwnerGeneration generation = this.generations.get(owner);
      return owner != null
         && ticket.mode == this.mode
         && generation != null
         && generation.generation() == ticket.generation
         && section != null
         && section.generation() == ticket.generation
         && section.owner().get() == owner
         && this.sources.get(ticket.section) == ticket.source;
   }

   public synchronized boolean commit(RoadHandoffState.Ticket<S> ticket, Object upload) {
      if (!this.current(ticket)) {
         this.rejected++;
         return false;
      } else {
         if (!ticket.owners().isEmpty()) {
            HashSet<UUID> covered = new HashSet<>(ticket.owners());
            covered.addAll(ticket.source.replaces);
            this.resident
               .put(
                  ticket.section,
                  new RoadHandoffState.Resident(
                     ticket.owners(), Set.copyOf(covered), ticket.owner, Objects.requireNonNull(upload), ticket.generation, ticket.source
                  )
               );
            this.ownershipRevision++;
         } else if (this.resident.remove(ticket.section) != null) {
            this.ownershipRevision++;
         }

         this.accepted++;
         return true;
      }
   }

   public synchronized RoadHandoffState.Resident resident(RoadTerrainMesh.Section section) {
      return this.resident.get(section);
   }

   public synchronized RoadHandoffState.Source<S> source(RoadTerrainMesh.Section section) {
      return this.sources.get(section);
   }

   public synchronized void release(Object owner) {
      RoadHandoffState.OwnerGeneration previous = this.generations.remove(owner);
      if (previous != null) {
         this.removeOwnerSection(owner, previous.section());
      }
   }

   private void removeOwnerSection(Object owner, RoadTerrainMesh.Section section) {
      RoadHandoffState.Resident allocation = this.resident.get(section);
      if (allocation != null && allocation.owner().get() == owner) {
         this.resident.remove(section);
         this.ownershipRevision++;
      }

      RoadHandoffState.SectionGeneration generation = this.sectionGenerations.get(section);
      if (generation != null && generation.owner().get() == owner) {
         this.sectionGenerations.remove(section);
      }

      this.pruneEmpty(section);
   }

   public synchronized void finish(RoadHandoffState.Ticket<S> ticket) {
      Object owner = ticket.owner();
      if (owner == null) {
         this.pruneEmpty(ticket.section);
      } else {
         RoadHandoffState.OwnerGeneration generation = this.generations.get(owner);
         RoadHandoffState.Resident allocation = this.resident.get(ticket.section);
         if (generation != null && generation.generation() == ticket.generation) {
            RoadHandoffState.SectionGeneration current = this.sectionGenerations.get(ticket.section);
            if (allocation == null || allocation.owner().get() != owner) {
               this.generations.remove(owner);
               if (current != null && current.generation() == ticket.generation && current.owner().get() == owner) {
                  this.sectionGenerations.remove(ticket.section);
               }
            } else if (current != null && current.generation() == ticket.generation && current.owner().get() == owner) {
               this.sectionGenerations.put(ticket.section, new RoadHandoffState.SectionGeneration(current.owner(), current.generation(), false));
            }
         }

         this.pruneEmpty(ticket.section);
      }
   }

   private void pruneEmpty(RoadTerrainMesh.Section section) {
      RoadHandoffState.Source<S> source = this.sources.get(section);
      if (source != null && source.empty && !this.resident.containsKey(section)) {
         RoadHandoffState.SectionGeneration sectionGeneration = this.sectionGenerations.get(section);
         if (sectionGeneration != null) {
            Object owner = sectionGeneration.owner().get();
            RoadHandoffState.OwnerGeneration generation = this.generations.get(owner);
            boolean current = owner != null
               && generation != null
               && generation.generation() == sectionGeneration.generation()
               && generation.section().equals(section);
            if (current && sectionGeneration.pending()) {
               return;
            }

            this.sectionGenerations.remove(section);
            if (current) {
               this.generations.remove(owner);
            }
         }

         this.sources.remove(section, source);
      }
   }

   public long ownershipRevision() {
      return this.ownershipRevision;
   }

   /**
    * Drops all resident terrain allocations and forces ownership-dependent draw selections to rebuild.
    * Used when the terrain backend is turned off at runtime so stale residents cannot keep hiding VBO ranges.
    */
   public synchronized void invalidateOwnership() {
      this.resident.clear();
      this.ownershipRevision++;
   }

   public synchronized void invalidAllocation(RoadTerrainMesh.Section section, RoadHandoffState.Resident expected) {
      if (this.resident.get(section) == expected) {
         this.resident.remove(section);
         this.ownershipRevision++;
         this.pruneEmpty(section);
      }
   }

   public synchronized void invalidate(RoadTerrainMesh.Section section) {
      this.sources.remove(section);
      this.revision++;
   }

   public synchronized void clearSources() {
      this.sources.clear();
      this.mode++;
   }

   public synchronized void reset() {
      this.sources.clear();
      this.resident.clear();
      this.generations.clear();
      this.sectionGenerations.clear();
      this.mode++;
      this.ownershipRevision++;
   }

   public synchronized int residentCount() {
      return this.resident.size();
   }

   public synchronized long accepted() {
      return this.accepted;
   }

   public synchronized long rejected() {
      return this.rejected;
   }

   private static record OwnerGeneration(RoadTerrainMesh.Section section, long generation) {
   }

   public static record Resident(Set<UUID> owners, Set<UUID> vboOwners, WeakReference<Object> owner, Object upload, long generation, Object source) {
   }

   private static record SectionGeneration(WeakReference<Object> owner, long generation, boolean pending) {
   }

   public static final class Source<S> {
      private final long revision;
      private final S data;
      private final Set<UUID> owners;
      private final Set<UUID> replaces;
      private final boolean empty;

      Source(long revision, S data, Set<UUID> owners, Set<UUID> replaces, boolean empty) {
         this.revision = revision;
         this.data = data;
         this.owners = owners;
         this.replaces = replaces;
         this.empty = empty;
      }

      public long revision() {
         return this.revision;
      }

      public S data() {
         return this.data;
      }

      public Set<UUID> owners() {
         return this.owners;
      }
   }

   public static final class Ticket<S> {
      public final RoadTerrainMesh.Section section;
      public final RoadHandoffState.Source<S> source;
      public final boolean terrain;
      public final boolean staged;
      private final long generation;
      private final long mode;
      private final WeakReference<Object> owner;

      private Ticket(
         RoadTerrainMesh.Section section, RoadHandoffState.Source<S> source, boolean terrain, boolean staged, long generation, long mode, Object owner
      ) {
         this.section = section;
         this.source = source;
         this.terrain = terrain;
         this.staged = staged;
         this.generation = generation;
         this.mode = mode;
         this.owner = new WeakReference<>(owner);
      }

      public Object owner() {
         return this.owner.get();
      }

      public Set<UUID> owners() {
         return this.terrain && this.source != null ? this.source.owners() : Set.of();
      }
   }
}

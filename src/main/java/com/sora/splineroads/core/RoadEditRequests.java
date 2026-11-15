package com.sora.splineroads.core;

import java.util.Objects;
import java.util.UUID;

public final class RoadEditRequests {
   private Object editor;
   private long generation;
   private RoadEditRequests.Request pending;
   private RoadEditRequests.Request completed;

   public void open(Object owner) {
      if (this.editor != owner) {
         this.editor = owner;
         this.generation++;
         this.pending = null;
         this.completed = null;
      }
   }

   public boolean owns(Object owner) {
      return owner != null && this.editor == owner;
   }

   public boolean changed(Object owner) {
      if (this.editor != owner) {
         return false;
      } else {
         boolean wasPending = this.pending != null;
         this.generation++;
         this.pending = null;
         this.completed = null;
         return wasPending;
      }
   }

   public RoadEditRequests.Request submit(Object owner, String action, String target) {
      this.open(owner);
      this.completed = null;
      this.pending = new RoadEditRequests.Request(UUID.randomUUID(), ++this.generation, action, target, owner);
      return this.pending;
   }

   public RoadEditRequests.Request take(UUID id, long revision, String action, String target) {
      RoadEditRequests.Request value = this.pending;
      if (value != null
         && value.id().equals(id)
         && value.generation() == revision
         && value.generation() == this.generation
         && value.owner() == this.editor
         && value.action().equals(action)
         && Objects.equals(value.target(), target)) {
         this.pending = null;
         this.completed = value;
         return value;
      } else {
         return null;
      }
   }

   public RoadEditRequests.Request completed(UUID id, long revision, String action, String target) {
      RoadEditRequests.Request value = this.completed;
      return value != null
            && value.id().equals(id)
            && value.generation() == revision
            && value.generation() == this.generation
            && value.owner() == this.editor
            && value.action().equals(action)
            && Objects.equals(value.target(), target)
         ? value
         : null;
   }

   public void reset() {
      this.editor = null;
      this.generation++;
      this.pending = null;
      this.completed = null;
   }

   public String diagnostics() {
      return "editor="
         + (this.editor == null ? "null" : this.editor.getClass().getSimpleName())
         + " gen="
         + this.generation
         + " pending="
         + (this.pending == null ? "null" : this.pending.id() + "/gen" + this.pending.generation() + "/" + this.pending.action());
   }

   public static record Request(UUID id, long generation, String action, String target, Object owner) {
   }
}

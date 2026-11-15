package com.sora.splineroads;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

public final class ItemNBT {
   private static final MethodHandle WRAP;
   private static final MethodHandle INNER;

   public static CompoundTag get(ItemStack stack) {
      return shared(stack);
   }

   public static CompoundTag edit(ItemStack stack) {
      return shared(stack);
   }

   private static CompoundTag shared(ItemStack stack) {
      CustomData data = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      if (data != null) {
         try {
            return (CompoundTag)INNER.invokeExact((CustomData)data);
         } catch (Throwable var4) {
            throw new RuntimeException(var4);
         }
      } else {
         CompoundTag tag = new CompoundTag();

         try {
            stack.set(DataComponents.CUSTOM_DATA, (CustomData)WRAP.invokeExact((CompoundTag)tag));
            return tag;
         } catch (Throwable var5) {
            throw new RuntimeException(var5);
         }
      }
   }

   private ItemNBT() {
   }

   static {
      try {
         Constructor<CustomData> ctor = CustomData.class.getDeclaredConstructor(CompoundTag.class);
         ctor.setAccessible(true);
         WRAP = MethodHandles.lookup().unreflectConstructor(ctor);
         Field field = CustomData.class.getDeclaredField("tag");
         field.setAccessible(true);
         INNER = MethodHandles.lookup().unreflectGetter(field);
      } catch (ReflectiveOperationException var2) {
         throw new ExceptionInInitializerError(var2);
      }
   }
}

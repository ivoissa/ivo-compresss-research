package io.ivo.usercode;

import java.util.Objects;
import java.util.function.Supplier;

/** Per-call representation choice. Probabilities and wire formats are identical. */
public final class MixerStorage {
 private MixerStorage() {}
 private static final ThreadLocal<Boolean> COMPACT = new ThreadLocal<>();
 static boolean isCompact() { return Boolean.TRUE.equals(COMPACT.get()); }
 public static <T> T compact(Supplier<T> action) {
  Objects.requireNonNull(action);Boolean previous=COMPACT.get();COMPACT.set(true);
  try{return action.get();}finally{if(previous==null)COMPACT.remove();else COMPACT.set(previous);}
 }
}

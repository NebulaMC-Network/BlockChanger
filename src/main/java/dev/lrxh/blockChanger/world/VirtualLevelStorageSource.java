package dev.lrxh.blockChanger.world;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.FieldAccessor;
import net.bytebuddy.implementation.FixedValue;
import net.bytebuddy.implementation.MethodCall;
import net.bytebuddy.implementation.StubMethod;
import net.bytebuddy.matcher.ElementMatchers;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelStorageSource;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public final class VirtualLevelStorageSource {
    private static final AtomicReference<Class<? extends LevelStorageSource.LevelStorageAccess>> CACHED_GENERATED = new AtomicReference<>();
    private static final String GENERATED_NAME = "dev.lrxh.blockChanger.world.VirtualLevelStorageAccess$Generated";
    private static final Class<?> LEVEL_DIR_CLASS;
    private static final Constructor<?> LEVEL_DIR_CTOR;
    private static final sun.misc.Unsafe UNSAFE;
    private static final Map<String, Field> FIELD_CACHE = new ConcurrentHashMap<>();

    static {
        try {
            LEVEL_DIR_CLASS = Class.forName("net.minecraft.world.level.storage.LevelStorageSource$LevelDirectory");
            LEVEL_DIR_CTOR = LEVEL_DIR_CLASS.getDeclaredConstructor(Path.class);
            LEVEL_DIR_CTOR.setAccessible(true);
            Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            UNSAFE = (sun.misc.Unsafe) theUnsafe.get(null);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private VirtualLevelStorageSource() {
    }

    public static void warmup() {
        if (CACHED_GENERATED.get() != null) return;
        synchronized (CACHED_GENERATED) {
            if (CACHED_GENERATED.get() != null) return;
            ClassLoader loader = VirtualLevelStorageSource.class.getClassLoader();
            Class<? extends LevelStorageSource.LevelStorageAccess> cls = tryLoadGeneratedClass(loader);
            if (cls == null) cls = generateClass(loader);
            CACHED_GENERATED.set(cls);
        }
    }

    public static LevelStorageSource.LevelStorageAccess createVirtual(LevelStorageSource outer, String id, Path path, ResourceKey<LevelStem> dimensionType) {
        try {
            ClassLoader pluginLoader = VirtualLevelStorageSource.class.getClassLoader();
            Class<? extends LevelStorageSource.LevelStorageAccess> generated = CACHED_GENERATED.get();
            if (generated == null) {
                generated = tryLoadGeneratedClass(pluginLoader);
                if (generated == null) {
                    synchronized (CACHED_GENERATED) {
                        generated = CACHED_GENERATED.get();
                        if (generated == null) {
                            generated = generateClass(pluginLoader);
                            CACHED_GENERATED.set(generated);
                        }
                    }
                } else {
                    CACHED_GENERATED.compareAndSet(null, generated);
                }
            }
            Object instance = allocateInstanceWithoutConstructor(generated);
            setFieldInHierarchyCached(instance, "virtual$parent", outer);
            setFieldInHierarchyCached(instance, "virtual$id", id);
            setFieldInHierarchyCached(instance, "virtual$path", path);
            setFieldInHierarchyCached(instance, "virtual$dimension", dimensionType);
            Object levelDirInstance = LEVEL_DIR_CTOR.newInstance(path);
            setFieldInHierarchyCached(instance, "virtual$levelDirectory", levelDirInstance);
            setFieldIfPresentCached(instance, "levelDirectory", levelDirInstance);
            setFieldIfPresentCached(instance, "levelId", id);
            setFieldIfPresentCached(instance, "dimensionType", dimensionType);
            setFieldIfPresentCached(instance, "resources", new java.util.HashMap<String, Object>());
            return (LevelStorageSource.LevelStorageAccess) instance;
        } catch (Throwable e) {
            throw new IllegalStateException("Failed to create virtual LevelStorageAccess", e);
        }
    }

    private static Class<? extends LevelStorageSource.LevelStorageAccess> tryLoadGeneratedClass(ClassLoader loader) {
        try {
            @SuppressWarnings("unchecked") Class<? extends LevelStorageSource.LevelStorageAccess> cls = (Class<? extends LevelStorageSource.LevelStorageAccess>) Class.forName(VirtualLevelStorageSource.GENERATED_NAME, false, loader);
            return cls;
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static Class<? extends LevelStorageSource.LevelStorageAccess> generateClass(ClassLoader pluginLoader) {
        try {
            ByteBuddy byteBuddy = new ByteBuddy();
            DynamicType.Builder<LevelStorageSource.LevelStorageAccess> builder = byteBuddy.subclass(LevelStorageSource.LevelStorageAccess.class).name(GENERATED_NAME).defineField("virtual$parent", LevelStorageSource.class, Modifier.PRIVATE).defineField("virtual$id", String.class, Modifier.PRIVATE).defineField("virtual$path", Path.class, Modifier.PRIVATE).defineField("virtual$dimension", ResourceKey.class, Modifier.PRIVATE).defineField("virtual$levelDirectory", LEVEL_DIR_CLASS, Modifier.PRIVATE).method(ElementMatchers.named("hasWorldData")).intercept(FixedValue.value(false)).method(ElementMatchers.named("getIconFile")).intercept(FixedValue.value(Optional.empty())).method(ElementMatchers.named("getLevelId")).intercept(FieldAccessor.ofField("virtual$id")).method(ElementMatchers.named("getFileModificationTime").and(ElementMatchers.takesArguments(1)).and(ElementMatchers.takesArgument(0, boolean.class))).intercept(MethodCall.invoke(Instant.class.getMethod("now"))).method(ElementMatchers.named("estimateDiskSpace")).intercept(FixedValue.value(Long.MAX_VALUE / 4L)).method(ElementMatchers.named("checkForLowDiskSpace")).intercept(FixedValue.value(false)).method(ElementMatchers.named("parent")).intercept(FieldAccessor.ofField("virtual$parent")).method(ElementMatchers.named("getLevelDirectory")).intercept(FieldAccessor.ofField("virtual$levelDirectory")).method(ElementMatchers.named("getDimensionPath").and(ElementMatchers.takesArguments(ResourceKey.class))).intercept(MethodCall.invoke(VirtualLevelStorageSource.class.getDeclaredMethod("virtualGetDimensionPath", Object.class, ResourceKey.class)).withThis().withAllArguments()).method(ElementMatchers.named("close")).intercept(StubMethod.INSTANCE).method(ElementMatchers.named("safeClose")).intercept(StubMethod.INSTANCE).method(ElementMatchers.named("createPlayerStorage")).intercept(StubMethod.INSTANCE).method(ElementMatchers.named("getDataTag")).intercept(StubMethod.INSTANCE).method(ElementMatchers.named("getDataTagFallback")).intercept(StubMethod.INSTANCE).method(ElementMatchers.named("saveDataTag").and(ElementMatchers.takesArguments(2))).intercept(StubMethod.INSTANCE).method(ElementMatchers.named("saveDataTag").and(ElementMatchers.takesArguments(3))).intercept(StubMethod.INSTANCE);
            DynamicType.Unloaded<?> unloaded = builder.make();
            Class<?> loaded = unloaded.load(pluginLoader, ClassLoadingStrategy.Default.WRAPPER).getLoaded();
            @SuppressWarnings("unchecked") Class<? extends LevelStorageSource.LevelStorageAccess> typed = (Class<? extends LevelStorageSource.LevelStorageAccess>) loaded;
            return typed;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate VirtualLevelStorageAccess class", e);
        }
    }

    private static void setFieldInHierarchyCached(Object instance, String fieldName, Object value) throws Exception {
        Field field = getCachedField(instance.getClass(), fieldName);
        if (field == null) {
            throw new NoSuchFieldException("Field '" + fieldName + "' not found in class hierarchy of " + instance.getClass());
        }
        field.set(instance, value);
    }

    private static void setFieldIfPresentCached(Object instance, String fieldName, Object value) {
        try {
            Field field = getCachedField(instance.getClass(), fieldName);
            if (field == null) return;
            field.set(instance, value);
        } catch (Throwable ignored) {
        }
    }

    private static Field getCachedField(Class<?> cls, String fieldName) {
        String key = cls.getName() + "#" + fieldName;
        Field f = FIELD_CACHE.get(key);
        if (f != null) return f;
        Field computed = findFieldInHierarchy(cls, fieldName);
        if (computed == null) {
            return null;
        }
        try {
            computed.setAccessible(true);
            try {
                Field modifiersField = Field.class.getDeclaredField("modifiers");
                modifiersField.setAccessible(true);
                modifiersField.setInt(computed, computed.getModifiers() & ~Modifier.FINAL);
            } catch (Throwable ignore) {
            }
        } catch (Throwable ignored) {
        }
        Field prev = FIELD_CACHE.putIfAbsent(key, computed);
        return prev != null ? prev : computed;
    }

    private static Field findFieldInHierarchy(Class<?> cls, String fieldName) {
        Class<?> current = cls;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static Object allocateInstanceWithoutConstructor(Class<?> cls) {
        try {
            return UNSAFE.allocateInstance(cls);
        } catch (ReflectiveOperationException unsafeFailure) {
            throw new IllegalStateException("Failed to allocate instance without constructor. Consider generating an explicit constructor in the generated class.", unsafeFailure);
        }
    }

    @SuppressWarnings({"unchecked", "unused"})
    public static Path virtualGetDimensionPath(Object self, ResourceKey<?> dimension) throws Exception {
        Path p = (Path) readDeclaredFieldCached(self, "virtual$path");
        @SuppressWarnings("unchecked") ResourceKey<LevelStem> storedDim = (ResourceKey<LevelStem>) readDeclaredFieldCached(self, "virtual$dimension");
        return LevelStorageSource.getStorageFolder(p, storedDim);
    }

    private static Object readDeclaredFieldCached(Object instance, String fieldName) throws Exception {
        Field f = getCachedField(instance.getClass(), fieldName);
        if (f == null) {
            Field direct = instance.getClass().getDeclaredField(fieldName);
            direct.setAccessible(true);
            return direct.get(instance);
        }
        return f.get(instance);
    }
}

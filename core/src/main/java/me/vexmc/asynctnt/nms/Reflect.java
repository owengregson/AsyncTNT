package me.vexmc.asynctnt.nms;

import java.lang.reflect.Method;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Minimal reflection helpers for the NMS bridge (ported from Mental's Reflect). */
final class Reflect {

    private Reflect() {
    }

    static @Nullable Method method(@NotNull Class<?> owner, @NotNull String name, Class<?>... parameterTypes) {
        try {
            Method method = owner.getMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException absent) {
            try {
                Method method = owner.getDeclaredMethod(name, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException alsoAbsent) {
                return null;
            }
        }
    }

    /** First no-arg method with this name (handles the common getter case across the hierarchy). */
    static @Nullable Method noArgMethod(@NotNull Class<?> owner, @NotNull String name) {
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 0) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        for (Method m : owner.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 0) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    /** First method with this name and the given arity whose params accept the argument types. */
    static @Nullable Method methodArity(@NotNull Class<?> owner, @NotNull String name, int arity, Class<?>... args) {
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == arity && accepts(m.getParameterTypes(), args)) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        for (Method m : owner.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == arity && accepts(m.getParameterTypes(), args)) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    private static boolean accepts(Class<?>[] params, Class<?>[] args) {
        if (args.length == 0) {
            return true;
        }
        if (params.length != args.length) {
            return false;
        }
        for (int i = 0; i < params.length; i++) {
            if (args[i] != null && !params[i].isAssignableFrom(args[i])) {
                return false;
            }
        }
        return true;
    }
}

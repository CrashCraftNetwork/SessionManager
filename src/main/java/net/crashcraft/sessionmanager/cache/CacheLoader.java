package net.crashcraft.sessionmanager.cache;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface CacheLoader {
    String name();

    LoadType type();

    ExecutionType thread() default ExecutionType.SYNC;

    boolean suppressWarnings() default false;
}

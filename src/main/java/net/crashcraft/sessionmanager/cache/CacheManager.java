package net.crashcraft.sessionmanager.cache;

import java.util.UUID;

public interface CacheManager<T extends CachedData> {
    String getCacheName();

    int getThreadCount();

    T createCacheObject(UUID uuid);
}

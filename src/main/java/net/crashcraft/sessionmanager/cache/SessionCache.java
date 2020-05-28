package net.crashcraft.sessionmanager.cache;

import net.crashcraft.sessionmanager.SessionManager;
import net.crashcraft.sessionmanager.api.SessionDependency;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.cache2k.Cache;
import org.cache2k.Cache2kBuilder;
import org.cache2k.CacheEntry;
import org.cache2k.configuration.Cache2kConfiguration;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public class SessionCache<T extends CachedData> extends SessionDependency {
    private final JavaPlugin plugin;
    private final Logger logger;
    private final Cache<UUID, T> cache;
    private final CacheManager<T> cacheManager;

    private final Set<Method> syncLoadMethods;
    private final Set<Method> asyncLoadMethods;

    private final Set<Method> syncSaveMethods;
    private final Set<Method> asyncSaveMethods;

    private final Set<Method> syncUnLoadMethods;
    private final Set<Method> asyncUnLoadMethods;

    @SuppressWarnings("unchecked")
    public SessionCache(JavaPlugin plugin, CacheManager<T> manager, SessionManager sessionManager){
        this.cacheManager = manager;
        this.plugin = plugin;
        this.logger = plugin.getLogger();

        this.syncLoadMethods = new HashSet<>();
        this.asyncLoadMethods = new HashSet<>();
        this.syncSaveMethods = new HashSet<>();
        this.asyncSaveMethods = new HashSet<>();
        this.syncUnLoadMethods = new HashSet<>();
        this.asyncUnLoadMethods = new HashSet<>();

        T obj = cacheManager.createCacheObject(null);

        Cache2kConfiguration configuration = new Cache2kConfiguration<>();

        configuration.setKeyType(UUID.class);
        configuration.setValueType(obj.getClass());

        this.cache = Cache2kBuilder.of(configuration)
                .name(manager.getCacheName())
                .loaderThreadCount(manager.getThreadCount())
                .storeByReference(true)
                .disableStatistics(true)
                .loader((id) -> {
                    T data = cacheManager.createCacheObject((UUID) id);
                    // For sync loads we just go full ham and shove it in the cache as fast as possible
                    try {
                        syncLoad(data);
                        asyncLoad(data);
                    } catch (IllegalAccessException|InvocationTargetException e){
                        e.printStackTrace();
                    }

                    return data;
                })
                .build();

        fetchMethods(obj.getClass());

        sessionManager.registerDependency(this, "SessionCache");

        Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> { // Auto save
            try {
                for (CacheEntry<UUID, T> entry : cache.entries()){
                    syncSave(entry.getValue());
                }
            } catch (IllegalAccessException|InvocationTargetException e){
                e.printStackTrace();
            }

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    for (CacheEntry<UUID, T> entry : cache.entries()){
                        asyncSave(entry.getValue());
                    }
                } catch (IllegalAccessException|InvocationTargetException e){
                    e.printStackTrace();
                }
            });
        }, 20 * 60 * 5, 20 * 60 * 5);
    }

    private void loadUser(UUID id){
        if (cache.containsKey(id)) {
            logger.severe("A call to load a user that is already in the cache was made for: " + id);
            return;
        }

        T data = cacheManager.createCacheObject(id);
        CompletableFuture<Void> future = new CompletableFuture<>();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                asyncLoad(data);
            } catch (IllegalAccessException|InvocationTargetException e){
                e.printStackTrace();
            }

            future.complete(null);
        });

        SessionManager.newChain()
                .sync(() -> {
                    try {
                        syncLoad(data);
                    } catch (IllegalAccessException|InvocationTargetException e){
                        e.printStackTrace();
                    }
                })
                .future(future)
                .current(() -> cache.putIfAbsent(id, data))
                .execute();
    }

    private void fetchMethods(Class clazz){
        Set<CacheLoader> registeredLoad = new HashSet<>();
        Set<CacheLoader> registeredSave = new HashSet<>();
        Set<CacheLoader> registeredUnLoad = new HashSet<>();

        for (Method method : clazz.getDeclaredMethods()){
            CacheLoader annotation = method.getDeclaredAnnotation(CacheLoader.class);
            if (method.getParameterCount() != 0 || annotation == null){
                continue;
            }

            method.setAccessible(true);

            switch (annotation.type()){
                case SAVE:
                    registeredSave.add(annotation);

                    switch (annotation.thread()){
                        case SYNC:
                            syncSaveMethods.add(method);
                            break;
                        case ASYNC:
                            asyncSaveMethods.add(method);
                            break;
                    }
                    break;
                case LOAD:
                    registeredLoad.add(annotation);

                    switch (annotation.thread()){
                        case SYNC:
                            syncLoadMethods.add(method);
                            break;
                        case ASYNC:
                            asyncLoadMethods.add(method);
                            break;
                    }
                    break;
                case UNLOAD:
                    registeredUnLoad.add(annotation);

                    switch (annotation.thread()){
                        case SYNC:
                            syncUnLoadMethods.add(method);
                            break;
                        case ASYNC:
                            asyncUnLoadMethods.add(method);
                            break;
                    }
                    break;
            }
        }

        for (CacheLoader loader : registeredLoad){
            if (!loader.suppressWarnings()){
                boolean error = true;
                for (CacheLoader unLoader : registeredUnLoad){
                    if (unLoader.name().equals(loader.name())){
                        error = false;
                        break;
                    }
                }

                if (error){
                    logger.severe("Cache load method does not have a matching unload method, " + loader.name());
                }
            }
        }

        for (CacheLoader saver : registeredSave){
            if (!saver.suppressWarnings()){
                boolean error = true;
                for (CacheLoader loader : registeredLoad){
                    if (saver.name().equals(loader.name())){
                        error = false;
                        break;
                    }
                }

                if (error){
                    logger.severe("Cache save method does not have a matching load method, " + saver.name());
                }
            }
        }

        for (CacheLoader unLoader : registeredUnLoad){
            if (!unLoader.suppressWarnings()){

                boolean error = true;
                for (CacheLoader loader : registeredLoad){
                    if (unLoader.name().equals(loader.name())){
                        error = false;
                        break;
                    }
                }

                if (error){
                    logger.severe("Cache unload method does not have a matching load method, " + unLoader.name());
                }
            }
        }
    }

    public T getCachedData(UUID uuid){
        return cache.get(uuid);
    }

    public void preFetchCachedData(UUID uuid){
        loadUser(uuid);
    }

    @Override
    public void onSessionCreate(UUID player) {
        preFetchCachedData(player);
    }

    @Override
    public void onSessionClose(UUID player) {

    }

    @Override
    public CompletableFuture<Void> onSessionCloseWithFuture(UUID player) {
        if (!cache.containsKey(player)){
            logger.severe("Attempted to close session but no cache data was available for: " + player);
            return null;
        }

        T data = getCachedData(player);
        CompletableFuture<Void> future = new CompletableFuture<>();

        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                syncSave(data);
                syncUnload(data);
            } catch (IllegalAccessException|InvocationTargetException e){
                e.printStackTrace();
            } finally {
                future.complete(null);

                cache.remove(player); //Remove cache entry as the session is being flushed now
            }
        });

        try {
            asyncSave(data);
            asyncUnload(data);
        } catch (IllegalAccessException|InvocationTargetException e){
            e.printStackTrace();
        }

        return future;
    }

    private void syncLoad(T data) throws IllegalAccessException, InvocationTargetException{
        for (Method method : syncLoadMethods){
            method.invoke(data);
        }
    }

    private void asyncLoad(T data) throws IllegalAccessException, InvocationTargetException {
        for (Method method : asyncLoadMethods) {
            method.invoke(data);
        }
    }

    private void syncSave(T data) throws IllegalAccessException, InvocationTargetException{
        for (Method method : syncSaveMethods){
            method.invoke(data);
        }
    }

    private void asyncSave(T data) throws IllegalAccessException, InvocationTargetException{
        for (Method method : asyncSaveMethods){
            method.invoke(data);
        }
    }

    private void syncUnload(T data) throws IllegalAccessException, InvocationTargetException{
        for (Method method : syncUnLoadMethods){
            method.invoke(data);
        }
    }

    private void asyncUnload(T data) throws IllegalAccessException, InvocationTargetException{
        for (Method method : asyncUnLoadMethods){
            method.invoke(data);
        }
    }
}

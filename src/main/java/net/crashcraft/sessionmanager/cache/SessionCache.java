package net.crashcraft.sessionmanager.cache;

import com.google.common.util.concurrent.Futures;
import net.crashcraft.sessionmanager.SessionManager;
import net.crashcraft.sessionmanager.api.SessionDependency;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.cache2k.Cache;
import org.cache2k.Cache2kBuilder;
import org.cache2k.configuration.Cache2kConfiguration;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public class SessionCache<T extends CachedData> extends SessionDependency implements Listener {
    private final boolean DEBUG;

    private final JavaPlugin plugin;
    private final Logger logger;
    private final Cache<UUID, T> cache;
    private final CacheManager<T> cacheManager;

    private final Set<Method> syncLoadMethods;
    private final Set<Method> asyncLoadMethods;

    private final Set<Method> syncUnLoadMethods;
    private final Set<Method> asyncUnLoadMethods;

    private final Set<Method> syncLoginMethods;
    private final Set<Method> asyncLoginMethods;

    @SuppressWarnings("unchecked")
    public SessionCache(JavaPlugin plugin, CacheManager<T> manager, SessionManager sessionManager, boolean debug){
        this.cacheManager = manager;
        this.plugin = plugin;
        this.logger = plugin.getLogger();

        this.DEBUG = debug;

        this.syncLoadMethods = new HashSet<>();
        this.asyncLoadMethods = new HashSet<>();
        this.syncUnLoadMethods = new HashSet<>();
        this.asyncUnLoadMethods = new HashSet<>();
        this.syncLoginMethods = new HashSet<>();
        this.asyncLoginMethods = new HashSet<>();

        T obj = cacheManager.createCacheObject(null);

        Cache2kConfiguration configuration = new Cache2kConfiguration<>();

        configuration.setKeyType(UUID.class);
        configuration.setValueType(obj.getClass());

        this.cache = Cache2kBuilder.of(configuration)
                .name(manager.getCacheName())
                .loaderThreadCount(manager.getThreadCount())
                .storeByReference(true)
                .disableStatistics(true)
                .loader((id) -> getUser((UUID) id))
                .build();

        fetchMethods(obj.getClass());

        /*
        if (ALLOWREMOTESAVE){
            new RemoteSaveManager(plugin, this);
        }
        Unimplemented because im stupid
         */

        Bukkit.getPluginManager().registerEvents(this, plugin);
        sessionManager.registerDependency(this, "SessionCache");
    }

    public Cache<UUID, T> getRawCache(){
        return cache;
    }

    public CompletableFuture<T> getUserFuture(UUID uuid){
        if (cache.containsKey(uuid)){
            return CompletableFuture.completedFuture(cache.get(uuid));
        }

        T data = cacheManager.createCacheObject(uuid);
        CompletableFuture<Void> future = new CompletableFuture<>();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                asyncLoad(data);
            } catch (IllegalAccessException|InvocationTargetException e){
                e.printStackTrace();
            }

            future.complete(null);
        });

        CompletableFuture<T> finalFuture = new CompletableFuture<>();

        SessionManager.newChain()
                .sync(() -> {
                    try {
                        syncLoad(data);
                    } catch (IllegalAccessException|InvocationTargetException e){
                        e.printStackTrace();
                    }
                })
                .future(future)
                .current(() -> finalFuture.complete(data))
                .execute();

        return finalFuture;
    }

    private T getUser(UUID id){
        if (DEBUG) {
            OfflinePlayer player = Bukkit.getOfflinePlayer(id);

            if (!player.isOnline()) {
                plugin.getLogger().severe("Invocation of SessionCache loader was used on a player that is not online. The cache will attempt to continue with the creation. ");
                Thread.dumpStack();
            }
        }

        T data = cacheManager.createCacheObject(id);
        // For sync loads we just go full ham and shove it in the cache as fast as possible
        try {
            syncLoad(data);
            asyncLoad(data);
        } catch (IllegalAccessException|InvocationTargetException e){
            e.printStackTrace();
        }

        return data;
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
        for (Method method : clazz.getDeclaredMethods()){
            CacheLoader annotation = method.getDeclaredAnnotation(CacheLoader.class);
            if (method.getParameterCount() != 0 || annotation == null){
                continue;
            }

            method.setAccessible(true);

            switch (annotation.type()){
                case LOAD:
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
                    switch (annotation.thread()){
                        case SYNC:
                            syncUnLoadMethods.add(method);
                            break;
                        case ASYNC:
                            asyncUnLoadMethods.add(method);
                            break;
                    }
                    break;
                case LOGIN:
                    switch (annotation.thread()){
                        case SYNC:
                            syncLoginMethods.add(method);
                            break;
                        case ASYNC:
                            asyncLoginMethods.add(method);
                            break;
                    }
                    break;
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
                syncUnload(data);
            } catch (IllegalAccessException|InvocationTargetException e){
                e.printStackTrace();
            } finally {
                future.complete(null);

                cache.remove(player); //Remove cache entry as the session is being flushed now
            }
        });

        try {
            asyncUnload(data);
        } catch (IllegalAccessException|InvocationTargetException e){
            e.printStackTrace();
        }

        return future;
    }

    @EventHandler (ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onLogin(PlayerJoinEvent e){
        T data = cache.get(e.getPlayer().getUniqueId());

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                asyncLogin(data);
            } catch (IllegalAccessException|InvocationTargetException ex){
                ex.printStackTrace();
            }
        });

        try {
            syncLogin(data);
        } catch (IllegalAccessException|InvocationTargetException ex){
            ex.printStackTrace();
        }
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

    private void syncLogin(T data) throws IllegalAccessException, InvocationTargetException{
        for (Method method : syncLoginMethods){
            method.invoke(data);
        }
    }

    private void asyncLogin(T data) throws IllegalAccessException, InvocationTargetException{
        for (Method method : asyncLoginMethods){
            method.invoke(data);
        }
    }

    public CacheManager<T> getCacheManager() {
        return cacheManager;
    }

    Cache<UUID, T> getCache() {
        return cache;
    }
}


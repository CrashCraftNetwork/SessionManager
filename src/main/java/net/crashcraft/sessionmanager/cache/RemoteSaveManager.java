package net.crashcraft.sessionmanager.cache;

import co.aikar.idb.DB;
import co.aikar.idb.DbRow;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.UUID;

public class RemoteSaveManager {
    /*
    private JavaPlugin plugin;
    private int cacheID;
    private SessionCache<?> cache;

    private ArrayList<Integer> alreadySavedID;
    private ArrayList<Integer> alreadySavedID;

    public RemoteSaveManager(JavaPlugin plugin, SessionCache<?> cache) {
        this.plugin = plugin;
        this.cache = cache;

        try {
            cacheID = DB.getFirstColumn("SELECT id FROM session_caches WHERE `name` = ?", cache.getCacheManager().getCacheName());
        } catch (SQLException e){
            e.printStackTrace();
            plugin.getLogger().severe("Remote save not enabled for cache. " + cache.getCacheManager().getCacheName());
            return;
        }

        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::checkRemote, 20, 20);
    }

    private void checkRemote(){
        try {
            for (DbRow row : DB.getResults("SELECT player_id, method, (SELECT uuid FROM players WHERE id = player_id) AS uuid FROM session_remotesave WHERE `cache` = ?", cacheID)){
                UUID uuid = UUID.fromString(row.getString("uuid"));
                String method = row.getString("method");

                if (!cache.getCache().containsKey(uuid)){
                    continue;
                }

                Object data = cache.getCachedData(uuid);

                Method sync = cache.getSyncLoadMethodMap().get(method);
                Method async = cache.getAsyncLoadMethodMap().get(method);

                if (sync != null) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        try {
                            sync.invoke(data);
                        } catch (IllegalAccessException | InvocationTargetException e) {
                            e.printStackTrace();
                        }
                    });
                }
                if (async != null) {
                    try {
                        async.invoke(data);
                    } catch (IllegalAccessException | InvocationTargetException e) {
                        e.printStackTrace();
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

     */
}

package net.crashcraft.sessionmanager;

import co.aikar.idb.*;
import co.aikar.taskchain.BukkitTaskChainFactory;
import co.aikar.taskchain.TaskChain;
import co.aikar.taskchain.TaskChainFactory;
import net.crashcraft.sessionmanager.api.SessionDependency;
import net.crashcraft.sessionmanager.config.BaseConfig;
import net.crashcraft.sessionmanager.config.GlobalConfig;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class SessionManager extends JavaPlugin {
    private static SessionManager manager;

    private int serverID = 0;
    private int taskID = 0;
    private Set<SessionDependency> registeredDependency;

    private boolean loaded = false;

    private static TaskChainFactory taskChainFactory;
    public static <T> TaskChain<T> newChain() {
        return taskChainFactory.newChain();
    }
    public static <T> TaskChain<T> newSharedChain(String name) {
        return taskChainFactory.newSharedChain(name);
    }

    @Override
    public void onLoad(){
        if (manager != null){
            getLogger().severe("Plugin was reloaded, this can cause major issues and should not be done");
            return;
        }

        manager = this;
        registeredDependency = new HashSet<>();
        File dataFolder = getDataFolder();

        dataFolder.mkdirs();

        try {
            initConfig(new File(dataFolder, "config.yml"), GlobalConfig.class, null);

            DatabaseOptions options = DatabaseOptions.builder().mysql(
                    GlobalConfig.sql_user, GlobalConfig.sql_pass, GlobalConfig.sql_db, GlobalConfig.sql_ip
            ).build();

            options.setOnDatabaseConnectionFailure((obj) -> {
                getLogger().severe("Server is shutting down due to no database connection for session management");
                Bukkit.getServer().shutdown();
            });

            Database db = PooledDatabaseOptions.builder().options(options).createHikariDatabase();
            DB.setGlobalDatabase(db);

            try {
                DB.getFirstColumn("SELECT 1"); //Test quarry
            } catch (SQLException e){
                System.out.println(e.getMessage());
                getLogger().severe("Server is shutting down due to no database connection for session management");
            }

            serverID = getServerID(GlobalConfig.serverName);

            if (serverID == 0){
                getLogger().severe("Server is shutting down due to an invalid or not existing server id");
                Bukkit.getServer().shutdown();
                return;
            }

            removeAllPlayerSessions(serverID); //We will force remove all sessions as they would have came from an improper shutdown

            loaded = true;
        } catch (Exception e){
            getLogger().severe("An error occurred while starting the session manager.");
            e.printStackTrace();
            Bukkit.getServer().shutdown();
        }
    }

    @Override
    public void onEnable(){
        if (!loaded)
            return;

        taskChainFactory = BukkitTaskChainFactory.create(this);
        Bukkit.getPluginManager().registerEvents(new SessionEvents(this), this);
        taskID = Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::finishClosedSessions, 2, 2).getTaskId(); //Register task and get its id
    }

    @Override
    public void onDisable(){ //Flush and cleanup sessions
        Bukkit.getScheduler().cancelTask(taskID);

        if (!loaded)
            return;

        long endTime = System.currentTimeMillis() + 30000; // 30 second timeout
        try {
            while (Bukkit.getScheduler().isCurrentlyRunning(taskID)){
                if (System.currentTimeMillis() >= endTime){
                    getLogger().severe("Sessions manager task has not closed after 30 seconds");
                    break;
                }
                Thread.sleep(100);
            }

            markAllSessionsClosing(serverID); //Mark all sessions as closing so next call can cleanup data.

            finishClosedSessions(); //Finish all tasks off for the last time to make sure none are not processed.
            getLogger().info("Closed all sessions");
        } catch (InterruptedException|SQLException e){
            e.printStackTrace();
        }
    }

    public void registerDependency(SessionDependency dependency, String name){
        registeredDependency.add(dependency);
        getLogger().info("Session dependency registered [" + name + "]");
    }

    private void finishClosedSessions(){
        try {
            Set<CompletableFuture<Void>> futures = new HashSet<>();
            HashMap<UUID, Integer> players = new HashMap<>();

            for (DbRow row : DB.getResults("SELECT id, uuid FROM players WHERE id IN (SELECT player_id FROM sessions WHERE server_id = ? AND isclosing = 1);", serverID)){
                UUID uuid = UUID.fromString(row.getString("uuid"));
                int player_id = row.getInt("id");

                futures.addAll(finishClosedSessionsAsync(uuid)); // adding all futures, at this point most things should be closed by now
                players.putIfAbsent(uuid, player_id);
            }

            for (CompletableFuture<Void> future : futures){
                future.join(); // make sure the futures are complete
            }

            for (Map.Entry<UUID, Integer> entry : players.entrySet()){
                finishClosingSession(entry.getValue(), entry.getKey()); // finish up by kicking the player
            }
        } catch (SQLException e){
            e.printStackTrace();
        }
    }

    @SuppressWarnings("Duplicates")
    void finishClosedSession(UUID uuid, int player_id) throws SQLException{
        Set<CompletableFuture<Void>> futures = new HashSet<>();

        for (SessionDependency dependency : registeredDependency){
            dependency.onSessionClose(uuid);
            CompletableFuture<Void> future = dependency.onSessionCloseWithFuture(uuid);
            if (future != null){
                futures.add(future);
            }
        }

        for (CompletableFuture<Void> future : futures){
            future.join();
        }

        finishClosingSession(player_id, uuid);
    }

    @SuppressWarnings("Duplicates")
    Set<CompletableFuture<Void>> finishClosedSessionsAsync(UUID uuid){
        Set<CompletableFuture<Void>> futures = new HashSet<>();

        for (SessionDependency dependency : registeredDependency){
            dependency.onSessionClose(uuid);
            CompletableFuture<Void> future = dependency.onSessionCloseWithFuture(uuid);
            if (future != null){
                futures.add(future);
            }
        }

        return futures;
    }

    void finishClosingSession(int player_id, UUID uuid) throws SQLException{
        OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
        if (player.isOnline()){
            Player p = player.getPlayer();

            if (p == null){
                return;
            }

            p.kickPlayer("Kicking User for closed session on server"); // This should not show up as the proxy is mid switch but it does ensure the session is closed
        }

        removePlayerSession(player_id, serverID);
    }

    void createUser(UUID uuid, String username) throws SQLException{
        DB.executeInsert("INSERT INTO players (username, uuid) VALUES (?, ?) ON DUPLICATE KEY UPDATE username = VALUES(username);", username, uuid.toString());
        DB.executeUpdateAsync("CALL `reset_ai`('players');");
    }

    void removePlayerSession(int player_id, int server_id) throws SQLException{
        DB.executeUpdate("DELETE FROM sessions WHERE server_id = ? AND player_id = ?;", server_id, player_id);
    }

    private void removeAllPlayerSessions(int server_id) throws SQLException{
        DB.executeUpdate("DELETE FROM sessions WHERE server_id = ?;", server_id);
    }

    boolean hasSessionOpen(int player_id, int server_id) throws SQLException{
        return DB.getFirstColumnResults("SELECT player_id FROM sessions WHERE isclosing = 0 AND player_id = ? AND server_id = ?;", player_id, server_id).size() > 0;
    }

    void createUserSessions(int player_id, int server_id) throws SQLException{
        DB.executeInsert("INSERT IGNORE INTO sessions (player_id, server_id, isclosing) VALUES (?, ?, 0);", player_id, server_id);
    }

    boolean hasClosingSessionAnywhere(int player_id) throws SQLException{
        return DB.getFirstColumnResults("SELECT player_id FROM sessions WHERE isclosing = 1 AND player_id = ?;", player_id).size() > 0;
    }

    boolean hasClosingSession(int player_id, int server_id) throws SQLException{
        return DB.getFirstColumnResults("SELECT player_id FROM sessions WHERE isclosing = 1 AND player_id = ? AND server_id = ?;", player_id, server_id).size() > 0;
    }

    int getPlayerID(UUID uuid) throws SQLException {
        Object value = DB.getFirstColumn("SELECT id FROM players WHERE uuid = ?", uuid.toString());
        if (value == null){
            return 0;
        }
        return (int) value;
    }

    private int getServerID(String name) throws SQLException{
        return (int) DB.getFirstColumn("SELECT id FROM servers WHERE `name` = ?", name);
    }

    void markSessionsClosing(int server_id, int player_id) throws SQLException{
        DB.executeUpdate("UPDATE sessions SET isclosing = 1 WHERE server_id != ? AND player_id = ?;", server_id, player_id);
    }

    private void markAllSessionsClosing(int server_id) throws SQLException{
        DB.executeUpdate("UPDATE sessions SET isclosing = 1 WHERE server_id != ?", server_id);
    }

    private static YamlConfiguration initConfig(File configFile, Class<? extends BaseConfig> clazz, Object instance) throws Exception{
        if (!configFile.exists()){
            configFile.createNewFile();
        }

        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(configFile);
        } catch (InvalidConfigurationException ex) {
            throw new Exception("Invalid syntax in config file, " + configFile);
        }
        config.options().copyDefaults(true);

        BaseConfig.setConfig(config); //Set for each load instance should be fine unless threaded - not thread safe

        return readConfig(clazz, instance, config, configFile);
    }

    private static YamlConfiguration readConfig(Class<?> clazz, Object instance, YamlConfiguration config, File file) throws Exception{ //Stole from paper their config system is so sexy
        for (Method method : clazz.getDeclaredMethods()) {
            if (Modifier.isPrivate(method.getModifiers())) {
                if (method.getParameterTypes().length == 0 && method.getReturnType() == Void.TYPE) {
                    try {
                        method.setAccessible(true);
                        method.invoke(instance);
                    } catch (Exception ex) {
                        ex.printStackTrace();
                        throw new Exception("Failed to instantiate config file: " + file + ", method: " + method);
                    }
                }
            }
        }

        try {
            config.save(file);
        } catch (IOException ex) {
            Bukkit.getLogger().log(Level.SEVERE, "Could not save " + file, ex); //Not completely fatal will not shut server down
        }

        return config;
    }

    int getServerID() {
        return serverID;
    }

    Set<SessionDependency> getRegisteredDependency() {
        return registeredDependency;
    }
}

package net.crashcraft.sessionmanager;

import net.crashcraft.sessionmanager.api.SessionDependency;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.sql.SQLException;
import java.util.UUID;

public class SessionEvents implements Listener {
    private SessionManager manager;

    public SessionEvents(SessionManager manager){
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAsyncPlayerPreLoginEvent(AsyncPlayerPreLoginEvent event){
        /*
         * Check if session exists -- if so stall, make all existing sessions closing
         * Create new session
         */
        try {
            //Create the user for the network or update his username
            manager.createUser(event.getUniqueId(), event.getName());

            int player_id = manager.getPlayerID(event.getUniqueId());

            if (player_id == 0){
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, "Unable to start player session, no id found");
                return;
            }

            manager.markSessionsClosing(manager.getServerID(), player_id);   //Close existing sessions - should fire sessionDependencies

            while (manager.hasClosingSessionAnywhere(player_id)){
                Thread.sleep(100);  // This function runs in its own connection thread per player (if Aikar is right), so we sleep until no sessions are closing
            }

            if (manager.hasSessionOpen(player_id, manager.getServerID())){ // if a session is already open data should be up to date so just keep it open and resume
                return;
            }

            manager.createUserSessions(player_id, manager.getServerID()); //Create session

            for (SessionDependency dependency : manager.getRegisteredDependency()){ // Need to use thread safe caching as all dependencies are being called async
                dependency.onSessionCreate(event.getUniqueId()); // Call all session dependency on new created session
            }
        } catch (SQLException |InterruptedException e){
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, "Unable to start player session, database exception");
            manager.getLogger().severe("Unable to connect player to server, " + event.getUniqueId().toString());
            e.printStackTrace();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLoginMnoitor(AsyncPlayerPreLoginEvent event){
        if (event.getLoginResult().equals(AsyncPlayerPreLoginEvent.Result.ALLOWED)){
            return;
        }

        cleanup(event.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLeave(PlayerQuitEvent event){
        UUID uuid = event.getPlayer().getUniqueId();

        Bukkit.getScheduler().runTaskAsynchronously(manager, () -> { // Run task on scheduler to hop into an async context so we dont halt the main thread
            OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
            if (!player.isOnline()){ //TODO check if this is actually firing correctly.....
                cleanup(uuid);
            }
        });
    }

    private void cleanup(UUID uuid){
        try {
            int player_id = manager.getPlayerID(uuid);

            if (player_id == 0){
                manager.getLogger().severe("User has left with an invalid player id, uuid: " + uuid.toString());
                return;
            }

            // We check for existing session just in case it was already closed remotely by the other task
            if (manager.hasClosingSession(player_id, manager.getServerID())) {  // Prevents flushing null cache data into the database from an already closed session
                return;
            }

            manager.markSessionsClosing(manager.getServerID(), player_id);
            manager.finishClosedSession(uuid, player_id); // FLush caches and drop the session
        } catch (SQLException e){
            e.printStackTrace();
        }
    }
}

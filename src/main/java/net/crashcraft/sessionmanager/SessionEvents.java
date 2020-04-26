package net.crashcraft.sessionmanager;

import net.crashcraft.sessionmanager.api.SessionDependency;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.sql.SQLException;

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
            int player_id = manager.getPlayerID(event.getUniqueId());

            if (player_id == 0){
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, "Unable to start player session, no id found");
                return;
            }

            manager.markSessionsClosing(manager.getServerID(), player_id);   //Close existing sessions - should fire sessionDependencies
            manager.createUserSessions(player_id, manager.getServerID()); //Create session and allow login now

            while (manager.hasExistingSession(player_id)){
                Thread.sleep(100);  // This function runs in its own connection thread per player (if Aikar is right), so we sleep until no sessions are closing
            }

            for (SessionDependency dependency : manager.getRegisteredDependency()){ // Need to use thread safe caching as all dependencies are being called async
                dependency.onSessionCreate(event.getUniqueId()); // Call all session dependency on new created session
            }
        } catch (SQLException |InterruptedException e){
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, "Unable to start player session, database exception");
            manager.getLogger().severe("Unable to connect player to server, " + event.getUniqueId().toString());
            e.printStackTrace();
        }
    }
}

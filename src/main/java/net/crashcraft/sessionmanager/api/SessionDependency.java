package net.crashcraft.sessionmanager.api;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public abstract class SessionDependency {
    public abstract void onSessionCreate(UUID player);

    public abstract void onSessionClose(UUID player);

    public CompletableFuture<Void> onSessionCloseWithFuture(UUID player){
        return null;
    }
}

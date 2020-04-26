package net.crashcraft.sessionmanager.api;

import java.util.UUID;

public abstract class SessionDependency {
    public abstract void onSessionCreate(UUID player);

    public abstract void onSessionClose(UUID player);
}

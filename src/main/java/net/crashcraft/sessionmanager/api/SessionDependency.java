package net.crashcraft.sessionmanager.api;

import org.bukkit.entity.Player;

import java.util.UUID;

public abstract class SessionDependency {
    public abstract void onSessionCreate(UUID player);

    public abstract void onSessionClose(UUID player);
}

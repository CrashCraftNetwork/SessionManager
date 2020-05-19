package net.crashcraft.sessionmanager.cache;

import java.util.UUID;

public class CachedData {
    public UUID uuid;

    public CachedData(UUID uuid){
        this.uuid = uuid;
    }

    @CacheLoader(name = "onTest", type = LoadType.LOAD, thread = ExecutionType.ASYNC)
    private void onTestLoad(){

    }

    @CacheLoader(name = "onTest", type = LoadType.UNLOAD, thread = ExecutionType.ASYNC)
    private void onTestUnload(){

    }
}

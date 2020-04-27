package net.crashcraft.sessionmanager.config;

public class GlobalConfig extends BaseConfig{
    public static String serverName;

    private static void onConfig(){
        serverName = getString("server-name", "server");
    }

    public static String sql_user;
    public static String sql_pass;
    public static String sql_db;
    public static String sql_ip;

    private static void onSQL(){
        sql_user = getString("sql.user", "");
        sql_pass = getString("sql.pass", "");
        sql_db = getString("sql.db", "");
        sql_ip = getString("sql.ip", "");
    }
}
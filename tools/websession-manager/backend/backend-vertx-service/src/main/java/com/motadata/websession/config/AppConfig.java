package com.motadata.websession.config;

import io.github.cdimascio.dotenv.Dotenv;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class AppConfig
{

    private final int httpPort;

    private final String dbHost;

    private final int dbPort;

    private final String dbName;

    private final String dbUser;

    private final String dbPassword;

    private final int accessTokenTtlMinutes;

    private final int refreshTokenTtlDays;

    private final boolean cookieSecure;

    private final String encryptionKey;

    private final String engineHost;

    private final int enginePort;

    private final String engineAuthToken;

    public AppConfig(
        int httpPort,
        String dbHost,
        int dbPort,
        String dbName,
        String dbUser,
        String dbPassword,
        int accessTokenTtlMinutes,
        int refreshTokenTtlDays,
        boolean cookieSecure,
        String encryptionKey,
        String engineHost,
        int enginePort,
        String engineAuthToken)
    {
        this.httpPort = httpPort;

        this.dbHost = dbHost;

        this.dbPort = dbPort;

        this.dbName = dbName;

        this.dbUser = dbUser;

        this.dbPassword = dbPassword;

        this.accessTokenTtlMinutes = accessTokenTtlMinutes;

        this.refreshTokenTtlDays = refreshTokenTtlDays;

        this.cookieSecure = cookieSecure;

        this.encryptionKey = encryptionKey;

        this.engineHost = engineHost;

        this.enginePort = enginePort;

        this.engineAuthToken = engineAuthToken;
    }

    public AppConfig(
        int httpPort,
        String dbHost,
        int dbPort,
        String dbName,
        String dbUser,
        String dbPassword,
        int tokenTtlMinutes,
        boolean cookieSecure,
        String encryptionKey,
        String engineHost,
        int enginePort,
        String engineAuthToken)
    {
        this(
            httpPort,
            dbHost,
            dbPort,
            dbName,
            dbUser,
            dbPassword,
            tokenTtlMinutes,
            7,
            cookieSecure,
            encryptionKey,
            engineHost,
            enginePort,
            engineAuthToken
        );
    }

    public static AppConfig load()
    {
        Dotenv dotenv = Dotenv.configure()
            .ignoreIfMissing()
            .load();

        int httpPort = Integer.parseInt(dotenv.get("HTTP_PORT", "8888"));

        String dbHost = dotenv.get("DB_HOST", "localhost");

        int dbPort = Integer.parseInt(dotenv.get("DB_PORT", "5432"));

        String dbName = dotenv.get("DB_NAME", "websession_db");

        String dbUser = dotenv.get("DB_USER", "websession_app");

        String dbPassword = dotenv.get("DB_PASSWORD", "");

        String fallbackTtl = dotenv.get("ACCESS_TOKEN_TTL_MINUTES", dotenv.get("SESSION_TIMEOUT_MINUTES", "15"));

        int accessTokenTtlMinutes = Integer.parseInt(fallbackTtl);

        int refreshTokenTtlDays = Integer.parseInt(dotenv.get("REFRESH_TOKEN_TTL_DAYS", "7"));

        boolean cookieSecure = Boolean.parseBoolean(dotenv.get("COOKIE_SECURE", "false"));

        String encryptionKey = dotenv.get("ENCRYPTION_KEY", "websession-default-master-key-32b!");

        String engineHost = dotenv.get("ENGINE_HOST", "127.0.0.1");

        int enginePort = Integer.parseInt(dotenv.get("ENGINE_PORT", "8081"));

        String engineAuthToken = dotenv.get("ENGINE_AUTH_TOKEN", "dev-secret-engine-token-change-in-prod");

        return new AppConfig(
            httpPort,
            dbHost,
            dbPort,
            dbName,
            dbUser,
            dbPassword,
            accessTokenTtlMinutes,
            refreshTokenTtlDays,
            cookieSecure,
            encryptionKey,
            engineHost,
            enginePort,
            engineAuthToken
        );
    }

    public int getHttpPort()
    {
        return httpPort;
    }

    public String getDbHost()
    {
        return dbHost;
    }

    public int getDbPort()
    {
        return dbPort;
    }

    public String getDbName()
    {
        return dbName;
    }

    public String getDbUser()
    {
        return dbUser;
    }

    public String getDbPassword()
    {
        return dbPassword;
    }

    public int getAccessTokenTtlMinutes()
    {
        return accessTokenTtlMinutes;
    }

    public int getRefreshTokenTtlDays()
    {
        return refreshTokenTtlDays;
    }

    public int getTokenTtlMinutes()
    {
        return accessTokenTtlMinutes;
    }

    public int getSessionTimeoutMinutes()
    {
        return accessTokenTtlMinutes;
    }

    public boolean isCookieSecure()
    {
        return cookieSecure;
    }

    public String getEncryptionKey()
    {
        return encryptionKey;
    }

    public String getEngineHost()
    {
        return engineHost;
    }

    public int getEnginePort()
    {
        return enginePort;
    }

    public String getEngineAuthToken()
    {
        return engineAuthToken;
    }
}

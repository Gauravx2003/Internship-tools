package com.motadata.websession.config;

import io.github.cdimascio.dotenv.Dotenv;

import java.io.File;

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

    private final int engineMaxSessionsPerProcess;

    private final String engineBinaryPath;

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
        int engineMaxSessionsPerProcess,
        String engineBinaryPath)
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
        this.engineMaxSessionsPerProcess = engineMaxSessionsPerProcess > 0 ? engineMaxSessionsPerProcess : 5;
        this.engineBinaryPath = resolveEngineBinary(engineBinaryPath);
    }

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
        String encryptionKey)
    {
        this(
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
            5,
            null
        );
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
        String encryptionKey)
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
            5,
            null
        );
    }

    private static String resolveEngineBinary(String configuredPath)
    {
        if (configuredPath != null && !configuredPath.trim().isEmpty())
        {
            File f = new File(configuredPath.trim());
            if (f.exists())
            {
                return f.getAbsolutePath();
            }
        }
        String[] candidates = {
            "/home/gaurav-daware/Documents/Internship-tools/tools/websession-manager/backend/session-engine/bin/session-engine",
            "../session-engine/bin/session-engine",
            "../../session-engine/bin/session-engine",
            "backend/session-engine/bin/session-engine"
        };
        for (String candidate : candidates)
        {
            File f = new File(candidate);
            if (f.exists() && f.canExecute())
            {
                return f.getAbsolutePath();
            }
        }
        return configuredPath != null && !configuredPath.trim().isEmpty() ? configuredPath : candidates[0];
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

        int engineMaxSessionsPerProcess = Integer.parseInt(dotenv.get("ENGINE_MAX_SESSIONS_PER_PROC", "5"));
        String engineBinaryPath = dotenv.get("ENGINE_BINARY_PATH", null);

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
            engineMaxSessionsPerProcess,
            engineBinaryPath
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

    public int getEngineMaxSessionsPerProcess()
    {
        return engineMaxSessionsPerProcess;
    }

    public String getEngineBinaryPath()
    {
        return engineBinaryPath;
    }
}

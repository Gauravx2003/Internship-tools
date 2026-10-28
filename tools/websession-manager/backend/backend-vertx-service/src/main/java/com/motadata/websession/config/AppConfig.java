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

    private final int tokenTtlMinutes;

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
        int tokenTtlMinutes,
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

        this.tokenTtlMinutes = tokenTtlMinutes;

        this.cookieSecure = cookieSecure;

        this.encryptionKey = encryptionKey;

        this.engineHost = engineHost;

        this.enginePort = enginePort;

        this.engineAuthToken = engineAuthToken;
    }

    public static AppConfig load()
    {
        Dotenv dotenv = Dotenv.configure()
            .ignoreIfMissing()
            .load();

        Map<String, String> dotEnv = loadDotEnv();

        int httpPort = Integer.parseInt(getVal("HTTP_PORT", dotenv.get("HTTP_PORT", "8888"), dotEnv));

        String dbHost = getVal("DB_HOST", dotenv.get("DB_HOST", "localhost"), dotEnv);

        int dbPort = Integer.parseInt(getVal("DB_PORT", dotenv.get("DB_PORT", "5432"), dotEnv));

        String dbName = getVal("DB_NAME", dotenv.get("DB_NAME", "websession_db"), dotEnv);

        String dbUser = getVal("DB_USER", dotenv.get("DB_USER", "websession_app"), dotEnv);

        String dbPassword = getVal("DB_PASSWORD", dotenv.get("DB_PASSWORD", ""), dotEnv);

        String fallbackTtl = getVal("SESSION_TIMEOUT_MINUTES", dotenv.get("SESSION_TIMEOUT_MINUTES", "30"), dotEnv);

        int tokenTtlMinutes = Integer.parseInt(getVal("auth.tokenTtlMinutes", dotenv.get("auth.tokenTtlMinutes", dotenv.get("AUTH_TOKEN_TTL_MINUTES", fallbackTtl)), dotEnv));

        boolean cookieSecure = Boolean.parseBoolean(getVal("COOKIE_SECURE", dotenv.get("COOKIE_SECURE", "false"), dotEnv));

        String encryptionKey = getVal("ENCRYPTION_KEY", dotenv.get("ENCRYPTION_KEY", "websession-default-master-key-32b!"), dotEnv);

        String engineHost = getVal("ENGINE_HOST", dotenv.get("ENGINE_HOST", "127.0.0.1"), dotEnv);

        int enginePort = Integer.parseInt(getVal("ENGINE_PORT", dotenv.get("ENGINE_PORT", "8081"), dotEnv));

        String engineAuthToken = getVal("ENGINE_AUTH_TOKEN", dotenv.get("ENGINE_AUTH_TOKEN", "dev-secret-engine-token-change-in-prod"), dotEnv);

        return new AppConfig(
            httpPort,
            dbHost,
            dbPort,
            dbName,
            dbUser,
            dbPassword,
            tokenTtlMinutes,
            cookieSecure,
            encryptionKey,
            engineHost,
            enginePort,
            engineAuthToken
        );
    }

    private static String getVal(String key, String defaultValue, Map<String, String> dotEnv)
    {
        String envVal = System.getenv(key);

        if (envVal != null && !envVal.trim().isEmpty())
        {
            return envVal.trim();
        }

        String sysVal = System.getProperty(key);

        if (sysVal != null && !sysVal.trim().isEmpty())
        {
            return sysVal.trim();
        }

        String dotEnvVal = dotEnv.get(key);

        if (dotEnvVal != null && !dotEnvVal.trim().isEmpty())
        {
            return dotEnvVal.trim();
        }

        return defaultValue;
    }

    private static Map<String, String> loadDotEnv()
    {
        Map<String, String> map = new HashMap<>();

        File envFile = new File(".env");

        if (!envFile.exists())
        {
            return map;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(envFile)))
        {
            String line;

            while ((line = reader.readLine()) != null)
            {
                line = line.trim();

                if (line.isEmpty() || line.startsWith("#"))
                {
                    continue;
                }

                int eqIdx = line.indexOf('=');

                if (eqIdx > 0)
                {
                    String key = line.substring(0, eqIdx).trim();

                    String val = line.substring(eqIdx + 1).trim();

                    if ((val.startsWith("\"") && val.endsWith("\"")) ||
                        (val.startsWith("'") && val.endsWith("'")))
                    {
                        val = val.substring(1, val.length() - 1);
                    }

                    map.put(key, val);
                }
            }
        }
        catch (IOException e)
        {
            System.err.println("Notice: Could not read .env file: " + e.getMessage());
        }

        return map;
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

    public int getTokenTtlMinutes()
    {
        return tokenTtlMinutes;
    }

    public int getSessionTimeoutMinutes()
    {
        return tokenTtlMinutes;
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

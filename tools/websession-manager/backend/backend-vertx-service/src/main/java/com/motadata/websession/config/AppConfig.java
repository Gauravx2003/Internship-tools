package com.motadata.websession.config;

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

    private final int sessionTimeoutMinutes;

    private final boolean cookieSecure;

    private AppConfig(
        int httpPort,
        String dbHost,
        int dbPort,
        String dbName,
        String dbUser,
        String dbPassword,
        int sessionTimeoutMinutes,
        boolean cookieSecure)
    {
        this.httpPort = httpPort;

        this.dbHost = dbHost;

        this.dbPort = dbPort;

        this.dbName = dbName;

        this.dbUser = dbUser;

        this.dbPassword = dbPassword;

        this.sessionTimeoutMinutes = sessionTimeoutMinutes;

        this.cookieSecure = cookieSecure;
    }

    public static AppConfig load()
    {
        Map<String, String> dotEnv = loadDotEnv();

        int httpPort = Integer.parseInt(getVal("HTTP_PORT", "8888", dotEnv));

        String dbHost = getVal("DB_HOST", "localhost", dotEnv);

        int dbPort = Integer.parseInt(getVal("DB_PORT", "5432", dotEnv));

        String dbName = getVal("DB_NAME", "websession_db", dotEnv);

        String dbUser = getVal("DB_USER", "websession_app", dotEnv);

        String dbPassword = getVal("DB_PASSWORD", "", dotEnv);

        int sessionTimeoutMinutes = Integer.parseInt(getVal("SESSION_TIMEOUT_MINUTES", "480", dotEnv));

        boolean cookieSecure = Boolean.parseBoolean(getVal("COOKIE_SECURE", "false", dotEnv));

        return new AppConfig(
            httpPort,
            dbHost,
            dbPort,
            dbName,
            dbUser,
            dbPassword,
            sessionTimeoutMinutes,
            cookieSecure
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

    public int getSessionTimeoutMinutes()
    {
        return sessionTimeoutMinutes;
    }

    public boolean isCookieSecure()
    {
        return cookieSecure;
    }
}

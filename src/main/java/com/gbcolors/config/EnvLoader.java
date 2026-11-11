package com.gbcolors.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

/**
 * Loads local .env configuration before Spring Boot starts and configures
 * MySQL (with auto-database & Hibernate auto-schema) or local H2 fallback.
 */
public final class EnvLoader {

    private static volatile boolean mysqlConnected = false;
    private static volatile String activeDatabaseName = "gb_colors";

    private EnvLoader() {}

    public static void loadAndConfigureDatasource() {
        loadDotEnvIfPresent();
        configureDatasourceProperties();
    }

    public static boolean isMysqlConnected() {
        return mysqlConnected;
    }

    public static String getActiveDatabaseName() {
        return activeDatabaseName;
    }

    private static void loadDotEnvIfPresent() {
        Path envPath = Path.of(".env");
        if (!Files.exists(envPath)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(envPath, StandardCharsets.UTF_8);
            for (String rawLine : lines) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eqIdx = line.indexOf('=');
                if (eqIdx <= 0) {
                    continue;
                }
                String key = line.substring(0, eqIdx).trim();
                String val = line.substring(eqIdx + 1).trim();
                if ((val.startsWith("\"") && val.endsWith("\"") && val.length() >= 2)
                        || (val.startsWith("'") && val.endsWith("'") && val.length() >= 2)) {
                    val = val.substring(1, val.length() - 1);
                }
                if (System.getenv(key) == null && System.getProperty(key) == null) {
                    System.setProperty(key, val);
                }
            }
        } catch (IOException ex) {
            System.err.println("⚠️ Could not read .env file: " + ex.getMessage());
        }
    }

    private static void configureDatasourceProperties() {
        if (System.getProperty("spring.datasource.url") != null) {
            return;
        }

        String host = getConfig("MYSQL_HOST", "127.0.0.1");
        String port = getConfig("MYSQL_PORT", "3306");
        String dbName = getConfig("MYSQL_DATABASE", "gb_colors").replaceAll("[^a-zA-Z0-9_]", "");
        if (dbName.isEmpty()) {
            dbName = "gb_colors";
        }
        activeDatabaseName = dbName;

        String user = getConfig("MYSQL_USER", "root");
        String pass = getConfig("MYSQL_PASSWORD", "");
        boolean useSsl = "true".equalsIgnoreCase(getConfig("MYSQL_SSL", "false"));
        boolean requireMysql = "true".equalsIgnoreCase(getConfig("REQUIRE_MYSQL", "false"));

        String sslParams = useSsl
                ? "useSSL=true&requireSSL=true&verifyServerCertificate=false"
                : "useSSL=false";

        String mysqlUrl = String.format(
                "jdbc:mysql://%s:%s/%s?createDatabaseIfNotExist=true&%s&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=3000&socketTimeout=10000",
                host, port, dbName, sslParams
        );

        try {
            DriverManager.setLoginTimeout(4);
            try (Connection ignored = DriverManager.getConnection(mysqlUrl, user, pass)) {
                mysqlConnected = true;
                System.setProperty("spring.datasource.url", mysqlUrl);
                System.setProperty("spring.datasource.username", user);
                System.setProperty("spring.datasource.password", pass);
                System.setProperty("spring.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
                System.out.println("✅ Connected to MySQL Database (Auto-Schema Ready): '" + dbName + "'");
                return;
            }
        } catch (Exception ex) {
            mysqlConnected = false;
            if (requireMysql) {
                throw new IllegalStateException(
                        "MySQL connection required (REQUIRE_MYSQL=true) but failed: " + ex.getMessage(), ex
                );
            }
            System.err.println("⚠️ MySQL Connection Not Active (" + ex.getMessage() + ").");
            System.err.println("   -> To connect MySQL Workbench locally: set MYSQL_PASSWORD in .env and restart.");
            System.err.println("   -> Starting safely with local embedded H2 persistence (data/gb_colors_h2) for now.");

            String h2Url = "jdbc:h2:file:./data/gb_colors_h2;MODE=MySQL;DB_CLOSE_DELAY=-1";
            System.setProperty("spring.datasource.url", h2Url);
            System.setProperty("spring.datasource.username", "sa");
            System.setProperty("spring.datasource.password", "");
            System.setProperty("spring.datasource.driver-class-name", "org.h2.Driver");
        }
    }

    public static String getConfig(String key, String defaultVal) {
        String env = System.getenv(key);
        if (env != null) {
            return env;
        }
        String prop = System.getProperty(key);
        return prop != null ? prop : defaultVal;
    }
}

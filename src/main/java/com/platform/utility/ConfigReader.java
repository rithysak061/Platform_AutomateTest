package com.platform.utility;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

public class ConfigReader {

    private static final String CONFIG_PATH = "Config/config.properties";
    private static Properties properties;

    private ConfigReader() {
    }

    private static void load() {
        properties = new Properties();
        try (FileInputStream fis = new FileInputStream(CONFIG_PATH)) {
            properties.load(fis);
        } catch (IOException e) {
            throw new RuntimeException("Unable to load config file: " + CONFIG_PATH, e);
        }
    }

    public static String get(String key) {
        String override = System.getProperty(key);
        if (override != null) {
            return override;
        }
        if (properties == null) {
            load();
        }
        String value = properties.getProperty(key);
        if (value == null) {
            throw new RuntimeException("Missing config key: " + key);
        }
        return value;
    }

    public static int getInt(String key) {
        return Integer.parseInt(get(key));
    }

    public static boolean getBoolean(String key) {
        return Boolean.parseBoolean(get(key));
    }
}

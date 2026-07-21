package common;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Utility class to read configuration settings from .properties files.
 */
public class ConfigReader{
    private final Properties properties;

    public ConfigReader(String filePath){
        properties = new Properties();
        try(InputStream input = new FileInputStream(filePath)){
            properties.load(input);
        }catch(IOException ex){
            System.err.println("Error loading configuration file (" + filePath + "): " + ex.getMessage());
        }
    }

    public String getProperty(String key, String defaultValue){
        return properties.getProperty(key, defaultValue);
    }

    public int getIntProperty(String key, int defaultValue){
        String value = properties.getProperty(key);
        if(value != null){
            try{
                return Integer.parseInt(value);
            }catch(NumberFormatException e){
                System.err.println("Error parsing property " + key + ". Using default: " + defaultValue);
            }
        }
        return defaultValue;
    }
}

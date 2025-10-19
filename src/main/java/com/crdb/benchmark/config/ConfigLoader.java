package com.crdb.benchmark.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Configuration loader with environment variable substitution
 */
public class ConfigLoader {
    private static final Logger logger = LoggerFactory.getLogger(ConfigLoader.class);
    private static final Pattern ENV_VAR_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory())
        .registerModule(new JavaTimeModule());
    
    /**
     * Load configuration from YAML file with environment variable substitution
     */
    public static BenchmarkConfig load(String configPath) throws IOException {
        logger.info("Loading configuration from: {}", configPath);
        
        Path path = Path.of(configPath);
        if (!Files.exists(path)) {
            throw new IOException("Configuration file not found: " + configPath);
        }
        
        // Read file content
        String content = Files.readString(path);
        
        // Substitute environment variables
        content = substituteEnvironmentVariables(content);
        
        // Parse YAML
        BenchmarkConfig config = YAML_MAPPER.readValue(content, BenchmarkConfig.class);
        
        // Validate configuration
        validateConfig(config);
        
        logger.info("Configuration loaded successfully");
        return config;
    }
    
    /**
     * Substitute environment variables in format ${VAR_NAME}
     */
    private static String substituteEnvironmentVariables(String content) {
        Matcher matcher = ENV_VAR_PATTERN.matcher(content);
        StringBuffer result = new StringBuffer();
        
        while (matcher.find()) {
            String envVarName = matcher.group(1);
            String envVarValue = System.getenv(envVarName);
            
            if (envVarValue == null) {
                logger.warn("Environment variable not found: {}, using empty string", envVarName);
                envVarValue = "";
            }
            
            matcher.appendReplacement(result, Matcher.quoteReplacement(envVarValue));
        }
        matcher.appendTail(result);
        
        return result.toString();
    }
    
    /**
     * Validate configuration for common issues
     */
    private static void validateConfig(BenchmarkConfig config) {
        if (config.regions() == null || config.regions().isEmpty()) {
            throw new IllegalArgumentException("At least one region must be configured");
        }
        
        if (config.connectionPool().testPoolSizes().isEmpty()) {
            throw new IllegalArgumentException("At least one pool size must be configured for testing");
        }
        
        for (int poolSize : config.connectionPool().testPoolSizes()) {
            if (poolSize <= 0) {
                throw new IllegalArgumentException("Pool size must be positive: " + poolSize);
            }
        }
        
        if (config.benchmark().durationSeconds() <= 0) {
            throw new IllegalArgumentException("Benchmark duration must be positive");
        }
        
        logger.info("Configuration validation passed");
    }
    
    /**
     * Load configuration with default path
     */
    public static BenchmarkConfig loadDefault() throws IOException {
        String defaultPath = "config/benchmark-config.yaml";
        String configPath = System.getProperty("benchmark.config", defaultPath);
        return load(configPath);
    }
}

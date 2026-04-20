package com.workflow.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class PipelineConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(PipelineConfigLoader.class);
    private static final Pattern ENV_VAR_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    private final ObjectMapper yamlMapper;

    public PipelineConfigLoader() {
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        this.yamlMapper.registerModule(new JavaTimeModule());
        this.yamlMapper.findAndRegisterModules();
    }

    public PipelineConfig load(Path yamlPath) throws IOException {
        String content = Files.readString(yamlPath);
        content = expandEnvVars(content);

        PipelineConfig config = yamlMapper.readValue(content, PipelineConfig.class);

        // Validate and nullify integration sections with unexpanded vars
        if (config.getIntegrations() != null) {
            IntegrationsConfig integrations = config.getIntegrations();
            if (containsUnexpandedVars(integrations.getYoutrack())) {
                integrations.setYoutrack(null);
            }
            if (containsUnexpandedVars(integrations.getGitlab())) {
                integrations.setGitlab(null);
            }
            if (containsUnexpandedVars(integrations.getGithub())) {
                integrations.setGithub(null);
            }
            if (containsUnexpandedVars(integrations.getOpenrouter())) {
                integrations.setOpenrouter(null);
            }
        }

        return config;
    }

    public List<Path> listConfigs(Path configDir) {
        List<Path> paths = new ArrayList<>();
        if (!Files.isDirectory(configDir)) {
            return paths;
        }
        try (Stream<Path> stream = Files.walk(configDir, 1)) {
            stream
                .filter(p -> !Files.isDirectory(p))
                .filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                .sorted()
                .forEach(paths::add);
        } catch (IOException e) {
            log.warn("Error listing config directory {}: {}", configDir, e.getMessage());
        }
        return paths;
    }

    private String expandEnvVars(String content) {
        StringBuffer result = new StringBuffer();
        Matcher matcher = ENV_VAR_PATTERN.matcher(content);
        while (matcher.find()) {
            String varName = matcher.group(1);
            String value = System.getenv(varName);
            if (value != null) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(value));
            } else {
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private boolean containsUnexpandedVars(String value) {
        return value != null && value.contains("${");
    }

    @SuppressWarnings("unchecked")
    private Object expandValue(Object value) {
        if (value instanceof String str) {
            return expandEnvVars(str);
        } else if (value instanceof Map<?, ?> map) {
            Map<String, Object> expanded = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                expanded.put(String.valueOf(entry.getKey()), expandValue(entry.getValue()));
            }
            return expanded;
        } else if (value instanceof List<?> list) {
            List<Object> expanded = new ArrayList<>();
            for (Object item : list) {
                expanded.add(expandValue(item));
            }
            return expanded;
        }
        return value;
    }
}

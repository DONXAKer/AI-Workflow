package com.workflow.skills;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Skill: search source files for a regex pattern (uses system grep).
 */
@Component
public class SearchCodeSkill implements Skill {

    private static final Logger log = LoggerFactory.getLogger(SearchCodeSkill.class);
    private static final int MAX_RESULTS = 50;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public String getName() {
        return "search_code";
    }

    @Override
    public String getDescription() {
        return "Search source files for a regex pattern. Returns matching lines with file paths and line numbers (up to 50 results).";
    }

    @Override
    public ObjectNode getInputSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode pattern = props.putObject("pattern");
        pattern.put("type", "string");
        pattern.put("description", "Regular expression to search for.");

        ObjectNode dir = props.putObject("directory");
        dir.put("type", "string");
        dir.put("description", "Directory to search in (default: current working directory).");

        ObjectNode glob = props.putObject("file_pattern");
        glob.put("type", "string");
        glob.put("description", "Glob pattern to filter files, e.g. '*.java' or '*.{ts,tsx}' (default: all files).");

        schema.putArray("required").add("pattern");
        return schema;
    }

    @Override
    public Object execute(Map<String, Object> params) throws Exception {
        String pattern = String.valueOf(params.get("pattern"));
        String directory = params.containsKey("directory") ? String.valueOf(params.get("directory")) : ".";
        String filePattern = params.containsKey("file_pattern") ? String.valueOf(params.get("file_pattern")) : null;

        List<String> cmd = new ArrayList<>();
        cmd.add("grep");
        cmd.add("-rn");
        cmd.add("--include=" + (filePattern != null ? filePattern : "*"));
        cmd.add("-m");
        cmd.add(String.valueOf(MAX_RESULTS));
        cmd.add(pattern);
        cmd.add(directory);

        log.debug("search_code: pattern='{}' dir='{}' file_pattern='{}'", pattern, directory, filePattern);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process proc = pb.start();

        List<String> matches = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null && matches.size() < MAX_RESULTS) {
                matches.add(line);
            }
        }
        proc.waitFor();

        return Map.of(
            "pattern", pattern,
            "directory", directory,
            "matches", matches,
            "count", matches.size()
        );
    }
}

package com.workflow.api;

import com.workflow.mcp.McpServer;
import com.workflow.mcp.McpServerRepository;
import com.workflow.project.ProjectContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/mcp-servers")
@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
public class McpServerController {

    @Autowired
    private McpServerRepository mcpServerRepository;

    @GetMapping
    public List<McpServer> list() {
        return mcpServerRepository.findByProjectSlug(ProjectContext.get());
    }

    @PostMapping
    public ResponseEntity<McpServer> create(@RequestBody McpServer server) {
        server.setId(null);
        server.setProjectSlug(ProjectContext.get());
        return ResponseEntity.ok(mcpServerRepository.save(server));
    }

    @GetMapping("/{id}")
    public ResponseEntity<McpServer> getOne(@PathVariable Long id) {
        return mcpServerRepository.findById(id)
            .filter(s -> ProjectContext.get().equals(s.getProjectSlug()))
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<McpServer> update(@PathVariable Long id, @RequestBody McpServer server) {
        return mcpServerRepository.findById(id)
            .filter(s -> ProjectContext.get().equals(s.getProjectSlug()))
            .map(existing -> {
                server.setId(id);
                server.setProjectSlug(existing.getProjectSlug());
                return ResponseEntity.ok(mcpServerRepository.save(server));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        return mcpServerRepository.findById(id)
            .filter(s -> ProjectContext.get().equals(s.getProjectSlug()))
            .map(s -> {
                mcpServerRepository.delete(s);
                return ResponseEntity.noContent().<Void>build();
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/test")
    public ResponseEntity<Map<String, Object>> test(@PathVariable Long id) {
        Map<String, Object> result = new HashMap<>();
        return mcpServerRepository.findById(id)
            .filter(s -> ProjectContext.get().equals(s.getProjectSlug()))
            .map(server -> {
                try {
                    URI uri = URI.create(server.getUrl());
                    int port = uri.getPort() > 0 ? uri.getPort() : 80;
                    String host = uri.getHost();
                    try (java.net.Socket socket = new java.net.Socket()) {
                        socket.connect(new java.net.InetSocketAddress(host, port), 5000);
                    }
                    result.put("success", true);
                    result.put("message", "TCP соединение установлено (" + host + ":" + port + ")");
                } catch (Exception e) {
                    result.put("success", false);
                    result.put("message", "Не удалось подключиться: " + e.getMessage());
                }
                return ResponseEntity.ok(result);
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}/toggle")
    public ResponseEntity<McpServer> toggle(@PathVariable Long id) {
        return mcpServerRepository.findById(id)
            .filter(s -> ProjectContext.get().equals(s.getProjectSlug()))
            .map(s -> {
                s.setEnabled(!s.isEnabled());
                return ResponseEntity.ok(mcpServerRepository.save(s));
            })
            .orElse(ResponseEntity.notFound().build());
    }
}

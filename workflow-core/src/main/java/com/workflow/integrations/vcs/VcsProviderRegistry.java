package com.workflow.integrations.vcs;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class VcsProviderRegistry {

    private final Map<String, VcsProvider> byProvider = new HashMap<>();

    @Autowired
    public VcsProviderRegistry(List<VcsProvider> providers) {
        for (VcsProvider p : providers) byProvider.put(p.providerName().toLowerCase(), p);
    }

    public VcsProvider get(String name) {
        if (name == null) throw new IllegalArgumentException("VCS provider name is required");
        VcsProvider p = byProvider.get(name.toLowerCase());
        if (p == null) throw new IllegalArgumentException("No VCS provider: " + name
            + " (available: " + byProvider.keySet() + ")");
        return p;
    }

    public boolean supports(String name) {
        return name != null && byProvider.containsKey(name.toLowerCase());
    }
}

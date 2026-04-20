package com.workflow.core;

import jakarta.persistence.*;

@Entity
@Table(name = "block_output")
public class BlockOutput {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "run_id", nullable = false)
    private PipelineRun run;

    @Column(nullable = false)
    private String blockId;

    @Column(columnDefinition = "TEXT")
    private String outputJson;

    public BlockOutput() {}

    public BlockOutput(PipelineRun run, String blockId, String outputJson) {
        this.run = run;
        this.blockId = blockId;
        this.outputJson = outputJson;
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final BlockOutput bo = new BlockOutput();
        public Builder run(PipelineRun run) { bo.run = run; return this; }
        public Builder blockId(String blockId) { bo.blockId = blockId; return this; }
        public Builder outputJson(String outputJson) { bo.outputJson = outputJson; return this; }
        public BlockOutput build() { return bo; }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public PipelineRun getRun() { return run; }
    public void setRun(PipelineRun run) { this.run = run; }
    public String getBlockId() { return blockId; }
    public void setBlockId(String blockId) { this.blockId = blockId; }
    public String getOutputJson() { return outputJson; }
    public void setOutputJson(String outputJson) { this.outputJson = outputJson; }
}

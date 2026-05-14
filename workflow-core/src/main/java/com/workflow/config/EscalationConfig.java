package com.workflow.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Escalation policy for a verify / loopback failure. Accepts three YAML forms:
 * <ul>
 *   <li>{@code escalation: none} — opt-out, no escalation</li>
 *   <li>{@code escalation: default} (or omitted) — use Project / global defaults
 *       (resolved by {@code EscalationResolver})</li>
 *   <li>{@code escalation: [{tier: cloud, ...}, {tier: human, ...}]} — explicit ladder
 *       overriding any defaults</li>
 * </ul>
 */
@JsonDeserialize(using = EscalationConfig.Deserializer.class)
@JsonSerialize(using = EscalationConfig.Serializer.class)
@JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = EscalationConfig.DefaultPolicyFilter.class)
public final class EscalationConfig {

    public enum Policy { NONE, DEFAULT, EXPLICIT }

    private static final EscalationConfig NONE = new EscalationConfig(Policy.NONE, List.of());
    private static final EscalationConfig DEFAULT = new EscalationConfig(Policy.DEFAULT, List.of());

    private final Policy policy;
    private final List<EscalationStep> steps;

    private EscalationConfig(Policy policy, List<EscalationStep> steps) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public static EscalationConfig none() { return NONE; }
    public static EscalationConfig defaults() { return DEFAULT; }
    public static EscalationConfig explicit(List<EscalationStep> steps) {
        return new EscalationConfig(Policy.EXPLICIT, steps);
    }

    public Policy policy() { return policy; }
    public List<EscalationStep> steps() { return steps; }

    @Override
    public String toString() {
        return switch (policy) {
            case NONE -> "EscalationConfig{none}";
            case DEFAULT -> "EscalationConfig{default}";
            case EXPLICIT -> "EscalationConfig{explicit, " + steps.size() + " steps}";
        };
    }

    /**
     * Accepts string sentinels ("none" / "default"), null (→ default), or an array of
     * {@link EscalationStep} objects. Empty arrays parse as EXPLICIT with no steps,
     * which behaves like NONE at runtime — validator should warn on this.
     */
    static final class Deserializer extends JsonDeserializer<EscalationConfig> {
        @Override
        public EscalationConfig deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
            JsonToken tok = p.currentToken();
            return switch (tok) {
                case VALUE_NULL -> defaults();
                case VALUE_STRING -> {
                    String v = p.getText().trim().toLowerCase();
                    yield switch (v) {
                        case "none" -> none();
                        case "default", "" -> defaults();
                        default -> throw JsonMappingException.from(p,
                                "escalation must be 'none', 'default', or an array; got '" + v + "'");
                    };
                }
                // YAML 1.1 maps `off`/`no`/`false` to boolean false. Treat that as opt-out
                // for operators who write `escalation: false`; symmetric `true` → defaults.
                case VALUE_FALSE -> none();
                case VALUE_TRUE -> defaults();
                case START_ARRAY -> {
                    JavaType listType = ctx.getTypeFactory()
                            .constructCollectionType(List.class, EscalationStep.class);
                    List<EscalationStep> steps = ctx.readValue(p, listType);
                    yield explicit(steps);
                }
                default -> throw JsonMappingException.from(p,
                        "escalation must be 'none', 'default', or an array of steps");
            };
        }

        @Override
        public EscalationConfig getNullValue(DeserializationContext ctx) {
            return defaults();
        }
    }

    /**
     * Symmetric serializer for roundtrip. NONE → string {@code "none"};
     * EXPLICIT → array of steps; DEFAULT → string {@code "default"} as a fallback
     * (in practice {@link DefaultPolicyFilter} omits DEFAULT-policy fields entirely).
     */
    static final class Serializer extends JsonSerializer<EscalationConfig> {
        @Override
        public void serialize(EscalationConfig value, JsonGenerator gen, SerializerProvider sp) throws IOException {
            switch (value.policy()) {
                case NONE -> gen.writeString("none");
                case DEFAULT -> gen.writeString("default");
                case EXPLICIT -> gen.writeObject(value.steps());
            }
        }
    }

    /**
     * Inclusion filter for {@link JsonInclude.Include#CUSTOM}: Jackson omits the
     * field iff {@code filter.equals(value)} returns true. We omit DEFAULT-policy
     * configs so block YAML stays clean — only explicit overrides ({@code none} /
     * array) round-trip into the YAML output.
     */
    public static final class DefaultPolicyFilter {
        @Override
        public boolean equals(Object obj) {
            return obj instanceof EscalationConfig cfg && cfg.policy() == Policy.DEFAULT;
        }

        @Override
        public int hashCode() { return 0; }
    }
}

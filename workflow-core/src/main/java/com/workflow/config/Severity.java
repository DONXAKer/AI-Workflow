package com.workflow.config;

/**
 * Severity of a {@link ValidationError}. Pre-run, on-save and explicit-validate
 * gates block only on {@link #ERROR}; {@link #WARN} and {@link #INFO} surface in
 * the editor as advisory but never abort save or run.
 */
public enum Severity {
    ERROR,
    WARN,
    INFO
}

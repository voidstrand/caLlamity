package com.llamafactory.model;

/**
 * Lifecycle status of an LLM instance managed by the factory.
 */
public enum InstanceStatus {

    /** Instance is created and idle, not currently processing any request. */
    IDLE,

    /** Instance is actively processing a request against Ollama. */
    PROCESSING,

    /** Instance has asked a question and is waiting for a response from the user or agent. */
    AWAITING_RESPONSE,

    /** Instance has been terminated and is no longer usable. */
    TERMINATED
}

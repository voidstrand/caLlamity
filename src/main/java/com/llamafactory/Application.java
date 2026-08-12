package com.llamafactory;

import io.micronaut.runtime.Micronaut;

/**
 * Entry point for the OllamaFactory MCP Server.
 * Starts a Micronaut HTTP server with Streamable HTTP MCP transport
 * exposing tools for spawning and managing Ollama LLM instances.
 */
public class Application {

    public static void main(String[] args) {
        Micronaut.run(Application.class, args);
    }
}

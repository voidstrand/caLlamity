# caLlamity — System Architecture & Design

`caLlamity` is a lightweight, high-throughput Model Context Protocol (MCP) server built with **Java 25** and **Micronaut 5**. It provides an autonomous factory layer for spawning, orchestrating, and tracking multiple Ollama LLM instances using OpenAI-compatible REST APIs.

---

## Architectural Overview

```
 ┌─────────────────────────────────────────────────────────────┐
 │                         IDE / User                          │
 └──────────────────────────────┬──────────────────────────────┘
                                │ Prompt / Workflow Commands
                                ▼
 ┌─────────────────────────────────────────────────────────────┐
 │                    AI Agent (Antigravity)                   │
 └──────────────────────────────┬──────────────────────────────┘
                                │ Streamable HTTP / SSE (JSON-RPC 2.0)
                                ▼
 ┌─────────────────────────────────────────────────────────────┐
 │                 caLlamity MCP Server (:9999)                │
 │  ┌───────────────────────────────────────────────────────┐  │
 │  │ McpController (Netty Event Loops)                     │  │
 │  └───────────────────────────┬───────────────────────────┘  │
 │                              ▼                              │
 │  ┌───────────────────────────────────────────────────────┐  │
 │  │ McpMessageDispatcher (JSON-RPC 2.0 Dispatcher)        │  │
 │  └───────────────────────────┬───────────────────────────┘  │
 │                              ▼                              │
 │  ┌───────────────────────┬───────────────────────────────┐  │
 │  │ InstanceManager       │ MetricsService                │  │
 │  │ (Peer Backchannel)    │ (VRAM / Token Aggregator)     │  │
 │  └───────────┬───────────┴───────────────┬───────────────┘  │
 │              │                           │                  │
 │              ▼                           ▼                  │
 │  ┌───────────────────────────────────────────────────────┐  │
 │  │ OllamaOpenAiClient (Reactive Mono Netty HttpClient)   │  │
 │  └───────────────────────────┬───────────────────────────┘  │
 └──────────────────────────────┼──────────────────────────────┘
                                │ REST API Requests
                                ▼
 ┌─────────────────────────────────────────────────────────────┐
 │                 Ollama Host Server (:11434)                 │
 │   • /v1/chat/completions (OpenAI Chat Format)               │
 │   • /v1/models            (OpenAI Model List)               │
 │   • /api/ps               (Native Process & VRAM Metrics)   │
 └─────────────────────────────────────────────────────────────┘
```

---

## Key Design Principles

1. **100% Reactive & Non-Blocking**:
   - Built on Netty I/O event loops without blocking threads.
   - `OllamaOpenAiClient` uses Project Reactor `Mono<T>` for async HTTP communication.
   - Long-running LLM completions do not hold open worker threads or block Netty loops.

2. **Streamable HTTP & SSE Transport**:
   - Supports both stateless HTTP POST (`/mcp`, `/api/mcp`) and Server-Sent Events (`/mcp/sse`, `/api/mcp/sse`).

3. **Autonomous Peer-to-Peer Backchannel**:
   - `start_peer_collaboration` allows two or more instances to exchange multi-turn dialog server-side without invoking the client AI agent on every turn.
   - **Zero client token cost** during intermediate collaboration turns.
   - Per-party token tracking captures prompt, completion, and total tokens for each participant.

4. **Independent Conversational Isolation**:
   - Each spawned instance maintains its own thread-safe `LlmInstance` state, `List<ChatMessage>` history, task context, and generation parameters (`temperature`, `maxTokens`, `topP`).

---

## Sequence Diagram: Autonomous Collaboration & Tool Dispatch

The following sequence diagram describes the end-to-end interaction flow between the **IDE**, **AI Agent (Antigravity)**, **caLlamity MCP Server**, **Model Instances**, and the **Ollama REST API Host**:

```mermaid
sequenceDiagram
    autonumber
    actor User as IDE User
    participant Agent as AI Agent (Antigravity)
    participant MCP as caLlamity MCP Server<br/>(:9999)
    participant Mgr as InstanceManager &<br/>McpMessageDispatcher
    participant InstA as Instance A<br/>(Herman / llama3.2)
    participant InstB as Instance B<br/>(Ethel / voidstrand-buk)
    participant Ollama as Ollama Host<br/>(:11434 /v1/ & /api/)

    %% Step 1: User & Agent Setup
    User->>Agent: "Collaborate between Herman and Ethel to write a story"
    Agent->>MCP: POST /mcp (tools/call: create_instance 'Herman')
    MCP->>Mgr: createInstance("llama3.2:1b", "Herman")
    Mgr-->>MCP: Instance A Created (ID: uuid1)
    MCP-->>Agent: JSON-RPC Success ("Created instance 'Herman'")

    Agent->>MCP: POST /mcp (tools/call: create_instance 'Ethel')
    MCP->>Mgr: createInstance("voidstrand-buk", "Ethel")
    Mgr-->>MCP: Instance B Created (ID: uuid2)
    MCP-->>Agent: JSON-RPC Success ("Created instance 'Ethel'")

    %% Step 2: Autonomous Backchannel Initiation
    Note over Agent, MCP: Agent calls start_peer_collaboration ONCE (1 Token Turn for Agent)
    Agent->>MCP: POST /mcp (tools/call: start_peer_collaboration 'Herman', 'Ethel', taskPrompt)
    MCP->>Mgr: runAutonomousCollaboration("Herman", "Ethel", prompt, maxTurns=6)

    %% Step 3: Turn 1 (Herman -> Ollama)
    rect rgb(240, 248, 255)
        Note over Mgr, Ollama: Turn 1: Server-Side Execution (0 Agent Tokens)
        Mgr->>InstA: Append Prompt & Request
        Mgr->>Ollama: POST /v1/chat/completions (Model: llama3.2:1b, Messages)
        Ollama-->>Mgr: 200 OK (Response Text A + Usage Tokens)
        Mgr->>InstA: Append Assistant Response A & Record Tokens
    end

    %% Step 4: Turn 2 (Herman Output Relayed to Ethel)
    rect rgb(245, 245, 245)
        Note over Mgr, Ollama: Turn 2: Local Server Relay (0 Agent Tokens)
        Mgr->>InstB: Route Response A as [Message from 'Herman']
        Mgr->>Ollama: POST /v1/chat/completions (Model: voidstrand-buk, Messages)
        Ollama-->>Mgr: 200 OK (Response Text B + Usage Tokens)
        Mgr->>InstB: Append Assistant Response B & Record Tokens
    end

    %% Step 5: Turn Loop Continues until [DONE]
    Note over Mgr: Loop repeats server-side until [DONE] or maxTurns reached

    %% Step 6: Consolidated Metrics & Final Report
    Mgr->>Ollama: GET /api/ps (Query Running Processes & VRAM)
    Ollama-->>Mgr: 200 OK (Process Memory Details)
    Note over Mgr: Calculate Per-Party Consumed Tokens (Instance A & Instance B)

    Mgr-->>MCP: Consolidated Transcript + Session Token Breakdown
    MCP-->>Agent: JSON-RPC Success (Full Story + Per-Party Token Report)
    Agent-->>User: Present Story & Token Summary (Zero Mediation Burn)
```

---

## Domain Model Summary

- **`LlmInstance`**: Represents a live spawned model instance holding alias, model name, status, temperature, max tokens, top-P, message list, and token counters.
- **`ChatMessage`**: Immutable record representing OpenAI-formatted chat turns (`role`: system/user/assistant, `content`).
- **`InstanceStatus`**: Enum representing `IDLE`, `PROCESSING`, `ERROR`, `TERMINATED`.
- **`OllamaOpenAiClient`**: Micronaut HTTP client interfacing with Ollama's OpenAI endpoints.
- **`MetricsService`**: Aggregates instance state with live process memory from `/api/ps`.

---

## Author, License & Contact

- **Author**: [voidstrand](https://github.com/voidstrand)
- **License**: Entirely permissive under the [MIT License](LICENSE).
- **Contact**: Questions regarding the project should be sent to `voidstrand@voidstrand.com`.



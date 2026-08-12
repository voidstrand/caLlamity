# caLlamity 🦙💥

**caLlamity** is a high-performance Java 25 & Micronaut 5 Model Context Protocol (MCP) server that acts as a dynamic **Ollama LLM Instance Factory**. It allows AI agents (such as Antigravity, Copilot, or custom IDE plugins) to dynamically spawn, manage, message, broadcast to, and orchestrate peer collaboration between multiple isolated model instances running on Ollama via OpenAI-compatible REST API endpoints.

---

## Technical Stack

- **Java Baseline**: JDK 25 (OpenJDK 25+)
- **Framework**: Micronaut 5 (Netty Reactive Core, Project Reactor)
- **Build System**: Gradle 9.7 (via Gradle Wrapper)
- **Transport Mode**: Streamable HTTP & SSE (Server-Sent Events) on Port `9999`
- **LLM Integration**: Ollama OpenAI-compatible REST API (`/v1/chat/completions`, `/v1/models`) & Native Memory Metrics (`/api/ps`)

---

## How to Build

### Prerequisites
- JDK 25 installed and configured in your environment (`JAVA_HOME` pointing to JDK 25).
- Local or remote Ollama instance running at `http://localhost:11434` (configurable).

### Build Command
From the project root directory:

```powershell
# Windows (PowerShell)
.\gradlew.bat build

# Linux / macOS
./gradlew build
```

---

## How to Run & Host

Start the server using the Gradle wrapper:

```powershell
# Windows (PowerShell)
.\gradlew.bat run

# Linux / macOS
./gradlew run
```

Upon startup, **caLlamity** listens on port **`9999`**:

- **Streamable HTTP POST Endpoint**: `http://localhost:9999/mcp` (or `/api/mcp`)
- **Server-Sent Events (SSE) Endpoint**: `http://localhost:9999/mcp/sse` (or `/api/mcp/sse`)

### Configuring IDE / Client Connections (`mcp_config.json`)
Add the following to your agent or IDE MCP configuration (e.g. `~/.gemini/config/mcp_config.json`):

```json
{
    "mcpServers": {
        "caLlamity": {
            "transport": "http",
            "serverUrl": "http://localhost:9999/mcp"
        }
    }
}
```

---

## Registered MCP Tools (12 Total)

| # | Tool Name | Required Arguments | Description |
|---|---|---|---|
| 1 | `list_available_models` | — | Discovers all available models on the connected Ollama host. |
| 2 | `create_instance` | `modelName`, `alias` | Spawns a new LLM instance with custom `temperature`, `maxTokens`, and `topP`. |
| 3 | `list_instances` | — | Lists all currently active spawned instances, their status, and token usage. |
| 4 | `get_instance_detail` | `alias` | Retrieves full configuration, token counters, task history, and memory usage for an instance. |
| 5 | `terminate_instance` | `alias` | Gracefully shuts down and removes a model instance. |
| 6 | `send_message` | `alias`, `message` | Sends a message to a specific instance and gets assistant response while preserving conversation state. |
| 7 | `assign_task` | `alias`, `taskDescription` | Assigns a dedicated task to an instance, setting its context summary and task role. |
| 8 | `get_conversation_history` | `alias`, `lastN` (optional) | Retrieves full or partial message history for an instance. |
| 9 | `send_message_to_instance` | `fromAlias`, `toAlias`, `message` | Routes a message from one instance to another with sender context. |
| 10 | `broadcast_message` | `message`, `aliases` (optional) | Broadcasts a prompt to multiple instances (or `"all"`) and aggregates responses. |
| 11 | `start_peer_collaboration` | `instanceA`, `instanceB`, `taskPrompt` | **Autonomous Backchannel**: Runs a multi-turn peer collaboration loop server-side between two instances. **Zero client/agent token burn** during relay turns. Returns per-party token breakdown. |
| 12 | `get_metrics` | — | Generates a comprehensive metrics report showing active instances, per-instance token/VRAM consumption, and live Ollama server processes. |

---

## Recommended Use Cases & Orchestration Patterns

### 1. Zero-Cost Autonomous Peer Collaboration (`start_peer_collaboration`)
- **Use Case**: Have two specialized local models (e.g. `DocWriter` and `CodeReviewer`, or `Herman` and `Ethel`) execute multi-turn brainstorming, story writing, or code review sessions completely server-side.
- **Benefit**: Antigravity / Client agent token burn is **0 tokens** during intermediate turns. Only the final consolidated output is returned.

### 2. Multi-Model Task Delegation & Roleplay
- **Use Case**: Spawn a small 1B model (`llama3.2:1b`) for quick text summaries, a 7B coding model (`qwen2.5-coder:7b`) for code generation, and a 30B model (`qwen3-coder:30b`) for complex architectural reasoning.
- **Benefit**: Keeps instance contexts focused and isolated without clogging a single prompt window.

### 3. Broadcast Brainstorming (`broadcast_message`)
- **Use Case**: Send a single problem statement to multiple model instances simultaneously and compare their different approaches or perspective outputs in a single turn.

### 4. Live Memory & Token Observability (`get_metrics`)
- **Use Case**: Monitor exact prompt and completion tokens used per instance, check VRAM/memory allocation via native Ollama `/api/ps` integration, and terminate unused instances to free up GPU resources.

---

## Architecture Documentation

For complete architectural details, class diagrams, and sequence flows, see [architecture.md].

---

## Author, License & Contact

### Author
- **[voidstrand](https://github.com/voidstrand)**

### License
This project is open-source, entirely permissive, and released under the [MIT License](LICENSE). See the [LICENSE](LICENSE) file for full details.

### Contact
Questions regarding the project should be sent to **[voidstrand@voidstrand.com](mailto:voidstrand@voidstrand.com)**.



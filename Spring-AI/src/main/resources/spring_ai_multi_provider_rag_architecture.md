# Spring AI Multi-Provider & RAG Knowledge Engine

A production-ready Spring AI service architecture supporting local inference via native **Ollama**, cloud-based fallback/hybrid execution via **OpenAI**, and an end-to-end **Retrieval-Augmented Generation (RAG)** pipeline.

---

## 1. Architectural Principles

1. **Provider Agnosticism**: Endpoints represent functional resources (`/chat`, `/documents`, `/rag`), not vendors (`/ollama`, `/openai`).
2. **Dynamic Model Routing**: Clients switch between local models (e.g., `qwen2.5-coder:14b`, `mistral`) and cloud models (`gpt-4o`, `gpt-4o-mini`) via payload properties or headers at runtime.
3. **Bare-Metal Ollama Execution**: Bypasses containerized virtualization overhead by leveraging host GPU/NPU acceleration through local daemons.

---

## 2. Infrastructure & Local Ollama Setup

### 2.1 Bypassing Docker Compose
Because Ollama is installed natively on your host OS:
* Do not spin up Ollama via Docker Compose (avoids container GPU passthrough complexities).
* Disable Spring Boot Docker Compose integration to prevent startup conflicts:

```yaml
# src/main/resources/application.yml
spring:
  docker:
    compose:
      enabled: false
```

### 2.2 Verify Native Ollama Models
Ensure Ollama is running and check your local inventory:

```bash
# Verify daemon health
curl http://localhost:11434

# List locally available models
ollama list
```

*Example pulled models:*
* `qwen2.5-coder:14b`
* `qwen3:14b`
* `mistral:latest`

---

## 3. Configuration (`application.yml`)

```yaml
spring:
  application:
    name: spring-ai-rag-engine
  docker:
    compose:
      enabled: false

  ai:
    # --- Local Ollama Configuration ---
    ollama:
      base-url: http://localhost:11434
      chat:
        options:
          model: qwen2.5-coder:14b
          temperature: 0.7

    # --- Cloud OpenAI Configuration ---
    openai:
      api-key: ${OPENAI_API_KEY:demo-key}
      chat:
        options:
          model: gpt-4o-mini
          temperature: 0.7

    # --- Vector Store Configuration (e.g., PgVector) ---
    vectorstore:
      pgvector:
        index-type: HNSW
        distance-type: COSINE
        dimensions: 1536
```

---

## 4. API Specification & REST Design

### 4.1 Document Ingestion & Knowledge Base (ETL)

| Method | Endpoint | Description | Content-Type |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/v1/documents/upload` | Upload & chunk PDF, DOCX, TXT into VectorStore | `multipart/form-data` |
| `POST` | `/api/v1/documents/raw` | Ingest arbitrary raw text or markdown blocks | `application/json` |
| `GET` | `/api/v1/documents` | List ingested document metadata & chunk counts | `application/json` |
| `DELETE` | `/api/v1/documents/{documentId}` | Evict document vectors from VectorStore | — |

#### Ingestion Request Example (`POST /api/v1/documents/raw`):
```json
{
  "title": "Corporate Leave Policy 2026",
  "content": "Employees are eligible for 20 annual leaves...",
  "metadata": {
    "department": "HR",
    "version": "1.2"
  }
}
```

---

### 4.2 Conversational & Dynamic Inference

| Method | Endpoint | Description | Content-Type |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/v1/chat` | Provider-agnostic chat with dynamic model switching | `application/json` |
| `POST` | `/api/v1/chat/stream` | Token-by-token streaming via SSE | `text/event-stream` |

#### Chat Request DTO:
```json
{
  "message": "Write a reactive Spring Boot WebFilter to validate JWTs.",
  "provider": "ollama",
  "model": "qwen2.5-coder:14b",
  "temperature": 0.2
}
```

* **To target local Mistral**: Change `model` to `"mistral:latest"`.
* **To switch to OpenAI**: Change `provider` to `"openai"` and `model` to `"gpt-4o-mini"`.

---

### 4.3 RAG (Retrieval-Augmented Generation)

| Method | Endpoint | Description | Content-Type |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/v1/rag/ask` | Retrieve vectors, augment context, and generate answer | `application/json` |
| `POST` | `/api/v1/rag/stream` | Streaming RAG completion with cited sources | `text/event-stream` |
| `POST` | `/api/v1/rag/search` | **Retrieval-only**: semantic search returning top-K chunks | `application/json` |

#### RAG Query Request Example (`POST /api/v1/rag/ask`):
```json
{
  "query": "How many days in advance should annual leave be requested?",
  "provider": "ollama",
  "model": "qwen2.5-coder:14b",
  "topK": 4,
  "similarityThreshold": 0.72
}
```

---

## 5. Core Implementation Architecture

### 5.1 Multi-Provider Configuration (`AiConfig.java`)

```java
package com.example.demo.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    @Bean(name = "ollamaChatClient")
    public ChatClient ollamaChatClient(OllamaChatModel ollamaChatModel) {
        return ChatClient.builder(ollamaChatModel)
                .defaultSystem("You are a specialized enterprise AI assistant.")
                .build();
    }

    @Bean(name = "openAiChatClient")
    public ChatClient openAiChatClient(OpenAiChatModel openAiChatModel) {
        return ChatClient.builder(openAiChatModel)
                .defaultSystem("You are a specialized enterprise AI assistant.")
                .build();
    }
}
```

### 5.2 Dynamic Chat Controller (`ChatController.java`)

```java
package com.example.demo.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

public record ChatRequest(
    String message,
    String provider,
    String model,
    Double temperature
) {}

@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    private final Map<String, ChatClient> clients;

    public ChatController(
        @Qualifier("ollamaChatClient") ChatClient ollamaChatClient,
        @Qualifier("openAiChatClient") ChatClient openAiChatClient
    ) {
        this.clients = Map.of(
            "ollama", ollamaChatClient,
            "openai", openAiChatClient
        );
    }

    @PostMapping
    public String chat(@RequestBody ChatRequest request) {
        String provider = (request.provider() != null) ? request.provider().toLowerCase() : "ollama";
        ChatClient client = clients.getOrDefault(provider, clients.get("ollama"));

        var prompt = client.prompt().user(request.message());

        if (request.model() != null && !request.model().isBlank()) {
            prompt.options(ChatOptions.builder().model(request.model()).build());
        }

        return prompt.call().content();
    }
}
```

---

## 6. Testing Quickstart

### 1. Test local Ollama inference via cURL:
```bash
curl -X POST http://localhost:8080/api/v1/chat \
  -H "Content-Type: application/json" \
  -d '{
    "provider": "ollama",
    "model": "qwen2.5-coder:14b",
    "message": "Hello from local Ollama!"
  }'
```

### 2. Test OpenAI cloud inference via cURL:
```bash
curl -X POST http://localhost:8080/api/v1/chat \
  -H "Content-Type: application/json" \
  -d '{
    "provider": "openai",
    "model": "gpt-4o-mini",
    "message": "Hello from OpenAI!"
  }'
```

Why gpt-4o-mini Works Well Here
Very Low Pricing:

At $0.15 / 1M input tokens and $0.60 / 1M output tokens, it is roughly 94% cheaper than standard gpt-4o while maintaining solid reasoning capability.

Built-in Prompt Caching:

If your RAG system prompt or base instructions are reused across requests, OpenAI automatically caches the prefix, dropping the input cost further (down to $0.075 / 1M tokens).

Supports All Advanced Features:

It supports tool calling / function calling, structured JSON outputs (schema enforcement), vision, and has a 128k context window, which is essential when injecting document chunks during RAG.
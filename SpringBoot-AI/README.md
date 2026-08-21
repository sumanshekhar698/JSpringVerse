# Stock Research Agent

A Spring AI agent that answers questions about US-listed companies from two grounded
sources: **live market data** and the company's **own SEC filings** (Form 10-K, Form 10-Q)
chunked and indexed in Postgres + pgvector.

The agent never answers a factual question from model memory. Prices come from a tool call;
filing claims come from retrieved passages and are cited. Successive editions of a filing
are versioned, so "what does the company say" reaches the current 10-K while "how has this
changed" can reach every prior one.

---

## Contents

- [Architecture](#architecture)
- [Design decisions](#design-decisions)
- [Prerequisites](#prerequisites)
- [Setup](#setup)
- [Schema management (Liquibase)](#schema-management-liquibase)
- [Embedding profiles](#embedding-profiles)
- [Ingesting filings](#ingesting-filings)
- [Versioning](#versioning)
- [Conversation memory](#conversation-memory)
- [Guardrails](#guardrails)
- [Prompts](#prompts)
- [API reference](#api-reference)
- [Agent tools](#agent-tools)
- [Actuator](#actuator)
- [Configuration reference](#configuration-reference)
- [Testing](#testing)
- [Project layout](#project-layout)
- [Known limitations](#known-limitations)

---

## Architecture

```
                          ┌──────────────────────────────────┐
   POST /api/agent/ask    │      GuardrailAdvisor            │  refuse → return, no model call
   ─────────────────────► │  injection / advice / length     │
                          └───────────────┬──────────────────┘
                                          ▼
                          ┌──────────────────────────────────┐
                          │      stockAgentChatClient        │
                          │  system prompt from .st resource │
                          └───────────────┬──────────────────┘
                                          │ model chooses tools
                          ┌───────────────┴────────────────┐
                          ▼                                ▼
                ┌──────────────────┐            ┌────────────────────┐
                │   StockTools     │            │   FilingTools      │
                │ quote / history  │            │ searchFilings      │
                │ search / compare │            │ listAvailable      │
                └────────┬─────────┘            └─────────┬──────────┘
                         ▼                                ▼
              ┌────────────────────┐          ┌──────────────────────┐
              │ StockDataProvider  │          │ FilingSearchService  │
              │  └ YahooFinance    │          │  version resolution  │
              └────────────────────┘          └──────────┬───────────┘
                                                         │
                                    ┌────────────────────┴──────────────┐
                                    ▼                                   ▼
                          ┌──────────────────┐              ┌──────────────────────┐
                          │ filing_document  │              │ filing_vectors_*     │
                          │  (registry)      │◄─document_id─│  (pgvector chunks)   │
                          └──────────────────┘              └──────────────────────┘
                                    ▲
   POST /api/filings ───────────────┘
   POST /api/filings/from-url
```

### Ingestion pipeline

```
bytes ─► SHA-256 ─► [duplicate? → stop, 0 cost] ─► Tika extract ─► section split
      ─► token chunk (within section) ─► stamp metadata ─► register ─► embed ─► pgvector
```

---

## Design decisions

| Decision | Reason |
|---|---|
| **SHA-256 before parsing** | Extraction + embedding is the expensive step and the one that silently doubles retrieval hits. Hashing raw bytes (not extracted text) means a Tika upgrade cannot spuriously miss the duplicate. |
| **Version by registry, not by flag** | "Latest" resolves at query time via `DISTINCT ON` in `filing_document`. A stored `is_latest` flag on each chunk would need rewriting across hundreds of rows whenever a new filing lands — an update that is easy to get half-done. Query-time resolution cannot drift. |
| **`document_id IN (...)` filter** | One filter expresses "latest per company" across every ticker at once. An OR-chain of `(ticker AND year)` pairs would not. |
| **Retrieval is a tool, not an advisor** | An advisor searches on *every* turn, including "what's AAPL trading at" — paying for an embedding call and injecting irrelevant text. More importantly, an advisor has no parameters, so it cannot express which company, year, or **version** to search. |
| **Section-aware chunking** | A flat splitter merges the tail of *Risk Factors* into the head of *Legal Proceedings*. Splitting on `Item` boundaries first means every chunk belongs to one section and can be cited. |
| **Longest ascending run of Item numbers** | The exhibit index at the end of a 10-K cites "Item 7" and "Item 8" again. Treating those as section starts splits the financial statements under the wrong heading. Verified against Apple's FY2025 10-K. |
| **Similarity threshold calibrated, not guessed** | Measured against a real 10-K: relevant queries score **0.38-0.48**, irrelevant ones **0.06-0.14**. 0.28 sits in that gap. An intuition-based 0.55 sat above *every* achievable score and returned zero hits for every question — while the agent politely refused to answer, which looks like working grounding rather than broken search. |
| **Tools return errors as strings** | A thrown exception aborts the tool-calling loop. A returned sentence lets the model recover — typically by resolving the ticker and retrying. |
| **Guardrails are patterns, not a second LLM** | A classifier call would double latency and cost on every turn, and would itself be susceptible to the injection it is meant to catch. This is a cheap first layer, not a complete defence. |
| **Output guardrail only appends** | A guardrail that silently rewrites an answer is worse than one that annotates it — the caller can no longer tell what the model actually said. |
| **Liquibase owns all DDL** | Including the pgvector tables. `initialize-schema=false`, `schema-validation=true`, so the app refuses to start if the changelog and configured dimensions disagree. |
| **Prompts in `.st` resources** | Prompts change far more often than wiring code and are reviewed by people who should not need to read Java. A prompt change shows up as a text diff. |
| **`StockDataProvider` interface** | Yahoo's endpoints are unofficial. Swapping in Alpha Vantage, Finnhub or a paid feed is one class plus a config flip. |

---

## Prerequisites

1. **Java 25**
2. **Postgres with the `vector` extension**
   ```sql
   SELECT * FROM pg_available_extensions WHERE name = 'vector';
   ```
3. **An OpenAI API key** — chat model, plus embeddings unless using the Ollama profile.
4. **Ollama** — only for the `embed-ollama` profile or the `/ai/ollama` sandbox.

### Getting pgvector on Windows

There is **no installer**. Specifically, EDB's Application StackBuilder does *not* offer it —
its catalog (`postgresql.org/applications-v2.xml`) has PostGIS, pgAgent, pgBouncer and
psqlODBC, but no pgvector. The two working options are:

**Docker (used by this project):**

```powershell
docker run -d --name pgvector -e POSTGRES_PASSWORD=<password> `
  -p 5433:5432 pgvector/pgvector:pg18
```

Runs alongside a local PostgreSQL on 5432 without touching it. This is why
`spring.datasource.url` defaults to port **5433**.

**Build from source into an existing install** — needs Visual Studio Build Tools, then from
an *x64 Native Tools Command Prompt* run as Administrator:

```cmd
set "PGROOT=C:\Program Files\PostgreSQL\18"
git clone --branch v0.8.5 https://github.com/pgvector/pgvector.git
cd pgvector
nmake /F Makefile.win
nmake /F Makefile.win install
```

Market data needs nothing: the Yahoo Finance provider requires no key, no signup and no
payment method.

---

## Setup

### 1. Create the database

```bash
psql -U postgres -h localhost -p 5433 -c "CREATE DATABASE jspringverse;"
```

That is all the manual SQL there is. Liquibase creates the extensions, tables and indexes on
first start.

### 2. Environment

```powershell
$env:OPENAI_API_KEY     = "sk-..."
$env:POSTGRES_URL       = "jdbc:postgresql://localhost:5433/jspringverse"
$env:POSTGRES_USER      = "postgres"
$env:POSTGRES_PASSWORD  = "..."
```

The API key is **never** stored in `application.properties`. Startup fails immediately if
`OPENAI_API_KEY` is unset, rather than failing on the first request.

### 3. Run

```powershell
.\mvnw.cmd spring-boot:run
```

---

## Schema management (Liquibase)

All DDL lives in `src/main/resources/db/changelog/`:

| Changeset | Creates |
|---|---|
| `001-extensions.sql` | `vector`, `uuid-ossp`. Halts with a clear message if pgvector is not available on the server. |
| `002-filing-registry.sql` | `filing_document` + lookup/recency indexes |
| `003-vector-tables.sql` | `filing_vectors_openai` (1536), `filing_vectors_ollama` (768), each with an HNSW cosine index |
| `004-metadata-indexes.sql` | Expression indexes on `metadata->>'document_id'`, `ticker`, `form_type`, `fiscal_year` |
| `005-chat-memory.sql` | `SPRING_AI_CHAT_MEMORY` + its two lookup indexes, mirroring what Spring AI would create |

Both vector tables are created regardless of the active profile — an empty table costs
nothing, and it keeps the schema independent of which profile happens to start first.

The HNSW indexes are built on empty tables, which is instant. Building one after a large
ingest is not.

Inspect applied changesets at `/actuator/liquibase`.

> **Dependency note.** This uses `spring-boot-starter-liquibase`, **not** bare
> `liquibase-core`. Spring Boot 4 split autoconfiguration out of `spring-boot-autoconfigure`
> into per-technology modules, so `liquibase-core` alone puts the library on the classpath
> with no `LiquibaseAutoConfiguration` behind it — `spring.liquibase.*` binds to nothing and
> no migration ever runs, silently. The same applies to Flyway and the other integrations.

### `filing_document`

The registry that makes versioning and deduplication possible.

| Column | Purpose |
|---|---|
| `content_sha256` | Duplicate-detection key |
| `embedding_model`, `vector_table` | Which profile produced these vectors |
| `ticker`, `company_name`, `form_type` | Identity |
| `fiscal_year`, `fiscal_period`, `period_end_date`, `filing_date` | Recency ordering |
| `chunk_count`, `section_count`, `extracted_chars`, `byte_size` | Ingestion audit |
| `source_filename`, `source_uri`, `ingested_at` | Provenance |

`UNIQUE (content_sha256, embedding_model)` — the dedup guarantee is a database constraint,
not just application logic. The model is part of the key because the same PDF legitimately
exists twice when both embedding profiles are in use.

---

## Embedding profiles

A pgvector column's dimension is fixed at table creation, so each model gets its own table.
Switching profiles means re-ingesting.

| Profile | Model | Dims | Table |
|---|---|---|---|
| `embed-openai` *(default)* | `text-embedding-3-small` | 1536 | `filing_vectors_openai` |
| `embed-ollama` | `nomic-embed-text` | 768 | `filing_vectors_ollama` |

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=embed-ollama"
```

OpenAI embeddings cost ~$0.02 / 1M tokens; a full 10-K is ~150k tokens, well under a cent.
Ollama runs fully local — nothing leaves the machine, which matters for confidential or
pre-release filings. Requires `ollama pull nomic-embed-text`.

---

## Ingesting filings

### Filename convention

Name the file `<Company>_<FormType>-<Period>.<ext>` and metadata is derived automatically:

| Filename | Company | Form | Fiscal year | Period |
|---|---|---|---|---|
| `Apple_10K-2025.pdf` | Apple | 10-K | 2025 | — |
| `AAPL_10-K_2024.pdf` | AAPL | 10-K | 2024 | — |
| `Apple_10Q-2025-Q3.pdf` | Apple | 10-Q | 2025 | Q3 |
| `Microsoft_10K-2024-06-30.pdf` | Microsoft | 10-K | 2024 | ends 2024-06-30 |
| `Berkshire_Hathaway_10K-2024.pdf` | Berkshire Hathaway | 10-K | 2024 | — |

Separators (`_`, `-`, space) are interchangeable.

**Precedence is always: explicit request parameter → filename → lookup.** A filename is
metadata a human typed and is frequently wrong; an explicit parameter is a deliberate
assertion. A wrong fiscal year silently corrupts every future version comparison, so
explicit always wins.

The **ticker** is resolved from the company name via the market data provider's symbol
search when not given explicitly. That lookup is best-effort — if it fails, ingestion asks
you to pass `ticker=` rather than guessing.

### Upload

```bash
# Metadata entirely from the filename
curl -F file=@Apple_10K-2025.pdf http://localhost:8081/api/filings

# Explicit metadata, for a badly named file
curl -F file=@scan001.pdf \
     -F ticker=AAPL -F companyName="Apple Inc." \
     -F formType=10-K -F fiscalYear=2025 \
     -F periodEndDate=2025-09-27 \
     http://localhost:8081/api/filings
```

Accepts PDF, HTML (EDGAR) and DOCX. `formType` accepts `10-K`, `10-Q`, `8-K`, `DEF 14A`.

### From a URL

```bash
curl -X POST http://localhost:8081/api/filings/from-url \
  -H "Content-Type: application/json" \
  -d '{"url":"https://www.sec.gov/Archives/edgar/data/320193/.../aapl-20250927.htm",
       "filename":"Apple_10K-2025.htm"}'
```

Only `http`/`https` are accepted — without that check a caller could make the server read
`file://` or probe internal services. Fetches are capped at 128 MB. The request sends a
descriptive `User-Agent`, which SEC EDGAR's access policy requires.

### Response

```json
{
  "status": "INGESTED",
  "documentId": "6f1c…",
  "contentSha256": "3eb270b2…",
  "citation": "AAPL 10-K FY2025",
  "latestVersion": true,
  "sectionsDetected": 14,
  "chunksStored": 91,
  "extractedCharacters": 291299,
  "sections": ["Cover and front matter", "Item 1. Business", "Item 1A. Risk Factors", "..."]
}
```

`sections` is the fastest way to spot a PDF that extracted badly. A scanned filing yields
one `Full document` section or none at all.

### Duplicate handling

Re-uploading identical bytes returns `status: DUPLICATE_SKIPPED` with no parsing and no
embedding:

```json
{
  "status": "DUPLICATE_SKIPPED",
  "documentId": "6f1c…",
  "message": "Identical content was already ingested on 2026-07-27T… as AAPL 10-K FY2025.
              Nothing was re-embedded. Pass force=true to replace it."
}
```

Pass `force=true` to delete the prior copy and re-ingest.

Note the distinction: **duplicate content** is rejected; **a new version** is not. Uploading
the FY2026 10-K alongside the FY2025 one keeps both.

---

## Versioning

Every filing exists in successive editions that contradict each other by design — a 2023
10-K and a 2025 10-K describe different businesses. Blending them is the single most
misleading failure mode of filing RAG.

So **`LATEST` is the default and history is opt-in**:

| `versionScope` | Behaviour |
|---|---|
| `LATEST` *(default)* | Only the newest filing of each form type per company |
| `ALL` | Every version ever ingested |

Passing an explicit `fiscalYear` is itself a version choice, so it implies `ALL` — otherwise
asking for FY2023 while scoped to the latest filing would return nothing.

**Recency ordering** prefers the actual reporting period over the ingestion timestamp, so
uploading an old 10-K today does not make it look current:

```
COALESCE(period_end_date, filing_date, make_date(fiscal_year, 12, 31)) DESC,
fiscal_period DESC,
ingested_at DESC
```

The agent is told in its system prompt to pass `versionScope=ALL` when a question compares
across years or names a past period, and to state which fiscal year each statement came
from. The search tool also reports which documents it actually searched, so the model can
tell the user.

```bash
curl "http://localhost:8081/api/filings/versions?ticker=AAPL&formType=10-K"
```

---

## Conversation memory

Follow-ups work. Pass the `conversationId` from a response back on the next request:

```bash
# First turn — omit conversationId, get one back
curl -X POST http://localhost:8081/api/agent/ask -H "Content-Type: application/json" \
  -d '{"question":"What is Apple trading at?"}'
# -> { "answer": "...", "conversationId": "3f1c8a52-...", ... }

# Follow-up — "their" resolves because the prior turn is replayed
curl -X POST http://localhost:8081/api/agent/ask -H "Content-Type: application/json" \
  -d '{"question":"And what did their latest 10-K say about competition?",
       "conversationId":"3f1c8a52-..."}'
```

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/agent/conversations` | List conversation ids |
| `GET` | `/api/agent/conversations/{id}` | Read the retained transcript |
| `DELETE` | `/api/agent/conversations/{id}` | Forget a conversation |

### How it is wired

`MessageChatMemoryAdvisor` over `MessageWindowChatMemory`, backed by
`JdbcChatMemoryRepository` writing to `SPRING_AI_CHAT_MEMORY` in the same Postgres.

**JDBC rather than in-memory** because an in-memory window silently loses every conversation
on each deploy and breaks the moment a second instance sits behind a load balancer — the
follow-up lands on the pod that has never seen the thread.

**A window, not the full history.** The transcript is replayed into the prompt on *every*
turn, so unbounded history means cost and latency growing without limit until the context
window is exceeded outright. `jspringverse.memory.max-messages` (40) caps it. Note that is
counted in **messages, not turns**, and this agent's turns are message-heavy: one question
that resolves a ticker then fetches a quote produces a user message, two tool-call messages,
two tool-result messages and an answer. 40 is roughly a dozen such exchanges.

**Liquibase owns the table** (changeset 005), and
`spring.ai.chat.memory.repository.jdbc.initialize-schema=never` stops the repository
creating its own. Two schema initialisers racing on one database is how a deploy ends up
half-migrated.

### Advisor ordering

The chain order is load-bearing, not incidental:

```
Guardrail  →  Memory  →  [Retrieval]  →  Logging
```

**Guardrail before memory** is the one that matters. If a refused question were written to
history, the refusal *and the offending text* would be replayed into the prompt on every
subsequent turn of that conversation — wasting context and handing an attacker persistence
across turns. Blocking first also means a refusal never reaches retrieval or the model, so
it costs nothing.

**Retrieval after memory** (on the document-only client) puts the retrieved passages closest
to the question rather than buried under replayed history.

### A note on follow-ups and retrieval

The tool-based agent handles *"and what about their risks?"* well: the model sees the prior
turns and formulates a specific `searchFilings` query itself.

`/api/filings/ask` handles it worse. `QuestionAnswerAdvisor` embeds the raw user text, and
*"and what about their risks?"* is a poor search string standing alone. This is another
argument for retrieval-as-a-tool over retrieval-as-an-advisor — the model gets to resolve
the reference before searching.

---

## Guardrails

Applied by `GuardrailAdvisor` at the highest advisor precedence, so a refused request
short-circuits before retrieval, tool calling or the model call — a refused question costs
nothing.

### Input

| Category | Blocks |
|---|---|
| `EMPTY_INPUT` | Blank questions |
| `INPUT_TOO_LONG` | Over `max-question-length` (4000). Long inputs are the usual carrier for injection payloads |
| `PROMPT_INJECTION` | "ignore all previous instructions", "reveal your system prompt", "you are now…", `<system>` tags |
| `INVESTMENT_ADVICE` | "should I buy X", "is X a good investment", "will X go up", "price target", "how much should I invest" |

A blocked request returns **HTTP 200** with the refusal text from
`prompts/guardrail-refusal.st` plus machine-readable flags:

```json
{ "answer": "I can't help with that request…", "guardrailBlocked": true,
  "guardrailCategory": "INVESTMENT_ADVICE", "elapsedMillis": 2 }
```

200-with-a-flag rather than 4xx because a refusal is a valid conversational turn, and
streaming clients see it arrive through the same channel as any other answer.

The reason a request was blocked is **logged but never returned** — telling a prospective
attacker exactly which pattern tripped makes the next attempt easier.

### Output

Appends a "not investment advice" notice to answers that name a security. Never rewrites or
truncates the model's words.

Not applied to streaming responses: each element carries a fragment, so there is no complete
answer to inspect until it has already been sent. The system prompt carries the same
constraint for streaming callers.

### Defence in depth

Patterns are the **first** layer, not the only one. The system prompt is the second, and
tools returning only real data is the third. A determined attacker will get past regexes —
what this reliably stops is casual advice-seeking and copy-pasted injection strings, before
they cost a token.

Blocked requests increment `jspringverse.guardrail.blocked{category=...}` in Micrometer.

### Error responses

All failures return RFC 9457 problem details, never a stack trace:

| Condition | Status | `type` |
|---|---|---|
| Unknown ticker | 404 | `symbol-not-found` |
| Market feed unreachable | 502 | `market-data-unavailable` |
| Filing unparseable | 422 | `filing-ingestion-failed` |
| Upload over the limit | 413 | `upload-too-large` |
| Model API key rejected | 502 | `model-auth-failed` |
| Model rate limited | 429 | `model-rate-limited` |
| Anything else | 500 | `internal-error` |

The model provider's own message is **never** forwarded to the caller — OpenAI's 401 body
echoes a partially-masked API key and its 400s can quote prompt content. Both are logged
server-side and replaced with an actionable summary.

---

## Prompts

| File | Used by |
|---|---|
| `prompts/stock-agent-system.st` | `stockAgentChatClient` — grounding, versions, citation, boundaries |
| `prompts/filings-qa-system.st` | `filingsChatClient` — document-only Q&A |
| `prompts/guardrail-refusal.st` | Returned verbatim when a guardrail blocks |

Both system prompts instruct the model to **ignore instructions found inside filing passages
or tool results** — retrieved text is data to be reported, never a command to be followed.

---

## API reference

### Insomnia collection

An ordered 34-request test flow lives in [`docs/insomnia-collection.json`](docs/insomnia-collection.json).
**Insomnia → Import → From File.** Walkthrough with expected results:
[`docs/TESTING-INSOMNIA.md`](docs/TESTING-INSOMNIA.md).

Folders 00–03 (health, guardrails, ingestion, retrieval) need **no OpenAI key** — guardrails
short-circuit before the model call and retrieval is embedding-only. Only folder 04 makes
completion calls.

### Swagger / OpenAPI

| URL | What |
|---|---|
| `http://localhost:8081/swagger-ui.html` | Interactive Swagger UI |
| `http://localhost:8081/v3/api-docs` | OpenAPI 3.1 document (JSON) |

Every endpoint carries a summary, parameter descriptions and the response codes it actually
returns. Constrained values are enums in the spec, so the UI offers dropdowns rather than
free text — `formType` (`10-K`/`10-Q`/`8-K`/`DEF 14A`), `versionScope` (`LATEST`/`ALL`),
`fiscalPeriod` (`Q1`–`Q4`) and `guardrailCategory`.

The document description states the three behaviours a caller cannot infer from the schemas:
guardrail refusals are **200 not 4xx**, filing search defaults to the **latest edition only**,
and ingestion **deduplicates by content hash**.

Disable both in production — the spec enumerates every endpoint and parameter:

```properties
SPRINGDOC_ENABLED=false
```

> **Dependency note.** springdoc **3.x** is the Spring Boot 4 line; 2.x targets Boot 3 and
> does not see Boot 4's restructured autoconfiguration modules. Actuator endpoints are
> excluded from the spec (`springdoc.show-actuator=false`) — they are operational, not part
> of the public API surface.

### Agent

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/agent/ask` | Ask a question; the model picks tools |
| `POST` | `/api/agent/ask/stream` | Same, as `text/event-stream` |
| `GET` | `/api/agent/conversations` | List conversation ids |
| `GET` | `/api/agent/conversations/{id}` | Read a retained transcript |
| `DELETE` | `/api/agent/conversations/{id}` | Forget a conversation |

```bash
curl -X POST http://localhost:8081/api/agent/ask \
  -H "Content-Type: application/json" \
  -d '{"question":"What is Nvidia trading at, and what supply chain risks did their latest 10-K disclose?"}'
```

The agent resolves `Nvidia` → `NVDA`, calls the quote tool for the price, searches only the
current 10-K for the risk disclosure, and cites the section.

### Filings

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/filings` | Multipart upload |
| `POST` | `/api/filings/from-url` | Server-side fetch (EDGAR) |
| `GET` | `/api/filings` | Inventory, with `currentVersion` per document |
| `GET` | `/api/filings/versions` | Fiscal years available for a ticker/form |
| `GET` | `/api/filings/search` | Raw retrieval, no model call — for tuning `topK` and threshold |
| `POST` | `/api/filings/ask` | Document-only Q&A |
| `DELETE` | `/api/filings/{documentId}` | Remove a filing and all its chunks |

### Sandbox

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/ai/ollama/chat` | Local-model tool-calling sandbox (crypto price tool) |

---

## Agent tools

| Tool | Purpose |
|---|---|
| `getStockQuote` | Current price, day range, 52-week range, volume |
| `getStockPriceHistory` | Daily OHLCV over `1d`–`max`, with period return and sampled bars |
| `searchStockSymbol` | Company name → ticker |
| `compareStockQuotes` | Several tickers at once; one bad ticker does not void the rest |
| `searchFilings` | Semantic search, scopable by ticker / form / fiscal year / **version** |
| `listAvailableFilings` | What is ingested, and which edition is current |

---

## Actuator

```
/actuator/health      overall + components
/actuator/health/readiness    db + filingCorpus
/actuator/info        app, java, os, build
/actuator/metrics     includes jspringverse.guardrail.blocked
/actuator/liquibase   applied changesets
/actuator/loggers     runtime log level changes
/actuator/env         resolved configuration
/actuator/configprops @ConfigurationProperties values
```

### Custom health indicators

**`marketData`** — probes `AAPL` through the configured provider. Worth a dedicated
indicator because the default feed is unofficial: when Yahoo changes shape or throttles,
the app keeps starting and keeps serving, it just stops being able to quote a price.
Without this the failure is invisible until a user reports it. Reports `DOWN` rather than
throwing, so the rest of the health report still renders.

**`filingCorpus`** — chunk counts, indexed documents, and which filings are current. An
agent whose vector table is empty answers "the filing does not cover this" to every document
question — a plausible-sounding response that looks like normal operation. Reports `UP` with
`empty: true` when nothing is ingested: a fresh deployment is correctly configured, not
broken.

`marketData` is deliberately **excluded from the readiness group** — a third-party feed
being down should not pull the instance out of the load balancer when filing Q&A still works.

---

## Configuration reference

| Property | Default | Purpose |
|---|---|---|
| `server.port` | `8081` | Not 8080 — commonly already taken by another local JVM. Override with `SERVER_PORT`. |
| `jspringverse.rag.chunk-size-tokens` | `800` | Large enough for a risk factor to survive intact, small enough that several chunks fit alongside the question |
| `jspringverse.rag.top-k` | `8` | Chunks retrieved per query |
| `jspringverse.rag.similarity-threshold` | `0.28` | Below this, return nothing rather than the least-bad chunk. **Calibrated** — see below |
| `jspringverse.rag.embedding-batch-size` | `100` | Bounds payload size and memory during a large ingest |
| `jspringverse.market.provider` | `yahoo` | Which `StockDataProvider` to use |
| `jspringverse.market.quote-cache-ttl` | `30s` | Deduplicates repeat quote calls within one agent turn |
| `jspringverse.market.user-agent` | browser UA | **Required** — Yahoo returns 403 to the default JDK user agent |
| `jspringverse.memory.enabled` | `true` | Master switch; removes the memory advisor entirely |
| `jspringverse.memory.max-messages` | `40` | Retained messages per conversation. Messages, not turns — a tool-calling turn produces ~6 |
| `jspringverse.guardrails.enabled` | `true` | Master switch; removes the advisor entirely |
| `jspringverse.guardrails.max-question-length` | `4000` | Input ceiling |
| `jspringverse.guardrails.block-investment-advice` | `true` | Refuse buy/sell/hold and predictions |
| `jspringverse.guardrails.block-prompt-injection` | `true` | Refuse system-prompt overrides |
| `jspringverse.guardrails.append-disclaimer` | `true` | Annotate answers naming securities |
| `spring.servlet.multipart.max-file-size` | `64MB` | Spring's 1 MB default rejects real 10-K PDFs |
| `spring.ai.vectorstore.pgvector.initialize-schema` | `false` | Liquibase owns DDL |
| `spring.ai.vectorstore.pgvector.schema-validation` | `true` | Refuse to start on a dimension mismatch |

---

## Testing

```powershell
.\mvnw.cmd test          # unit tests only: no network, no DB, no keys

# Everything, including the opt-in integration tests:
$env:MARKET_LIVE_TEST  = "true"
$env:POSTGRES_TEST     = "true"
$env:POSTGRES_URL      = "jdbc:postgresql://localhost:5433/jspringverse"
$env:POSTGRES_PASSWORD = "..."
$env:OPENAI_API_KEY    = "sk-..."   # any non-empty value works for contextLoads
.\mvnw.cmd test
```

The integration tests are opt-in via environment variable so a plain `mvnw test` never
depends on Docker, network access or credentials.

| Suite | Covers |
|---|---|
| `FilingSectionSplitterTest` | Item detection, TOC rows, back-references, sub-item ordering |
| `RealFilingExtractionTest` | Extract → section → chunk against the real `Apple_10K-2025.pdf` |
| `FilingNameParserTest` | Every filename shape in the convention table |
| `ContentHasherTest` | Fixed SHA-256 vector, so a refactor cannot invalidate existing dedup records |
| `GuardrailServiceTest` | Injection and advice patterns, plus false-positive checks on legitimate questions |
| `StockToolsTest` | Errors returned not thrown, null tolerance, history sampling |
| `YahooFinanceLiveTest` | Opt-in contract test against the real feed |
| `LiquibaseChangelogTest` | Applies the changelog; asserts exact column layout, vector dimensions, the unique constraint and idempotency |
| `FilingVersioningSqlTest` | Version resolution against real Postgres — including that a backfilled older 10-K does **not** become the current one. Runs in a throwaway schema, needs no pgvector |
| `ChatMemoryPersistenceTest` | Round-trip, cross-instance persistence, window eviction reaching the table, conversation isolation |
| `ConversationServiceTest` | Id generation and rejection of malformed/oversized/injection-carrying ids |
| `SpringBootAiApplicationTests` | Full context: every bean, prompt resource, and PgVectorStore schema validation against the migrated tables |

The live market test is opt-in so a normal build never depends on Yahoo being up — but it is
the only thing that will tell you the unofficial feed changed shape. **Run it on a schedule.**

---

## Project layout

```
src/main/java/dev/codecounty/springai/
├── actuator/     MarketDataHealthIndicator, FilingCorpusHealthIndicator
├── config/       StockAgentConfig (agent wiring), ChatClientConfig (Ollama sandbox)
├── controllers/  StockAgentController, FilingController, GlobalExceptionHandler
├── filings/      Ingestion, sectioning, registry, versioning, search
├── guardrails/   GuardrailService, GuardrailAdvisor, verdicts
├── memory/       ChatMemoryProperties, ConversationService
├── market/       StockDataProvider + Yahoo implementation, DTOs, TTL cache
└── tools/        StockTools, FilingTools, CryptoTools  ← the agent's @Tool surface

src/main/resources/
├── prompts/      *.st system prompts
├── db/changelog/ Liquibase master + changesets
└── application*.properties
```

---

## Known limitations

- **Yahoo Finance is unofficial.** No SLA, no support, subject to throttling. For anything
  load-bearing, add a keyed provider behind `StockDataProvider` (Alpha Vantage and Finnhub
  are both free with an email address and no card).
- **No auth on any endpoint.** Ingestion, deletion and query are all open. Add Spring
  Security before this leaves localhost.
- **Conversation ids are unauthenticated.** The id is the only thing separating one
  caller's history from another's, and it comes from the request body — anyone who guesses
  or obtains an id can read that transcript via `GET /api/agent/conversations/{id}`, and
  `GET /api/agent/conversations` lists every id on the instance. Tie conversations to an
  authenticated principal before this is reachable by more than one user.
- **Memory is a window, not an archive.** Only the last 40 messages are retained; older ones
  are deleted, so the transcript endpoint is not an audit log.
- **Scanned PDFs produce no text.** Tika does not OCR. Prefer the EDGAR HTML filing.
- **Exhibits attach to the last Item.** A 10-K PDF with exhibits appended attributes them to
  whichever heading precedes them (usually Item 16). The text is indexed and searchable, but
  its citation label is imprecise. Detecting the `SIGNATURES` boundary would fix it.
- **Guardrails are regex-based.** Effective against casual misuse and pasted payloads, not
  against a determined adversary. Treat as one layer of three.
- **`/api/filings/ask` searches all versions.** The advisor path has no parameters through
  which to express a version scope. Use `/api/agent/ask` when latest-only matters.
- **Uploads are buffered in memory.** The pipeline reads each document twice (hash, then
  extract), so it needs a re-readable resource. Bounded by the multipart limit; a temp-file
  resource would be the fix for much larger documents.

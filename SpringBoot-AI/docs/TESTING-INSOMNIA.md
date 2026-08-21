# Testing the Stock Research Agent with Insomnia

A 34-request collection in dependency order. Folders **00 → 03 need no OpenAI key**;
only folder 04 makes completion calls.

---

## Import

**Insomnia → Import → From File →** `SpringBoot-AI/docs/insomnia-collection.json`

You get a **Stock Research Agent** collection with a **Local** environment:

| Variable | Default |
|---|---|
| `base_url` | `http://localhost:8081` |
| `ticker` | `AAPL` |
| `filing_path` | absolute path to `Apple_10K-2025.pdf` |
| `document_id` | placeholder — you fill this in at step 02 |
| `conversation_id` | placeholder — you fill this in at step 04b |

Every request has a description explaining what to expect and what a failure means. Read
them as you go; they're the actual guide.

> Alternative: **Import → From URL →** `http://localhost:8081/v3/api-docs` generates
> requests straight from the OpenAPI spec. That gives you every endpoint but no ordering,
> no bodies and no expectations — use the file import for a first pass.

---

## Before you start

**1. Container up:**
```powershell
docker ps --filter name=pgvector      # want: Up
docker start pgvector                 # if not
```

**2. App restarted.** If it's running from before Swagger was added, restart it — otherwise
`/v3/api-docs` 500s.

**3. Port.** The collection targets **8081**. If something else holds it, either change
`base_url` or start with `SERVER_PORT=8099`.

**4. Raise Insomnia's timeout — you will hit this.** The default is **30 seconds**; a single
agent turn takes **25–50s** because the model makes several tool round trips (resolve ticker
→ quote → search filings) and then writes a long cited answer.

> **Preferences → Request → Request timeout →** `120000` (ms). `0` disables it entirely.

This is not the app being slow — the server logs `elapsedMillis` for every turn, so compare
that against what Insomnia reports before assuming a hang. Folders 00–03 all return in under
a second; only folder 04 is slow.

For an interactive feel, use `POST /api/agent/ask/stream` — tokens arrive as they're
generated instead of after the whole turn.

---

## The flow

### 00 — Health and docs

Start here. `/actuator/health` should show five components UP.

| Component | Means |
|---|---|
| `db` | pgvector container reachable on 5433 |
| `filingCorpus` | `empty: true` before you ingest |
| `marketData` | a live AAPL price — the Yahoo feed works |
| `liveness` / `readinessState` | app lifecycle |

`db` DOWN → container isn't running. `marketData` DOWN → Yahoo changed or throttled you;
everything else still works, which is why it's excluded from the readiness group.

`/actuator/liquibase` should list **9 changesets, all `EXECUTED`** (the ninth is the chat-memory table).

### 01 — Guardrails (no API key needed)

These short-circuit before any model call, so they work regardless of your key.

| Request | Expect |
|---|---|
| investment advice | **200**, `guardrailBlocked: true`, `INVESTMENT_ADVICE`, single-digit ms |
| prompt injection | **200**, `PROMPT_INJECTION`, ~0 ms |
| oversized input | **400** `validation-failed` |
| control | `guardrailBlocked: false` |

Two things worth noticing.

**200, not 4xx.** A refusal is a valid conversational turn. Branch on `guardrailBlocked`,
not on the status code.

**The control matters more than the blocks.** *"How many shares did Apple buy back last
year?"* contains "buy" but isn't soliciting a recommendation — a guardrail that blocks it is
worse than no guardrail, because it makes the agent useless for legitimate questions. If you
tighten the patterns, re-run this one.

Then `/actuator/metrics/jspringverse.guardrail.blocked` — count and category tags. Empty
before the first block is normal; Micrometer registers the counter on first increment.

### 02 — Ingest

**"Upload Apple_10K-2025.pdf"** sends only the file. Metadata comes from the filename:
`Apple_10K-2025.pdf` → company Apple, form 10-K, FY2025, with the ticker resolved from
"Apple" via symbol search.

Takes 10–60s — every chunk is embedded. Expect:

```json
{ "status": "INGESTED", "sectionsDetected": 14, "chunksStored": 91,
  "latestVersion": true,
  "sections": ["Cover and front matter", "Item 1. Business", "Item 1A. Risk Factors", "..."] }
```

**Check the `sections` list.** It's the fastest way to spot a bad extract: a single
`Full document` entry means the PDF is image-only and Tika got nothing (Tika does not OCR —
use the EDGAR HTML instead).

**Then upload the identical file again.** Expect `DUPLICATE_SKIPPED`, returned instantly,
nothing re-embedded. The key is SHA-256 of the raw bytes, enforced by a UNIQUE constraint —
not just application logic.

Duplicate *content* is rejected; a new *version* is not. A FY2026 10-K would be accepted
alongside this one.

**From the inventory response, copy `documentId` into the `document_id` environment
variable** — folder 05 needs it.

### 03 — Retrieval only (still no completion calls)

One embedding call each, no completion. The cheapest way to see what the agent would
retrieve and to tune `topK` / `similarity-threshold`.

| Query | Should land in |
|---|---|
| supply chain concentration | `Item 1A. Risk Factors` |
| revenue by segment, gross margin | `Item 7. Management's Discussion and Analysis` |
| how to bake sourdough | **nothing — `hitCount: 0`** |

The first two landing in *different* sections is section-aware chunking working: chunks never
straddle an `Item` boundary, so retrieval lands in the right part of the filing.

The third returning nothing is the **similarity threshold** working. Without it you'd get the
least-bad chunk in the corpus and the agent would answer confidently from irrelevant text.
Zero hits is the correct answer.

`documentsSearched` tells you which filings were actually in scope. With one filing ingested,
`versionScope=ALL` looks identical to `LATEST` — the difference only appears once you ingest
a second year.

### 04 — Ask the agent (needs a working key)

| Request | Verifies |
|---|---|
| Market data only | `searchStockSymbol` → `getStockQuote`, disclaimer appended |
| Filing only | Answer cited as `(AAPL 10-K FY2025, Item 1A. …)` |
| Both sources | Live price *and* filing claims in one turn, attributed separately |
| **Honest "I don't have that"** | **Refuses to answer about un-ingested Tesla** |
| Document-only endpoint | No market tools available on this path |
| Streaming | SSE; note no disclaimer is appended (nothing complete to inspect) |

**The Tesla one is the most important test in the collection.** No Tesla filing has been
ingested, so the agent must say so rather than answering from model memory. That's the
difference between a grounded agent and one that merely sounds grounded. If it answers, the
grounding has failed and nothing else here matters.

Watch the app log while these run — you'll see the tool-calling sequence.

`502 model-auth-failed` means the key is bad. Note it says exactly that and nothing more:
the provider's own message contains a partially-masked key, so it's logged and never returned.

### 04b — Conversation memory

Run the three turns **in order**, pasting `conversationId` from turn 1 into the environment.

| Turn | Question | Proves |
|---|---|---|
| 1 | "What is Apple trading at right now?" | Server mints a `conversationId` |
| 2 | "And what did **their** latest 10-K say about competition?" | Never names Apple — only answerable from replayed history |
| 3 | Refers back to the price from turn 1 | The assistant's *own* earlier answers are retained, not just yours |

If turn 2 comes back asking *"which company?"*, memory isn't working — check
`conversation_id` is set and `jspringverse.memory.enabled=true`.

Then three checks that matter more than the happy path:

**Read the transcript** (`GET /api/agent/conversations/{id}`) — exactly what gets replayed
next turn. Only the last 40 messages are kept; this is a window, not an audit log.

**Different conversation = clean slate.** The same pronoun question on a different id should
leave the agent with no idea who "their" refers to. That's conversation isolation.

**Blocked questions stay out of history.** Send *"Should I buy Apple stock?"* on the live
conversation, then re-read the transcript — the refusal must not be there. The guardrail
advisor runs *ahead* of the memory advisor precisely so a blocked question is never
persisted; otherwise the offending text would be replayed into the prompt on every later
turn of that conversation.

Finish with `DELETE /api/agent/conversations/{id}` → 204, then re-read the transcript to see
`messageCount: 0`.

> Memory is stored in Postgres, so it survives a restart. Restart the app mid-conversation
> and continue with the same id — an in-memory window would have lost it.

### 05 — Cleanup

`DELETE /api/filings/{document_id}` → **204**. Removes the registry row and every chunk
referencing it. If it was the current edition, the next-newest becomes current
automatically — nothing to update by hand, because "latest" is resolved at query time.

### 06 — Sandbox

Local Ollama with a crypto tool. Needs the Ollama daemon. Not part of the agent — it's for
checking tool descriptions without paying for hosted inference.

---

## Going further: prove versioning

Everything above uses one filing, so version resolution is invisible. To actually see it:

1. Download a second Apple 10-K (FY2024) from EDGAR.
2. Rename it `Apple_10K-2024.pdf` and upload.
3. `GET /api/filings` → two filings, only FY2025 marked `currentVersion: true`.
4. `GET /api/filings/versions?ticker=AAPL` → `fiscalYears: [2025, 2024]`.
5. Compare `documentsSearched` between `versionScope=LATEST` and `ALL`.
6. Ask the agent *"How did Apple's risk disclosures change between FY2024 and FY2025?"* —
   it should pass `versionScope=ALL` and state which year each statement came from.

The ordering rule prefers the **reporting period** over upload time, so backfilling an older
filing never makes it look current. That's covered by `FilingVersioningSqlTest`.

---

## Troubleshooting

| Symptom | Cause |
|---|---|
| Connection refused | App not running, or wrong `base_url` port |
| `/v3/api-docs` 500 | App running from a build before Swagger was added — restart it |
| health `db` DOWN | `docker start pgvector` |
| Upload 422, "no ticker" | Filename off-convention and no `ticker` param — add one |
| Upload 422, "image-only" | Scanned PDF; Tika does not OCR. Use the EDGAR HTML |
| Upload 413 | Over 64 MB — raise `spring.servlet.multipart.max-file-size` |
| Agent 502 `model-auth-failed` | `OPENAI_API_KEY` invalid or expired |
| **Insomnia "timeout reached" on `/api/agent/ask`** | **Client timeout, not a hang. A turn takes 25-50s. Raise Preferences → Request → Request timeout to 120000ms** |
| Agent 429 | Provider rate limit; retry |
| Search returns 0 for a fair query | Threshold too high. Real query-to-chunk scores run **0.38-0.48**, not 0.7+. Check the `score` on `/api/filings/search` hits and set `jspringverse.rag.similarity-threshold` below the lowest one you want kept |
| Everything blocked | Check `jspringverse.guardrails.*`; set `enabled=false` to isolate |

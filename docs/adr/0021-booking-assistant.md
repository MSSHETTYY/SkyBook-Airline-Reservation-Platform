# ADR-0021: Booking assistant with Spring AI and a local LLM (optional)

- **Status:** Accepted
- **Date:** 2026-11-09
- **Deciders:** SkyBook architecture

## Context

Customers ask the same questions repeatedly: which flights are there on a date, what a cancellation would
refund, and what state their booking is in. A conversational assistant can answer these. Introducing an
LLM brings new risks, though:

- answers that sound right but are invented (hallucination);
- prompt injection that makes the model act outside its remit;
- leakage of other customers' data;
- cost and latency.

The project must stay free and run locally.

## Decision

- **A separate, optional service `booking-assistant`.** It uses Spring AI 2.0, which is built for Spring
  Boot 4, and its `ChatClient`.
- **A local model** served by **Ollama**: `llama3.2:3b` by default, which needs about 2 GB of RAM and
  supports tool calling. It is configurable with `OLLAMA_MODEL`. No data leaves the machine, and there are
  no API costs.
- **Grounding through tools, not training.** The model answers flight questions only through:
  - `searchFlights`, which calls inventory's public API v2;
  - `getMyBooking`, which calls booking-service.

  The system prompt forbids inventing flights or prices, and it states the fare rules exactly as the
  domain implements them (`RefundPolicy`, `ChangeFeePolicy`).
- **Read-only by construction.** No tool books, pays, changes, cancels or refunds. A prompt injection can
  at worst produce a wrong sentence; it cannot change state. The customer acts in the portal, where every
  action has its normal authorization, idempotency and audit.
- **The caller's own token.** `getMyBooking` calls booking-service with the caller's `Authorization`
  header, passed through Spring AI's tool context and never shown to the model. booking-service enforces
  ownership exactly as for the web UI. Tool results exclude names, dates of birth and e-mail addresses.
- **Untrusted tool arguments.** Arguments produced by the model are validated (IATA codes, ISO dates,
  booking reference pattern, passenger bounds) before any HTTP call. Failures come back as text the model
  can relay.
- **Conversation memory** is a window of the last 12 messages, keyed by *caller subject + conversation
  id*, so a guessed conversation id never exposes another customer's history. It is in memory for now,
  so the service runs as a single replica.
- Operational limits:
  - tool calls time out after 3 s;
  - the gateway allows 60 s per assistant request (CPU inference);
  - model failures become `503 ASSISTANT_UNAVAILABLE` problems.

## Alternatives considered

| Option | Pros | Cons |
|--------|------|------|
| Hosted model (Claude, GPT) | Much better reasoning and tool use, low latency | Costs money and sends customer questions to a third party; the brief requires free and local. Swapping is a configuration change with Spring AI |
| RAG over documentation (vector store) | Good for long policy documents | The fare rules are short and precise; stating them in the prompt is exact and cheaper |
| Write tools (book or cancel on request) | More "magic" | Large prompt-injection blast radius; would duplicate the portal's flows with weaker UX and confirmation |
| Assistant inside booking-service | One deployable fewer | Couples a slow, failure-prone dependency (the LLM) to the core booking service |

## Consequences

- The core platform is unaffected when the assistant or Ollama is down; it is optional in Compose
  (profile `ai`) and in Helm (`services.booking-assistant.enabled`).
- A 3B model is modest: answers can be clumsy and tool calls occasionally wrong. Grounding and the
  read-only design bound the damage, and a stronger or hosted model can be configured without code
  changes.
- Tests run without a model, using a fake `ChatModel` that records prompts. They cover authentication,
  validation, per-caller memory isolation, the system rules and the outage path. Answer quality is not
  covered by automated tests; an evaluation set is follow-up work.
- Follow-ups:
  - memory in Redis, so the service can scale out;
  - a chat panel in the web UI;
  - an evaluation suite of representative questions with expected tool calls.

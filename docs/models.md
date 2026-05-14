# LLM-модели: справочник оператора

Живой реестр всех моделей, через которые маршрутизируется AI-Workflow. Используй его чтобы:

- Понять, **какая модель сейчас активна** на конкретном блоке для конкретного проекта.
- Выбрать модель/tier при создании нового блока или pipeline.
- Диагностировать **плохое качество вывода** (не та модель / не тот провайдер).
- Диагностировать **"модель не видит файлы"** (см. § 5 — это почти всегда контекст, не модель).

Документ обновляется при каждом изменении `ModelPresetResolver` / `Models.java` / sampling-кода и при каждой подтверждённой регрессии — см. § 7.

Связанные документы: [`docs/provider-switching.md`](provider-switching.md) (как переключить провайдер за run), [`CLAUDE.md`](../CLAUDE.md) → раздел "LLM routing & model presets" (архитектурный контекст).

---

## 1. TL;DR

Текущие defaults по провайдерам (источник: [`ModelPresetResolver.java`](../workflow-core/src/main/java/com/workflow/llm/ModelPresetResolver.java)):

| Провайдер | smart | flash | fast | reasoning | cheap |
|---|---|---|---|---|---|
| **OpenRouter** | `z-ai/glm-4.6` | `z-ai/glm-4.7-flash` | `google/gemini-2.5-flash-lite` | `google/gemini-2.5-pro` | `openai/gpt-4o-mini` |
| **CLAUDE_CODE_CLI** | `claude-sonnet-4-6` | `claude-haiku-4-5` | `claude-haiku-4-5` | `claude-opus-4-7` | `claude-haiku-4-5` |
| **Ollama** (все tier'ы) | `mychen76/qwen3_cline_roocode:8b` | ↑ | ↑ | ↑ | ↑ |
| **vLLM** (все tier'ы) | `Qwen/Qwen3-4B-AWQ` | ↑ | ↑ | ↑ | ↑ |

> ### ⚠ Главное предупреждение про качество кода
>
> **vLLM-стек сейчас хостит `Qwen/Qwen3-4B-AWQ` — это 4-миллиардная модель.** Она объективно слабее всех остальных опций для codegen, особенно на больших файлах и сложных задачах. Если жалуешься на качество вывода и проект сидит на `defaultProvider=VLLM` — **переключи провайдер на OpenRouter или CLAUDE_CODE_CLI ДО того, как править промпт**. Скорее всего проблема не в промпте.
>
> Альтернативы:
> - Поднять `Qwen/Qwen3-8B-AWQ` (нужно ≥12 GB VRAM, иначе vLLM crash-loop'нется при старте, см. `Models.java:37-40`).
> - Переключиться на OpenRouter — `z-ai/glm-4.6` оператор-валидирован для WarCard и стоит копейки.
> - Использовать CLAUDE_CODE_CLI — биллинг под Anthropic Max, не за запрос.

**"Модель не видит файлы / создаёт не там"** → не спеши менять модель, иди в § 5.

---

## 2. Routing matrix и как переопределить

### 2.1 Как резолвится модель

1. **YAML блока:** `agent.tier: smart` или `agent.model: "z-ai/glm-4.6"`.
2. **Project.defaultProvider** определяет, какая из 4 карт работает (OPENROUTER → `DEFAULTS`, OLLAMA → `OLLAMA_DEFAULTS`, и т.д.). См. Settings UI → Project → Default LLM provider.
3. **Project.orchestratorModel** (если не пуст) перекрывает tier-резолв для всех блоков, использующих `LlmClient` через orchestrator-путь.
4. **`workflow.model-presets.*`** в `application.yaml` — глобальный override карты (только для OpenRouter).

### 2.2 Где смотреть актуальные значения

| Карта | Файл:строки |
|---|---|
| OpenRouter `DEFAULTS` (12 tier'ов: smart, flash, fast, reasoning, cheap + extras) | [`ModelPresetResolver.java:71-93`](../workflow-core/src/main/java/com/workflow/llm/ModelPresetResolver.java#L71-L93) |
| CLI `CLI_DEFAULTS` | [`ModelPresetResolver.java:63-69`](../workflow-core/src/main/java/com/workflow/llm/ModelPresetResolver.java#L63-L69) |
| Ollama `OLLAMA_DEFAULTS` | [`ModelPresetResolver.java:35-46`](../workflow-core/src/main/java/com/workflow/llm/ModelPresetResolver.java#L35-L46) |
| vLLM `VLLM_DEFAULTS` | [`ModelPresetResolver.java:48-61`](../workflow-core/src/main/java/com/workflow/llm/ModelPresetResolver.java#L48-L61) |
| Литеральные ID моделей | [`Models.java`](../workflow-core/src/main/java/com/workflow/llm/Models.java) |

### 2.3 Override-рецепты

**Глобально (вся OpenRouter-карта на проекте):** `workflow-core/src/main/resources/application.yaml`
```yaml
workflow:
  model-presets:
    smart: deepseek/deepseek-chat-v3-0324
    flash: google/gemini-2.0-flash-001
```

**Per-project** (UI): Settings → Project → Default LLM provider / Orchestrator Model.
- Очисти `orchestratorModel` при смене провайдера на VLLM/Ollama, иначе словишь 404 (см. полевые наблюдения, `project_warcard_provider_routing.md`).

**Per-block** (pipeline YAML):
```yaml
- id: codegen
  block: agent_with_tools
  agent:
    model: "Qwen/Qwen3-8B-AWQ"   # full ID, не tier — pass-through через resolveVllm/resolveOllama
    temperature: 0.2             # 1.0 = "unset sentinel", vLLM применит default 0.25
    max_tokens: 4096
```

Важно: `agent.model` со слешем — pass-through; голый `smart`/`flash` — резолвится через карту провайдера. См. `resolve()`/`resolveCli()`/`resolveOllama()`/`resolveVllm()` ([`ModelPresetResolver.java:98-204`](../workflow-core/src/main/java/com/workflow/llm/ModelPresetResolver.java#L98-L204)).

---

## 3. Per-block рекомендации

| Блок | Рекомендуемый tier | Почему | Минимальный размер | Если не работает |
|---|---|---|---|---|
| `analysis` | `smart` | Тяжёлая мысль: декомпозиция требования, acceptance_checklist | 8B+ | → `reasoning` |
| `clarification` | `smart` | Должна понять что НЕ ясно — нужен критический ум | 8B+ | → `reasoning` |
| `business_intake` | `flash` | Простой структурный extraction | 7B+ | → `smart` |
| `orchestrator` (mode=plan) | `reasoning` | Долгий tool-use loop + структурный план | **не Ollama-7B**: 0 tool_calls | → `smart` если reasoning слишком дорог |
| `orchestrator` (mode=review) | `smart` | Критика по structured checklist | 8B+ | → `reasoning` для сложного review |
| `code_generation` | `smart` (для критичного) или `flash` (для рутины) | Точность важнее скорости | 8B+ | → `reasoning` если делает мусор |
| `agent_with_tools` (impl) | `flash` (быстро + tool-use) | Главное — стабильный tool-call protocol | **Ollama: только cline_roocode**; **vLLM: 4B на пределе** | → `smart` |
| `agent_verify` | `smart` | Чек по acceptance_checklist с tool-use | 8B+ | → `reasoning` |
| `verify` (структурный + LLM rate) | `fast` или `cheap` | Простой LLM-score 0-10 | 7B+ | → `flash` |
| `ai_review` | `smart` | Полный code review с justification | 8B+ | → `reasoning` |

**Что делать когда видишь "плохо пишет код" в codegen/agent_with_tools:**

1. Проверь провайдер. **vLLM Qwen3-4B?** Это и есть причина. Переключи на OpenRouter или CLI.
2. На OpenRouter с `flash=glm-4.7-flash` всё ещё плохо? Промоути блок на `smart` (glm-4.6) для одной итерации — если стало лучше, оставь.
3. Всё ещё плохо? Иди в § 5 — модель скорее всего не получила контекст.
4. Контекст ок, модель ок — это уже промпт. См. `system_prompt` блока в `workflow-core/config/*.yaml`.

---

## 4. Карточки моделей

### 4.1 OpenRouter

#### `z-ai/glm-4.6` (tier=smart, default)

- **Провайдер:** OpenRouter (Z.ai, Китай)
- **Where in code:** [`Models.java:51`](../workflow-core/src/main/java/com/workflow/llm/Models.java#L51), карта `DEFAULTS` [`ModelPresetResolver.java:80`](../workflow-core/src/main/java/com/workflow/llm/ModelPresetResolver.java#L80)
- **Используется фактически:** analysis, agent_verify, orchestrator plan/review (везде где `tier: smart`)
- **Контекстное окно:** до 200K (через OpenRouter)
- **Sampling по умолчанию:** провайдер-defaults (нет hardcoded в нашем коде для OpenRouter)
- **Плюсы (полевые):** оператор-валидирован на WarCard — лучше показал себя чем claude-sonnet-4-5 при заметно меньшей цене. Доступен из России (нет geoblock'а у Z.ai). Поддерживает structured tool_calls стабильно.
- **Минусы (полевые):** иногда reasoning поверхностный на длинных task.md — для критичных решений можно временно поднять до `reasoning` (gemini-2.5-pro).
- **Когда брать:** дефолт для аналитики (analysis, clarification, review, agent_verify).
- **Когда НЕ брать:** когда задача требует длинного chain-of-thought — попробуй `reasoning` tier.

#### `z-ai/glm-4.7-flash` (tier=flash, default)

- **Провайдер:** OpenRouter (Z.ai)
- **Where in code:** [`Models.java:52`](../workflow-core/src/main/java/com/workflow/llm/Models.java#L52)
- **Используется фактически:** agent_with_tools impl блоки в WarCard `feature.yaml` (7 мест).
- **Контекстное окно:** 200K
- **Sampling:** провайдер-defaults
- **Плюсы (полевые):** дешёвый ($0.06/$0.40 за 1M tokens), быстрый, стабильный tool-call protocol через 6-10 agentic итераций.
- **Минусы (полевые):** качество impl на сложных задачах ниже чем у smart — для критичных PR стоит промоутить на `smart`.
- **Когда брать:** исполнитель в tool-use loop (Read/Edit/Bash).
- **Когда НЕ брать:** если codegen требует архитектурного мышления — лучше smart.

#### `google/gemini-2.5-flash-lite` (tier=fast)

- **Where in code:** [`Models.java:53`](../workflow-core/src/main/java/com/workflow/llm/Models.java#L53)
- **Используется фактически:** smart-detect endpoint (`/api/runs/detect`), быстрые LLM-проверки.
- **Плюсы:** быстрый и дешёвый, ОК для классификации/extraction.
- **Минусы:** для кодогенерации недостаточно глубины.
- **Когда брать:** smart-detect, classify, quick LLM-checks.
- **Когда НЕ брать:** codegen, reasoning, review.

#### `google/gemini-2.5-pro` (tier=reasoning)

- **Where in code:** [`Models.java:54,61`](../workflow-core/src/main/java/com/workflow/llm/Models.java#L54)
- **Плюсы:** глубокое reasoning, большой контекст.
- **Минусы:** дорогой; для России geoblock возможен (нужно проверить доступность OpenRouter-route'а).
- **Когда брать:** orchestrator plan для сложных задач, fallback когда `smart` мажет на reasoning.
- **Когда НЕ брать:** массовые блоки — слишком дорого.

#### `openai/gpt-4o-mini` (tier=cheap)

- **Where in code:** [`Models.java:55`](../workflow-core/src/main/java/com/workflow/llm/Models.java#L55)
- **Когда брать:** non-critical LLM-вызовы, embeddings-style классификация (хотя для real embeddings есть `nomic-embed-text`).

#### Extras на OpenRouter (через named preset, не tier)

| Preset | Model | Notes |
|---|---|---|
| `deepseek` | `deepseek/deepseek-chat-v3-0324` | **Не для agent_with_tools** — 400 Bad Request на iter 12 при длинном tool-use контексте (полевое наблюдение). Безопасен для одиночных LLM-вызовов. |
| `glm` | `z-ai/glm-5.1` | новая ветка GLM, не оттестирована глубоко. |
| `gemini-pro` | `google/gemini-2.5-pro` | алиас reasoning. |
| `gemini-flash` | `google/gemini-2.0-flash-001` | старая ветка Gemini Flash. |
| `gpt4o` | `openai/gpt-4o` | полноразмерный. |
| `mistral` | `mistralai/mistral-large-2411` | не оттестирован для tool-use. |
| `qwen` | `qwen/qwen-2.5-72b-instruct` | большой qwen, для тех у кого OpenRouter лимит позволяет. |

**Retired / known broken (исторические записи):**

- `anthropic/claude-sonnet-4-5` — работал, но дорогой; снят с default tier=smart в пользу glm-4.6 (2026-04-30).
- `qwen/qwen-2.5-coder-32b-instruct` — модель исчезла с OpenRouter (404 к 2026-04-23).
- `qwen/qwen3-coder-flash` — Alibaba 400 на iter 2: `function.arguments must be in JSON format`. Модель эпизодически возвращает невалидный JSON в `tool_call.arguments`.

### 4.2 Claude Code CLI

#### `claude-sonnet-4-6` (tier=smart)

- **Where in code:** [`Models.java:45`](../workflow-core/src/main/java/com/workflow/llm/Models.java#L45)
- **Routing:** только когда `provider=CLAUDE_CODE_CLI` (per-run override или Project.defaultProvider). Биллинг идёт через Anthropic Max-подписку, не per-request.
- **CLI-fallback:** non-Anthropic модели под CLI routing'ом fall back на `claude-sonnet-4-6` с WARN (`ModelPresetResolver.resolveCli`, [строки 125-141](../workflow-core/src/main/java/com/workflow/llm/ModelPresetResolver.java#L125-L141)).
- **Плюсы:** очень сильный код, не требует геоплатёжки (Anthropic Max работает в России через VPN на этапе CLI auth).
- **Минусы:** требует локальный `claude -p` бинарь, идёт через subprocess; rate limits зависят от подписки.

#### `claude-haiku-4-5` (tier=flash/fast/cheap)

- **Where in code:** [`Models.java:46`](../workflow-core/src/main/java/com/workflow/llm/Models.java#L46)
- **Когда брать:** быстрые/массовые операции под CLI.

#### `claude-opus-4-7` (tier=reasoning)

- **Where in code:** [`Models.java:47`](../workflow-core/src/main/java/com/workflow/llm/Models.java#L47)
- **Когда брать:** orchestrator plan для самых сложных задач, code review критичных PR.
- **Не брать:** массово — даже под Max это перебор.

### 4.3 Ollama (local)

#### `mychen76/qwen3_cline_roocode:8b` (tier=smart/flash/fast/reasoning/cheap, default для Ollama)

- **Where in code:** [`Models.java:22`](../workflow-core/src/main/java/com/workflow/llm/Models.java#L22), карта `OLLAMA_DEFAULTS` [`ModelPresetResolver.java:35-46`](../workflow-core/src/main/java/com/workflow/llm/ModelPresetResolver.java#L35-L46)
- **Размер:** ~5 GB (Q4), вмещается в RTX 4060 8 GB
- **Контекстное окно:** жёстко `OLLAMA_NUM_CTX=16384` ([`OllamaProviderClient.java:62`](../workflow-core/src/main/java/com/workflow/llm/provider/OllamaProviderClient.java#L62)) — переопределяет Modelfile (65536 не помещается на 8 GB).
- **`OLLAMA_MAX_TOKENS_CAP=3000`** ([строка 58](../workflow-core/src/main/java/com/workflow/llm/provider/OllamaProviderClient.java#L58)).
- **Special:** автоматически инжектирует `/no_think` для qwen3-семьи (но не qwen3.6) через `injectNoThink()` — нужно потому что OpenAI-compat endpoint Ollama игнорирует `think:false`.
- **Плюсы:** **единственная локальная Ollama-модель, которая стабильно эмитит structured tool_calls в нашем agent_with_tools loop** — выдерживает 6-10 итераций. Cline-tuned, заточена под Read/Write/Edit/Grep/Bash.
- **Минусы:** требует 16K контекста — на 32K OOM'ит, на 8K обрезает tool schemas. Throughput на 4060 средний.
- **Когда брать:** дефолт для всех Ollama-проектов (все 5 tier'ов мапят сюда).
- **Когда НЕ брать:** нет — это безальтернативный выбор пока локально, см. § 4.3 "Не работает".

**Не работает локально (полевые наблюдения, 2026-05-13):**

| Модель | Симптом |
|---|---|
| `qwen2.5:7b` | **0 tool_calls** в нашем agent_with_tools loop — игнорирует tool-use протокол, эмитит финальный chat-response с `finish_reason=stop`. |
| `qwen2.5-coder:7b-instruct-q4_K_M` | то же — 0 tool_calls. |
| `qwen3:8b` base | tool_calls нестабильно; с `think:false` — почти никогда. |
| `qwen2.5:14b` Q4 (~9-10 GB) | не помещается в 8 GB → CPU offload → 2.6 t/s → timeout fail на analysis с max_tokens=8192. |
| `qwen3:30b-a3b`, `qwen3.6:35b-a3b` MoE | 18-28 GB, не помещается. |
| `mistral-nemo:12b` | на грани (~7 GB + KV-cache), нестабильно. |
| `granite3-dense:8b` | OK для plan, fail на review JSON. |
| `hermes3:8b` | для orchestrator только prose → нужен `prose_fallback: true`. |

#### `nomic-embed-text:v1.5` (embeddings)

- **Where in code:** [`Models.java:24`](../workflow-core/src/main/java/com/workflow/llm/Models.java#L24)
- **Используется фактически:** Qdrant-индекс кода (`QdrantKnowledgeBase`). Должен совпадать с тем, на чём индекс был построен.
- **Размер:** 137M params, ~270 MB
- **Когда менять:** не менять без переиндексации.

### 4.4 vLLM (local)

#### `Qwen/Qwen3-4B-AWQ` (tier=smart/flash/fast/reasoning/cheap, текущий vLLM default)

- **Where in code:** [`Models.java:37`](../workflow-core/src/main/java/com/workflow/llm/Models.java#L37), карта `VLLM_DEFAULTS` [`ModelPresetResolver.java:48-61`](../workflow-core/src/main/java/com/workflow/llm/ModelPresetResolver.java#L48-L61)
- **Размер:** ~2.5 GB на диске, влезает на RTX 4060 8 GB с полным 32K контекстом + prefix-caching.
- **Throughput:** ~70-100 t/s на Ada Lovelace (AWQ-Marlin kernels + FP8 Tensor Cores).
- **Sampling defaults** ([`VllmProviderClient.java:57-60`](../workflow-core/src/main/java/com/workflow/llm/provider/VllmProviderClient.java#L57-L60)): `temperature=0.25, top_p=0.9, top_k=40, repetition_penalty=1.1`. Применяются когда оператор оставил `temperature=1.0` (unset sentinel). Если в YAML стоит явно `agent.temperature: 0.5` — применится 0.5.
- **`VLLM_MAX_TOKENS_CAP=3000`** ([`VllmProviderClient.java:73`](../workflow-core/src/main/java/com/workflow/llm/provider/VllmProviderClient.java#L73)).
- **Плюсы:** ~3× быстрее Ollama на той же 4060, tool_calls стабильнее. Local — no billing.
- **Минусы:** **4 миллиарда параметров — потолок интеллекта.** Делает поверхностные ошибки в архитектуре, забывает контекст, генерирует код "не там". Если жалуешься на качество — это первый подозреваемый. См. § 1.
- **Когда брать:** простой ритм работы где важна скорость и стоимость (локально), не качество.
- **Когда НЕ брать:** критичный codegen, сложный orchestrator plan, review большого PR.

#### `Qwen/Qwen3-8B-AWQ` (per-block override)

- **Where in code:** [`Models.java:40`](../workflow-core/src/main/java/com/workflow/llm/Models.java#L40)
- **НЕ default** — 5.7 GB на диске + KV-cache на 8 GB GPU = crash-loop при старте vLLM ("No available memory for the cache blocks") даже после FP8 KV + max-num-seqs=4 + enforce-eager + prefix-cache off.
- **Когда брать:** GPU 12 GB+, либо если vllm-stack явно стартует с `--model Qwen/Qwen3-8B-AWQ`. В этом случае per-block: `agent.model: "Qwen/Qwen3-8B-AWQ"`.
- **Pitfall:** если pin'нешь 8B per-block, а vLLM стек загружен с 4B — получишь HTTP 404 от vLLM. Один vLLM-процесс хостит ровно одну модель.

### 4.5 Model quirks (хитрости движков)

Поведения, которые приходится знать чтобы избежать падений:

| Trigger | Поведение | Где в коде |
|---|---|---|
| qwen3-family под Ollama | `think:false` инжектится автоматически как `/no_think` в user message — OpenAI-compat endpoint Ollama игнорирует поле `think`. | `OllamaProviderClient.injectNoThink()` + `isQwen3Model()` |
| qwen3.6 (FAMILY_QWEN36) | НЕ инжектить `/no_think` (требует thinking ON) + требует `response_format=json_object` для structured output | `OrchestratorBlock` ~line 765-786 |
| `response_format=json_object` | **Отключает tool_calls** в OpenAI-compat — применять только когда tool-use не нужен | то же |
| Bare `claude-*` через OpenRouter | `resolveModel` фолбэчит на `smart` с warn — не падает молча на anthropic/* через OR | `LlmClient.resolveModel` / `ModelPresetResolver.resolveCli` |
| Ollama-tag (`org/name:tag`) через vLLM | fallback на vLLM `smart` с warn (vLLM 404 на неизвестный repo id) | `ModelPresetResolver.resolveVllm` строки 162-184 |
| Anthropic-name под vLLM/Ollama | то же — fallback на dev'льтный smart | `resolveVllm`, `resolveOllama` |
| `Project.orchestratorModel` непустой + provider=VLLM | vLLM HTTP 404 на `/v1/chat/completions` если модель не загружена в vllm-stack | очисти через Settings UI → "Orchestrator Model = empty" |

---

## 5. Troubleshooting: "модель не видит индексированные файлы / создаёт не там"

Это **почти всегда не баг модели, а контекст**. Чеклист проверки по порядку:

### 5.1 CLAUDE.md в правильном месте?

- `ProjectClaudeMd.readForPrompt` читает **`<Project.workingDir>/CLAUDE.md`** ([`ProjectClaudeMd.java:35-56`](../workflow-core/src/main/java/com/workflow/project/ProjectClaudeMd.java#L35-L56)).
- Если файл в другом месте — модель **не увидит** инструкции.
- Хард-кап: 16000 символов ([`ProjectClaudeMd.java:27`](../workflow-core/src/main/java/com/workflow/project/ProjectClaudeMd.java#L27)). Длиннее → хвост обрезается (с пометкой "truncated — full file at …").
- **Проверка:** `dir D:\path\to\target\repo\CLAUDE.md` (или `ls` на Unix). Если файла нет — создай его в корне проекта с описанием layout, build-команд, "do not touch" списков.

### 5.2 Файлы в RAG-индексе?

- RAG-поиск работает только если `workflow.knowledge.qdrant.url` задан в `application.yaml` (иначе `NoOpKnowledgeBase` возвращает пусто).
- Индекс лежит в Qdrant collection `code_<projectSlug>`. Если индекс пустой — модель не найдёт ничего семантически.
- **Проверка:** `curl http://localhost:6333/collections/code_<slug>` (Qdrant API) или через UI knowledge_base.
- **Что индексируется:** определяется секцией `knowledge_base.sources` в pipeline YAML. Если папка не покрыта source'ом — она не попадёт в индекс.

### 5.3 `auto_inject_rag: true` стоит на блоке?

- RAG инжектится автоматически **только** в `agent_with_tools` и **только** при флаге `auto_inject_rag: true` в YAML блока ([`AgentWithToolsBlock.java:567-606`](../workflow-core/src/main/java/com/workflow/blocks/AgentWithToolsBlock.java#L567-L606)).
- Без флага — модель должна сама вызвать `Grep`/`Glob`/`Read` чтобы найти нужные файлы.
- Кап: top-3 hits, 6000 chars (`RAG_INJECTION_TOP_K`, `RAG_INJECTION_MAX_CHARS`).
- **Skip-кейс:** если в prompt'е уже есть `## Pre-loaded files` (из plan preload) или `### Файлы, изменённые тобой` (loopback) — RAG не дублирует.

### 5.4 `plan.files_to_touch` заполнен в orchestrator?

- Если используешь `agent_with_tools` с `preload_from: <plan_block_id>`, файлы из `plan.files_to_touch` инжектятся в начало user-message ([`AgentWithToolsBlock.java:530-553`](../workflow-core/src/main/java/com/workflow/blocks/AgentWithToolsBlock.java#L530-L553)).
- Лимиты: 12 файлов, 32000 chars total. Дальше — пути перечислены, контент пропущен.
- Если orchestrator plan не вернул `files_to_touch` или вернул пустой массив — preload пропускается.

### 5.5 Project tree summary видим в prompt'е?

- `ProjectTreeSummary.summarise(workingDir)` автоматически prepends `## Codebase layout` в `agent_with_tools` ([`AgentWithToolsBlock.java:516-522`](../workflow-core/src/main/java/com/workflow/blocks/AgentWithToolsBlock.java#L516-L522)).
- Для **больших монорепо** tree обрезается — глубокие папки модель не видит, но может Glob'нуть.
- Если важная папка не в tree — попробуй явно упомянуть путь в `system_prompt` или task.md.

### 5.6 Модель пишет "не там" — PathScope error?

- `PathScope` рубит запись вне `Project.workingDir` ([`PathScope.java`](../workflow-core/src/main/java/com/workflow/tools/PathScope.java)). Канонизирует пути, ловит symlink-escape.
- Если ToolCallAudit показывает `Write` с error "path escapes scope" — модель пытается записать в `..` или абсолютный путь вне workingDir.
- **Это операторская ошибка** в `Project.workingDir`, не баг модели. Проверь Settings → Project → Working Dir.

### 5.7 Модель создаёт дубль вместо редактирования существующего?

- Чаще всего: модель не Glob'нула перед Write. Симптом — в репо появился `SomethingNew.java` рядом с `Something.java`.
- Лечение **не в модели**, а в:
  - **CLAUDE.md** проекта: явно перечисли "не создавай дубли, ищи через Grep по класс-нейму перед Write".
  - **system_prompt** блока: добавить "Read before Write" (есть в дефолтных `AgentWithToolsBlock` FALLBACK_PROMPT_HEADER, но операторский YAML может переопределить).
  - **`auto_inject_rag: true`** на блоке — даст top-3 хитов по семантике, модель увидит существующий файл.
  - **`preload_from: <plan_block_id>`** — orchestrator plan должен вернуть точный путь в `files_to_touch`.

### 5.8 Контекст-окно переполнено?

- Ollama: 16K фиксировано. После Read'а 5 файлов по 3K — output обрезается, модель теряет нить.
- vLLM 4B: 16384 max_model_len (запуск с этим параметром), 3000 max_tokens output.
- OpenRouter/CLI: контекст 200K+, но **prompt + tool history** растут по итерациям.
- **Симптом:** на iter 4-5 модель начинает повторяться или забывает что делала.
- **Лечение:** разбить задачу на меньшие, использовать orchestrator для chunk'инга, переключиться на провайдер с большим контекстом.

---

## 6. Maintenance protocol — когда этот документ обновляется

Триггеры обновления (для будущих сеансов / другого Claude):

1. **PR меняет `ModelPresetResolver.java` или `Models.java`** → обновить § 1 (таблица defaults) + § 2.2 (ссылки на строки) + затронутые карточки в § 4.
2. **PR меняет sampling в `VllmProviderClient` / `OllamaProviderClient`** → обновить sampling-секцию в карточке + поле "`*_MAX_TOKENS_CAP`".
3. **Оператор сообщает регрессию** (`"эта модель плохо работает на X"`) → добавить строку в § 7 Changelog с датой; при подтверждении (повторное наблюдение) — переписать "Минусы" в карточке модели.
4. **Появилась новая модель в `DEFAULTS` / `OLLAMA_DEFAULTS` / `VLLM_DEFAULTS` / `CLI_DEFAULTS`** → новая карточка в § 4 + строка в матрице § 1.
5. **Модель снята с дефолтов** → карточка остаётся, помечается `Status: retired (YYYY-MM-DD, заменена на X)`.
6. **Сменился default-провайдер для активного проекта** (через Settings UI) → строка в § 7 changelog (это влияет на интерпретацию § 1 для конкретного оператора).
7. **vLLM-стек загружает другую модель** → обновить § 4.4 (текущий vLLM default).

Документ читается оператором при попытке диагностировать "почему модель меня подвела". Если описанная проблема не находит ответа в § 5 — добавь в § 5 новый пункт чеклиста.

---

## 7. Changelog / operator feedback

### 2026-05-13
- Документ создан. Источники: `ModelPresetResolver.java` (commit `b837408`), `Models.java`, memory snapshots `project_openrouter_models`, `project_ollama_tool_use_models`, `project_warcard_provider_routing`, `project_ollama_hardware_limits`.
- Жалоба оператора: модель пишет плохой код, не смотрит индексированные файлы, кладёт файлы не туда. **Главный подозреваемый — vLLM Qwen3-4B на WarCard** (см. § 1 ⚠).
- WarCard pipeline сейчас сидит на `defaultProvider=VLLM` ([`project_warcard_provider_routing.md`](../../.claude/projects/D----------AI-Workflow/memory/project_warcard_provider_routing.md)), `orchestratorModel=""`. Все блоки идут через `Qwen/Qwen3-4B-AWQ`.

### Шаблон будущих записей

```
### YYYY-MM-DD
- <model_id> в <блок> на <pipeline.yaml>: <симптом>. Воспроизводится: <да/нет>.
  Workaround: <переключение/override>.
  → обновлены: <карточка модели §4 / минусы / quirks §4.5>
```

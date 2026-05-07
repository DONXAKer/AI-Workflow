# Добавление нового блока

Блок (Block) — единица исполнения в пайплайне. Каждая нода на канвасе редактора, каждый шаг в YAML — это инстанс блока с уникальным `id`. Сам **тип** блока — это Java-класс, реализующий интерфейс `com.workflow.blocks.Block` и зарегистрированный как Spring `@Component`.

Этот гайд описывает, как добавить новый тип блока — что нужно реализовать, какие метаданные заполнить, и где блок появится в UI после рестарта.

## TL;DR — минимальный блок за 5 минут

```java
package com.workflow.blocks;

import com.workflow.config.BlockConfig;
import com.workflow.core.PipelineRun;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class HelloWorldBlock implements Block {

    @Override public String getName() { return "hello_world"; }
    @Override public String getDescription() { return "Печатает приветствие в run output."; }

    @Override
    public BlockMetadata getMetadata() {
        return new BlockMetadata(
            "Hello World",         // label (UI display name)
            "general",             // category
            Phase.ANY,             // phase
            List.of(               // configFields
                FieldSchema.requiredString("greeting", "Greeting", "Текст приветствия")
            ),
            false,                 // hasCustomForm
            Map.of(),              // uiHints
            List.of(               // outputs
                FieldSchema.output("message", "Message", "string", "Итоговая строка.")
            ),
            50                     // recommendedRank
        );
    }

    @Override
    public Map<String, Object> run(Map<String, Object> input, BlockConfig config, PipelineRun run) {
        String greeting = (String) config.getConfig().getOrDefault("greeting", "Hello");
        Map<String, Object> result = new HashMap<>();
        result.put("message", greeting + ", world!");
        return result;
    }
}
```

После рестарта `gradle bootRun`:

- Блок появится в **палитре редактора** в группе фазы `ANY`.
- В **wizard'е создания** (PR-3) попадёт в `Skip phase` ноды или может быть выбран через `+ Add another block`.
- В **side-panel'е** будет рендериться `GenericBlockForm` с одним обязательным полем `greeting` (essential, потому что `requiredString`).
- В `OutputsRefPicker` ниже по DAG станет доступен ref `$.{block_id}.message`.
- Validator проверит, что `${block_id.message}` ссылается на существующий output.

## Что нужно реализовать

### 1. Интерфейс `Block`

`com.workflow.blocks.Block` имеет три обязательных метода + один с дефолтом:

```java
public interface Block {
    String getName();                                              // YAML-имя типа: "code_generation", "verify"
    String getDescription();                                       // Человекочитаемое описание (русский ок)
    Map<String, Object> run(Map<String, Object> input,             // Главная логика
                            BlockConfig config,
                            PipelineRun run) throws Exception;
    default BlockMetadata getMetadata() {                          // UI-метаданные (override-ить почти всегда)
        return BlockMetadata.defaultFor(getName());
    }
}
```

Аннотация `@Component` обязательна — Spring auto-pickup через component-scan регистрирует блок в `BlockRegistry` без ручных правок в реестре.

### 2. Сигнатуры

- **`getName()`** — snake_case, уникален в проекте. Это значение, которое пишут в YAML: `block: hello_world`. Должен совпадать с тем, что используется для `Phase.forBlockType(...)` (см. ниже).
- **`getDescription()`** — короткий русский текст для tooltip'а в палитре.
- **`run(input, config, run)`** — логика исполнения. Возвращает `Map<String, Object>` — это и есть **outputs** блока. Ключи map'а должны соответствовать `FieldSchema` из `getMetadata().outputs()`. Если кто-то ниже по DAG напишет `${hello_world.message}` — `PathResolver` достанет именно этот ключ.
- **`getMetadata()`** — описывает блок для UI и валидатора. Без override блок будет рендериться как raw-JSON-textarea без помощников.

## `BlockMetadata` — структура

Канонический 8-arg constructor:

```java
new BlockMetadata(
    String label,                       // "Run tests"
    String category,                    // "input" | "agent" | "verify" | "ci" | "infra" | "output" | "general"
    Phase phase,                        // INTAKE | ANALYZE | IMPLEMENT | VERIFY | PUBLISH | RELEASE | ANY
    List<FieldSchema> configFields,     // Поля, которые юзер заполняет в YAML под ключом config:
    boolean hasCustomForm,              // true → UI использует custom React-форму вместо GenericBlockForm
    Map<String, Object> uiHints,        // Свободный map для будущих UI-подсказок
    List<FieldSchema> outputs,          // Поля, которые блок кладёт в свой output map
    int recommendedRank                 // 0..100, выше = чаще выбирается wizard'ом для своей фазы
)
```

Backwards-compat constructors (5-arg, 6-arg) есть, но не используй их в новых блоках — они дефолтят `outputs=List.of(), recommendedRank=0`, и блок не появится в `OutputsRefPicker` ниже по DAG, а wizard будет его игнорировать.

### `category`

Влияет на иконку в `BlockNode.tsx`:

| `category` | Иконка | Когда |
|---|---|---|
| `input` | FileInput | Источники данных (`task_md_input`, `youtrack_input`) |
| `agent` | Sparkles | LLM-блоки (`agent_with_tools`, `code_generation`) |
| `verify` | ShieldCheck | Проверки (`verify`, `agent_verify`, `run_tests`) |
| `ci` | Wrench | CI-системы (`gitlab_ci`, `github_actions`) |
| `infra` | Cog | Инфра (`deploy`, `rollback`) |
| `output` | Globe | Публикация (`github_pr`, `gitlab_mr`) |
| `general` | Cog | Прочее |

### `phase`

Определяет:
- **Группу в палитре** редактора и wizard'е (одна группа на фазу).
- **Валидацию монотонности**: `depends_on` не может ссылаться на блок более поздней фазы (`PHASE_MONOTONICITY` ошибка).
- **Recommended-выбор в wizard'е** — на каждом шаге фазы предлагается блок этой фазы с максимальным `recommendedRank`.

Канонический map `block-type → phase` живёт в `Phase.BLOCK_TYPE_PHASES`. **Если ты добавляешь блок с конкретной фазой, добавь запись и туда** — `BlockMetadata.defaultFor(name)` использует этот map для блоков без явного `getMetadata()` (test stubs, дефолтная регистрация). Нести фазу в двух местах не идеально, но это backwards-compat-минимум; рефакторинг в один источник истины — отдельная задача.

`Phase.ANY` — для полиморфных блоков (`shell_exec`, `http_get`, `orchestrator`), которые могут стоять на любой фазе. UI показывает их в отдельной группе «ANY» и валидатор пропускает их в проверке монотонности.

### `recommendedRank`

Целое число `0..100`. Используется wizard'ом (PR-3) для выбора блока, который предзаполняется на шаге фазы:

- `100` — основной блок фазы (`analysis` для ANALYZE, `code_generation`/`agent_with_tools` для IMPLEMENT, `github_pr`/`gitlab_mr` для PUBLISH).
- `80–90` — крепкий fallback (`verify` rank=80, `agent_verify` rank=90).
- `0–50` — экзотика, не предлагается по умолчанию.

Если фаза имеет несколько блоков с одинаковым максимальным rank — берётся первый по порядку имени (детерминированно).

## `FieldSchema` — описание полей

Универсальный record, описывает один параметр (config-поле или output-поле).

```java
public record FieldSchema(
    String name,            // ключ в YAML / output map
    String label,           // human-readable в UI
    String type,            // "string" | "number" | "boolean" | "string_array" | "enum" | "block_ref" | "tool_list"
    boolean required,       // обязательное поле
    Object defaultValue,    // для UI
    String description,     // tooltip / hint
    Map<String, Object> hints,  // свободный map (multiline=true, monospace=true, values=[...] для enum)
    String level            // "essential" | "advanced" — где видно в side-panel и wizard'е
) { ... }
```

Готовые статические фабрики (используй их вместо canonical constructor):

| Фабрика | Когда |
|---|---|
| `FieldSchema.string(name, label, desc)` | Optional строка — попадёт в Advanced |
| `FieldSchema.requiredString(name, label, desc)` | Обязательная строка — попадёт в Essentials |
| `FieldSchema.multilineString(name, label, desc)` | Большой текст (textarea) |
| `FieldSchema.monospaceString(name, label, desc)` | Шрифт кода (выражения, regex) |
| `FieldSchema.number(name, label, defaultValue, desc)` | Число с дефолтом |
| `FieldSchema.bool(name, label, desc)` | Чекбокс |
| `FieldSchema.stringArray(name, label, desc)` | Chip-list (comma-separated) |
| `FieldSchema.enumField(name, label, values, default, desc)` | Select |
| `FieldSchema.blockRef(name, label, desc)` | Select из id блоков пайплайна |
| `FieldSchema.toolList(name, label, desc)` | Виджет для `allowed_tools` (Read, Write, Edit, …) |
| `FieldSchema.output(name, label, type, desc)` | Output-поле, всегда `essential` |

### `level`: essential vs advanced

Определяет, где поле появляется в UI:

- **`essential`** — секция Essentials в side-panel (открыта по умолчанию) + появляется в wizard'е (PR-3).
- **`advanced`** — секция Advanced (свёрнута по умолчанию). Не показывается в wizard'е.

Дефолт: фабрики `requiredString` → `essential`, `string`/`number`/`bool` → `advanced`. Можно переопределить через `.withLevel("essential" | "advanced")`.

**Правило:** в `essential` кладёшь то, без чего блок ломается или ведёт себя бессмысленно (например, `verify.subject`, `code_generation.task_input`). В `advanced` — тонкие настройки с разумными дефолтами (`max_iterations`, `temperature`, `bash_allowlist`).

### `outputs` — декларация I/O контракта

Каждый ключ из `result` map'а в `run()` должен быть описан в `outputs`. Это даёт три преимущества:

1. **Picker `${block.field}` в side-panel'е.** Любое поле condition / inject_context / interpolation подсказывает доступные ссылки от depends_on-предков.
2. **Level 3 валидация.** Если кто-то напишет `${analysis.summry}` (typo) — валидатор подсветит `REF_UNKNOWN_FIELD` warning. Без `outputs` валидатор не может проверить (silent skip).
3. **PR-3 wizard preview.** На финальном шаге wizard'а DAG прогоняется через валидатор — outputs обеспечивают честную оценку «pipeline валиден».

Все output-поля автоматически `essential` (это контракт блока, не настройка).

## Что юзер кладёт в YAML

После регистрации блок становится доступен в YAML:

```yaml
pipeline:
  - id: hello
    block: hello_world          # = getName()
    config:
      greeting: "Привет"        # ключи из configFields
    depends_on: [analysis]      # стандартный механизм
```

Поля верхнего уровня (`id`, `block`, `config`, `depends_on`, `condition`, `verify`, `on_failure`, `agent`) — общие для всех блоков, ты их не объявляешь. Они описаны в `BlockConfig.java`.

## Что появляется в UI автоматически

После рестарта `workflow-core` и без правок в `workflow-ui`:

| Где | Что |
|---|---|
| `BlockPalette.tsx` | Блок появится в группе фазы (или ANY) с label/иконкой по category |
| `Canvas.tsx` (BlockNode) | Иконка по category + цветная полоса фазы |
| `BlockNode` "+" popover | Доступен после блоков более ранних фаз (фильтр по `Phase.order()`) |
| `SidePanel.tsx` | 3 секции: pinned-header → Essentials (essential поля) → Conditions & Retry → Advanced (advanced поля + agent override) |
| `OutputsRefPicker` | Поля из `outputs` доступны в `${block.field}` autocomplete для блоков ниже по DAG |
| Wizard (PR-3) | На шаге `phase` блок может быть выбран если `recommendedRank > 0` |

Если блок с `hasCustomForm: true` — UI ищет дедикейтед React-компонент по типу. Сейчас custom-формы есть только у `agent_with_tools` и `verify` (`forms/AgentWithToolsForm.tsx`, `forms/VerifyForm.tsx`). Добавить ещё одну — отдельная задача в `workflow-ui`.

## Что проверит валидатор

`PipelineConfigValidator` (Spring `@Component`) гоняет три уровня:

| Level | Что |
|---|---|
| 1 (структура) | Блок зарегистрирован в `BlockRegistry`, обязательные поля заполнены |
| 2 (граф) | Нет циклов, нет dangling `depends_on`, фазы монотонны |
| 3 (data flow) | `${block.field}` / `$.block.field` ссылается на существующий блок и **существующее поле** в его `outputs` (твой блок этим бесплатно покрыт) |

Самопроверка после написания: `POST /api/pipelines/validate` с пайплайном, использующим твой блок, должен вернуть `valid: true`.

## Тестирование

### Unit

В `workflow-core/src/test/java/com/workflow/blocks/`:

```java
class HelloWorldBlockTest {
    @Test
    void run_returnsGreeting() {
        var block = new HelloWorldBlock();
        var config = new BlockConfig("hello", "hello_world", Map.of("greeting", "Hi"), null, null, null, null);
        var result = block.run(Map.of(), config, mock(PipelineRun.class));
        assertEquals("Hi, world!", result.get("message"));
    }

    @Test
    void getMetadata_declaresOutputs() {
        var meta = new HelloWorldBlock().getMetadata();
        assertTrue(meta.outputs().stream().anyMatch(f -> "message".equals(f.name())));
    }
}
```

### Integration (`*IT`)

Если блок делает внешние вызовы (HTTP, OpenRouter, GitHub API) — заверни в `*IT.java` с `@EnabledIfEnvironmentVariable(named = "OPENROUTER_API_KEY", matches = ".+")` (или соответствующий ключ). Это исключит его из дефолтного `gradle test`.

## Чек-лист «новый блок готов»

- [ ] Класс реализует `Block`, аннотирован `@Component`.
- [ ] `getName()` уникален и snake_case.
- [ ] `getMetadata()` использует canonical 8-arg constructor с заполненными `outputs` и `recommendedRank`.
- [ ] `Phase.BLOCK_TYPE_PHASES` обновлён (если фаза не `ANY`).
- [ ] Все ключи из `result` map в `run()` описаны в `outputs`.
- [ ] Essential поля помечены `requiredString` или явным `.withLevel("essential")`.
- [ ] Unit-тест на `run()` + `getMetadata().outputs()`.
- [ ] (Опционально) Описание добавлено в корневой `CLAUDE.md` в секцию «Blocks».

## Примеры для копирования

Хорошие референсы — блоки с полным `getMetadata()`:

- **`RunTestsBlock.java`** — простой блок без LLM, с enum-полем, числом, и понятным outputs-контрактом.
- **`AnalysisBlock.java`** — LLM-блок, `configFields=[]`, богатые outputs (включая структурный `acceptance_checklist`).
- **`AgentWithToolsBlock.java`** — сложный блок с tool-list, level-разметкой, и сильной разницей essential/advanced.
- **`OrchestratorBlock.java`** — mode-dependent outputs (union plan+review).
- **`GitHubPRBlock.java`** — output-категория, явная фаза PUBLISH, минимальный configFields (всё инжектится из integration).

## Что НЕ нужно делать

- **Не плоди новые `category`.** Если блок не вписывается ни в один из 7 — выбери ближайший (`general` всегда подойдёт). Каждая новая категория требует новой иконки в UI.
- **Не используй 5-arg/6-arg constructors `BlockMetadata`.** Они есть только для backwards-compat со старыми блоками, написанными до PR-1. Без `outputs` блок становится «слепой» для validator'а Level 3 и для OutputsRefPicker.
- **Не дублируй поля верхнего уровня в `configFields`.** `id`, `depends_on`, `condition`, `verify`, `on_failure`, `agent` — общие, рендерятся side-panel'ом отдельно.
- **Не возвращай из `run()` ключи, которых нет в `outputs`.** Будет работать (валидатор молчит про лишние поля), но downstream-блоки не смогут на них автокомплитом сослаться. Если поле временно или эфемерное — префикс `_` (как в `_github_config`, инжектится из integrations и вырезается из публичных outputs).

## Связанные документы

- `docs/plans/pipeline-editor-v3.md` — план Phase 3 (PR-1 + PR-2 закрыты, PR-3/wizard — в работе).
- `CLAUDE.md` (корневой) — список существующих блоков и общая архитектура.
- `workflow-ui/CLAUDE.md` — устройство фронта, где пишутся custom-формы.

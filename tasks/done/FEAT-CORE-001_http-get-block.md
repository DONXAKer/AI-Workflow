# Add http_get pipeline block

## Как сейчас
В AI-Workflow нет простого способа сделать HTTP GET из YAML pipeline.
Для любого внешнего чека (healthcheck, webhook ping, API poll) нужно
городить shell_exec с curl, что ломает кроссплатформенность (curl на
Windows host отсутствует по умолчанию) и не даёт структурного доступа
к status/headers/body для downstream блоков.

## Как надо
Добавить нативный блок `http_get` со следующим контрактом:

- Вход (YAML config):
  - `url` (required) — поддерживает `${block.field}` интерполяцию
  - `headers` (optional) — map, значения через интерполяцию
  - `timeout_sec` (optional, default 30)
  - `parse_json` (optional, default false) — парсить body как JSON
    и класть в поле `json`
  - `allow_nonzero_status` (optional, default false) — не-2xx без
    исключения, success=false в output'е

- Output map:
  - `url`, `status`, `success` (2xx), `body`, `headers`, `duration_ms`
  - `json` + `json_parse_error` когда `parse_json=true`

- Реализация через существующий `WebClient.Builder` (он уже в DI,
  используется `LlmClient`). Body капать на 512 KB.

## Вне scope
- POST / PUT / DELETE / PATCH — отдельный блок `http_request` позже
- mTLS, custom SSL — конфигурация через отдельный integration
- Retry / backoff — на уровне pipeline, не блока

## Критерии приёмки
- `HttpGetBlockTest` зелёный: body/status, headers sent, json parse
  ok + fail, nonzero-status throws, allow_nonzero_status returns,
  missing url fails
- `gradle build` зелёный
- Блок регистрируется в PipelineRunner (логи boot показывают +1
  блок в registry)

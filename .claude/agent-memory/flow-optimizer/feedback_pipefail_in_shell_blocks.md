---
name: pipefail in shell_exec blocks with pipes
description: Любая shell_exec команда вида `cmd | tail` теряет exit-код cmd; обязательно ставить set -o pipefail или избавляться от пайпа в проверочных блоках (tests/build/validate)
type: feedback
---

В YAML-блоках `shell_exec`, чей exit_code потом проверяется через `verify {success==true}`, обязательно делать `set -o pipefail` ПЕРЕД любой командой с пайпом. Без него `./gradlew test 2>&1 | tail -40` всегда возвращает 0 (exit-код tail), даже если тесты упали.

**Why:** `ShellExecBlock` берёт `exit_code = process.exitValue()`, который равен exit'у последней команды в pipe. По умолчанию sh/bash в неинтерактивном режиме pipefail выключен. Симптом: verify_tests/verify_build/verify_contracts ложно зеленеют, pipeline идёт дальше с битым кодом.

**How to apply:** Любая команда вида `... | tail`, `... | head`, `... | grep` в блоках `tests`, `build`, `validate_contracts`, `ai_simulator` (и аналогах в других проектах) должна начинаться со строки `set -o pipefail`. Альтернатива — писать stdout в файл и потом `tail file.log`, но pipefail проще.

Найдено при анализе `D:\WarCard\.ai-workflow\pipelines\feature.yaml` 2026-05-08. Затронуто 3 блока: `tests` (стр. 300-309), `validate_contracts` (стр. 350-356), `ai_simulator` (стр. 393-397). В блоке `build` уже есть явная проверка `BUILD_OK==1`, но это hack — pipefail чище.

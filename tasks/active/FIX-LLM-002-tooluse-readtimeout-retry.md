---
id: FIX-LLM-002
title: completeWithTools не ретраит ReadTimeoutException на медленных upstream-итерациях
created: 2026-05-19
status: planned
---

## Как сейчас

`workflow-core/src/main/java/com/workflow/llm/provider/OpenAICompatibleProviderClient.java:353-365` — retry-loop внутри `completeWithTools`:

```java
} catch (Exception ex) {
    boolean transient_ = ex.getMessage() != null && (
        ex.getMessage().contains("PrematureCloseException") ||
        ex.getMessage().contains("Connection reset") ||
        ex.getMessage().contains("connection") && ex.getMessage().contains("closed"));
    if (transient_ && attempt < 3) {
        logger().warn("Tool-use iteration {} attempt {}/3 transient error, retrying: {}", ...);
        Thread.sleep(2000L * attempt);
    } else {
        lastEx = ex;
        break;
    }
}
```

Предикат `transient_` ловит три формы (внезапный close + reset + closed). НЕ ловит:

- `io.netty.handler.timeout.ReadTimeoutException` / `ReadTimeoutHandler.readTimedOut(ReadTimeoutHandler.java:98)` — срабатывает когда upstream берет паузу >`responseTimeout` (120s для ALLTOKENS, см. `AllTokensProviderClient.java:93`). На длинных prompt/generation парах ALLTOKENS/AITUNNEL/OpenRouter регулярно отвечают 60-180s.
- `WebClientRequestException` без вложенного `Message` (на верхнем уровне `getMessage()` возвращает только класс).
- `SocketTimeoutException` от blocking-варианта WebClient.

Конкретный кейс (2026-05-19, run `befa764e-f668-4670-a8b9-8f4195fefc3b`, блок `impl_bp` iteration 13/20):
```
java.lang.RuntimeException: completeWithTools iteration 13 failed: null
  ... at io.netty.handler.timeout.ReadTimeoutHandler.readTimedOut(ReadTimeoutHandler.java:98)
  ... at io.netty.handler.timeout.IdleStateHandler$ReaderIdleTimeoutTask.run(IdleStateHandler.java:525)
```

Note: `getMessage()` вернул `null` — поэтому даже если добавить `contains("ReadTimeout")` без null-check, не сработает; нужно проверять `ex.getCause()` или тип.

## Как надо

Расширить retry-предикат `transient_`:

1. Type-based: `ex instanceof io.netty.handler.timeout.ReadTimeoutException` (и transitively через `ex.getCause()`).
2. Сохранить string-based fallback для уже покрытых случаев — он работает для PrematureClose и т.д.
3. Учесть `null` message — если `getMessage()` null, всё равно посмотреть тип / cause chain.
4. Опционально: расширить `responseTimeout` для AllTokens с 120s до 180s — на длинных tool-use генерациях upstream legitimately отвечает медленно.

После изменения: на ReadTimeout итерация ретраится 3 раза с backoff 2s/4s/6s, до того как пометить как fatal.

## Вне scope

- Не менять глобальный `responseTimeout` defaults для других провайдеров без отдельного тикета.
- Не менять количество retry'ев (3) — оно ok.
- Не менять backoff schedule (2s/attempt).
- Не трогать `completeWithMessages` / `complete` (single-shot) — они уже имеют свои retry внутри WebClient/Reactor.

## Критерии приёмки

- [ ] В `OpenAICompatibleProviderClient.completeWithTools` retry-предикат ловит `ReadTimeoutException` через type-check (instanceof или getCause()-chain).
- [ ] Новый unit-тест в `OpenAICompatibleProviderClientTest`: мок WebClient бросает `ReadTimeoutException` на первой попытке, успешный ответ на второй — итерация в итоге завершается без exception, retry log виден.
- [ ] Существующие тесты на retry других transient errors (PrematureClose etc) проходят без изменений.
- [ ] `gradle build` зелёный.
- [ ] (manual) WarCard FEAT-DRAFT-002 заново запущенный после merge — проходит блок impl_bp без read-timeout fail. Если упал по другой причине — не в scope этого тикета.

## Реализовано

<!-- заполнить при переводе в done/ -->

# C++ / Unreal Engine — Best Practices

Кодовая база — Unreal Engine 5 (C++). Это движковый C++, не «чистый» std.

## Структура проекта и зависимости
- `<Project>.uproject` (JSON) — корневой манифест: перечисляет `Modules` и `Plugins`. Это аналог `build.gradle`/`pom.xml` для анализа зависимостей.
- Модуль = каталог в `Source/<Module>/` + `<Module>.Build.cs`. Зависимости модуля — в `PublicDependencyModuleNames` / `PrivateDependencyModuleNames` внутри `.Build.cs`.
- `<Project>.Target.cs` — описывает target сборки (Editor / Game / Server).
- `Content/` — бинарные ассеты (`.uasset`/`.umap`), не парсятся как текст.

## Конвенции движка
- Префиксы классов: `A` Actor, `U` UObject, `F` struct, `I` interface, `E` enum, `T` template. Файл — без префикса (`AFoo` → `Foo.h/.cpp`).
- Рефлексия: `UCLASS/USTRUCT/UENUM` + `GENERATED_BODY()`; `UPROPERTY/UFUNCTION` для доступа из Blueprint и сериализации.
- Ссылки на UObject держать в `UPROPERTY()` (GC), member-указатели — `TObjectPtr<>` (UE5). UObject вручную не `delete`.

## Типы и контейнеры
- `FString/FName/FText`, `TArray/TMap/TSet`, `TSharedPtr/TWeakObjectPtr` — не `std::`.
- `*.generated.h` — последним include в заголовке.

## Безопасность исполнения
- `check()`/`ensure()` для инвариантов; `UE_LOG` для диагностики.
- Никаких blocking-вызовов в game thread.

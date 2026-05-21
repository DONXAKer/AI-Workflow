# Unreal Engine 5 Guidelines

Проект — UE5 (C++ + Blueprints). C++ здесь — движковый диалект, не «чистый» std. Конвенции:

## Префиксы имён
- Классы C++: `A` — Actor, `U` — UObject, `F` — struct/не-UObject, `I` — interface, `E` — enum, `T` — template, `b` — bool-поле.
- Файл класса БЕЗ префикса: `ADraftWidget` → `DraftWidget.h`/`.cpp`.
- Ассеты: `BP_` Blueprint, `WBP_` Widget Blueprint (UMG), `ABP_` Anim BP, `M_` Material, `T_` Texture, `SM_` StaticMesh.

## Рефлексия и память
- `UCLASS()/USTRUCT()/UENUM()` над типами; `GENERATED_BODY()` первой строкой тела.
- `UPROPERTY()` над полями, `UFUNCTION()` над методами — для доступа из BP и сериализации.
- Ссылку на UObject держи в `UPROPERTY()` — иначе GC соберёт объект. Member-указатели в UE5 — `TObjectPtr<UFoo>`, не сырой `UFoo*`. UObject вручную не `delete`.

## C++ диалект
- Типы движка: `FString/FName/FText`, `TArray/TMap/TSet`, `TSharedPtr/TWeakObjectPtr` — не `std::`.
- В заголовках forward-declare; тяжёлые `#include` в `.cpp`; `*.generated.h` — последним include.
- Крупные структуры — по `const&`. Никаких blocking-вызовов в game thread (долгие задачи — `AsyncTask`/`FRunnable`).
- Логи — `UE_LOG(LogTemp, Warning, TEXT("..."))`; инварианты — `check()`/`ensure()`.

## UMG (виджеты)
- C++-виджет наследуется от `UUserWidget`; визуальное дерево — в `WBP_*`-ассете.
- Связь C++ ↔ дерево: `UPROPERTY(meta=(BindWidget)) UButton* MyButton;` — имя поля = имя виджета в WBP.
- `.uasset`/`.umap` бинарны — не править как текст, только через Editor/MCP.

## Прочее
- Модуль — в `*.Build.cs` (зависимости в `PublicDependencyModuleNames`).
- Gameplay-framework: `AGameModeBase`, `AGameStateBase`, `APlayerController`, `APawn`.

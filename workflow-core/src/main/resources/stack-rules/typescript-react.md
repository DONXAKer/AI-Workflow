# TypeScript / React — обязательные требования

## Типизация
- Запрещено использовать `any` без явного обоснования в комментарии `// eslint-disable-next-line @typescript-eslint/no-explicit-any`.
- Все props компонентов должны иметь явный TypeScript тип (`interface Props` или `type Props`).
- Не использовать `as SomeType` кастинги без проверки — лучше type guard.

## Компоненты
- Функциональные компоненты: `export function Foo({ bar }: FooProps)` или `const Foo: React.FC<FooProps> = ...`.
- Хуки следуют rules-of-hooks: вызываются только на верхнем уровне, не внутри условий.
- Зависимости useEffect/useCallback указываются полностью (не отключать exhaustive-deps).

## Стиль
- Tailwind классы: не смешивать с inline-стилями на одном элементе.
- Ключи в списках: уникальные стабильные id, не индексы массива.

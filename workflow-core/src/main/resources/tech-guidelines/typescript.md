# TypeScript Guidelines

Используй TypeScript 5.0+ в strict mode:

## Конфигурация
- Всегда включай `strict: true` в tsconfig.json
- Используй `strictNullChecks: true`
- Применяй `noImplicitAny: true`
- Используй `noImplicitReturns: true`

## Типы
- Предпочитай interface для object shapes
- Используй type для unions, intersections, computed types
- Применяй generic types для переиспользуемых компонентов
- Используй utility types (`Partial`, `Pick`, `Omit`)

## Best Practices
- Избегай `any` - используй `unknown` или конкретные типы
- Используй `readonly` для immutable данных
- Применяй `as const` для literal types
- Используй discriminated unions для type-safe вариантов

## React Integration
- Используй `React.FC` для functional components
- Применяй `React.MouseEvent` и другие event типы
- Используй `useState<Type>` для типизированного state
- Применяй `useRef<Type>` для ref объектов

## Инфраструктура
- Используй path mapping в tsconfig.json
- Применяй `declare module` для не-типизированных библиотек
- Используй `d.ts` файлы для type declarations
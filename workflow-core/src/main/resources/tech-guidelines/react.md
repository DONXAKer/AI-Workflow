# React Guidelines

Используй React 18+ с современными практиками:

## Компоненты
- Используй functional components с hooks
- Предпочитай `useState` и `useEffect` для state management
- Применяй `useCallback` и `useMemo` для оптимизации производительности
- Используй custom hooks для переиспользуемой логики

## Структура проекта
- Компоненты в `components/` директории
- Pages в `pages/` или `views/`
- Hooks в `hooks/` директории
- Utils в `utils/` директории
- Types в `types/` директории (для TypeScript)

## Best Practices
- Используй key props в списках
- Предпочитай composition over inheritance
- Применяй error boundaries для обработки ошибок
- Используй React.lazy() и Suspense для code splitting

## State Management
- Для простого состояния используй `useState`
- Для сложного состояния рассмотри useReducer или Context API
- Для глобального состояния используй Redux Toolkit или Zustand

## Стилизация
- Предпочитай CSS Modules или styled-components
- Используй Tailwind CSS для utility-first подхода
- Избегай inline стилей для производительности
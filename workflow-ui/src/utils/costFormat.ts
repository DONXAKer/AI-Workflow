import type { LlmProvider } from '../types'

/**
 * Provider-aware cost formatter. Stack stores all amounts in the `cost_usd` column
 * agnostic of source currency — but AllTokens.ru returns `cost` in rubles, не в USD
 * (их dashboard и биллинг в ₽; per-call `cost` field в их OpenAI-совместимом ответе
 * — это сумма в ₽, которую мы стораджим как-есть). Чтобы оператор не путал
 * единицы, отображаем символ валюты per-call по фактическому провайдеру.
 *
 * Для агрегатов без provider'а (totalCostUsd на run-level) символ неоднозначен —
 * показываем "₽/$" если в раннe были смешанные провайдеры.
 */
export const RUBLE_PROVIDERS: ReadonlySet<LlmProvider> = new Set<LlmProvider>(['ALLTOKENS'])

export function providerCurrency(provider?: LlmProvider | string | null): '₽' | '$' {
  if (provider && RUBLE_PROVIDERS.has(provider as LlmProvider)) return '₽'
  return '$'
}

/** Format a single LLM call cost using the call's own provider. */
export function formatCallCost(amount: number, provider?: LlmProvider | string | null, fractionDigits = 4): string {
  const sym = providerCurrency(provider)
  return `${sym}${amount.toFixed(fractionDigits)}`
}

/**
 * Format a run-level / aggregated cost. If a provider is known (single-provider run),
 * use its symbol; otherwise default to `$` but mark ambiguity with a small "~".
 * Pass `providersSeen` from LLM-call sampling to pick the right symbol.
 */
export function formatTotalCost(amount: number, providersSeen?: ReadonlySet<string>, fractionDigits = 2): string {
  if (!providersSeen || providersSeen.size === 0) return `$${amount.toFixed(fractionDigits)}`
  if (providersSeen.size === 1) {
    const only = providersSeen.values().next().value
    return `${providerCurrency(only)}${amount.toFixed(fractionDigits)}`
  }
  // Mixed providers — currencies aren't comparable, surface that visually.
  const hasRub = Array.from(providersSeen).some(p => RUBLE_PROVIDERS.has(p as LlmProvider))
  const hasUsd = Array.from(providersSeen).some(p => !RUBLE_PROVIDERS.has(p as LlmProvider))
  if (hasRub && hasUsd) return `~${amount.toFixed(fractionDigits)} (₽+$)`
  return `${providerCurrency(Array.from(providersSeen)[0])}${amount.toFixed(fractionDigits)}`
}

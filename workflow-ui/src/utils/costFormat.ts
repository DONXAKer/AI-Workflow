import type { LlmProvider } from '../types'

/**
 * Provider-aware cost formatter.
 *
 * Starting 2026-05-18 (S1 manager-audit fix) the backend converts AllTokens.ru's
 * ruble cost to USD-equivalent at the `cost_usd` storage site (rate ₽100/$1),
 * so DB column and `budget_usd_cap` comparisons are single-currency USD across
 * providers. UI does the reverse-conversion at render time for AllTokens calls
 * so the operator sees rubles they actually paid (matches AllTokens dashboard).
 * Other providers (OpenRouter, AITunnel, etc.) render as USD verbatim.
 */
export const RUBLE_PROVIDERS: ReadonlySet<LlmProvider> = new Set<LlmProvider>(['ALLTOKENS'])
const USD_TO_RUB = 100  // mirrors backend RUB_PER_USD in OpenAICompatibleProviderClient

export function providerCurrency(provider?: LlmProvider | string | null): '₽' | '$' {
  if (provider && RUBLE_PROVIDERS.has(provider as LlmProvider)) return '₽'
  return '$'
}

function displayAmount(storedUsd: number, provider?: LlmProvider | string | null): number {
  if (provider && RUBLE_PROVIDERS.has(provider as LlmProvider)) return storedUsd * USD_TO_RUB
  return storedUsd
}

/** Format a single LLM call cost using the call's own provider. */
export function formatCallCost(storedUsd: number, provider?: LlmProvider | string | null, fractionDigits = 4): string {
  const sym = providerCurrency(provider)
  return `${sym}${displayAmount(storedUsd, provider).toFixed(fractionDigits)}`
}

/**
 * Format a run-level / aggregated cost. If a provider is known (single-provider run),
 * use its symbol; otherwise default to `$` but mark ambiguity with a small "~".
 * Pass `providersSeen` from LLM-call sampling to pick the right symbol.
 */
export function formatTotalCost(storedUsd: number, providersSeen?: ReadonlySet<string>, fractionDigits = 2): string {
  if (!providersSeen || providersSeen.size === 0) return `$${storedUsd.toFixed(fractionDigits)}`
  if (providersSeen.size === 1) {
    const only = providersSeen.values().next().value
    return `${providerCurrency(only)}${displayAmount(storedUsd, only).toFixed(fractionDigits)}`
  }
  const hasRub = Array.from(providersSeen).some(p => RUBLE_PROVIDERS.has(p as LlmProvider))
  const hasUsd = Array.from(providersSeen).some(p => !RUBLE_PROVIDERS.has(p as LlmProvider))
  if (hasRub && hasUsd) return `~$${storedUsd.toFixed(fractionDigits)} (mixed)`
  return `${providerCurrency(Array.from(providersSeen)[0])}${displayAmount(storedUsd, Array.from(providersSeen)[0]).toFixed(fractionDigits)}`
}

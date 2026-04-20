import { FolderKanban } from 'lucide-react'
import { currentProjectSlug } from '../services/projectContext'

/**
 * Визуальный индикатор текущего project scope для страниц которые показывают
 * только данные одного проекта (Cost, Audit, Integrations, Runs History). Без
 * него оператор может не заметить что данные отфильтрованы, особенно при
 * переключении проектов с ожиданием увидеть "все данные".
 */
export default function ProjectScopeBadge({ className = '' }: { className?: string }) {
  const slug = currentProjectSlug()
  return (
    <span
      className={`inline-flex items-center gap-1.5 text-xs text-slate-400 bg-slate-800/50 border border-slate-700 rounded px-2 py-0.5 ${className}`}
      title="Данные отфильтрованы по текущему проекту"
      data-testid="project-scope-badge"
    >
      <FolderKanban className="w-3 h-3" />
      scope: <span className="font-mono text-slate-200">{slug}</span>
    </span>
  )
}

import { useState, useEffect, useCallback } from 'react'
import { FolderKanban, Plus, Pencil, Trash2, AlertCircle, Loader2, X, Save } from 'lucide-react'
import { api } from '../services/api'
import { ProjectInfo } from '../types'
import PageHeader from '../components/layout/PageHeader'
import PathInput from '../components/PathInput'

interface FormState {
  slug: string
  displayName: string
  description: string
  configDir: string
}

const EMPTY: FormState = { slug: '', displayName: '', description: '', configDir: './config' }

export default function ProjectsSettingsPage() {
  const [projects, setProjects] = useState<ProjectInfo[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [editing, setEditing] = useState<ProjectInfo | 'new' | null>(null)
  const [form, setForm] = useState<FormState>(EMPTY)
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      setProjects(await api.listProjects())
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Не удалось загрузить проекты')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [load])

  const startEdit = (p: ProjectInfo) => {
    setEditing(p)
    setForm({
      slug: p.slug,
      displayName: p.displayName,
      description: p.description ?? '',
      configDir: p.configDir ?? './config',
    })
    setSaveError(null)
  }

  const startNew = () => {
    setEditing('new')
    setForm(EMPTY)
    setSaveError(null)
  }

  const cancelEdit = () => {
    setEditing(null)
    setForm(EMPTY)
    setSaveError(null)
  }

  const submit = async () => {
    setSaving(true)
    setSaveError(null)
    try {
      if (editing === 'new') {
        await api.createProject({
          slug: form.slug.trim(),
          displayName: form.displayName.trim(),
          description: form.description.trim() || undefined,
          configDir: form.configDir.trim() || undefined,
        })
      } else if (editing) {
        await api.updateProject(editing.slug, {
          displayName: form.displayName.trim(),
          description: form.description.trim(),
          configDir: form.configDir.trim(),
        })
      }
      await load()
      cancelEdit()
    } catch (e) {
      setSaveError(e instanceof Error ? e.message : 'Не удалось сохранить')
    } finally {
      setSaving(false)
    }
  }

  const remove = async (slug: string) => {
    if (!confirm(`Удалить проект "${slug}"?`)) return
    try {
      await api.deleteProject(slug)
      await load()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Не удалось удалить')
    }
  }

  return (
    <div className="max-w-5xl mx-auto px-4 sm:px-6 lg:px-8 py-6 space-y-6">
      <PageHeader
        title="Проекты"
        breadcrumbs={[{ label: 'Настройки' }, { label: 'Проекты' }]}
        actions={
          <button
            type="button"
            onClick={startNew}
            disabled={editing !== null}
            className="flex items-center gap-2 bg-blue-600 hover:bg-blue-500 disabled:bg-slate-700 text-white text-sm font-medium px-3 py-1.5 rounded-lg transition-colors"
          >
            <Plus className="w-4 h-4" /> Новый проект
          </button>
        }
      />

      {error && (
        <div className="flex items-center gap-2 text-red-400 bg-red-950/40 border border-red-800 rounded-lg px-4 py-3">
          <AlertCircle className="w-4 h-4 flex-shrink-0" /> {error}
        </div>
      )}

      {editing !== null && (
        <div className="bg-slate-900 border border-slate-800 rounded-xl p-5 space-y-4">
          <div className="flex items-center justify-between">
            <h2 className="text-sm font-semibold text-white">
              {editing === 'new' ? 'Создать проект' : `Редактировать: ${editing.displayName}`}
            </h2>
            <button type="button" onClick={cancelEdit} className="p-1 text-slate-500 hover:text-white">
              <X className="w-4 h-4" />
            </button>
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <div>
              <label htmlFor="slug" className="block text-xs font-medium text-slate-400 uppercase tracking-wide mb-1.5">
                Slug (URL-friendly)
              </label>
              <input
                id="slug"
                type="text"
                value={form.slug}
                onChange={e => setForm(f => ({ ...f, slug: e.target.value }))}
                disabled={editing !== 'new' || saving}
                placeholder="backend-platform"
                className="w-full bg-slate-950 border border-slate-700 rounded-lg px-3 py-2 text-sm text-slate-100 font-mono focus:outline-none focus:ring-2 focus:ring-blue-500 disabled:opacity-50"
              />
              {editing !== 'new' && (
                <p className="text-xs text-slate-500 mt-1">Slug менять нельзя — это стабильный ID проекта.</p>
              )}
            </div>
            <div>
              <label htmlFor="displayName" className="block text-xs font-medium text-slate-400 uppercase tracking-wide mb-1.5">
                Название
              </label>
              <input
                id="displayName"
                type="text"
                value={form.displayName}
                onChange={e => setForm(f => ({ ...f, displayName: e.target.value }))}
                disabled={saving}
                placeholder="Backend Platform"
                className="w-full bg-slate-950 border border-slate-700 rounded-lg px-3 py-2 text-sm text-slate-100 focus:outline-none focus:ring-2 focus:ring-blue-500 disabled:opacity-50"
              />
            </div>
            <div className="sm:col-span-2">
              <label htmlFor="description" className="block text-xs font-medium text-slate-400 uppercase tracking-wide mb-1.5">
                Описание
              </label>
              <textarea
                id="description"
                value={form.description}
                onChange={e => setForm(f => ({ ...f, description: e.target.value }))}
                disabled={saving}
                rows={2}
                className="w-full bg-slate-950 border border-slate-700 rounded-lg px-3 py-2 text-sm text-slate-100 focus:outline-none focus:ring-2 focus:ring-blue-500 disabled:opacity-50 resize-none"
              />
            </div>
            <div className="sm:col-span-2">
              <label className="block text-xs font-medium text-slate-400 uppercase tracking-wide mb-1.5">
                Путь к pipeline-конфигам
              </label>
              <PathInput
                value={form.configDir}
                onChange={v => setForm(f => ({ ...f, configDir: v }))}
                placeholder="./config"
                disabled={saving}
              />
            </div>
          </div>

          {saveError && (
            <div className="flex items-center gap-2 text-red-400 text-sm bg-red-950/40 border border-red-800 rounded-lg px-3 py-2">
              <AlertCircle className="w-4 h-4 flex-shrink-0" /> {saveError}
            </div>
          )}

          <div className="flex items-center justify-end gap-2">
            <button
              type="button"
              onClick={cancelEdit}
              disabled={saving}
              className="text-sm text-slate-400 hover:text-slate-200 px-4 py-2 rounded-lg transition-colors disabled:opacity-50"
            >
              Отмена
            </button>
            <button
              type="button"
              onClick={submit}
              disabled={saving || !form.slug.trim() || !form.displayName.trim()}
              className="flex items-center gap-2 bg-blue-600 hover:bg-blue-500 disabled:bg-slate-700 disabled:text-slate-500 text-white text-sm font-medium px-4 py-2 rounded-lg transition-colors"
            >
              {saving ? <Loader2 className="w-4 h-4 animate-spin" /> : <Save className="w-4 h-4" />}
              Сохранить
            </button>
          </div>
        </div>
      )}

      <div className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden">
        <div className="px-4 py-3 border-b border-slate-800 flex items-center gap-2">
          <FolderKanban className="w-4 h-4 text-slate-400" />
          <span className="text-sm font-medium text-slate-300">
            {projects.length} {projects.length === 1 ? 'проект' : 'проекта(-ов)'}
          </span>
          {loading && <Loader2 className="w-4 h-4 animate-spin text-slate-500 ml-2" />}
        </div>
        <ul className="divide-y divide-slate-800/60">
          {projects.length === 0 && !loading && (
            <li className="text-center py-8 text-slate-500">Проектов ещё нет — создайте первый.</li>
          )}
          {projects.map(p => (
            <li key={p.id} className="px-4 py-3 flex items-center gap-3">
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2">
                  <span className="text-sm font-medium text-white truncate">{p.displayName}</span>
                  <span className="text-xs text-slate-500 font-mono">{p.slug}</span>
                </div>
                {p.description && (
                  <p className="text-xs text-slate-400 mt-0.5 truncate">{p.description}</p>
                )}
                {p.configDir && (
                  <p className="text-xs text-slate-500 font-mono mt-0.5">📁 {p.configDir}</p>
                )}
              </div>
              <div className="flex items-center gap-1">
                <button
                  type="button"
                  onClick={() => startEdit(p)}
                  aria-label={`Редактировать ${p.slug}`}
                  className="p-1.5 rounded-md text-slate-400 hover:text-white hover:bg-slate-800"
                >
                  <Pencil className="w-3.5 h-3.5" />
                </button>
                <button
                  type="button"
                  onClick={() => remove(p.slug)}
                  aria-label={`Удалить ${p.slug}`}
                  title="Удалить"
                  className="p-1.5 rounded-md text-slate-400 hover:text-red-300 hover:bg-red-950/40"
                >
                  <Trash2 className="w-3.5 h-3.5" />
                </button>
              </div>
            </li>
          ))}
        </ul>
      </div>
    </div>
  )
}

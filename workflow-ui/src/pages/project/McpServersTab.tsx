import { useState, useEffect } from 'react'
import { Plus, Trash2, CheckCircle, XCircle, Loader2, Plug, ToggleLeft, ToggleRight, ChevronDown, ChevronUp, Edit2 } from 'lucide-react'
import { api } from '../../services/api'
import { McpServer } from '../../types'

const EMPTY: Omit<McpServer, 'id' | 'createdAt' | 'updatedAt'> = {
  name: '',
  description: '',
  url: '',
  headersJson: '',
  enabled: true,
}

export default function McpServersTab() {
  const [servers, setServers] = useState<McpServer[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [showForm, setShowForm] = useState(false)
  const [editId, setEditId] = useState<number | null>(null)
  const [form, setForm] = useState({ ...EMPTY })
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
  const [testResults, setTestResults] = useState<Record<number, { success: boolean; message: string } | 'testing'>>({})
  const [expandedId, setExpandedId] = useState<number | null>(null)

  useEffect(() => {
    load()
  }, [])

  function load() {
    setLoading(true)
    setError(null)
    api.listMcpServers()
      .then(setServers)
      .catch(e => setError(e instanceof Error ? e.message : 'Ошибка загрузки'))
      .finally(() => setLoading(false))
  }

  function openCreate() {
    setEditId(null)
    setForm({ ...EMPTY })
    setSaveError(null)
    setShowForm(true)
  }

  function openEdit(s: McpServer) {
    setEditId(s.id ?? null)
    setForm({
      name: s.name,
      description: s.description ?? '',
      url: s.url,
      headersJson: s.headersJson ?? '',
      enabled: s.enabled,
    })
    setSaveError(null)
    setShowForm(true)
  }

  function cancel() {
    setShowForm(false)
    setEditId(null)
    setSaveError(null)
  }

  async function save() {
    if (!form.name.trim() || !form.url.trim()) {
      setSaveError('Название и URL обязательны')
      return
    }
    setSaving(true)
    setSaveError(null)
    try {
      if (editId != null) {
        const updated = await api.updateMcpServer(editId, form)
        setServers(prev => prev.map(s => s.id === editId ? updated : s))
      } else {
        const created = await api.createMcpServer(form)
        setServers(prev => [...prev, created])
      }
      setShowForm(false)
      setEditId(null)
    } catch (e) {
      setSaveError(e instanceof Error ? e.message : 'Ошибка сохранения')
    } finally {
      setSaving(false)
    }
  }

  async function remove(id: number) {
    if (!confirm('Удалить MCP сервер?')) return
    try {
      await api.deleteMcpServer(id)
      setServers(prev => prev.filter(s => s.id !== id))
    } catch (e) {
      alert(e instanceof Error ? e.message : 'Ошибка удаления')
    }
  }

  async function toggle(id: number) {
    try {
      const updated = await api.toggleMcpServer(id)
      setServers(prev => prev.map(s => s.id === id ? updated : s))
    } catch (e) {
      alert(e instanceof Error ? e.message : 'Ошибка')
    }
  }

  async function test(id: number) {
    setTestResults(prev => ({ ...prev, [id]: 'testing' }))
    try {
      const result = await api.testMcpServer(id)
      setTestResults(prev => ({ ...prev, [id]: result }))
    } catch (e) {
      setTestResults(prev => ({
        ...prev,
        [id]: { success: false, message: e instanceof Error ? e.message : 'Ошибка' }
      }))
    }
  }

  return (
    <div className="max-w-3xl mx-auto px-4 sm:px-6 py-6 space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-base font-semibold text-white">MCP Серверы</h2>
          <p className="text-xs text-slate-500 mt-0.5">
            Model Context Protocol — внешние поставщики инструментов для агентных блоков
          </p>
        </div>
        <button
          type="button"
          onClick={openCreate}
          className="flex items-center gap-1.5 text-xs px-3 py-1.5 bg-blue-600 hover:bg-blue-700 text-white rounded-md transition-colors"
        >
          <Plus className="w-3.5 h-3.5" /> Добавить
        </button>
      </div>

      {error && (
        <div className="flex items-center gap-2 text-red-400 bg-red-950/40 border border-red-800 rounded-lg px-4 py-3 text-sm">
          <XCircle className="w-4 h-4 flex-shrink-0" /> {error}
        </div>
      )}

      {showForm && (
        <div className="bg-slate-900 border border-slate-700 rounded-xl p-5 space-y-4">
          <h3 className="text-sm font-medium text-white">
            {editId ? 'Редактировать MCP сервер' : 'Новый MCP сервер'}
          </h3>

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-xs text-slate-400 mb-1">Название *</label>
              <input
                className="w-full bg-slate-800 border border-slate-700 rounded-md px-3 py-2 text-sm text-white placeholder-slate-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                placeholder="my-mcp-server"
                value={form.name}
                onChange={e => setForm(f => ({ ...f, name: e.target.value }))}
              />
            </div>
            <div>
              <label className="block text-xs text-slate-400 mb-1">URL *</label>
              <input
                className="w-full bg-slate-800 border border-slate-700 rounded-md px-3 py-2 text-sm text-white placeholder-slate-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                placeholder="http://localhost:3000"
                value={form.url}
                onChange={e => setForm(f => ({ ...f, url: e.target.value }))}
              />
            </div>
          </div>

          <div>
            <label className="block text-xs text-slate-400 mb-1">Описание</label>
            <input
              className="w-full bg-slate-800 border border-slate-700 rounded-md px-3 py-2 text-sm text-white placeholder-slate-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
              placeholder="Опциональное описание"
              value={form.description}
              onChange={e => setForm(f => ({ ...f, description: e.target.value }))}
            />
          </div>

          <div>
            <label className="block text-xs text-slate-400 mb-1">
              Заголовки (JSON)
              <span className="ml-1 text-slate-600">{"— например: {\"Authorization\": \"Bearer token\"}"}</span>
            </label>
            <textarea
              className="w-full bg-slate-800 border border-slate-700 rounded-md px-3 py-2 text-sm text-white font-mono placeholder-slate-500 focus:outline-none focus:ring-1 focus:ring-blue-500 resize-none"
              rows={3}
              placeholder='{}'
              value={form.headersJson}
              onChange={e => setForm(f => ({ ...f, headersJson: e.target.value }))}
            />
          </div>

          <div className="flex items-center gap-2">
            <input
              type="checkbox"
              id="mcp-enabled"
              checked={form.enabled}
              onChange={e => setForm(f => ({ ...f, enabled: e.target.checked }))}
              className="w-4 h-4 rounded border-slate-600 bg-slate-800 text-blue-600 focus:ring-blue-500"
            />
            <label htmlFor="mcp-enabled" className="text-sm text-slate-300">Включён</label>
          </div>

          {saveError && (
            <p className="text-xs text-red-400">{saveError}</p>
          )}

          <div className="flex gap-2 justify-end">
            <button
              type="button"
              onClick={cancel}
              className="text-xs px-3 py-1.5 bg-slate-800 hover:bg-slate-700 text-slate-300 rounded-md transition-colors"
            >
              Отмена
            </button>
            <button
              type="button"
              onClick={save}
              disabled={saving}
              className="flex items-center gap-1.5 text-xs px-3 py-1.5 bg-blue-600 hover:bg-blue-700 disabled:opacity-50 text-white rounded-md transition-colors"
            >
              {saving && <Loader2 className="w-3 h-3 animate-spin" />}
              {editId ? 'Сохранить' : 'Добавить'}
            </button>
          </div>
        </div>
      )}

      {loading ? (
        <div className="flex items-center justify-center py-12 text-slate-400">
          <Loader2 className="w-5 h-5 animate-spin" />
        </div>
      ) : servers.length === 0 ? (
        <div className="bg-slate-900 border border-slate-800 rounded-xl px-6 py-14 text-center">
          <Plug className="w-8 h-8 text-slate-600 mx-auto mb-3" />
          <p className="text-slate-500 text-sm">MCP серверов пока нет.</p>
          <p className="text-slate-600 text-xs mt-1">
            Добавьте сервер и подключайте его в блоках агентов через <code className="text-slate-500">mcp_servers: [name]</code>
          </p>
        </div>
      ) : (
        <div className="space-y-2">
          {servers.map(s => {
            const testResult = s.id != null ? testResults[s.id] : undefined
            const expanded = s.id === expandedId

            return (
              <div
                key={s.id}
                className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden"
              >
                <div className="flex items-center gap-3 px-4 py-3">
                  <button
                    type="button"
                    onClick={() => s.id != null && toggle(s.id)}
                    className="text-slate-400 hover:text-slate-200 flex-shrink-0"
                    title={s.enabled ? 'Выключить' : 'Включить'}
                  >
                    {s.enabled
                      ? <ToggleRight className="w-5 h-5 text-blue-400" />
                      : <ToggleLeft className="w-5 h-5" />}
                  </button>

                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2">
                      <span className="text-sm font-medium text-white">{s.name}</span>
                      {!s.enabled && (
                        <span className="text-xs bg-slate-700 text-slate-400 px-1.5 py-0.5 rounded">выкл</span>
                      )}
                    </div>
                    <span className="text-xs text-slate-500 font-mono truncate block">{s.url}</span>
                  </div>

                  <div className="flex items-center gap-1.5">
                    {testResult === 'testing' ? (
                      <Loader2 className="w-4 h-4 animate-spin text-slate-400" />
                    ) : testResult ? (
                      <span title={testResult.message}>
                        {testResult.success
                          ? <CheckCircle className="w-4 h-4 text-green-400" />
                          : <XCircle className="w-4 h-4 text-red-400" />}
                      </span>
                    ) : null}

                    <button
                      type="button"
                      onClick={() => s.id != null && test(s.id)}
                      className="text-xs px-2 py-1 bg-slate-800 hover:bg-slate-700 text-slate-300 rounded transition-colors"
                    >
                      Тест
                    </button>
                    <button
                      type="button"
                      onClick={() => openEdit(s)}
                      className="p-1.5 hover:bg-slate-800 text-slate-400 hover:text-slate-200 rounded transition-colors"
                    >
                      <Edit2 className="w-3.5 h-3.5" />
                    </button>
                    <button
                      type="button"
                      onClick={() => s.id != null && remove(s.id)}
                      className="p-1.5 hover:bg-red-900/40 text-slate-500 hover:text-red-400 rounded transition-colors"
                    >
                      <Trash2 className="w-3.5 h-3.5" />
                    </button>
                    <button
                      type="button"
                      onClick={() => setExpandedId(expanded ? null : (s.id ?? null))}
                      className="p-1.5 hover:bg-slate-800 text-slate-400 rounded transition-colors"
                    >
                      {expanded ? <ChevronUp className="w-3.5 h-3.5" /> : <ChevronDown className="w-3.5 h-3.5" />}
                    </button>
                  </div>
                </div>

                {expanded && (
                  <div className="border-t border-slate-800 px-4 py-3 space-y-2 text-xs">
                    {s.description && (
                      <p className="text-slate-400">{s.description}</p>
                    )}
                    <div className="text-slate-500">
                      <span className="text-slate-600">Использование в YAML:</span>
                      <pre className="mt-1 bg-slate-800 rounded px-3 py-2 text-slate-300 font-mono text-xs overflow-auto">
{`- id: impl
  block: agent_with_tools
  config:
    mcp_servers:
      - ${s.name}`}
                      </pre>
                    </div>
                    {s.headersJson && s.headersJson !== '{}' && (
                      <p className="text-slate-500">
                        <span className="text-slate-600">Заголовки: </span>
                        <code className="font-mono">{s.headersJson}</code>
                      </p>
                    )}
                    {testResult && testResult !== 'testing' && (
                      <p className={testResult.success ? 'text-green-400' : 'text-red-400'}>
                        {testResult.message}
                      </p>
                    )}
                  </div>
                )}
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}

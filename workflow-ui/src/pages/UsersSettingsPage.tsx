import { useState, useEffect, useCallback } from 'react'
import { Users, Plus, Pencil, Trash2, AlertCircle, Loader2, X, Save, KeyRound } from 'lucide-react'
import { api } from '../services/api'
import { UserInfo, UserRole, CreateUserBody, UpdateUserBody } from '../types'
import PageHeader from '../components/layout/PageHeader'
import clsx from 'clsx'

const ROLES: UserRole[] = ['VIEWER', 'OPERATOR', 'RELEASE_MANAGER', 'ADMIN']
const ROLE_LABEL: Record<UserRole, string> = {
  VIEWER: 'Viewer',
  OPERATOR: 'Operator',
  RELEASE_MANAGER: 'Release Manager',
  ADMIN: 'Admin',
}

const ROLE_COLOR: Record<UserRole, string> = {
  VIEWER: 'bg-slate-800 border-slate-700 text-slate-400',
  OPERATOR: 'bg-blue-950/40 border-blue-800/60 text-blue-300',
  RELEASE_MANAGER: 'bg-amber-950/40 border-amber-800/60 text-amber-300',
  ADMIN: 'bg-red-950/40 border-red-800/60 text-red-300',
}

interface FormState {
  username: string
  password: string
  displayName: string
  email: string
  role: UserRole
  enabled: boolean
}

const EMPTY: FormState = {
  username: '', password: '', displayName: '', email: '', role: 'VIEWER', enabled: true,
}

function RoleBadge({ role }: { role: UserRole }) {
  return (
    <span className={clsx('inline-flex items-center px-2 py-0.5 rounded text-xs font-medium border', ROLE_COLOR[role])}>
      {ROLE_LABEL[role]}
    </span>
  )
}

export default function UsersSettingsPage() {
  const [users, setUsers] = useState<UserInfo[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [editing, setEditing] = useState<UserInfo | 'new' | null>(null)
  const [form, setForm] = useState<FormState>(EMPTY)
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      setUsers(await api.listUsers())
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Не удалось загрузить пользователей')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [load])

  const startEdit = (u: UserInfo) => {
    setEditing(u)
    setForm({
      username: u.username,
      password: '',
      displayName: u.displayName ?? '',
      email: u.email ?? '',
      role: u.role,
      enabled: u.enabled,
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
        const body: CreateUserBody = {
          username: form.username.trim(),
          password: form.password,
          displayName: form.displayName.trim() || undefined,
          email: form.email.trim() || undefined,
          role: form.role,
        }
        await api.createUser(body)
      } else if (editing) {
        const body: UpdateUserBody = {
          displayName: form.displayName.trim(),
          email: form.email.trim(),
          role: form.role,
          enabled: form.enabled,
        }
        if (form.password) body.password = form.password
        await api.updateUser(editing.id, body)
      }
      await load()
      cancelEdit()
    } catch (e) {
      setSaveError(e instanceof Error ? e.message : 'Не удалось сохранить')
    } finally {
      setSaving(false)
    }
  }

  const remove = async (u: UserInfo) => {
    if (!confirm(`Удалить пользователя "${u.username}"?`)) return
    try {
      await api.deleteUser(u.id)
      await load()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Не удалось удалить')
    }
  }

  return (
    <div className="max-w-5xl mx-auto px-4 sm:px-6 lg:px-8 py-6 space-y-6">
      <PageHeader
        title="Пользователи"
        breadcrumbs={[{ label: 'Настройки' }, { label: 'Пользователи' }]}
        actions={
          <button
            type="button"
            onClick={startNew}
            disabled={editing !== null}
            className="flex items-center gap-2 bg-blue-600 hover:bg-blue-500 disabled:bg-slate-700 text-white text-sm font-medium px-3 py-1.5 rounded-lg transition-colors"
          >
            <Plus className="w-4 h-4" /> Новый пользователь
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
              {editing === 'new' ? 'Создать пользователя' : `Редактировать: ${editing.username}`}
            </h2>
            <button type="button" onClick={cancelEdit} className="p-1 text-slate-500 hover:text-white">
              <X className="w-4 h-4" />
            </button>
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <div>
              <label htmlFor="username" className="block text-xs font-medium text-slate-400 uppercase tracking-wide mb-1.5">
                Логин
              </label>
              <input
                id="username"
                type="text"
                value={form.username}
                onChange={e => setForm(f => ({ ...f, username: e.target.value }))}
                disabled={editing !== 'new' || saving}
                placeholder="alice"
                autoComplete="off"
                className="w-full bg-slate-950 border border-slate-700 rounded-lg px-3 py-2 text-sm text-slate-100 font-mono focus:outline-none focus:ring-2 focus:ring-blue-500 disabled:opacity-50"
              />
            </div>
            <div>
              <label htmlFor="displayName" className="block text-xs font-medium text-slate-400 uppercase tracking-wide mb-1.5">
                Имя
              </label>
              <input
                id="displayName"
                type="text"
                value={form.displayName}
                onChange={e => setForm(f => ({ ...f, displayName: e.target.value }))}
                disabled={saving}
                placeholder="Alice Johnson"
                className="w-full bg-slate-950 border border-slate-700 rounded-lg px-3 py-2 text-sm text-slate-100 focus:outline-none focus:ring-2 focus:ring-blue-500 disabled:opacity-50"
              />
            </div>
            <div>
              <label htmlFor="email" className="block text-xs font-medium text-slate-400 uppercase tracking-wide mb-1.5">
                Email
              </label>
              <input
                id="email"
                type="email"
                value={form.email}
                onChange={e => setForm(f => ({ ...f, email: e.target.value }))}
                disabled={saving}
                placeholder="alice@example.com"
                className="w-full bg-slate-950 border border-slate-700 rounded-lg px-3 py-2 text-sm text-slate-100 focus:outline-none focus:ring-2 focus:ring-blue-500 disabled:opacity-50"
              />
            </div>
            <div>
              <label htmlFor="role" className="block text-xs font-medium text-slate-400 uppercase tracking-wide mb-1.5">
                Роль
              </label>
              <select
                id="role"
                value={form.role}
                onChange={e => setForm(f => ({ ...f, role: e.target.value as UserRole }))}
                disabled={saving}
                className="w-full bg-slate-950 border border-slate-700 rounded-lg px-3 py-2 text-sm text-slate-100 focus:outline-none focus:ring-2 focus:ring-blue-500 disabled:opacity-50"
              >
                {ROLES.map(r => (
                  <option key={r} value={r}>{ROLE_LABEL[r]}</option>
                ))}
              </select>
            </div>
            <div className="sm:col-span-2">
              <label htmlFor="password" className="block text-xs font-medium text-slate-400 uppercase tracking-wide mb-1.5">
                Пароль {editing !== 'new' && <span className="text-slate-500 normal-case font-normal">(оставьте пустым чтобы не менять)</span>}
              </label>
              <input
                id="password"
                type="password"
                value={form.password}
                onChange={e => setForm(f => ({ ...f, password: e.target.value }))}
                disabled={saving}
                placeholder="Минимум 8 символов"
                autoComplete="new-password"
                className="w-full bg-slate-950 border border-slate-700 rounded-lg px-3 py-2 text-sm text-slate-100 focus:outline-none focus:ring-2 focus:ring-blue-500 disabled:opacity-50"
              />
            </div>
            {editing !== 'new' && (
              <div className="sm:col-span-2">
                <label className="flex items-center gap-2 text-sm text-slate-300 cursor-pointer">
                  <input
                    type="checkbox"
                    checked={form.enabled}
                    onChange={e => setForm(f => ({ ...f, enabled: e.target.checked }))}
                    disabled={saving}
                    className="rounded bg-slate-800 border-slate-600"
                  />
                  Пользователь активен
                </label>
              </div>
            )}
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
              disabled={
                saving ||
                (editing === 'new' && (!form.username.trim() || form.password.length < 8))
              }
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
          <Users className="w-4 h-4 text-slate-400" />
          <span className="text-sm font-medium text-slate-300">
            {users.length} {users.length === 1 ? 'пользователь' : 'пользователя(-ей)'}
          </span>
          {loading && <Loader2 className="w-4 h-4 animate-spin text-slate-500 ml-2" />}
        </div>
        <ul className="divide-y divide-slate-800/60">
          {users.length === 0 && !loading && (
            <li className="text-center py-8 text-slate-500">Пользователей ещё нет</li>
          )}
          {users.map(u => (
            <li
              key={u.id}
              className={clsx(
                'px-4 py-3 flex items-center gap-3',
                !u.enabled && 'opacity-60'
              )}
            >
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2 flex-wrap">
                  <span className="text-sm font-medium text-white truncate">
                    {u.displayName || u.username}
                  </span>
                  <span className="text-xs text-slate-500 font-mono">{u.username}</span>
                  <RoleBadge role={u.role} />
                  {!u.enabled && (
                    <span className="text-xs bg-slate-800 border border-slate-700 text-slate-500 px-1.5 py-0.5 rounded">
                      disabled
                    </span>
                  )}
                </div>
                {u.email && <p className="text-xs text-slate-400 mt-0.5 truncate">{u.email}</p>}
              </div>
              <div className="flex items-center gap-1">
                <button
                  type="button"
                  onClick={() => startEdit(u)}
                  aria-label={`Редактировать ${u.username}`}
                  className="p-1.5 rounded-md text-slate-400 hover:text-white hover:bg-slate-800"
                >
                  <Pencil className="w-3.5 h-3.5" />
                </button>
                <button
                  type="button"
                  onClick={() => remove(u)}
                  aria-label={`Удалить ${u.username}`}
                  className="p-1.5 rounded-md text-slate-400 hover:text-red-300 hover:bg-red-950/40"
                >
                  <Trash2 className="w-3.5 h-3.5" />
                </button>
              </div>
            </li>
          ))}
        </ul>
      </div>

      <p className="text-xs text-slate-500 flex items-center gap-1.5">
        <KeyRound className="w-3 h-3" />
        Пароли хранятся как bcrypt-хеши и не экспонируются через API.
      </p>
    </div>
  )
}

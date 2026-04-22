import { useState } from 'react'
import { FolderOpen } from 'lucide-react'
import DirectoryBrowserModal from './DirectoryBrowserModal'

interface Props {
  value: string
  onChange: (value: string) => void
  placeholder?: string
  disabled?: boolean
  id?: string
}

export default function PathInput({ value, onChange, placeholder = './path', disabled, id }: Props) {
  const [showBrowser, setShowBrowser] = useState(false)

  return (
    <>
      <div className="flex gap-2">
        <input
          id={id}
          type="text"
          value={value}
          onChange={e => onChange(e.target.value)}
          placeholder={placeholder}
          disabled={disabled}
          className="flex-1 bg-slate-950 border border-slate-700 rounded-lg px-3 py-2 text-sm text-slate-100 font-mono focus:outline-none focus:ring-2 focus:ring-blue-500 disabled:opacity-50"
        />
        <button
          type="button"
          disabled={disabled}
          onClick={() => setShowBrowser(true)}
          title="Выбрать папку"
          className="flex items-center gap-1.5 px-3 py-2 bg-slate-800 hover:bg-slate-700 disabled:opacity-50 border border-slate-700 rounded-lg text-slate-300 hover:text-white text-sm transition-colors flex-shrink-0"
        >
          <FolderOpen className="w-4 h-4" />
          <span className="hidden sm:inline">Обзор</span>
        </button>
      </div>

      {showBrowser && (
        <DirectoryBrowserModal
          initialPath={value || undefined}
          onSelect={path => { onChange(path); setShowBrowser(false) }}
          onClose={() => setShowBrowser(false)}
        />
      )}
    </>
  )
}

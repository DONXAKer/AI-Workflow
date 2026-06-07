import { useState, useEffect } from 'react'
import { Save, Loader2, AlertCircle, RefreshCw, Plus, X, Edit2, Check, ChevronDown, ChevronUp } from 'lucide-react'
import { api } from '../services/api'

/** Reads the XSRF-TOKEN cookie set by Spring Security's CookieCsrfTokenRepository. */
function getCsrfToken(): string | null {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/)
  return match ? decodeURIComponent(match[1]) : null
}

interface TechItem {
  name: string
  version?: string
}

interface TechStackEditorProps {
  projectSlug: string
  initialTechStack?: string
  onTechStackChange?: (techStack: string) => void
}

export default function TechStackEditor({ projectSlug, initialTechStack, onTechStackChange }: TechStackEditorProps) {
  const [techStack, setTechStack] = useState<TechItem[]>([])
  const [loading, setLoading] = useState(false)
  const [detecting, setDetecting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [editing, setEditing] = useState(false)
  const [newTech, setNewTech] = useState({ name: '', version: '' })
  const [expandedItems, setExpandedItems] = useState<Set<string>>(new Set())
  const [hasChanges, setHasChanges] = useState(false)

  // Parse initial tech stack
  useEffect(() => {
    try {
      if (initialTechStack) {
        const parsed = JSON.parse(initialTechStack)
        setTechStack(Array.isArray(parsed) ? parsed : [])
      }
    } catch (e) {
      console.warn('Failed to parse tech stack JSON:', e)
      setTechStack([])
    }
  }, [initialTechStack])

  const handleDetectStack = async () => {
    if (!projectSlug) return
    
    setDetecting(true)
    setError(null)
    try {
      const response = await fetch(`/api/projects/${projectSlug}/detect-stack`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-XSRF-TOKEN': getCsrfToken() || '',
        },
        credentials: 'same-origin',
      })
      
      if (!response.ok) {
        if (response.status === 404) {
          setError('Project not found')
        } else if (response.status === 503) {
          setError('Stack scanner not available')
        } else {
          const errorData = await response.json().catch(() => ({}))
          setError(errorData.error || `Failed to detect tech stack (${response.status})`)
        }
        return
      }
      
      const data = await response.json()
      if (data.success) {
        const detected = JSON.parse(data.techStack || '[]')
        setTechStack(detected)
        setHasChanges(true)
        onTechStackChange?.(data.techStack)
      } else {
        setError(data.error || 'Failed to detect tech stack')
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to detect tech stack')
    } finally {
      setDetecting(false)
    }
  }

  const handleSave = async () => {
    if (!projectSlug) return
    
    setLoading(true)
    setError(null)
    try {
      const techStackJson = JSON.stringify(techStack)
      await api.updateProject(projectSlug, { techStackJson })
      setHasChanges(false)
      onTechStackChange?.(techStackJson)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to save tech stack')
    } finally {
      setLoading(false)
    }
  }

  const handleAddTech = () => {
    if (newTech.name.trim()) {
      const updated = [...techStack, { name: newTech.name.trim(), version: newTech.version.trim() || undefined }]
      setTechStack(updated)
      setNewTech({ name: '', version: '' })
      setHasChanges(true)
    }
  }

  const handleRemoveTech = (index: number) => {
    const updated = techStack.filter((_, i) => i !== index)
    setTechStack(updated)
    setHasChanges(true)
  }

  const toggleExpanded = (name: string) => {
    const set = new Set(expandedItems)
    if (set.has(name)) {
      set.delete(name)
    } else {
      set.add(name)
    }
    setExpandedItems(set)
  }

  const groupedTech = techStack.reduce((acc, tech) => {
    const category = getTechCategory(tech.name)
    if (!acc[category]) acc[category] = []
    acc[category].push(tech)
    return acc
  }, {} as Record<string, TechItem[]>)

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-xl p-5 space-y-4" data-testid="tech-stack-editor">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <h3 className="text-sm font-medium text-slate-300">Tech Stack</h3>
          {techStack.length > 0 && (
            <span className="text-xs bg-slate-800 text-slate-400 px-1.5 py-0.5 rounded-full">
              {techStack.length}
            </span>
          )}
        </div>
        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={handleDetectStack}
            disabled={detecting || !projectSlug}
            className="flex items-center gap-2 bg-purple-700 hover:bg-purple-600 disabled:bg-slate-700 disabled:text-slate-500 text-white text-xs font-medium px-3 py-1.5 rounded-lg transition-colors"
            data-testid="rescan-button"
          >
            {detecting ? (
              <Loader2 className="w-3.5 h-3.5 animate-spin" data-testid="loading-spinner" />
            ) : (
              <RefreshCw className="w-3.5 h-3.5" />
            )}
            {detecting ? 'Scanning...' : 'Rescan'}
          </button>
          {hasChanges && (
            <button
              type="button"
              onClick={handleSave}
              disabled={loading}
              className="flex items-center gap-2 bg-blue-600 hover:bg-blue-500 disabled:bg-slate-700 disabled:text-slate-500 text-white text-xs font-medium px-3 py-1.5 rounded-lg transition-colors"
              data-testid="save-button"
            >
              {loading ? (
                <Loader2 className="w-3.5 h-3.5 animate-spin" />
              ) : (
                <Save className="w-3.5 h-3.5" />
              )}
              Save
            </button>
          )}
        </div>
      </div>

      {error && (
        <div className="flex items-center gap-2 text-red-400 text-sm bg-red-950/40 border border-red-800 rounded-lg px-3 py-2" data-testid="error-message">
          <AlertCircle className="w-4 h-4 flex-shrink-0" /> {error}
        </div>
      )}

      {techStack.length === 0 ? (
        <div className="text-center py-8">
          <p className="text-slate-500 text-sm mb-3">No technologies detected</p>
          <button
            type="button"
            onClick={handleDetectStack}
            disabled={detecting || !projectSlug}
            className="flex items-center gap-2 mx-auto bg-purple-700 hover:bg-purple-600 disabled:bg-slate-700 disabled:text-slate-500 text-white text-xs font-medium px-3 py-1.5 rounded-lg transition-colors"
            data-testid="detect-stack-button"
          >
            {detecting ? (
              <Loader2 className="w-3.5 h-3.5 animate-spin" />
            ) : (
              <RefreshCw className="w-3.5 h-3.5" />
            )}
            Detect Stack
          </button>
        </div>
      ) : (
        <div className="space-y-3">
          {Object.entries(groupedTech).map(([category, items]) => (
            <div key={category} className="space-y-2">
              <button
                type="button"
                onClick={() => toggleExpanded(category)}
                className="flex items-center gap-2 text-xs font-medium text-slate-400 uppercase tracking-wide hover:text-slate-300 transition-colors"
              >
                {expandedItems.has(category) ? (
                  <ChevronUp className="w-3 h-3" />
                ) : (
                  <ChevronDown className="w-3 h-3" />
                )}
                {category} ({items.length})
              </button>
              
              {expandedItems.has(category) && (
                <div className="space-y-1 ml-5">
                  {items.map((tech, index) => (
                    <div key={`${tech.name}-${index}`} className="flex items-center gap-2 bg-slate-950 border border-slate-800 rounded-lg px-3 py-2">
                      <span className="text-sm font-medium text-slate-200 min-w-0 flex-1 truncate">
                        {tech.name}
                      </span>
                      {tech.version && (
                        <span className="text-xs text-slate-500 font-mono bg-slate-800 px-1.5 py-0.5 rounded">
                          {tech.version}
                        </span>
                      )}
                      {editing && (
                        <button
                          type="button"
                          onClick={() => handleRemoveTech(techStack.indexOf(tech))}
                          className="text-red-400 hover:text-red-300 transition-colors"
                        >
                          <X className="w-3.5 h-3.5" />
                        </button>
                      )}
                    </div>
                  ))}
                </div>
              )}
            </div>
          ))}
        </div>
      )}

      {editing && (
        <div className="border-t border-slate-800 pt-4 space-y-3">
          <h4 className="text-xs font-medium text-slate-400 uppercase tracking-wide">Add Technology</h4>
          <div className="flex gap-2">
            <input
              type="text"
              placeholder="Technology name"
              value={newTech.name}
              onChange={e => setNewTech({ ...newTech, name: e.target.value })}
              className="flex-1 bg-slate-950 border border-slate-700 rounded-lg px-3 py-2 text-sm text-slate-100 focus:outline-none focus:ring-2 focus:ring-blue-500"
              data-testid="new-tech-name"
            />
            <input
              type="text"
              placeholder="Version (optional)"
              value={newTech.version}
              onChange={e => setNewTech({ ...newTech, version: e.target.value })}
              className="w-32 bg-slate-950 border border-slate-700 rounded-lg px-3 py-2 text-sm text-slate-100 focus:outline-none focus:ring-2 focus:ring-blue-500"
              data-testid="new-tech-version"
            />
            <button
              type="button"
              onClick={handleAddTech}
              disabled={!newTech.name.trim()}
              className="flex items-center gap-2 bg-green-700 hover:bg-green-600 disabled:bg-slate-700 disabled:text-slate-500 text-white text-sm font-medium px-3 py-2 rounded-lg transition-colors"
              data-testid="add-tech-button"
            >
              <Plus className="w-4 h-4" />
              Add
            </button>
          </div>
        </div>
      )}

      <div className="flex justify-between items-center pt-2 border-t border-slate-800">
        <button
          type="button"
          onClick={() => setEditing(!editing)}
          className="flex items-center gap-2 text-slate-400 hover:text-slate-300 text-xs transition-colors"
          data-testid="edit-button"
        >
          {editing ? <Check className="w-3.5 h-3.5" /> : <Edit2 className="w-3.5 h-3.5" />}
          {editing ? 'Done' : 'Edit'}
        </button>
        
        {hasChanges && (
          <span className="text-xs text-amber-400" data-testid="unsaved-changes">Unsaved changes</span>
        )}
      </div>
    </div>
  )
}

function getTechCategory(techName: string): string {
  const categories: Record<string, string[]> = {
    'Languages': ['java', 'javascript', 'typescript', 'python', 'go', 'rust', 'php', 'ruby', 'csharp', 'cpp'],
    'Frameworks': ['spring-boot', 'react', 'vue', 'angular', 'nextjs', 'express', 'flask', 'django'],
    'Build Tools': ['maven', 'gradle', 'npm', 'yarn', 'pnpm', 'webpack', 'vite'],
    'Databases': ['mysql', 'postgresql', 'mongodb', 'redis', 'sqlite', 'hibernate', 'jpa', 'mybatis'],
    'Testing': ['junit', 'testng', 'mockito', 'spring-test', 'selenium', 'jest', 'cypress', 'playwright'],
    'DevOps': ['docker', 'kubernetes', 'terraform', 'ansible'],
    'Code Quality': ['eslint', 'prettier', 'babel'],
    'Logging': ['logback', 'log4j', 'slf4j'],
    'Serialization': ['jackson', 'gson', 'fastjson'],
    'Migration': ['liquibase', 'flyway'],
    'Styling': ['tailwind', 'bootstrap'],
    'Architecture': ['monorepo']
  }

  for (const [category, technologies] of Object.entries(categories)) {
    if (technologies.includes(techName.toLowerCase())) {
      return category
    }
  }

  return 'Other'
}
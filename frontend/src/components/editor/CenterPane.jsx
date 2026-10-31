import { useEffect, useState } from 'react'
import Markdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { getFile } from '../../api/projectApi.js'
import DiagramPane from '../diagram/DiagramPane.jsx'

// Loads the file from the project folder; reloads when `version` changes (the chat wrote or applied something).
function MarkdownView({ projectId, tab, version, raw = false }) {
  const [state, setState] = useState({ key: null, text: '', error: null })
  const key = `${projectId}|${tab.id}|${version}`

  useEffect(() => {
    let current = true
    getFile(projectId, tab.id)
      .then((text) => current && setState({ key, text, error: null }))
      .catch((e) => current && setState({ key, text: '', error: e.message }))
    return () => {
      current = false
    }
  }, [projectId, tab.id, version, key])

  // text of another file (or an older project) is never shown under this file's header
  const sameFile = state.key?.startsWith(`${projectId}|${tab.id}|`)
  const loading = !sameFile
  const body = sameFile
    ? state.text.replace(/^---\n[\s\S]*?\n---\n/, '').replace(/<!--[\s\S]*?-->/g, '')
    : ''
  return (
    <div className="min-h-0 flex-1 overflow-y-auto p-6">
      <div className="mb-3 text-xs text-slate-400">{tab.id}</div>
      {loading && <p className="text-sm text-slate-500">Loading…</p>}
      {sameFile && state.error && <p className="text-sm text-red-700">{state.error}</p>}
      {raw ? (
        <pre className="overflow-x-auto rounded bg-slate-50 p-3 text-xs leading-relaxed text-slate-800">
          {sameFile ? state.text : ''}
        </pre>
      ) : (
        <div className="prose prose-sm prose-slate max-w-none">
          <Markdown remarkPlugins={[remarkGfm]}>{body}</Markdown>
        </div>
      )}
    </div>
  )
}

// Center area: tab strip plus the selected item (architecture diagram or an md file).
function CenterPane({ tabs, activeId, onSelect, onClose, projectId, diagramVersion, fileVersion, onReloadDiagram }) {
  const active = tabs.find((t) => t.id === activeId)

  return (
    <section className="flex min-h-0 min-w-0 flex-col bg-white">
      <div className="flex shrink-0 overflow-x-auto border-b border-slate-200 bg-slate-100" role="tablist">
        {tabs.map((t) => (
          <div
            key={t.id}
            className={`flex items-center gap-2 border-r border-slate-200 px-3 py-2 text-sm ${
              t.id === activeId ? 'bg-white text-slate-900' : 'text-slate-500 hover:bg-slate-50'
            }`}
          >
            <button role="tab" aria-selected={t.id === activeId} className="whitespace-nowrap" onClick={() => onSelect(t.id)}>
              {t.name}
            </button>
            <button className="text-slate-400 hover:text-slate-900" aria-label={`Close ${t.name}`} onClick={() => onClose(t.id)}>
              ×
            </button>
          </div>
        ))}
      </div>
      {!active && <p className="m-auto text-sm text-slate-500">Select a file in the explorer.</p>}
      {active?.kind === 'diagram' && (
        <DiagramPane projectId={projectId} version={diagramVersion} onReload={onReloadDiagram} />
      )}
      {active?.kind === 'md' && <MarkdownView projectId={projectId} tab={active} version={fileVersion} />}
      {active?.kind === 'code' && <MarkdownView projectId={projectId} tab={active} version={fileVersion} raw />}
    </section>
  )
}

export default CenterPane

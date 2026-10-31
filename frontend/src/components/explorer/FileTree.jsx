import { useState } from 'react'

const ICON = { md: '📄', diagram: '🗺️', code: '🧩' }

function Folder({ node, activeId, onOpen }) {
  const [open, setOpen] = useState(true)
  return (
    <li>
      <button
        className="flex w-full items-center gap-1.5 px-3 py-1 text-left text-sm font-medium text-slate-700 hover:bg-slate-100"
        onClick={() => setOpen((o) => !o)}
        aria-expanded={open}
      >
        <span className="w-3 text-xs text-slate-400">{open ? '▾' : '▸'}</span>
        <span>📁</span>
        <span className="truncate">{node.name}</span>
      </button>
      {open && node.emptyHint && (
        <p className="py-1 pl-9 pr-3 text-xs italic text-slate-400">{node.emptyHint}</p>
      )}
      {open && (
        <ul>
          {node.children.map((child) => (
            <li key={child.id}>
              <button
                className={`flex w-full items-center gap-1.5 py-1 pl-9 pr-3 text-left text-sm hover:bg-slate-100 ${
                  child.id === activeId ? 'bg-slate-200 font-medium text-slate-900' : 'text-slate-600'
                }`}
                onClick={() => onOpen(child)}
                title={child.id}
              >
                <span>{ICON[child.kind]}</span>
                <span className="truncate">{child.name}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </li>
  )
}

function FileTree({ tree, error, activeId, onOpen }) {
  return (
    <nav className="flex min-h-0 flex-col border-r border-slate-200 bg-white" aria-label="Project files">
      <div className="border-b border-slate-200 px-3 py-2 text-xs font-semibold uppercase tracking-wide text-slate-500">
        Explorer
      </div>
      {error && (
        <p className="border-b border-red-200 bg-red-50 px-3 py-2 text-xs text-red-700">
          Could not load project files ({error}). If the backend was started before the latest update, restart it.
        </p>
      )}
      <ul className="min-h-0 flex-1 overflow-y-auto py-1">
        {tree.map((node) => (
          <Folder key={node.id} node={node} activeId={activeId} onOpen={onOpen} />
        ))}
      </ul>
    </nav>
  )
}

export default FileTree

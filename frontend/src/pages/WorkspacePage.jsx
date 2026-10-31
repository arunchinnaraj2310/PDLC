import { useEffect, useMemo, useState } from 'react'
import { listFiles } from '../api/projectApi.js'
import ChatPanel from '../components/chat/ChatPanel.jsx'
import CenterPane from '../components/editor/CenterPane.jsx'
import FileTree from '../components/explorer/FileTree.jsx'
import { buildTree, DEFAULT_TAB } from '../components/explorer/workspaceTree.js'
import ProjectBar from '../components/ProjectBar.jsx'
import { useCodeSync } from '../hooks/useCodeSync.js'
import { useProjects } from '../hooks/useProjects.js'

function WorkspacePage({ onLogout }) {
  const { projects, current, select, create, error } = useProjects()
  // bumped whenever the md model changes so the diagram pane re-renders
  const [diagramVersion, setDiagramVersion] = useState(0)
  // bumped whenever any project file may have changed (the interview wrote a doc, a proposal was applied or undone)
  const [fileVersion, setFileVersion] = useState(0)
  const refreshDiagram = () => setDiagramVersion((v) => v + 1)
  const refreshFiles = () => setFileVersion((v) => v + 1)

  const [files, setFiles] = useState([])
  const [filesError, setFilesError] = useState(null)
  useEffect(() => {
    if (!current) return undefined
    let live = true
    listFiles(current)
      .then((paths) => {
        if (!live) return
        setFiles(paths)
        setFilesError(null)
      })
      .catch((e) => {
        if (!live) return
        setFiles([])
        setFilesError(e.message)
      })
    return () => {
      live = false
    }
  }, [current, fileVersion])
  const tree = useMemo(() => buildTree(files), [files])

  // the backend re-reads changed code and updates the design by itself: reload what that touched
  const { notice, dismiss } = useCodeSync(current, () => {
    refreshDiagram()
    refreshFiles()
  })

  const [tabs, setTabs] = useState([DEFAULT_TAB])
  const [activeId, setActiveId] = useState(DEFAULT_TAB.id)

  const openTab = (node) => {
    setTabs((prev) => (prev.some((t) => t.id === node.id) ? prev : [...prev, node]))
    setActiveId(node.id)
  }
  const closeTab = (id) => {
    const next = tabs.filter((t) => t.id !== id)
    setTabs(next)
    if (id === activeId) setActiveId(next.length ? next[next.length - 1].id : null)
  }
  // the interview just wrote these: show the first one so the admin sees the result of their answer
  const showDocs = (paths) => {
    refreshFiles()
    const first = paths?.[0]
    if (first) openTab({ id: first, name: first.slice(first.lastIndexOf('/') + 1), kind: 'md' })
  }

  // requirements/design were applied: show the diagram, re-rendered from the new model
  const showDiagram = () => {
    refreshDiagram()
    openTab(DEFAULT_TAB)
  }

  return (
    <div className="flex h-screen flex-col bg-slate-50 text-slate-900">
      <ProjectBar projects={projects} current={current} onSelect={select} onCreate={create} onLogout={onLogout} />
      {error && <p className="bg-red-50 px-4 py-2 text-sm text-red-700">{error}</p>}
      {notice && (
        <div className="flex items-start justify-between gap-4 bg-emerald-50 px-4 py-2 text-sm text-emerald-900">
          <div>
            <div>{notice.summary}</div>
            {notice.warnings.length > 0 && (
              <div className="text-xs text-emerald-800">
                Drift: {notice.warnings.slice(0, 3).join('; ')}
                {notice.warnings.length > 3 ? `; and ${notice.warnings.length - 3} more` : ''}
              </div>
            )}
          </div>
          <button className="shrink-0 underline" onClick={dismiss}>
            Dismiss
          </button>
        </div>
      )}
      {current ? (
        <main className="grid min-h-0 flex-1 grid-cols-[240px_minmax(0,1fr)_minmax(340px,28rem)]">
          <FileTree tree={tree} error={filesError} activeId={activeId} onOpen={openTab} />
          <CenterPane
            tabs={tabs}
            activeId={activeId}
            onSelect={setActiveId}
            onClose={closeTab}
            projectId={current}
            diagramVersion={diagramVersion}
            fileVersion={fileVersion}
            onReloadDiagram={refreshDiagram}
          />
          <div className="flex min-h-0 flex-col border-l border-slate-200">
            <ChatPanel
              key={current}
              projectId={current}
              onModelChanged={showDiagram}
              onDocsChanged={showDocs}
            />
          </div>
        </main>
      ) : (
        <p className="m-auto text-sm text-slate-500">Create a project above to get started.</p>
      )}
    </div>
  )
}

export default WorkspacePage

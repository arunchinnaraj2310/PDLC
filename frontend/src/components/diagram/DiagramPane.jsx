import { useDiagram } from '../../hooks/useDiagram.js'

function Notice({ children }) {
  return <p className="m-auto max-w-sm px-6 text-center text-sm text-slate-500">{children}</p>
}

// The diagram is a self-contained Archify HTML page rendered from the md model. It is fetched with our auth
// header and shown via srcDoc, sandboxed so its scripts cannot reach the app or its credentials.
function DiagramPane({ projectId, version, onReload }) {
  const { status, html, error } = useDiagram(projectId, version)

  return (
    <section className="flex min-h-0 flex-1 flex-col bg-white">
      <div className="flex items-center justify-between border-b border-slate-200 px-4 py-2">
        <h2 className="text-sm font-medium text-slate-700">Architecture</h2>
        <button className="text-sm text-slate-500 underline hover:text-slate-900" onClick={onReload}>
          Reload
        </button>
      </div>
      <div className="flex min-h-0 flex-1">
        {status === 'loading' && <Notice>Rendering diagram…</Notice>}
        {status === 'empty' && <Notice>No components yet. Send requirements in the chat and apply the proposal.</Notice>}
        {status === 'error' && <Notice>{error}</Notice>}
        {status === 'ready' && (
          <iframe title="Architecture diagram" className="h-full w-full" sandbox="allow-scripts" srcDoc={html} />
        )}
      </div>
    </section>
  )
}

export default DiagramPane

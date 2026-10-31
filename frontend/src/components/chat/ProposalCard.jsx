import DiffView from './DiffView.jsx'

const button = 'rounded px-3 py-1.5 text-sm font-medium disabled:opacity-50'

// Inline Apply/Reject card for a proposal. Nothing is written until Apply.
function ProposalCard({ proposal, onApply, onReject, onUndo }) {
  const { status, error, warnings, files } = proposal
  const busy = status === 'applying'

  return (
    <div className="mt-2 space-y-3 rounded-lg border border-slate-300 bg-slate-50 p-3">
      {warnings.length > 0 && (
        <ul className="space-y-1 rounded border border-amber-300 bg-amber-50 p-2 text-xs text-amber-900">
          {warnings.map((w) => (
            <li key={w}>⚠ {w}</li>
          ))}
        </ul>
      )}

      {files.map((file) => (
        <details key={file.file} open>
          <summary className="cursor-pointer text-xs font-medium text-slate-700">
            {file.file} <span className="text-slate-400">(version {file.version})</span>
          </summary>
          <div className="mt-1">
            <DiffView before={file.before} after={file.after} />
          </div>
        </details>
      ))}

      {error && <p className="text-xs text-red-600">{error}</p>}

      {(status === 'pending' || status === 'applying') && (
        <div className="flex gap-2">
          <button
            className={`${button} bg-slate-900 text-white hover:bg-slate-700`}
            disabled={busy}
            onClick={onApply}
          >
            {busy ? 'Applying…' : 'Apply'}
          </button>
          <button
            className={`${button} border border-slate-300 bg-white hover:bg-slate-100`}
            disabled={busy}
            onClick={onReject}
          >
            Reject
          </button>
        </div>
      )}
      {status === 'applied' && (
        <div className="flex items-center gap-3 text-sm">
          <span className="font-medium text-green-700">✓ Applied</span>
          <button className="text-slate-500 underline hover:text-slate-800" onClick={onUndo}>
            Undo
          </button>
        </div>
      )}
      {status === 'undone' && <p className="text-sm text-slate-500">Applied, then undone.</p>}
      {status === 'rejected' && <p className="text-sm text-slate-500">Rejected. Nothing was changed.</p>}
    </div>
  )
}

export default ProposalCard

import { diffLines } from 'diff'

const STYLES = {
  add: 'bg-green-50 text-green-800',
  del: 'bg-red-50 text-red-800 line-through decoration-red-300',
  ctx: 'text-slate-500',
  skip: 'text-slate-400 italic',
}
const PREFIX = { add: '+ ', del: '- ', ctx: '  ', skip: '  ' }

// Line diff of one md file. Long unchanged stretches are collapsed so the card stays short.
function toRows(before, after) {
  const rows = []
  for (const part of diffLines(before, after)) {
    const lines = part.value.replace(/\n$/, '').split('\n')
    if (part.added || part.removed) {
      lines.forEach((text) => rows.push({ type: part.added ? 'add' : 'del', text }))
    } else if (lines.length <= 4) {
      lines.forEach((text) => rows.push({ type: 'ctx', text }))
    } else {
      rows.push({ type: 'skip', text: `… ${lines.length} unchanged lines` })
    }
  }
  return rows
}

function DiffView({ before, after }) {
  return (
    <pre className="max-h-72 overflow-auto rounded border border-slate-200 bg-white p-2 font-mono text-xs leading-5">
      {toRows(before, after).map((row, i) => (
        <div key={i} className={`whitespace-pre-wrap break-words ${STYLES[row.type]}`}>
          {PREFIX[row.type]}
          {row.text}
        </div>
      ))}
    </pre>
  )
}

export default DiffView

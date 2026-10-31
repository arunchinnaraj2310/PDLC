import { useRef, useState } from 'react'

function Composer({ onSend, onStop, busy }) {
  const [text, setText] = useState('')
  // A loaded file is a document, so it defaults to the requirements flow; typed text is routed by the server.
  const [asRequirements, setAsRequirements] = useState(false)
  const fileInput = useRef(null)

  const submit = () => {
    if (!text.trim() || busy) return
    onSend(text, { asRequirements })
    setText('')
    setAsRequirements(false)
  }

  const loadFile = async (event) => {
    const file = event.target.files[0]
    if (file) {
      setText(await file.text())
      setAsRequirements(true)
    }
    event.target.value = '' // allow picking the same file again
  }

  return (
    <div className="border-t border-slate-200 bg-white p-3">
      <textarea
        className="h-28 w-full resize-none rounded border border-slate-300 p-2 text-sm focus:border-slate-500 focus:outline-none"
        placeholder='Ask a question, describe a change (e.g. "add Redis caching"), or paste requirements…'
        value={text}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) submit()
        }}
      />
      <div className="mt-2 flex items-center gap-3">
        <input ref={fileInput} type="file" accept=".md,.markdown,.txt" hidden onChange={loadFile} />
        <button
          className="text-sm text-slate-600 underline hover:text-slate-900"
          onClick={() => fileInput.current.click()}
        >
          Load .md file
        </button>
        <label className="flex items-center gap-1 text-xs text-slate-500">
          <input
            type="checkbox"
            checked={asRequirements}
            onChange={(e) => setAsRequirements(e.target.checked)}
          />
          This is a requirements document
        </label>
        {busy ? (
          <button
            className="ml-auto rounded border border-slate-400 px-4 py-1.5 text-sm font-medium hover:bg-slate-100"
            onClick={onStop}
          >
            Stop
          </button>
        ) : (
          <button
            className="ml-auto rounded bg-slate-900 px-4 py-1.5 text-sm font-medium text-white hover:bg-slate-700 disabled:opacity-50"
            disabled={!text.trim()}
            onClick={submit}
          >
            Send
          </button>
        )}
      </div>
    </div>
  )
}

export default Composer

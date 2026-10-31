import { useState } from 'react'
import { PROJECT_ID_PATTERN } from '../hooks/useProjects.js'

function ProjectBar({ projects, current, onSelect, onCreate, onLogout }) {
  const [name, setName] = useState('')
  const [error, setError] = useState(null)

  const submit = async (event) => {
    event.preventDefault()
    if (!PROJECT_ID_PATTERN.test(name)) {
      setError('Use lowercase letters, digits and dashes (max 41 characters)')
      return
    }
    try {
      await onCreate(name)
      setName('')
      setError(null)
    } catch (e) {
      setError(e.message)
    }
  }

  return (
    <header className="flex flex-wrap items-center gap-3 border-b border-slate-200 bg-white px-4 py-2">
      <h1 className="text-base font-semibold">PDLC Assistant</h1>
      {projects.length > 0 && (
        <select
          className="rounded border border-slate-300 px-2 py-1 text-sm"
          value={current ?? ''}
          onChange={(e) => onSelect(e.target.value)}
        >
          {projects.map((id) => (
            <option key={id}>{id}</option>
          ))}
        </select>
      )}
      <form className="flex items-center gap-2" onSubmit={submit}>
        <input
          className="w-40 rounded border border-slate-300 px-2 py-1 text-sm"
          placeholder="new-project-name"
          value={name}
          onChange={(e) => setName(e.target.value)}
        />
        <button className="rounded border border-slate-300 px-2 py-1 text-sm hover:bg-slate-100">Create</button>
      </form>
      {error && <span className="text-xs text-red-600">{error}</span>}
      <button className="ml-auto text-sm text-slate-500 underline hover:text-slate-900" onClick={onLogout}>
        Sign out
      </button>
    </header>
  )
}

export default ProjectBar

import { useState } from 'react'
import { useHealth } from '../hooks/useHealth.js'

function LoginPage({ onLogin }) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)
  const { health, error: backendDown } = useHealth()

  const submit = async (event) => {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await onLogin(username, password)
    } catch (e) {
      setError(e.message)
      setBusy(false)
    }
  }

  const field = 'w-full rounded border border-slate-300 px-3 py-2 text-sm focus:border-slate-500 focus:outline-none'

  return (
    <main className="flex min-h-screen items-center justify-center bg-slate-50 text-slate-900">
      <form className="w-80 space-y-4 rounded-lg border border-slate-200 bg-white p-6" onSubmit={submit}>
        <h1 className="text-xl font-semibold">PDLC Assistant</h1>
        <input
          className={field}
          placeholder="Username"
          autoComplete="username"
          value={username}
          onChange={(e) => setUsername(e.target.value)}
        />
        <input
          className={field}
          type="password"
          placeholder="Password"
          autoComplete="current-password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
        {error && <p className="text-sm text-red-600">{error}</p>}
        <button
          className="w-full rounded bg-slate-900 py-2 text-sm font-medium text-white hover:bg-slate-700 disabled:opacity-50"
          disabled={busy || !username || !password}
        >
          Sign in
        </button>
        <p className="text-center text-xs text-slate-400">
          {backendDown ? 'Backend unreachable' : health ? `Backend ${health.status}` : 'Checking backend…'}
        </p>
      </form>
    </main>
  )
}

export default LoginPage

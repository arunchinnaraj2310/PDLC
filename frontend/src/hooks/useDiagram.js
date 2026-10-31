import { useEffect, useState } from 'react'
import { getDiagram } from '../api/projectApi.js'

// status: loading | ready | empty (no components yet) | error. Pass a new `version` to re-render.
export function useDiagram(projectId, version) {
  const [state, setState] = useState({ status: 'loading', html: null, error: null })

  useEffect(() => {
    if (!projectId) return undefined
    let cancelled = false
    getDiagram(projectId)
      .then((html) => !cancelled && setState({ status: 'ready', html, error: null }))
      .catch((e) => {
        if (cancelled) return
        const empty = e.message.startsWith('No components yet')
        setState({ status: empty ? 'empty' : 'error', html: null, error: e.message })
      })
    return () => {
      cancelled = true
    }
  }, [projectId, version])

  return state
}

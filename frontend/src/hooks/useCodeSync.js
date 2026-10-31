import { useEffect, useRef, useState } from 'react'
import { getCodeStatus } from '../api/projectApi.js'

const POLL_MS = 5000

// The backend watches the project's source folder and updates the design when the code changes. This notices:
// when the sync version moves, call onChanged (reload files and diagram) and expose a notice to show.
export function useCodeSync(projectId, onChanged) {
  const [notice, setNotice] = useState(null)
  const callback = useRef(onChanged)
  useEffect(() => {
    callback.current = onChanged
  })

  useEffect(() => {
    if (!projectId) return undefined
    let live = true
    let seen = null
    setNotice(null)
    const tick = async () => {
      try {
        const status = await getCodeStatus(projectId)
        if (!live) return
        if (seen === null) seen = status.version
        else if (status.version !== seen) {
          seen = status.version
          setNotice({ summary: status.summary, warnings: status.warnings ?? [] })
          callback.current()
        }
      } catch {
        // backend restarting or project gone: try again on the next tick
      }
    }
    tick()
    const timer = setInterval(tick, POLL_MS)
    return () => {
      live = false
      clearInterval(timer)
    }
  }, [projectId])

  return { notice, dismiss: () => setNotice(null) }
}

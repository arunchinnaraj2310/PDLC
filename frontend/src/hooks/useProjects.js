import { useCallback, useEffect, useState } from 'react'
import { createProject, listProjects } from '../api/projectApi.js'

const STORAGE_KEY = 'pdlc.project'

export const PROJECT_ID_PATTERN = /^[a-z0-9][a-z0-9-]{0,40}$/

export function useProjects() {
  const [projects, setProjects] = useState([])
  const [current, setCurrent] = useState(() => localStorage.getItem(STORAGE_KEY))
  const [error, setError] = useState(null)

  useEffect(() => {
    listProjects()
      .then((ids) => {
        setProjects(ids)
        // forget a remembered project that no longer exists, and default to the first one
        setCurrent((id) => (ids.includes(id) ? id : (ids[0] ?? null)))
      })
      .catch((e) => setError(e.message))
  }, [])

  const select = useCallback((id) => {
    localStorage.setItem(STORAGE_KEY, id)
    setCurrent(id)
  }, [])

  const create = async (id) => {
    await createProject(id)
    setProjects((ids) => [...ids, id].sort())
    select(id)
  }

  return { projects, current, select, create, error }
}

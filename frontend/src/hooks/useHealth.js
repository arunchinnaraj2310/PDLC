import { useEffect, useState } from 'react'
import { getHealth } from '../api/healthApi.js'

export function useHealth() {
  const [health, setHealth] = useState(null)
  const [error, setError] = useState(false)

  useEffect(() => {
    getHealth().then(setHealth).catch(() => setError(true))
  }, [])

  return { health, error }
}

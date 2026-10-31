import { useEffect, useState } from 'react'
import { setUnauthorizedHandler } from '../api/apiClient.js'
import { clearCredentials, getAuthHeader, saveCredentials } from '../api/auth.js'
import { listProjects } from '../api/projectApi.js'

export function useAuth() {
  const [signedIn, setSignedIn] = useState(() => Boolean(getAuthHeader()))

  // any 401 from the backend sends the admin back to the login screen
  useEffect(() => setUnauthorizedHandler(() => setSignedIn(false)), [])

  const login = async (username, password) => {
    saveCredentials(username, password)
    try {
      await listProjects()
      setSignedIn(true)
    } catch (e) {
      clearCredentials()
      throw new Error(e.message === 'Please sign in' ? 'Wrong username or password' : e.message)
    }
  }

  const logout = () => {
    clearCredentials()
    setSignedIn(false)
  }

  return { signedIn, login, logout }
}

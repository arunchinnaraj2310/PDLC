import { clearCredentials, getAuthHeader } from './auth.js'

// Single place for talking to the Spring Boot backend (proxied at /api in dev, same origin in prod).
let onUnauthorized = () => {}

export const setUnauthorizedHandler = (handler) => {
  onUnauthorized = handler
}

// Call on any 401: forgets the credentials, sends the admin back to the login screen, returns the error to throw.
export function signedOut() {
  clearCredentials()
  onUnauthorized()
  return new Error('Please sign in')
}

export const jsonBody = (value) => ({
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(value),
})

// Returns parsed JSON, or the raw text for non-JSON responses (e.g. the diagram HTML).
export async function apiRequest(path, { headers, ...options } = {}) {
  const auth = getAuthHeader()
  const res = await fetch(`/api${path}`, {
    // 'omit' keeps the browser from showing its own Basic-auth prompt on 401
    credentials: 'omit',
    ...options,
    headers: { ...(auth && { Authorization: auth }), ...headers },
  })
  if (res.status === 401) throw signedOut()
  const isJson = res.headers.get('content-type')?.includes('json')
  const payload = isJson ? await res.json().catch(() => null) : await res.text()
  if (!res.ok) {
    throw new Error(payload?.error ?? `Request failed (${res.status})`)
  }
  return payload
}

// The admin signs in on our own login screen and every API call carries an explicit Authorization header.
// Kept in sessionStorage (cleared when the tab closes) and never as a browser-managed Basic-auth login, which
// the browser would also attach to cross-site requests.
const KEY = 'pdlc.auth'

export const getAuthHeader = () => sessionStorage.getItem(KEY)

export function saveCredentials(username, password) {
  const bytes = new TextEncoder().encode(`${username}:${password}`)
  sessionStorage.setItem(KEY, `Basic ${btoa(String.fromCharCode(...bytes))}`)
}

export const clearCredentials = () => sessionStorage.removeItem(KEY)

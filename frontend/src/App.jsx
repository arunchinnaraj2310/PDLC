import LoginPage from './pages/LoginPage.jsx'
import WorkspacePage from './pages/WorkspacePage.jsx'
import { useAuth } from './hooks/useAuth.js'

function App() {
  const { signedIn, login, logout } = useAuth()
  return signedIn ? <WorkspacePage onLogout={logout} /> : <LoginPage onLogin={login} />
}

export default App

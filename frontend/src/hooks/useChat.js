import { useEffect, useRef, useState } from 'react'
import { streamChat } from '../api/chatApi.js'
import { applyPatches, getInterview, undoFile } from '../api/projectApi.js'

let nextId = 1

const HISTORY_TURNS = 6

// Messages live in memory only. An assistant reply streams in over SSE: a status line, then either tokens (an
// answer) or a proposal. A proposal goes pending -> applying -> applied (-> undone), or pending -> rejected.
// Nothing is written until Apply. Use with key={projectId} so each project starts with a fresh conversation.
export function useChat(projectId, onModelChanged, onDocsChanged) {
  const [messages, setMessages] = useState([])
  const [busy, setBusy] = useState(false)
  const [progress, setProgress] = useState([])
  const abort = useRef(null)

  // where the product interview stands (survives a page reload); the chat also pushes updates as it goes
  useEffect(() => {
    let live = true
    getInterview(projectId)
      .then((r) => live && setProgress(r.steps))
      .catch(() => {})
    return () => {
      live = false
    }
  }, [projectId])

  // leaving the project (or the page) mid-reply cancels the stream, and with it the backend LLM call
  useEffect(() => () => abort.current?.abort(), [])

  const patchMessage = (id, changes) =>
    setMessages((all) => all.map((m) => (m.id === id ? { ...m, ...changes } : m)))

  const setProposalState = (id, status, error = null) =>
    setMessages((all) =>
      all.map((m) => (m.id === id ? { ...m, proposal: { ...m.proposal, status, error } } : m)),
    )

  const handleEvent = (replyId, { name, data }) => {
    if (name === 'status') patchMessage(replyId, { status: data.text })
    else if (name === 'token')
      setMessages((all) => all.map((m) => (m.id === replyId ? { ...m, text: m.text + data.text } : m)))
    else if (name === 'progress') setProgress(data.steps)
    else if (name === 'docs') {
      // the interview wrote product documents: remember which for the reply, and reload them in the center pane
      setMessages((all) =>
        all.map((m) => (m.id === replyId ? { ...m, docs: [...(m.docs ?? []), ...data.files] } : m)),
      )
      onDocsChanged?.(data.files)
    } else if (name === 'proposal')
      // a proposal can follow streamed text (a product change that also touches the design): keep both
      setMessages((all) =>
        all.map((m) =>
          m.id === replyId
            ? {
                ...m,
                text: `${m.text ? `${m.text}

` : ''}${data.summary} Review the changes below, then apply or reject.`,
                proposal: { ...data, status: 'pending', error: null },
              }
            : m,
        ),
      )
    else if (name === 'error') patchMessage(replyId, { error: data.message })
  }

  const send = async (text, { asRequirements = false } = {}) => {
    const history = messages
      .filter((m) => m.text && !m.pending && !m.isError)
      .slice(-HISTORY_TURNS)
      .map((m) => ({ role: m.role, content: m.text }))
    const userId = nextId++
    const replyId = nextId++
    setMessages((all) => [
      ...all,
      { id: userId, role: 'user', text },
      { id: replyId, role: 'assistant', text: '', status: 'Connecting…', pending: true },
    ])
    setBusy(true)
    abort.current = new AbortController()
    try {
      await streamChat(
        projectId,
        { message: text, history, mode: asRequirements ? 'requirements' : 'auto' },
        (event) => handleEvent(replyId, event),
        abort.current.signal,
      )
    } catch (e) {
      if (e.name === 'AbortError') patchMessage(replyId, { stopped: true })
      else patchMessage(replyId, { error: e.message })
    } finally {
      patchMessage(replyId, { pending: false })
      abort.current = null
      setBusy(false)
    }
  }

  // Closing the connection also makes the backend cancel the LLM call.
  const stop = () => abort.current?.abort()

  const apply = async (message) => {
    setProposalState(message.id, 'applying')
    try {
      await applyPatches(projectId, message.proposal.summary, message.proposal.patches)
      setProposalState(message.id, 'applied')
      onModelChanged()
      onDocsChanged?.()
    } catch (e) {
      setProposalState(message.id, 'pending', e.message)
    }
  }

  const reject = (message) => setProposalState(message.id, 'rejected')

  const undo = async (message) => {
    setProposalState(message.id, 'applying')
    try {
      for (const patch of message.proposal.patches) {
        await undoFile(projectId, patch.file)
      }
      setProposalState(message.id, 'undone')
      onModelChanged()
      onDocsChanged?.()
    } catch (e) {
      setProposalState(message.id, 'applied', e.message)
    }
  }

  return { messages, busy, progress, send, stop, apply, reject, undo }
}

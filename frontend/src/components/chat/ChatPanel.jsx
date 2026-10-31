import { useEffect, useRef } from 'react'
import Markdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { useChat } from '../../hooks/useChat.js'
import Composer from './Composer.jsx'
import ProposalCard from './ProposalCard.jsx'

const PREVIEW_CHARS = 400

function UserBubble({ text }) {
  const long = text.length > PREVIEW_CHARS
  return (
    <div className="ml-8 self-end whitespace-pre-wrap break-words rounded-lg bg-slate-900 px-3 py-2 text-sm text-white">
      {long ? `${text.slice(0, PREVIEW_CHARS)}…` : text}
      {long && <div className="mt-1 text-xs text-slate-400">({text.length} characters sent)</div>}
    </div>
  )
}

function AssistantBubble({ message, chat }) {
  const waiting = message.pending && !message.text
  return (
    <div className="mr-4 self-start">
      {waiting && (
        <div className="animate-pulse rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm text-slate-500">
          {message.status}
        </div>
      )}
      {message.pending && message.text && (
        <div className="mb-1 text-xs text-slate-400">{message.status}</div>
      )}
      {message.text && (
        <div className="rounded-lg border border-slate-200 bg-white px-3 py-2">
          <div className="prose prose-sm prose-slate max-w-none break-words prose-p:my-1 prose-li:my-0 prose-pre:my-2">
            <Markdown remarkPlugins={[remarkGfm]}>{message.text}</Markdown>
          </div>
        </div>
      )}
      {message.docs?.length > 0 && (
        <div className="mt-1 text-xs text-emerald-700">
          Updated {[...new Set(message.docs)].map((d) => d.slice(d.lastIndexOf('/') + 1)).join(', ')}
        </div>
      )}
      {message.stopped && <div className="mt-1 text-xs text-slate-500">Stopped.</div>}
      {message.error && (
        <div className="mt-1 rounded-lg border border-red-300 bg-red-50 px-3 py-2 text-sm text-red-800">
          {message.error}
        </div>
      )}
      {message.proposal && (
        <ProposalCard
          proposal={message.proposal}
          onApply={() => chat.apply(message)}
          onReject={() => chat.reject(message)}
          onUndo={() => chat.undo(message)}
        />
      )}
    </div>
  )
}

const STEP_STYLE = {
  done: 'border-emerald-300 bg-emerald-50 text-emerald-800',
  current: 'border-slate-900 bg-slate-900 text-white',
  todo: 'border-slate-200 bg-white text-slate-400',
}

// The product interview goes section by section; this shows which are captured and which one is being asked.
function InterviewProgress({ steps }) {
  if (!steps.length) return null
  const done = steps.filter((s) => s.state === 'done').length
  return (
    <div className="border-b border-slate-200 bg-white px-4 py-2">
      <div className="mb-1 text-xs text-slate-500">
        Product interview: {done} of {steps.length} sections captured
      </div>
      <div className="flex flex-wrap gap-1">
        {steps.map((s) => (
          <span key={s.key} className={`rounded-full border px-2 py-0.5 text-xs ${STEP_STYLE[s.state]}`}>
            {s.state === 'done' ? '✓ ' : ''}
            {s.label}
          </span>
        ))}
      </div>
    </div>
  )
}

// Render with key={projectId} so each project gets its own conversation.
function ChatPanel({ projectId, onModelChanged, onDocsChanged }) {
  const chat = useChat(projectId, onModelChanged, onDocsChanged)
  const bottom = useRef(null)

  useEffect(() => {
    // 'auto' (not smooth): replies stream in many times a second
    bottom.current?.scrollIntoView({ behavior: 'auto' })
  }, [chat.messages])

  return (
    <section className="flex min-h-0 flex-col bg-slate-50">
      <InterviewProgress steps={chat.progress} />
      <div className="flex min-h-0 flex-1 flex-col gap-3 overflow-y-auto p-4">
        {chat.messages.length === 0 && (
          <p className="m-auto max-w-xs text-center text-sm text-slate-500">
            Tell me about the product you want to build and I will interview you, writing the product intent,
            personas and user stories as we go. When they are ready, say "generate requirements" to get
            requirements and a design to review. You can also paste an existing requirements document, change the
            product at any time, or ask questions about the design.
          </p>
        )}
        {chat.messages.map((m) =>
          m.role === 'user' ? (
            <UserBubble key={m.id} text={m.text} />
          ) : (
            <AssistantBubble key={m.id} message={m} chat={chat} />
          ),
        )}
        <div ref={bottom} />
      </div>
      <Composer onSend={chat.send} onStop={chat.stop} busy={chat.busy} />
    </section>
  )
}

export default ChatPanel

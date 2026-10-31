import { signedOut } from './apiClient.js'
import { getAuthHeader } from './auth.js'

// "event: name\ndata: {json}" blocks. Comment/keep-alive blocks have no data line and are skipped.
function parseBlock(block) {
  let name = 'message'
  const data = []
  for (const line of block.split('\n')) {
    if (line.startsWith('event:')) name = line.slice(6).trim()
    else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''))
  }
  return data.length ? { name, data: JSON.parse(data.join('\n')) } : null
}

// POST + Server-Sent Events. EventSource cannot POST or send an Authorization header, so read the body stream
// ourselves. Resolves when the server ends the stream; aborting `signal` rejects with an AbortError.
export async function postEventStream(path, payload, onEvent, signal) {
  const auth = getAuthHeader()
  const res = await fetch(`/api${path}`, {
    method: 'POST',
    credentials: 'omit',
    signal,
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...(auth && { Authorization: auth }),
    },
    body: JSON.stringify(payload),
  })
  if (res.status === 401) throw signedOut()
  if (!res.ok) {
    const body = await res.json().catch(() => null)
    throw new Error(body?.error ?? `Request failed (${res.status})`)
  }

  const reader = res.body.pipeThrough(new TextDecoderStream()).getReader()
  let buffer = ''
  for (;;) {
    const { value, done } = await reader.read()
    if (done) return
    buffer += value.replaceAll('\r\n', '\n')
    let end
    while ((end = buffer.indexOf('\n\n')) !== -1) {
      const event = parseBlock(buffer.slice(0, end))
      buffer = buffer.slice(end + 2)
      if (event) onEvent(event)
    }
  }
}

import { postEventStream } from './sse.js'

// Events: status {text}, token {text}, proposal {patches, files, warnings}, done, error {message}.
// `history` is the recent [{role, content}] turns; mode 'requirements' forces the requirements-import flow.
export const streamChat = (projectId, { message, history, mode }, onEvent, signal) =>
  postEventStream(`/projects/${projectId}/chat`, { message, history, mode }, onEvent, signal)

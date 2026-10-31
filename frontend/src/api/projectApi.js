import { apiRequest, jsonBody } from './apiClient.js'

export const listProjects = () => apiRequest('/projects')

export const createProject = (id) => apiRequest(`/projects/${id}`, { method: 'POST' })

// Returns a proposal {patches, files, warnings}; nothing is written until applyPatches.
export const importRequirements = (id, markdown) =>
  apiRequest(`/projects/${id}/import/requirements`, {
    method: 'POST',
    headers: { 'Content-Type': 'text/markdown; charset=utf-8' },
    body: markdown,
  })

export const applyPatches = (id, reason, patches) =>
  apiRequest(`/projects/${id}/patch-set`, { method: 'POST', ...jsonBody({ reason, patches }) })

export const undoFile = (id, file) =>
  apiRequest(`/projects/${id}/undo?file=${encodeURIComponent(file)}`, { method: 'POST' })

// Self-contained Archify HTML, re-rendered from the md model on every call.
export const getDiagram = (id) => apiRequest(`/projects/${id}/diagram`)

// Project-relative paths for the explorer (backups excluded; product docs are listed even before they exist).
export const listFiles = (id) => apiRequest(`/projects/${id}/files`)

export const getFile = (id, path) => apiRequest(`/projects/${id}/file?path=${encodeURIComponent(path)}`)

// Steps of the product interview [{key, label, state: done|current|todo}]; empty once requirements exist.
export const getInterview = (id) => apiRequest(`/projects/${id}/interview`)

// What the last code sync did: {version, at, summary, warnings, error}. `version` goes up whenever the design was
// changed from the code, by "sync code", code generation or the background watcher.
export const getCodeStatus = (id) => apiRequest(`/projects/${id}/code/status`)

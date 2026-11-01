// Builds the explorer tree from the project's real file paths.
// kind: 'folder' | 'md' | 'diagram'
const DIAGRAM_NODE = { id: 'diagram:architecture', name: 'architecture diagram', kind: 'diagram' }

export const DEFAULT_TAB = DIAGRAM_NODE

// Which folder a file is shown under, by its numeric prefix (01-02 product, 03-04 requirements, 11 design).
const GROUPS = [
  { id: 'product', name: 'Product Intent', match: /^docs\/0[12]-/ },
  { id: 'requirements', name: 'Requirements', match: /^docs\/0[3-9]-/ },
  { id: 'design', name: 'Design', match: /^docs\/1\d-/ },
  { id: 'implementation', name: 'Implementation', match: /^implementation\// },
]

const fileName = (path) => path.slice(path.lastIndexOf('/') + 1)

// Places a file under nested folder nodes (one per path segment), creating them as needed.
function addNested(root, segments, file) {
  let parent = root
  let prefix = 'implementation'
  for (const seg of segments.slice(0, -1)) {
    prefix += '/' + seg
    let dir = parent.children.find((c) => c.kind === 'folder' && c.id === prefix)
    if (!dir) {
      dir = { id: prefix, name: seg, kind: 'folder', children: [] }
      parent.children.push(dir)
    }
    parent = dir
  }
  parent.children.push(file)
}

// Folders first, then files, alphabetical, recursively.
function sortTree(node) {
  node.children.sort((a, b) => (a.kind === 'folder') !== (b.kind === 'folder') ? (a.kind === 'folder' ? -1 : 1) : a.name.localeCompare(b.name))
  node.children.forEach((c) => c.kind === 'folder' && sortTree(c))
}

export function buildTree(paths) {
  const groups = GROUPS.map((g) => ({ id: g.id, name: g.name, kind: 'folder', children: [] }))
  const other = { id: 'other', name: 'Other', kind: 'folder', children: [] }
  for (const path of paths) {
    if (path.startsWith('diagrams/')) continue // derived; shown as the diagram node
    const index = GROUPS.findIndex((g) => g.match.test(path))
    const inCode = GROUPS[index]?.id === 'implementation'
    const node = { id: path, name: fileName(path), kind: path.endsWith('.md') ? 'md' : 'code' }
    if (inCode) addNested(groups[index], path.slice('implementation/'.length).split('/'), node)
    else (index >= 0 ? groups[index] : other).children.push(node)
  }
  groups.find((g) => g.id === 'design').children.push(DIAGRAM_NODE)
  // Implementation is always shown: it is where code lives (synced into the design) and, later, is generated
  const impl = groups.find((g) => g.id === 'implementation')
  sortTree(impl)
  if (!impl.children.length) impl.emptyHint = 'Type "generate code" in the chat to build the app from the design, or "sync code from <folder>" to read existing code'
  return [...groups, other].filter((g) => g.children.length || g.emptyHint)
}

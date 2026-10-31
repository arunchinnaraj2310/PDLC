#!/usr/bin/env node
// Deterministic code fact extractor: tree-sitter (WASM) for Java and TS/JS. No LLM, no network.
//
//   node tools/extract/extract.mjs <rootDir> <filesJson> <outJson>
//
// <filesJson> is a JSON array of root-relative paths to parse (the backend decides which: only changed files on a
// re-sync). <outJson> receives { files: { <path>: <facts> }, errors: [...] }. Every fact carries a 1-based line so
// the design can cite `file:line` evidence. Imports are emitted raw; resolving them to files is the backend's job
// because it holds the facts of the files that were not re-parsed.
import { readFileSync, writeFileSync, statSync } from 'node:fs'
import { join, extname } from 'node:path'
import { fileURLToPath } from 'node:url'
import Parser from 'web-tree-sitter'

const MAX_BYTES = 1_000_000
const WASM_DIR = fileURLToPath(new URL('./node_modules/tree-sitter-wasms/out/', import.meta.url))
const GRAMMAR = {
  '.java': ['java', 'java'],
  '.js': ['javascript', 'js'],
  '.jsx': ['javascript', 'jsx'],
  '.mjs': ['javascript', 'js'],
  '.cjs': ['javascript', 'js'],
  '.ts': ['typescript', 'ts'],
  '.tsx': ['tsx', 'tsx'],
}

const HTTP_METHODS = new Set(['get', 'post', 'put', 'delete', 'patch', 'all'])
const ROUTER_OBJECT = /^(app|router|server|api|route|routes|[A-Za-z]*Router)$/
const CLIENT_OBJECT = /^(axios|http|httpClient|client|ky|got|\$http|instance|[A-Za-z]*Client|[A-Za-z]*Api)$/
const SPRING_MAPPINGS = {
  GetMapping: 'GET',
  PostMapping: 'POST',
  PutMapping: 'PUT',
  DeleteMapping: 'DELETE',
  PatchMapping: 'PATCH',
  RequestMapping: 'ANY',
}
const JAVA_TYPES = new Set(['class_declaration', 'interface_declaration', 'enum_declaration', 'record_declaration'])

const line = (node) => node.startPosition.row + 1
const unquote = (text) => text.replace(/^["'`]|["'`]$/g, '')
const joinPath = (a, b) => ('/' + [a, b].filter(Boolean).join('/')).replace(/\/+/g, '/').replace(/(.)\/$/, '$1')

// `/users/:id`, `/users/${id}` and `/users/{id}` are the same route
const normalizePath = (path) =>
  path
    .replace(/\$\{[^}]*\}/g, '{x}')
    .replace(/:([A-Za-z_]\w*)/g, '{$1}')
    .replace(/\{[^}/]*\}/g, '{x}')

// ---------- Java ----------

function annotationsOf(modifiers) {
  if (!modifiers) return []
  return modifiers.namedChildren
    .filter((c) => c.type === 'marker_annotation' || c.type === 'annotation')
    .map((a) => {
      const name = a.childForFieldName('name')?.text.split('.').pop() ?? ''
      const args = a.childForFieldName('arguments')
      const named = {}
      let first = null
      if (args) {
        for (const s of args.descendantsOfType('string_literal')) {
          first = first ?? unquote(s.text)
          break
        }
        for (const pair of args.descendantsOfType('element_value_pair')) {
          const key = pair.childForFieldName('key')?.text
          const value = pair.childForFieldName('value')
          const str = value?.descendantsOfType('string_literal')[0] ?? (value?.type === 'string_literal' ? value : null)
          if (key && str) named[key] = unquote(str.text)
        }
      }
      return { name, first: named.value ?? named.path ?? first, named, line: line(a) }
    })
}

function typeNames(node) {
  return node ? node.descendantsOfType('type_identifier').map((t) => t.text) : []
}

function walkJavaType(node, facts) {
  const name = node.childForFieldName('name')?.text
  if (!name) return
  const annotations = annotationsOf(node.namedChildren.find((c) => c.type === 'modifiers'))
  const names = annotations.map((a) => a.name)
  facts.types.push({
    name,
    kind: node.type.replace('_declaration', ''),
    annotations: names,
    superclass: typeNames(node.childForFieldName('superclass'))[0] ?? null,
    interfaces: typeNames(node.childForFieldName('interfaces')),
    line: line(node),
  })

  const prefix = annotations.find((a) => a.name === 'RequestMapping')?.first ?? ''
  const feign = annotations.find((a) => a.name === 'FeignClient')
  if (feign) {
    facts.outbound.push({ kind: 'feign', url: feign.named.url ?? feign.named.name ?? feign.first ?? '', line: feign.line })
  }
  const entity = annotations.find((a) => a.name === 'Entity' || a.name === 'Document')
  if (entity) {
    const table = annotations.find((a) => a.name === 'Table')?.named.name
    facts.entities.push({ name, table: table ?? name.toLowerCase(), line: entity.line })
  }

  const body = node.childForFieldName('body')
  for (const member of body?.namedChildren ?? []) {
    if (JAVA_TYPES.has(member.type)) {
      walkJavaType(member, facts)
    } else if (member.type === 'method_declaration') {
      const modifiers = member.namedChildren.find((c) => c.type === 'modifiers')
      for (const a of annotationsOf(modifiers)) {
        const method = SPRING_MAPPINGS[a.name]
        if (method && names.some((n) => n === 'RestController' || n === 'Controller' || n === 'RequestMapping')) {
          facts.routes.push({
            method: a.named.method ?? method,
            path: normalizePath(joinPath(prefix, a.first ?? '')),
            line: a.line,
            handler: `${name}.${member.childForFieldName('name')?.text}`,
          })
        }
      }
      if (
        member.childForFieldName('name')?.text === 'main' &&
        (modifiers?.text ?? '').includes('static')
      ) {
        facts.hasMain = true
      }
    }
  }
}

function extractJava(root) {
  const facts = { pkg: '', imports: [], types: [], routes: [], outbound: [], entities: [], hasMain: false }
  for (const n of root.namedChildren) {
    if (n.type === 'package_declaration') {
      facts.pkg = n.namedChildren.find((c) => c.type.includes('identifier'))?.text ?? ''
    } else if (n.type === 'import_declaration') {
      const name = n.text.replace(/^import\s+(static\s+)?/, '').replace(/;\s*$/, '').trim()
      facts.imports.push({ spec: name, line: line(n), isStatic: /^import\s+static/.test(n.text) })
    } else if (JAVA_TYPES.has(n.type)) {
      walkJavaType(n, facts)
    }
  }
  for (const s of root.descendantsOfType('string_literal')) {
    const value = unquote(s.text)
    if (/^https?:\/\//.test(value)) facts.outbound.push({ kind: 'url-literal', url: value, line: line(s) })
  }
  // simple names of the types this file refers to, minus its own generic parameters: lets the backend spot
  // classes that are used but declared nowhere (same-package references need no import)
  const params = new Set(
    root.descendantsOfType('type_parameter').flatMap((p) => p.descendantsOfType('type_identifier').map((t) => t.text)),
  )
  facts.usedTypes = [...new Set(root.descendantsOfType('type_identifier').map((t) => t.text))]
    .filter((name) => !params.has(name))
    .slice(0, 300)
  return facts
}

// ---------- JavaScript / TypeScript ----------

function firstArgPath(call) {
  const arg = call.childForFieldName('arguments')?.namedChildren[0]
  if (!arg) return null
  if (arg.type === 'string') return unquote(arg.text)
  if (arg.type === 'template_string') return arg.text.slice(1, -1)
  return null
}

function extractJs(root) {
  const facts = { imports: [], exports: [], routes: [], outbound: [], types: [], entities: [], hasMain: false }

  for (const n of root.descendantsOfType(['import_statement', 'export_statement'])) {
    const source = n.childForFieldName('source')
    if (source) facts.imports.push({ spec: unquote(source.text), line: line(n) })
  }
  for (const call of root.descendantsOfType('call_expression')) {
    const fn = call.childForFieldName('function')
    if (!fn) continue
    const arg = firstArgPath(call)
    if ((fn.type === 'identifier' && fn.text === 'require') || fn.type === 'import') {
      if (arg) facts.imports.push({ spec: arg, line: line(call) })
    } else if (fn.type === 'identifier' && ['fetch', 'axios', 'ky', 'got'].includes(fn.text)) {
      if (arg && /^(https?:)?\/|^\$\{/.test(arg)) {
        facts.outbound.push({ kind: fn.text, method: 'ANY', url: normalizePath(arg), line: line(call) })
      }
    } else if (fn.type === 'member_expression') {
      const prop = fn.childForFieldName('property')?.text
      const object = fn.childForFieldName('object')?.text ?? ''
      if (!prop || !HTTP_METHODS.has(prop) || !arg) continue
      if (ROUTER_OBJECT.test(object) && arg.startsWith('/')) {
        facts.routes.push({ method: prop.toUpperCase(), path: normalizePath(arg), line: line(call), handler: object })
      } else if (CLIENT_OBJECT.test(object) && /^(https?:)?\/|^\$\{/.test(arg)) {
        facts.outbound.push({ kind: object, method: prop.toUpperCase(), url: normalizePath(arg), line: line(call) })
      }
    }
  }

  // NestJS: @Controller('x') on a class, @Get('y') on its methods, in document order
  let prefix = ''
  for (const d of root.descendantsOfType('decorator')) {
    const m = d.text.match(/^@(Controller|Get|Post|Put|Delete|Patch)\(\s*(?:['"`]([^'"`]*)['"`])?/)
    if (!m) continue
    if (m[1] === 'Controller') prefix = m[2] ?? ''
    else {
      facts.routes.push({ method: m[1].toUpperCase(), path: normalizePath(joinPath(prefix, m[2] ?? '')), line: line(d), handler: 'nest' })
    }
  }

  for (const n of root.namedChildren) {
    if (n.type !== 'export_statement') continue
    const decl = n.childForFieldName('declaration')
    if (!decl) continue
    const names =
      decl.type === 'lexical_declaration'
        ? decl.descendantsOfType('variable_declarator').map((v) => v.childForFieldName('name')?.text)
        : [decl.childForFieldName('name')?.text]
    for (const name of names) if (name && facts.exports.length < 12) facts.exports.push(name)
    if (decl.type.includes('class') && decl.childForFieldName('name')) {
      facts.types.push({ name: decl.childForFieldName('name').text, kind: 'class', annotations: [], line: line(decl) })
    }
  }
  return facts
}

// ---------- main ----------

const [, , rootDir, filesJson, outJson] = process.argv
if (!rootDir || !filesJson || !outJson) {
  console.error('usage: extract.mjs <rootDir> <filesJson> <outJson>')
  process.exit(2)
}

await Parser.init()
const languages = {}
const parser = new Parser()
const result = { files: {}, errors: [] }

for (const rel of JSON.parse(readFileSync(filesJson, 'utf8'))) {
  const spec = GRAMMAR[extname(rel).toLowerCase()]
  if (!spec) continue
  try {
    const abs = join(rootDir, rel)
    if (statSync(abs).size > MAX_BYTES) {
      result.errors.push({ file: rel, error: 'skipped: larger than 1 MB' })
      continue
    }
    languages[spec[0]] ??= await Parser.Language.load(join(WASM_DIR, `tree-sitter-${spec[0]}.wasm`))
    parser.setLanguage(languages[spec[0]])
    const tree = parser.parse(readFileSync(abs, 'utf8'))
    const facts = spec[0] === 'java' ? extractJava(tree.rootNode) : extractJs(tree.rootNode)
    result.files[rel] = { lang: spec[1], partial: Boolean(tree.rootNode.hasError), ...facts }
  } catch (e) {
    result.errors.push({ file: rel, error: String(e.message ?? e) })
  }
}
writeFileSync(outJson, JSON.stringify(result))
console.log(`extracted ${Object.keys(result.files).length} files, ${result.errors.length} errors`)

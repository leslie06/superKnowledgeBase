import MarkdownIt from 'markdown-it'

const md = new MarkdownIt({ html: false, linkify: true, breaks: true })

// 把回答中的 [1]、[2] 渲染成可点击的引用角标（排除 [1](url) 这种链接写法）
md.inline.ruler.before('link', 'citation', (state, silent) => {
  const match = /^\[(\d{1,2})\](?!\()/.exec(state.src.slice(state.pos))
  if (!match) return false
  if (!silent) {
    const token = state.push('citation', '', 0)
    token.meta = { index: Number(match[1]) }
  }
  state.pos += match[0].length
  return true
})
md.renderer.rules.citation = (tokens, idx) => {
  const index = tokens[idx].meta?.index
  return `<sup class="cite" data-cite="${index}">${index}</sup>`
}

md.renderer.rules.link_open = (tokens, idx, options, _env, self) => {
  tokens[idx].attrSet('target', '_blank')
  tokens[idx].attrSet('rel', 'noopener noreferrer')
  return self.renderToken(tokens, idx, options)
}

export function renderMarkdown(text: string) {
  return md.render(text)
}

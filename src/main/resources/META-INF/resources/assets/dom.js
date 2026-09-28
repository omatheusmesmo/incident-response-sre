/**
 * Creates an element. Children may be nodes, strings or arrays; strings always become text
 * nodes, so model output can never be interpreted as markup.
 */
export function h(tag, attrs = {}, ...children) {
  const el = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs)) {
    if (value == null || value === false) continue;
    if (key === 'class') el.className = value;
    else if (key.startsWith('on')) el.addEventListener(key.slice(2), value);
    else el.setAttribute(key, value === true ? '' : value);
  }
  append(el, children);
  return el;
}

function append(el, children) {
  for (const child of children.flat(Infinity)) {
    if (child == null || child === false) continue;
    el.append(child instanceof Node ? child : String(child));
  }
}

const JAVA_TOKENS = [
  ['comment', /^\/\/[^\n]*/],
  ['string', /^"(?:[^"\\]|\\.)*"/],
  ['annotation', /^@[A-Za-z]+/],
  ['keyword', /^\b(?:public|static|interface|return|new|boolean|double|int|class|extends)\b/],
  ['type', /^\b[A-Z][A-Za-z0-9]*\b/],
  ['number', /^\b\d+(?:\.\d+)?\b/],
];

/** Highlights a Java excerpt into spans, GitHub light style like the deck's code blocks. */
export function highlightJava(source) {
  const code = h('code');
  let rest = source;
  let plain = '';
  const flush = () => { if (plain) { code.append(plain); plain = ''; } };
  while (rest.length) {
    const hit = JAVA_TOKENS.map(([kind, re]) => [kind, re.exec(rest)]).find(([, m]) => m);
    if (hit) {
      flush();
      const [kind, match] = hit;
      code.append(h('span', { class: `tk-${kind}` }, match[0]));
      rest = rest.slice(match[0].length);
    } else {
      const word = /^[a-z_][A-Za-z0-9_]*|^./s.exec(rest)[0];
      plain += word;
      rest = rest.slice(word.length);
    }
  }
  flush();
  return code;
}

export function formatSeconds(ms) {
  return `${(ms / 1000).toFixed(1)}s`;
}

export function humanize(value) {
  return value == null ? '' : String(value).replaceAll('_', ' ');
}

function inline(text) {
  return text.split(/(\*\*[^*]+\*\*|`[^`]+`)/g).filter(Boolean).map(part => {
    if (part.startsWith('**') && part.endsWith('**')) return h('strong', {}, part.slice(2, -2));
    if (part.startsWith('`') && part.endsWith('`')) return h('code', {}, part.slice(1, -1));
    return part;
  });
}

/**
 * Renders the small Markdown subset LLMs tend to return (paragraphs, headings, bullet and
 * numbered lists, bold, inline code) as DOM nodes. Text is never parsed as HTML.
 */
export function richText(source) {
  const root = h('div', { class: 'rich' });
  let list = null;
  let paragraph = [];
  const flushParagraph = () => {
    if (paragraph.length) root.append(h('p', {}, inline(paragraph.join(' '))));
    paragraph = [];
  };
  for (const raw of String(source ?? '').split('\n')) {
    const line = raw.trim();
    const bullet = /^(?:[-*•]|\d+[.)])\s+(.*)$/.exec(line);
    const heading = /^#{1,6}\s+(.*)$/.exec(line) ?? /^\*\*([^*]+)\*\*:?$/.exec(line);
    if (bullet) {
      flushParagraph();
      if (!list) { list = h('ul'); root.append(list); }
      list.append(h('li', {}, inline(bullet[1])));
      continue;
    }
    list = null;
    if (!line) { flushParagraph(); continue; }
    if (heading) {
      flushParagraph();
      root.append(h('h4', {}, heading[1].replace(/:$/, '')));
      continue;
    }
    paragraph.push(line);
  }
  flushParagraph();
  return root;
}

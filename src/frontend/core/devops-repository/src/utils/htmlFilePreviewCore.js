const ABSOLUTE_RESOURCE_PATTERN = /^(https?:|data:|blob:|mailto:|javascript:|tel:)/i
const RESOURCE_ATTRS_BY_TAG = {
    script: ['src'],
    img: ['src', 'srcset'],
    image: ['src', 'href', 'xlink:href'],
    link: ['href'],
    iframe: ['src'],
    embed: ['src'],
    object: ['data'],
    source: ['src', 'srcset'],
    video: ['src', 'poster'],
    audio: ['src'],
    track: ['src'],
    input: ['src'],
    use: ['href', 'xlink:href']
}

function buildHtmlPreviewGuardScript () {
    return `<script>
(function () {
  function wrapHistoryMethod (name) {
    var original = history[name];
    if (typeof original !== 'function') {
      return;
    }
    history[name] = function () {
      try {
        return original.apply(this, arguments);
      } catch (e) {
        /* opaque-origin srcdoc cannot adopt the parent URL */
      }
    };
  }
  wrapHistoryMethod('replaceState');
  wrapHistoryMethod('pushState');
  try {
    Object.defineProperty(document, 'cookie', {
      configurable: true,
      get: function () { return ''; },
      set: function () {}
    });
  } catch (e) { /* ignore */ }
  try {
    Object.defineProperty(navigator, 'serviceWorker', {
      configurable: true,
      get: function () { return undefined; }
    });
  } catch (e) { /* ignore */ }
  document.addEventListener('click', function (event) {
    var anchor = event.target && event.target.closest ? event.target.closest('a[href]') : null;
    if (!anchor) {
      return;
    }
    var href = anchor.getAttribute('href');
    if (!href) {
      return;
    }
    if (href.charAt(0) === '#') {
      if (href === '#') {
        event.preventDefault();
        return;
      }
      if (event.defaultPrevented) {
        return;
      }
      var id = decodeURIComponent(href.slice(1));
      var target = document.getElementById(id) || document.getElementsByName(id)[0];
      if (!target) {
        event.preventDefault();
        return;
      }
      event.preventDefault();
      target.scrollIntoView({ behavior: 'smooth', block: 'start' });
      return;
    }
    event.preventDefault();
    event.stopPropagation();
  }, true);
})();
<\/script>`
}

function skipEmbeddedContent (html, match) {
    const token = match[0].slice(0, 10).toLowerCase()
    if (token.startsWith('<!--')) {
        const end = html.indexOf('-->', match.index + 4)
        return end === -1 ? html.length : end + 3
    }
    const closeRe = token.startsWith('<script') ? /<\/script>/gi : /<\/style>/gi
    closeRe.lastIndex = match.index + match[0].length
    const close = closeRe.exec(html)
    return close ? close.index + close[0].length : html.length
}

function findFirstMatchOutsideEmbeddedContent (html, patternSource) {
    const skip = /<!--|<script\b|<style\b/gi
    const pattern = new RegExp(patternSource, 'gi')
    let cursor = 0
    while (cursor < html.length) {
        skip.lastIndex = cursor
        pattern.lastIndex = cursor
        const skipMatch = skip.exec(html)
        const wanted = pattern.exec(html)
        if (!wanted) {
            return null
        }
        if (skipMatch && skipMatch.index < wanted.index) {
            cursor = skipEmbeddedContent(html, skipMatch)
            continue
        }
        return wanted
    }
    return null
}

function findDocumentHeadInjectionIndex (html) {
    const headOpen = findFirstMatchOutsideEmbeddedContent(html, '<head\\b[^>]*>')
    if (!headOpen) {
        return -1
    }
    let index = headOpen.index + headOpen[0].length
    const following = html.slice(index, index + 512)
    const metaMatch = following.match(/^\s*<meta\b[^>]*>/i)
    if (metaMatch && /charset/i.test(metaMatch[0])) {
        index += metaMatch[0].length
    }
    return index
}

function findDocumentHtmlOpenEnd (html) {
    const htmlOpen = findFirstMatchOutsideEmbeddedContent(html, '<html\\b[^>]*>')
    if (!htmlOpen) {
        return -1
    }
    return htmlOpen.index + htmlOpen[0].length
}

function resolvePreviewResourceUrl (value, resolveAssetUrl) {
    const url = (value || '').trim()
    if (!url || url.charAt(0) === '#' || url.startsWith('//') || ABSOLUTE_RESOURCE_PATTERN.test(url)) {
        return value
    }
    try {
        return resolveAssetUrl(url) || value
    } catch (e) {
        return value
    }
}

function rewriteCssUrls (css, resolveAssetUrl) {
    return css.replace(/url\(\s*(['"]?)([^'")]+)\1\s*\)/gi, (match, quote, url) => {
        const resolved = resolvePreviewResourceUrl(url, resolveAssetUrl)
        if (!resolved || resolved === url) {
            return match
        }
        const wrapper = quote || '"'
        return `url(${wrapper}${resolved}${wrapper})`
    })
}

function rewriteSrcsetValue (value, resolveAssetUrl) {
    return value.split(',').map((part) => {
        const trimmed = part.trim()
        if (!trimmed) {
            return part
        }
        const tokens = trimmed.split(/\s+/)
        const resolved = resolvePreviewResourceUrl(tokens[0], resolveAssetUrl)
        tokens[0] = resolved || tokens[0]
        return tokens.join(' ')
    }).join(', ')
}

function rewriteQuotedAttr (tag, attrName, resolveAssetUrl) {
    const pattern = new RegExp(`(\\s${attrName.replace(/:/g, '\\:')}\\s*=\\s*)(["'])([\\s\\S]*?)\\2`, 'gi')
    return tag.replace(pattern, (match, prefix, quote, value) => {
        const resolved = attrName === 'srcset'
            ? rewriteSrcsetValue(value, resolveAssetUrl)
            : resolvePreviewResourceUrl(value, resolveAssetUrl)
        if (!resolved || resolved === value) {
            return match
        }
        return `${prefix}${quote}${resolved}${quote}`
    })
}

function rewriteResourceTag (tag, resolveAssetUrl) {
    const nameMatch = tag.match(/^<([a-zA-Z][\w:-]*)/)
    if (!nameMatch) {
        return tag
    }
    const attrs = RESOURCE_ATTRS_BY_TAG[nameMatch[1].toLowerCase()]
    let next = tag
    if (attrs) {
        attrs.forEach((attr) => {
            next = rewriteQuotedAttr(next, attr, resolveAssetUrl)
        })
    }
    if (/\sstyle\s*=/i.test(next)) {
        next = next.replace(/(\sstyle\s*=\s*)(["'])([\s\S]*?)\2/gi, (match, prefix, quote, css) => {
            return `${prefix}${quote}${rewriteCssUrls(css, resolveAssetUrl)}${quote}`
        })
    }
    return next
}

function rewriteMarkupResources (html, resolveAssetUrl) {
    return html.replace(/<[a-zA-Z][^>]*>/g, (tag) => rewriteResourceTag(tag, resolveAssetUrl))
}

function rewriteOpeningTag (block, resolveAssetUrl) {
    const tagEnd = block.indexOf('>')
    if (tagEnd < 0) {
        return block
    }
    return rewriteResourceTag(block.slice(0, tagEnd + 1), resolveAssetUrl) + block.slice(tagEnd + 1)
}

function rewriteHtmlResourceUrls (html, resolveAssetUrl) {
    if (!html || !resolveAssetUrl) {
        return html
    }
    const skip = /<!--|<script\b|<style\b/gi
    let cursor = 0
    let out = ''
    while (cursor < html.length) {
        skip.lastIndex = cursor
        const skipMatch = skip.exec(html)
        if (!skipMatch) {
            out += rewriteMarkupResources(html.slice(cursor), resolveAssetUrl)
            break
        }
        out += rewriteMarkupResources(html.slice(cursor, skipMatch.index), resolveAssetUrl)
        const token = skipMatch[0].slice(0, 10).toLowerCase()
        if (token.startsWith('<!--')) {
            const end = html.indexOf('-->', skipMatch.index + 4)
            const closeAt = end === -1 ? html.length : end + 3
            out += html.slice(skipMatch.index, closeAt)
            cursor = closeAt
            continue
        }
        const isScript = token.startsWith('<script')
        const closeRe = isScript ? /<\/script>/gi : /<\/style>/gi
        closeRe.lastIndex = skipMatch.index + skipMatch[0].length
        const close = closeRe.exec(html)
        const closeAt = close ? close.index + close[0].length : html.length
        const block = html.slice(skipMatch.index, closeAt)
        out += isScript
            ? rewriteOpeningTag(block, resolveAssetUrl)
            : rewriteCssUrls(block, resolveAssetUrl)
        cursor = closeAt
    }
    return out
}

function injectPreviewGuard (source, guardScript) {
    const headIndex = findDocumentHeadInjectionIndex(source)
    if (headIndex >= 0) {
        return source.slice(0, headIndex) + guardScript + source.slice(headIndex)
    }
    const htmlIndex = findDocumentHtmlOpenEnd(source)
    if (htmlIndex >= 0) {
        return source.slice(0, htmlIndex) + `<head>${guardScript}</head>` + source.slice(htmlIndex)
    }
    return `<!DOCTYPE html><html><head><meta charset="utf-8">${guardScript}</head><body>${source}</body></html>`
}

/**
 * Build sandboxed srcdoc for a single HTML file.
 * Isolation is the iframe sandbox plus opaque-origin guards — not a resource CSP whitelist.
 * Relative repo assets are rewritten when resolveAssetUrl is provided; absolute CDN URLs stay intact.
 */
export function buildHtmlSandboxSrcdoc (htmlSource, { resolveAssetUrl } = {}) {
    const rewritten = rewriteHtmlResourceUrls(htmlSource || '', resolveAssetUrl)
    return injectPreviewGuard(rewritten, buildHtmlPreviewGuardScript())
}

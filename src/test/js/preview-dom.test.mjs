/**
 * Behaviour test for the markdown preview page, run against a real browser.
 *
 * The page is JavaScript, and what matters about it is the DOM it ends up
 * building — not the text of its script. So this test loads the page the plugin
 * actually ships (written to build/preview-fixture/ by MarkdownPreviewFixtureTest),
 * feeds documents to it through the same window.__setMarkdownB64 entry point the
 * plugin uses, and asserts on the resulting nodes.
 *
 * Usage:
 *
 *     ./gradlew test --tests '*MarkdownPreviewFixtureTest'   # writes the fixture
 *     node src/test/js/preview-dom.test.mjs
 *
 * No npm dependencies: it drives a headless Chromium over the DevTools protocol
 * using Node's built-in fetch and WebSocket. It needs a Chromium binary, taken
 * from $CHROME if set, otherwise from the usual Playwright/Puppeteer cache
 * locations. A missing fixture or missing browser exits non-zero — this test
 * never reports success without having run.
 */

import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { createServer } from 'node:net';
import { mkdtempSync, existsSync, readdirSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir, homedir } from 'node:os';
import { join, dirname, resolve } from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../../..');
const fixtureDir = join(repoRoot, 'build', 'preview-fixture');
const fixturePage = join(fixtureDir, 'preview.html');

// ---------------------------------------------------------------- test harness

const failures = [];
let checks = 0;

function check(name, passed, detail) {
  checks++;
  if (passed === true) {
    console.log(`  ok   ${name}`);
  } else {
    console.log(`  FAIL ${name} — ${JSON.stringify(detail)}`);
    failures.push(name);
  }
}

function fatal(message) {
  console.error(`\npreview-dom.test.mjs: ${message}`);
  process.exit(2);
}

// ------------------------------------------------------------- browser locator

/** Expands a slash-separated pattern whose segments may contain '*'. */
function expand(root, pattern) {
  let paths = existsSync(root) ? [root] : [];
  for (const segment of pattern.split('/')) {
    const next = [];
    for (const base of paths) {
      if (segment.includes('*')) {
        const re = new RegExp('^' + segment.replace(/[.+^${}()|[\]\\]/g, '\\$&').replace(/\*/g, '.*') + '$');
        let entries = [];
        try {
          entries = readdirSync(base);
        } catch { /* unreadable directory */ }
        for (const entry of entries) if (re.test(entry)) next.push(join(base, entry));
      } else {
        next.push(join(base, segment));
      }
    }
    paths = next;
  }
  return paths;
}

function findBrowser() {
  if (process.env.CHROME) return process.env.CHROME;
  const home = homedir();
  const candidates = [
    ...expand(join(home, 'Library/Caches/ms-playwright'), 'chromium_headless_shell-*/chrome-headless-shell-mac-*/chrome-headless-shell'),
    ...expand(join(home, 'Library/Caches/ms-playwright'), 'chromium-*/chrome-mac/Chromium.app/Contents/MacOS/Chromium'),
    ...expand(join(home, '.cache/puppeteer/chrome-headless-shell'), '*/chrome-headless-shell-*/chrome-headless-shell'),
    ...expand(join(home, '.cache/ms-playwright'), 'chromium_headless_shell-*/chrome-linux/chrome-headless-shell'),
    ...expand(join(home, '.cache/ms-playwright'), 'chromium-*/chrome-linux/chrome'),
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    '/usr/bin/chromium',
    '/usr/bin/google-chrome',
  ];
  return candidates.find((candidate) => existsSync(candidate)) ?? null;
}

function freePort() {
  return new Promise((res, rej) => {
    const server = createServer();
    server.on('error', rej);
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address();
      server.close(() => res(port));
    });
  });
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ------------------------------------------------------------------ CDP client

class Cdp {
  constructor(ws) {
    this.ws = ws;
    this.id = 0;
    this.pending = new Map();
    ws.addEventListener('message', (event) => {
      const message = JSON.parse(event.data);
      if (message.id && this.pending.has(message.id)) {
        const { resolve: res, reject } = this.pending.get(message.id);
        this.pending.delete(message.id);
        if (message.error) reject(new Error(JSON.stringify(message.error)));
        else res(message.result);
      }
    });
  }

  send(method, params = {}) {
    const id = ++this.id;
    return new Promise((res, reject) => {
      this.pending.set(id, { resolve: res, reject });
      this.ws.send(JSON.stringify({ id, method, params }));
    });
  }

  /** Runs `body` as an async function body in the page and returns its value. */
  async eval(body) {
    const result = await this.send('Runtime.evaluate', {
      expression: `(async () => { ${body} })()`,
      awaitPromise: true,
      returnByValue: true,
    });
    if (result.exceptionDetails) {
      const details = result.exceptionDetails;
      throw new Error('page threw: ' + (details.exception?.description ?? JSON.stringify(details)));
    }
    return result.result.value;
  }
}

async function openBrowser(browserBin, port, userDataDir, fileUrl) {
  const args = [
    `--remote-debugging-port=${port}`,
    `--user-data-dir=${userDataDir}`,
    '--no-first-run',
    '--no-default-browser-check',
    '--disable-gpu',
    fileUrl,
  ];
  // chrome-headless-shell is headless by design and rejects the flag.
  if (!browserBin.includes('headless-shell')) args.unshift('--headless=new');
  // CI runners restrict the unprivileged user namespaces Chromium's sandbox needs
  // and give containers a small /dev/shm. Neither matters for a local file opened
  // in a throwaway profile.
  if (process.env.CI) args.unshift('--no-sandbox', '--disable-dev-shm-usage');

  const child = spawn(browserBin, args, { stdio: ['ignore', 'pipe', 'pipe'] });
  let stderr = '';
  child.stderr.on('data', (d) => { stderr += d.toString(); });

  let target = null;
  for (let attempt = 0; attempt < 150 && !target; attempt++) {
    await sleep(100);
    try {
      const list = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
      target = list.find((t) => t.type === 'page' && t.webSocketDebuggerUrl);
    } catch { /* not listening yet */ }
  }
  if (!target) {
    child.kill('SIGKILL');
    fatal(`browser never exposed a page target.\n${stderr}`);
  }
  const ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((res, rej) => {
    ws.addEventListener('open', res, { once: true });
    ws.addEventListener('error', rej, { once: true });
  });
  return { child, cdp: new Cdp(ws), stderrOf: () => stderr };
}

/** Waits until the shell's own script has installed the render entry point. */
async function awaitPageReady(cdp) {
  for (let attempt = 0; attempt < 200; attempt++) {
    const ready = await cdp
      .eval('return document.readyState === "complete" && typeof window.__setMarkdownB64 === "function";')
      .catch(() => false);
    if (ready) return true;
    await sleep(100);
  }
  return false;
}

// ------------------------------------------------------------------ the probes

/**
 * Renders `markdown` through the page's own entry point, then evaluates `probe`
 * against the result. Inside `probe`, `el` is the rendered #content element and
 * `await` is available; `window.__ran` reports whether the document managed to
 * execute anything.
 *
 * `until` polls for a condition (up to `budget` ms) before probing, so an
 * asynchronous render is waited for rather than slept past; `budget` on its own
 * is a plain settle delay.
 */
function render(cdp, markdown, probe, { until = null, budget = 0 } = {}) {
  const b64 = Buffer.from(markdown, 'utf8').toString('base64');
  const wait = until
    ? `var deadline = Date.now() + ${budget || 8000};
       while (Date.now() < deadline && !(${until})) { await new Promise(function (r) { setTimeout(r, 50); }); }`
    : budget
      ? `await new Promise(function (r) { setTimeout(r, ${budget}); });`
      : '';
  return cdp.eval(`
    window.__ran = false;
    window.__setMarkdownB64(${JSON.stringify(b64)});
    var el = document.getElementById('content');
    ${wait}
    return await (async function () { ${probe} })();
  `);
}

/** Counts elements under `el` carrying an attribute whose name starts with "on". */
const HANDLER_COUNT = `
  Array.prototype.filter.call(el.querySelectorAll('*'), function (n) {
    return Array.prototype.some.call(n.attributes, function (a) {
      return a.name.toLowerCase().indexOf('on') === 0;
    });
  }).length`;

async function run() {
  if (!existsSync(fixturePage)) {
    fatal(`fixture missing: ${fixturePage}\nRun: ./gradlew test --tests '*MarkdownPreviewFixtureTest'`);
  }
  const browserBin = findBrowser();
  if (!browserBin) fatal('no Chromium found. Set $CHROME to a Chromium/Chrome binary.');

  console.log(`browser: ${browserBin}`);
  console.log(`fixture: ${fixturePage}\n`);

  const userDataDir = mkdtempSync(join(tmpdir(), 'preview-dom-'));
  const { child, cdp, stderrOf } = await openBrowser(
    browserBin, await freePort(), userDataDir, pathToFileURL(fixturePage).href
  );

  try {
    if (!await awaitPageReady(cdp)) {
      fatal(`page never became ready — the policy may be blocking its own scripts.\n${stderrOf()}`);
    }

    // ---- the page's own machinery survives the policy ---------------------
    console.log('page machinery');
    const machinery = await cdp.eval(`return {
      purify: !!(window.DOMPurify && window.DOMPurify.isSupported),
      purifyVersion: window.DOMPurify && window.DOMPurify.version,
      marked: typeof window.marked,
      mermaid: typeof window.mermaid,
      baseURI: document.baseURI,
      contentWidth: getComputedStyle(document.getElementById('content')).maxWidth
    };`);
    check('sanitizer loaded and usable', machinery.purify === true, machinery);
    check('sanitizer is the pinned version', machinery.purifyVersion === '3.4.11', machinery);
    check('markdown parser loaded', machinery.marked === 'object', machinery);
    check('diagram renderer loaded', machinery.mermaid === 'object', machinery);
    check('base href points at the document directory', machinery.baseURI.endsWith('/doc/'), machinery);
    check("the page's own inline stylesheet applied", machinery.contentWidth === '860px', machinery);

    // ---- the policy is enforced, not merely present -----------------------
    console.log('\ncontent security policy');
    const csp = await cdp.eval(`
      window.__cspProbe = false;
      var plain = document.createElement('script');
      plain.textContent = 'window.__cspProbe = true;';
      document.body.appendChild(plain);
      plain.remove();
      var nonce = document.querySelector('script[src]').nonce;
      window.__nonceProbe = false;
      var nonced = document.createElement('script');
      nonced.setAttribute('nonce', nonce);
      nonced.textContent = 'window.__nonceProbe = true;';
      document.body.appendChild(nonced);
      nonced.remove();
      return { unnoncedRan: window.__cspProbe, noncedRan: window.__nonceProbe, nonceLength: nonce.length };
    `);
    check('a script the policy does not name cannot run', csp.unnoncedRan === false, csp);
    check('the page nonce is long enough to be unguessable', csp.nonceLength >= 16, csp);
    check('a nonced script still runs', csp.noncedRan === true, csp);

    // ---- documents that try to run something ------------------------------
    console.log('\ndocuments that try to run something');

    const handlers = await render(cdp,
      'text\n\n<img src="pixel.png" onerror="window.__ran = true" onload="window.__ran = true">\n',
      `var img = el.querySelector('img');
       if (img && !img.complete) await new Promise(function (r) { img.onload = r; img.onerror = r; });
       return { img: !!img, onerror: img && img.hasAttribute('onerror'), onload: img && img.hasAttribute('onload'),
                handlers: ${HANDLER_COUNT}, ran: window.__ran };`,
      { budget: 500 });
    check('inline event handlers are gone from the element',
      handlers.img === true && handlers.onerror === false && handlers.onload === false &&
      handlers.handlers === 0 && handlers.ran === false, handlers);

    const scriptBlock = await render(cdp,
      'before\n\n<script>window.__ran = true;</script>\n\nafter\n',
      `return { scripts: el.querySelectorAll('script').length, ran: window.__ran,
                keptText: el.textContent.indexOf('before') >= 0 && el.textContent.indexOf('after') >= 0 };`,
      { budget: 250 });
    check('a script block does not survive as an element',
      scriptBlock.scripts === 0 && scriptBlock.ran === false && scriptBlock.keptText === true, scriptBlock);

    const jsUrl = await render(cdp, '[click me](javascript:window.__ran=true)\n',
      `var a = el.querySelector('a');
       return { present: !!a, href: a && a.getAttribute('href'), text: a && a.textContent };`);
    check('a javascript: URL is dropped from the link',
      jsUrl.present === true && jsUrl.href === null && jsUrl.text === 'click me', jsUrl);

    const iframe = await render(cdp, '<iframe src="pixel.png"></iframe>\n\ntext\n',
      `return { iframes: el.querySelectorAll('iframe').length, keptText: el.textContent.indexOf('text') >= 0 };`);
    check('an iframe does not survive', iframe.iframes === 0 && iframe.keptText === true, iframe);

    const svg = await render(cdp,
      '<svg xmlns="http://www.w3.org/2000/svg"><script>window.__ran=true</script><circle r="4"/></svg>\n',
      `return { svgs: el.querySelectorAll('svg').length, scripts: el.querySelectorAll('script').length,
                ran: window.__ran };`,
      { budget: 250 });
    check('an svg carrying a script does not survive',
      svg.svgs === 0 && svg.scripts === 0 && svg.ran === false, svg);

    const plugins = await render(cdp, '<object data="pixel.png"></object><embed src="pixel.png">\n',
      `return el.querySelectorAll('object, embed').length;`);
    check('object and embed do not survive', plugins === 0, plugins);

    const styleTag = await render(cdp, '<style>#content { display: none }</style>\n\ntext\n',
      `return { styles: el.querySelectorAll('style').length, display: getComputedStyle(el).display };`);
    check('a style element does not survive',
      styleTag.styles === 0 && styleTag.display !== 'none', styleTag);

    // DOMPurify unwraps a forbidden element rather than dropping its subtree, so
    // the input remains as inert decoration — with no form element, and therefore
    // no form owner, there is nothing to submit it.
    const form = await render(cdp, '<form action="pixel.png"><input type="password" name="p"></form>\n',
      `var input = el.querySelector('input');
       return { forms: el.querySelectorAll('form').length, orphaned: input ? input.form === null : 'no input' };`);
    check('a form element does not survive, leaving nothing to submit',
      form.forms === 0 && form.orphaned === true, form);

    const rebase = await render(cdp, '<base href="https://example.invalid/">\n\n![p](pixel.png)\n',
      `var img = el.querySelector('img');
       return { bases: document.querySelectorAll('base').length, baseURI: document.baseURI,
                src: img && img.src };`,
      { budget: 250 });
    check('a document cannot re-point the page base',
      rebase.bases === 1 && rebase.baseURI.endsWith('/doc/') &&
      rebase.src.endsWith('/doc/pixel.png'), rebase);

    // ---- ordinary markdown still renders ----------------------------------
    console.log('\nordinary markdown');

    const table = await render(cdp, '| a | b |\n| --- | ---: |\n| 1 | 2 |\n',
      `var t = el.querySelector('table');
       return { table: !!t, th: el.querySelectorAll('th').length, td: el.querySelectorAll('td').length,
                aligned: el.querySelectorAll('[align="right"], [style*="right"]').length };`);
    check('a table renders as a table, keeping its column alignment',
      table.table === true && table.th === 2 && table.td === 2 && table.aligned > 0, table);

    const code = await render(cdp, '```js\nconst x = 1 < 2 && "a";\n```\n',
      `var c = el.querySelector('pre > code');
       return { cls: c && c.className, text: c && c.textContent.trim(), children: c && c.children.length };`);
    check('a fenced code block keeps its language class and shows its source inert',
      code.cls === 'language-js' && code.text === 'const x = 1 < 2 && "a";' && code.children === 0, code);

    const tasks = await render(cdp, '- [x] done\n- [ ] todo\n',
      `var boxes = el.querySelectorAll('input[type="checkbox"]');
       return { count: boxes.length, first: boxes[0] && boxes[0].checked, second: boxes[1] && boxes[1].checked };`);
    check('a task list renders checkboxes with their state',
      tasks.count === 2 && tasks.first === true && tasks.second === false, tasks);

    const relativeImage = await render(cdp, '![pixel](pixel.png)\n',
      `var img = el.querySelector('img');
       if (!img) return { img: false };
       if (!img.complete) await new Promise(function (r) { img.onload = r; img.onerror = r; });
       return { img: true, src: img.src, width: img.naturalWidth };`,
      { until: 'el.querySelector("img") && el.querySelector("img").complete', budget: 4000 });
    check('a relative image resolves against the base and loads',
      relativeImage.img === true && relativeImage.width === 1 &&
      relativeImage.src.endsWith('/doc/pixel.png'), relativeImage);

    const dataImage = await render(cdp,
      '![d](data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==)\n',
      `var img = el.querySelector('img');
       if (!img) return { img: false };
       if (!img.complete) await new Promise(function (r) { img.onload = r; img.onerror = r; });
       return { img: true, scheme: img.getAttribute('src').slice(0, 15), width: img.naturalWidth };`,
      { until: 'el.querySelector("img") && el.querySelector("img").complete', budget: 4000 });
    check('a data: image is kept and loads',
      dataImage.img === true && dataImage.scheme === 'data:image/gif;' && dataImage.width === 1, dataImage);

    const links = await render(cdp,
      '## Heading\n\n[jump](#heading) [other](./OTHER.md) [site](https://example.invalid/x)\n',
      `var as = el.querySelectorAll('a');
       return { count: as.length,
                hrefs: Array.prototype.map.call(as, function (a) { return a.getAttribute('href'); }),
                targets: Array.prototype.map.call(as, function (a) { return a.target; }) };`);
    // A fragment link must stay in-page. It used to be marked _blank along with
    // everything else, which sent '#heading' to the host and meant a README's
    // table of contents did nothing at all.
    check('outbound links are routed to a new tab but fragment links stay in-page',
      links.count === 3 && links.hrefs[0] === '#heading' && links.hrefs[1] === './OTHER.md' &&
      links.hrefs[2] === 'https://example.invalid/x' &&
      links.targets[0] === '' && links.targets[1] === '_blank' && links.targets[2] === '_blank', links);

    // marked v18 emits no heading ids of its own, so the slugs a '#heading' link
    // resolves against are ours. Duplicate titles are common in a changelog.
    const slugs = await render(cdp,
      '# Getting Started\n\n## API: `foo()` & bar!\n\n## Notes\n\n## Notes\n\n## Notes\n\n## ***\n',
      `var hs = el.querySelectorAll('h1,h2');
       return { ids: Array.prototype.map.call(hs, function (h) { return h.id; }) };`);
    check('headings get github-style slug ids, deduped in document order',
      slugs.ids[0] === 'getting-started' && slugs.ids[1] === 'api-foo-bar' &&
      slugs.ids[2] === 'notes' && slugs.ids[3] === 'notes-1' && slugs.ids[4] === 'notes-2' &&
      slugs.ids[5] === '', slugs);

    // \w is ASCII-only, so these all slugged to '' and got no id at all -- a
    // non-English README's table of contents stayed dead after the fragment fix.
    const unicodeSlugs = await render(cdp,
      '# \u65e5\u672c\u8a9e\u306e\u898b\u51fa\u3057\n\n## Caf\u00e9 r\u00e9sum\u00e9\n\n## \u0440\u0430\u0437\u0434\u0435\u043b\n',
      `var hs = el.querySelectorAll('h1,h2');
       return { ids: Array.prototype.map.call(hs, function (h) { return h.id; }) };`);
    check('non-latin and accented headings get real slug ids',
      unicodeSlugs.ids[0] === '\u65e5\u672c\u8a9e\u306e\u898b\u51fa\u3057' &&
      unicodeSlugs.ids[1] === 'caf\u00e9-r\u00e9sum\u00e9' &&
      unicodeSlugs.ids[2] === '\u0440\u0430\u0437\u0434\u0435\u043b', unicodeSlugs);

    // The shell renders into <article id="content">, so a heading slugging to
    // 'content' would be the second one in the tree and lose getElementById to its
    // own ancestor -- '#content' would scroll to the top of the pane, not the heading.
    const collision = await render(cdp,
      '## Content\n\n' + 'filler\n\n'.repeat(60) + '[toc](#content-1)\n',
      `var h = el.querySelector('h2');
       var a = el.querySelector('a[href="#content-1"]');
       var before = window.scrollY;
       if (a) a.click();
       await new Promise(function (r) { setTimeout(r, 250); });
       return { id: h.id, movedDown: window.scrollY > before, href: location.href };`,
      { budget: 300 });
    check('a heading named Content does not collide with the shell container',
      collision.id === 'content-1' && collision.movedDown, collision);

    // Cheap now the harness exists, and both are ways the preview could still be
    // thrown away: a link to an id that isn't there, and a bare '#'.
    const noTarget = await render(cdp,
      '[gone](#not-here)\n',
      `var before = location.href;
       el.querySelector('a').click();
       await new Promise(function (r) { setTimeout(r, 250); });
       return { navigated: location.href !== before,
                stillRendered: !!el.querySelector('a[href="#not-here"]') };`,
      { budget: 300 });
    check('a fragment link with no target still keeps the preview alive',
      !noTarget.navigated && noTarget.stillRendered, noTarget);

    const bareHash = await render(cdp,
      'filler\n\n'.repeat(60) + '[top](#)\n',
      `window.scrollTo(0, 400);
       await new Promise(function (r) { setTimeout(r, 100); });
       var before = location.href;
       el.querySelector('a[href="#"]').click();
       await new Promise(function (r) { setTimeout(r, 250); });
       return { navigated: location.href !== before, y: window.scrollY };`,
      { budget: 300 });
    check('a bare hash scrolls to the top without navigating',
      !bareHash.navigated && bareHash.y === 0, bareHash);

    // Not marking it _blank is only half the job. The page carries a <base href>
    // pointing at the markdown file's directory so relative images resolve, and per
    // spec a bare '#heading' resolves against the BASE, not the document — so a
    // plain in-page click would navigate to file:///thatdir/#heading, i.e. a
    // directory listing, rather than scrolling. Assert the click actually stays.
    const jump = await render(cdp,
      '## Target Heading\n\n' + 'filler\n\n'.repeat(60) + '[jump](#target-heading)\n',
      `var a = el.querySelector('a[href="#target-heading"]');
       var before = { href: location.href, y: window.scrollY };
       a.click();
       await new Promise(function (r) { setTimeout(r, 250); });
       var h = document.getElementById('target-heading');
       return { navigated: location.href !== before.href,
                movedDown: window.scrollY > before.y,
                headingExists: !!h,
                stillRendered: !!el.querySelector('a[href="#target-heading"]') };`,
      { budget: 300 });
    check('clicking a fragment link scrolls the preview instead of navigating away',
      jump.headingExists && !jump.navigated && jump.movedDown && jump.stillRendered, jump);

    // Footnote markers and their back-links are fragment links too, so the same
    // rule has to hold for the shapes marked-with-footnotes actually emits.
    const footnoteTargets = await render(cdp,
      'text<sup id="fnref1"><a href="#fn1">1</a></sup>\n\n' +
      '<section class="footnotes"><ol><li id="fn1">note <a href="#fnref1">back</a></li></ol></section>\n',
      `var as = el.querySelectorAll('a[href^="#"]');
       return { count: as.length,
                targets: Array.prototype.map.call(as, function (a) { return a.target; }) };`);
    check('footnote links and back-links scroll in-page rather than leaving',
      footnoteTargets.count === 2 && footnoteTargets.targets.every((t) => t === ''), footnoteTargets);

    const richHtml = await render(cdp,
      '<details><summary>more</summary>\n\nbody\n\n</details>\n\n' +
      '<p align="center"><img src="pixel.png" width="8" height="8" alt="p"></p>\n\n' +
      'text<sup id="fnref1"><a href="#fn1">1</a></sup>\n\n' +
      '<section class="footnotes"><ol><li id="fn1">note <a href="#fnref1">back</a></li></ol></section>\n',
      `return {
         details: el.querySelectorAll('details > summary').length,
         align: el.querySelector('p[align="center"]') !== null,
         width: el.querySelector('img') && el.querySelector('img').getAttribute('width'),
         sup: el.querySelector('sup#fnref1 > a[href="#fn1"]') !== null,
         footnote: el.querySelector('section.footnotes li#fn1 > a[href="#fnref1"]') !== null
       };`,
      { budget: 400 });
    check('details, footnote markup, alignment and sizing survive',
      richHtml.details === 1 && richHtml.align === true && richHtml.width === '8' &&
      richHtml.sup === true && richHtml.footnote === true, richHtml);

    const inline = await render(cdp,
      '**bold** _em_ ~~struck~~ `code`\n\n> quoted\n\n1. one\n1. two\n\n---\n',
      `return { strong: el.querySelectorAll('strong').length, em: el.querySelectorAll('em').length,
                del: el.querySelectorAll('del').length, code: el.querySelectorAll('code').length,
                quote: el.querySelectorAll('blockquote').length, items: el.querySelectorAll('ol > li').length,
                hr: el.querySelectorAll('hr').length };`);
    check('inline formatting, quotes, lists and rules survive',
      inline.strong === 1 && inline.em === 1 && inline.del === 1 && inline.code === 1 &&
      inline.quote === 1 && inline.items === 2 && inline.hr === 1, inline);

    // ---- mermaid ----------------------------------------------------------
    console.log('\nmermaid');

    // Mermaid builds its diagram inside the target element in stages: an empty
    // <svg><g></g></svg> scaffold appears first, and only at the end is it
    // replaced by the finished drawing. So "an svg exists" is not a completion
    // signal — waiting on it probes the scaffold, and every assertion about the
    // diagram's contents then passes for want of any contents at all. The
    // finished drawing always carries the <style> element mermaid inserts into
    // it, so that is what these wait on.
    const DIAGRAM_DONE = 'el.querySelector("pre.mermaid svg style")';

    const diagram = await render(cdp, '```mermaid\ngraph TD;\n  A[Start] --> B[End];\n```\n',
      `var svg = el.querySelector('pre.mermaid svg');
       return { svgs: el.querySelectorAll('pre.mermaid svg').length,
                fences: el.querySelectorAll('code.language-mermaid').length,
                styles: svg ? svg.querySelectorAll('style').length : 0,
                shapes: svg ? svg.querySelectorAll('rect, polygon, circle, path').length : 0,
                labels: svg ? (svg.textContent.indexOf('Start') >= 0 && svg.textContent.indexOf('End') >= 0) : false };`,
      { until: DIAGRAM_DONE, budget: 25000 });
    check('a mermaid fence renders a diagram, replacing the fence',
      diagram.svgs === 1 && diagram.fences === 0 && diagram.labels === true && diagram.shapes > 0, diagram);
    check('the diagram keeps the style element mermaid inserts into it',
      diagram.styles >= 1, diagram);

    // A label is drawn as SVG <text>, never as HTML in a foreignObject — which is
    // what makes markup in a label unable to become markup. Both cases assert that
    // structure rather than mermaid's exact label wording, which is its own
    // business and varies by version.
    const LABEL_SHAPE = `
      var svg = el.querySelector('pre.mermaid svg');
      return { drawn: svg ? svg.querySelectorAll('style').length >= 1 : false,
               texts: svg ? svg.querySelectorAll('text').length : 0,
               foreignObjects: svg ? svg.querySelectorAll('foreignObject').length : 0,
               scripts: el.querySelectorAll('script').length,
               imgs: el.querySelectorAll('img').length,
               bold: el.querySelectorAll('b').length,
               handlers: ${HANDLER_COUNT}, ran: window.__ran };`;
    const labelIsInert = (r) =>
      r.drawn === true && r.texts > 0 && r.foreignObjects === 0 &&
      r.scripts === 0 && r.imgs === 0 && r.bold === 0 && r.handlers === 0 && r.ran === false;

    const diagramLabel = await render(cdp,
      '```mermaid\ngraph TD;\n  A["<img src=x onerror=\'window.__ran=true\'>"] --> B["plain"];\n```\n',
      LABEL_SHAPE, { until: DIAGRAM_DONE, budget: 25000 });
    check('markup in a diagram label is drawn as text, not as markup',
      labelIsInert(diagramLabel), diagramLabel);

    const escalation = await render(cdp,
      '```mermaid\n%%{init: {"securityLevel": "loose", "flowchart": {"htmlLabels": true}} }%%\n' +
      'graph TD;\n  A["<b>bold</b>"] --> B["plain"];\n```\n',
      LABEL_SHAPE, { until: DIAGRAM_DONE, budget: 25000 });
    check('a diagram cannot turn its own labels back into html',
      labelIsInert(escalation), escalation);

    // ---- the scrub applied to rendered diagram output ----------------------
    console.log('\ndiagram output scrub');
    const scrub = await cdp.eval(`
      var host = document.createElement('div');
      host.innerHTML = '<svg xmlns="http://www.w3.org/2000/svg">' +
        '<script>window.__ran = true;<\\/script>' +
        '<a xlink:href="javascript:1"><circle r="3" onclick="1" onmouseover="1"/></a>' +
        '<foreignObject><span data-keep="1">label</span></foreignObject>' +
        '<use href="#marker-1"/><image href="data:image/gif;base64,AAA"/>' +
        '<text style="fill:red">t</text>' +
        '</svg>';
      document.getElementById('content').appendChild(host);
      window.__previewInternals.scrubRendered(host);
      var el = host;
      var a = host.querySelector('a');
      var use = host.querySelector('use');
      var text = host.querySelector('text');
      var result = {
        scripts: host.querySelectorAll('script').length,
        handlers: ${HANDLER_COUNT},
        jsHref: a ? a.getAttribute('xlink:href') : 'no anchor',
        keptForeignObject: host.querySelector('foreignObject span[data-keep="1"]') !== null,
        keptUse: use && use.getAttribute('href'),
        keptDataImage: host.querySelector('image') !== null,
        keptCircle: host.querySelector('circle') !== null,
        keptStyleAttr: text && text.getAttribute('style')
      };
      host.remove();
      return result;
    `);
    check('the scrub strips scripts, handlers and script URLs from rendered svg',
      scrub.scripts === 0 && scrub.handlers === 0 && scrub.jsHref === null, scrub);
    check('the scrub leaves diagram geometry, labels and references alone',
      scrub.keptForeignObject === true && scrub.keptUse === '#marker-1' &&
      scrub.keptDataImage === true && scrub.keptCircle === true &&
      scrub.keptStyleAttr === 'fill:red', scrub);

    // Two shapes a plain `^javascript:` test does not catch, both reachable only
    // through mermaid's output — DOMPurify normalizes attribute whitespace and
    // drops animation elements itself, so the sanitized path never sees either.
    // The policy is what actually stops them from running; these checks keep the
    // second line from being the layer that quietly does not hold.
    const evasion = await cdp.eval(`
      var host = document.createElement('div');
      host.innerHTML = '<svg xmlns="http://www.w3.org/2000/svg">' +
        '<a id="tabbed" xlink:href="java&#9;script:window.__ran=true"><circle r="3"/></a>' +
        '<a id="animated" xlink:href="#ok">' +
        '<animate attributeName="xlink:href" to="javascript:window.__ran=true"/>' +
        '<set attributeName="xlink:href" to="javascript:window.__ran=true"/></a>' +
        '</svg>';
      document.getElementById('content').appendChild(host);
      window.__previewInternals.scrubRendered(host);
      var result = {
        tabbedHref: host.querySelector('#tabbed').getAttribute('xlink:href'),
        animations: host.querySelectorAll('animate, set, animateTransform, animateMotion').length,
        keptAnchor: host.querySelector('#animated') !== null,
        keptCircle: host.querySelector('circle') !== null
      };
      host.remove();
      return result;
    `);
    check('a control character inside the scheme name does not smuggle it past the scrub',
      evasion.tabbedHref === null, evasion);
    check('an animation element cannot re-point an attribute after the scrub',
      evasion.animations === 0 && evasion.keptAnchor === true && evasion.keptCircle === true, evasion);

    // ---- fail closed ------------------------------------------------------
    console.log('\nfail closed');

    // A DOMPurify that reports itself unsupported returns its input untouched
    // instead of throwing, which is the case a render must refuse rather than
    // sail through. Stand one in whose sanitize is a pure passthrough.
    const unsupported = await cdp.eval(`
      var real = window.DOMPurify;
      window.DOMPurify = {
        version: real.version, isSupported: false,
        sanitize: function (html) {
          var t = document.createElement('template');
          t.innerHTML = html;
          return t.content;
        }
      };
      window.__ran = false;
      try {
        window.__setMarkdownB64(${JSON.stringify(
          Buffer.from('<img src="pixel.png" onerror="window.__ran=true"> **hi**', 'utf8').toString('base64')
        )});
        await new Promise(function (r) { setTimeout(r, 300); });
        var el = document.getElementById('content');
        return { text: el.textContent, imgs: el.querySelectorAll('img').length,
                 strong: el.querySelectorAll('strong').length, ran: window.__ran };
      } finally { window.DOMPurify = real; }
    `);
    check('a sanitizer that reports itself unsupported is refused, not trusted',
      unsupported.imgs === 0 && unsupported.strong === 0 && unsupported.ran === false &&
      unsupported.text.startsWith('Markdown render error'), unsupported);

    const withoutSanitizer = join(fixtureDir, 'preview-no-sanitizer.html');
    const stripped = readFileSync(fixturePage, 'utf8')
      .replace(/[ \t]*<script[^>]*purify\.min\.js"><\/script>\n?/, '');
    if (stripped.includes('purify.min.js')) fatal('could not build the no-sanitizer variant');
    writeFileSync(withoutSanitizer, stripped);
    await cdp.send('Page.navigate', { url: pathToFileURL(withoutSanitizer).href });
    await sleep(1200);
    if (!await awaitPageReady(cdp)) fatal('no-sanitizer page never became ready');
    const failClosed = await cdp.eval(`
      window.__ran = false;
      window.__setMarkdownB64(${JSON.stringify(
        Buffer.from('<img src="pixel.png" onerror="window.__ran=true"> **hi**', 'utf8').toString('base64')
      )});
      await new Promise(function (r) { setTimeout(r, 400); });
      var el = document.getElementById('content');
      return { sanitizer: !!window.DOMPurify, text: el.textContent,
               imgs: el.querySelectorAll('img').length, strong: el.querySelectorAll('strong').length,
               ran: window.__ran };
    `);
    check('without a sanitizer the page reports an error instead of rendering',
      failClosed.sanitizer === false && failClosed.imgs === 0 && failClosed.strong === 0 &&
      failClosed.ran === false && failClosed.text.startsWith('Markdown render error'), failClosed);
    rmSync(withoutSanitizer, { force: true });
  } finally {
    // Wait for the process to actually be gone before deleting its profile. SIGKILL
    // does not wait, so a bare rmSync raced Chromium's last writes and failed
    // ENOTEMPTY on a directory it had just emptied - failing the suite after every
    // assertion had already passed. Waiting removes the race rather than retrying
    // through it, so a genuinely stuck directory still surfaces.
    child.kill('SIGKILL');
    await once(child, 'exit').catch(function () {});
    rmSync(userDataDir, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
  }

  console.log(`\n${checks - failures.length}/${checks} checks passed`);
  if (failures.length) {
    console.error(`FAILED: ${failures.join(', ')}`);
    process.exit(1);
  }
}

run().catch((error) => fatal(error && error.stack ? error.stack : String(error)));

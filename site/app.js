// Nether Client site: always links the newest installer from GitHub Releases (no manual updates),
// renders the latest release notes, and adds small touches (sticky nav, reveal, embers).
(() => {
  'use strict';
  const REPO = 'clapbxt/nether-client';
  const API = `https://api.github.com/repos/${REPO}/releases?per_page=6`;
  const CACHE = 'nc-releases-v1', TTL = 10 * 60 * 1000; // stay well inside GitHub's 60 requests/hour per visitor
  const SETUP = /^NetherClientSetup.*\.exe$/i;
  const $$ = (s) => document.querySelectorAll(s);
  const calm = matchMedia('(prefers-reduced-motion: reduce)').matches;

  // ---------- nav glass on scroll ----------
  const nav = document.querySelector('.nav');
  const onScroll = () => nav.classList.toggle('scrolled', scrollY > 8);
  addEventListener('scroll', onScroll, { passive: true });
  onScroll();

  // ---------- reveal on scroll ----------
  const rv = $$('.rv');
  if ('IntersectionObserver' in window) {
    const io = new IntersectionObserver((es) => es.forEach((e) => {
      if (e.isIntersecting) { e.target.classList.add('in'); io.unobserve(e.target); }
    }), { rootMargin: '0px 0px -8% 0px' });
    rv.forEach((el, i) => { el.style.transitionDelay = calm ? '0s' : `${(i % 4) * 60}ms`; io.observe(el); });
  } else rv.forEach((el) => el.classList.add('in'));

  // ---------- OS note ----------
  const ua = navigator.userAgent;
  if (!/Windows/i.test(ua)) {
    const mobile = /Android|iPhone|iPad|iPod|Mobile/i.test(ua);
    $$('[data-os-note]').forEach((e) => {
      e.textContent = mobile ? 'Nether is for Windows PCs. Open this page on your computer to download it.' : 'Nether runs on Windows 10 and 11 for now.';
      e.hidden = false;
    });
  }

  // ---------- latest release ----------
  const read = () => { try { return JSON.parse(localStorage.getItem(CACHE)); } catch { return null; } };
  const write = (d) => { try { localStorage.setItem(CACHE, JSON.stringify({ t: Date.now(), d })); } catch { /* private mode */ } };

  async function releases() {
    const c = read();
    if (c && Date.now() - c.t < TTL) return c.d;
    try {
      const r = await fetch(API, { headers: { Accept: 'application/vnd.github+json' } });
      if (!r.ok) throw new Error(`GitHub ${r.status}`);
      const d = (await r.json()).filter((x) => !x.draft && !x.prerelease).map((x) => {
        const a = (x.assets || []).find((a) => SETUP.test(a.name));
        return { tag: x.tag_name, date: x.published_at, url: x.html_url, body: x.body || '', asset: a ? { url: a.browser_download_url, size: a.size } : null };
      });
      write(d);
      return d;
    } catch (e) {
      if (c) return c.d; // stale beats nothing
      throw e;
    }
  }

  const mb = (b) => `${Math.round(b / 1048576)} MB`;
  const day = (s) => new Date(s).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' });
  const esc = (s) => s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const inline = (s) => esc(s)
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/`([^`]+)`/g, '<code>$1</code>');

  // Tiny, safe Markdown subset for release notes: headings, bullets, paragraphs, bold, code.
  function md(src) {
    let out = '', list = false;
    for (const raw of src.replace(/\r/g, '').split('\n')) {
      const line = raw.trim();
      const li = line.match(/^[-*]\s+(.*)/);
      if (li) { if (!list) { out += '<ul>'; list = true; } out += `<li>${inline(li[1])}</li>`; continue; }
      if (list) { out += '</ul>'; list = false; }
      if (!line) continue;
      const h = line.match(/^#{1,6}\s+(.*)/);
      out += h ? `<h4>${inline(h[1])}</h4>` : `<p>${inline(line)}</p>`;
    }
    return list ? out + '</ul>' : out;
  }

  function apply(list) {
    const rel = list.find((r) => r.asset);
    if (rel) {
      $$('[data-dl]').forEach((a) => { a.href = rel.asset.url; a.dataset.version = rel.tag; });
      $$('[data-meta]').forEach((e) => { e.textContent = `${rel.tag} · ${mb(rel.asset.size)}`; });
      $$('[data-ver-inline]').forEach((e) => { e.textContent = `(${rel.tag})`; });
    }
    const log = document.querySelector('[data-log]');
    if (!log || !list.length) return;
    log.innerHTML = list.slice(0, 3).map((r, i) => `
      <article class="rel">
        <div class="rel-tag">
          ${i === 0 ? '<span class="new">LATEST</span>' : ''}
          <a href="${esc(r.url)}">${esc(r.tag)}</a>
          <span>${esc(day(r.date))}</span>
        </div>
        <div class="rel-body">${md(r.body) || '<p>Fixes and improvements.</p>'}</div>
      </article>`).join('') +
      `<a class="btn btn-ghost log-all" href="https://github.com/${REPO}/releases">ALL RELEASES</a>`;
  }

  releases().then(apply).catch(() => { /* links already point at the GitHub latest-release page */ });

  // ---------- embers ----------
  const cv = document.querySelector('.embers');
  if (!cv || calm) return;
  const ctx = cv.getContext('2d');
  const cols = ['#F76A05', '#FF9A2E', '#FDCB33', '#E5372D'];
  let w = 0, h = 0, dpr = 1, parts = [], on = true, raf = 0;
  const spawn = (fresh) => ({
    x: Math.random() * w, y: fresh ? Math.random() * h : h + 10,
    s: (Math.random() < .7 ? 2 : 3) * dpr, v: (.25 + Math.random() * .7) * dpr,
    drift: (Math.random() - .5) * .3 * dpr, ph: Math.random() * 6.28,
    life: 0, max: 400 + Math.random() * 500, c: cols[(Math.random() * cols.length) | 0],
  });
  const size = () => {
    dpr = Math.min(devicePixelRatio || 1, 2);
    w = cv.width = cv.clientWidth * dpr; h = cv.height = cv.clientHeight * dpr;
    const n = Math.round(Math.min(70, cv.clientWidth / 22));
    parts = Array.from({ length: n }, () => spawn(true));
  };
  const tick = () => {
    ctx.clearRect(0, 0, w, h);
    for (const p of parts) {
      p.life++; p.y -= p.v; p.ph += .02; p.x += p.drift + Math.sin(p.ph) * .25 * dpr;
      const k = p.life / p.max, a = Math.min(1, k * 6) * (1 - k) * Math.min(1, p.y / (h * .35));
      ctx.globalAlpha = Math.max(0, a) * .9;
      ctx.fillStyle = p.c;
      ctx.fillRect(Math.round(p.x), Math.round(p.y), p.s, p.s);
      if (p.life >= p.max || p.y < -10) Object.assign(p, spawn(false));
    }
    raf = on ? requestAnimationFrame(tick) : 0;
  };
  size();
  addEventListener('resize', size);
  new IntersectionObserver(([e]) => { on = e.isIntersecting; if (on && !raf) raf = requestAnimationFrame(tick); }).observe(cv);
})();

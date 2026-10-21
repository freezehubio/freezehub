#!/usr/bin/env node
/*
 * CloudFront access logs, reported request by request (`FZ-230`, rewritten by `FZ-234`).
 *
 * Reads the decompressed, concatenated log on stdin. Field positions come from the log's own
 * `#Fields:` header, never from memory — assuming them is what once printed user agents
 * under a heading that said "referrers".
 *
 * **This report does not decide who is a person.** The version before it did, and was wrong
 * in both directions: it labelled a known-real visit a system, and labelled a probe a
 * person. What it produced was a verdict with the evidence hidden behind it, and the words
 * it used — "human", "visitor" — were guesses presented as counts.
 *
 * So: this prints what each address asked for and what the edge did about it. It separates
 * traffic that is *provably* automated — by its own admission, or by asking for something
 * that does not exist — from everything else. "Everything else" is not a claim that a
 * person was present. It means nothing in the log rules it out.
 */

/** User agents that declare themselves. */
const DECLARED_TOOL = /bot|crawl|spider|slurp|scan|curl|wget|python-requests|httpclient|go-http|libwww|headless|palo alto|virustotal|facebookexternalhit|monitor|uptime|probe|^java\/|okhttp|apache-httpclient|axios|node-fetch|guzzle|restsharp/i;

/** Paths nothing but an exploit scanner asks for. */
const EXPLOIT = /^\/(wp|wordpress|wp-|xmlrpc|\.env|\.git|vendor\/|public\/vendor|debug\/|_ignition|phpmyadmin|admin\.php|eval-stdin|config\.|backup|shell|cgi-bin)/i;

/*
 * The routes this SPA has, from `frontend/src/app/router.tsx`.
 *
 * CloudFront rewrites 403 and 404 to `200 /index.html` so the SPA can route, which means
 * every probe gets a success and a full page in the log. Enumerating what exists catches the
 * random-string probes that a named-exploit list can never anticipate.
 *
 * A route added to the product and not added here is misread as a probe. That undercounts,
 * which is the safer direction.
 */
const ROUTES = [
  /^\/$/, /^\/signin$/, /^\/signup$/, /^\/dashboard$/, /^\/catalog$/, /^\/settings$/,
  /^\/deployment-checks$/, /^\/audit$/, /^\/notifications$/,
  /^\/restrictions(\/.*)?$/, /^\/legal\/[a-z-]+$/,
];
const isAsset = (p) => /^\/(assets|favicon|robots|sitemap)/.test(p);
const isKnownRoute = (p) => ROUTES.some((re) => re.test(p));

/* What the edge did, in words rather than CloudFront's vocabulary. */
const EDGE_RESULT = {
  Hit: 'served from cache',
  Miss: 'fetched from S3',
  RefreshHit: 'revalidated with S3',
  Error: 'no such file -> index.html',
  Redirect: 'redirected',
  LimitExceeded: 'rate limited',
};

const dec = (v) => {
  if (!v || v === '-') return '';
  try { return decodeURIComponent(v.replace(/\+/g, ' ')); } catch { return v; }
};
const size = (n) => (n >= 1024 ? Math.round(n / 1024) + 'kB' : n + 'B');
const pad = (s, n) => String(s).padEnd(n);
const num = (s, n) => String(s).padStart(n);
const rule = (c = '-', n = 94) => console.log(c.repeat(n));

const read = () => new Promise((resolve) => {
  let s = '';
  process.stdin.setEncoding('utf8');
  process.stdin.on('data', (d) => (s += d));
  process.stdin.on('end', () => resolve(s));
});

function parse(text) {
  let fields = null;
  const rows = [];
  for (const line of text.split('\n')) {
    if (line.startsWith('#Fields:')) {
      fields = line.slice('#Fields:'.length).trim().split(/\s+/);
      continue;
    }
    if (!line || line.startsWith('#') || !fields) continue;
    const parts = line.split('\t');
    if (parts.length < fields.length) continue;
    const r = {};
    fields.forEach((f, i) => (r[f] = parts[i]));
    rows.push({
      date: r['date'],
      time: r['time'],
      at: Date.parse(r['date'] + 'T' + r['time'] + 'Z'),
      edge: r['x-edge-location'] || '',
      ip: r['c-ip'],
      host: r['x-host-header'] || '',
      path: r['cs-uri-stem'] || '',
      query: dec(r['cs-uri-query']),
      status: r['sc-status'],
      result: r['x-edge-result-type'] || '',
      bytes: Number(r['sc-bytes'] || 0),
      took: Number(r['time-taken'] || 0),
      proto: r['cs-protocol-version'] || '',
      type: r['sc-content-type'] || '',
      referer: dec(r['cs(Referer)']),
      ua: dec(r['cs(User-Agent)']),
    });
  }
  return rows;
}

/*
 * Why an address is provably automated. Three rules, each one a fact about the log rather
 * than an inference about behaviour. The absence of a reason is not evidence of a person —
 * a headless browser asking only for real paths leaves no trace here at all.
 */
function provablyAutomated(byIp) {
  const why = new Map();
  for (const [ip, rs] of byIp) {
    for (const r of rs) {
      if (EXPLOIT.test(r.path)) { why.set(ip, 'asked for ' + r.path.slice(0, 44)); break; }
      if (!isAsset(r.path) && !isKnownRoute(r.path)) {
        why.set(ip, 'asked for a path that does not exist: ' + r.path.slice(0, 32)); break;
      }
      if (DECLARED_TOOL.test(r.ua)) {
        why.set(ip, 'user agent declares a tool: ' + (r.ua.split(/[ /]/)[0] || '?').slice(0, 24)); break;
      }
    }
  }
  return why;
}

(async () => {
  const rows = parse(await read());
  if (!rows.length) {
    console.log('No log lines parsed. Either nothing is delivered yet, or the files were empty.');
    process.exit(0);
  }

  const byIp = new Map();
  for (const r of rows) {
    if (!byIp.has(r.ip)) byIp.set(r.ip, []);
    byIp.get(r.ip).push(r);
  }
  const why = provablyAutomated(byIp);
  const rest = [...byIp].filter(([ip]) => !why.has(ip));
  const days = [...new Set(rows.map((r) => r.date))].sort();

  console.log('');
  console.log(`CloudFront access log — ${days[0]} to ${days[days.length - 1]} (UTC)`);
  rule('=');

  console.log(`${pad('DAY', 12)}${num('REQUESTS', 10)}${num('ADDRESSES', 11)}${num('AUTOMATED', 11)}${num('REST', 7)}`);
  rule();
  for (const d of days) {
    const dr = rows.filter((r) => r.date === d);
    const ips = [...new Set(dr.map((r) => r.ip))];
    console.log(
      pad(d, 12) + num(dr.length, 10) + num(ips.length, 11) +
      num(ips.filter((i) => why.has(i)).length, 11) +
      num(ips.filter((i) => !why.has(i)).length, 7),
    );
  }
  rule();
  console.log(pad('total', 12) + num(rows.length, 10) + num(byIp.size, 11) +
              num(why.size, 11) + num(rest.length, 7));
  console.log('');
  console.log('AUTOMATED is proven, by the three rules at the bottom. REST means only that');
  console.log('nothing in the log proves otherwise — not that a person was there. Read the');
  console.log('requests below and judge each one yourself.');

  for (const [ip, rs] of rest.sort((a, b) => b[1].length - a[1].length)) {
    rs.sort((a, b) => a.at - b.at);
    const span = (rs[rs.length - 1].at - rs[0].at) / 1000;
    const pages = rs.filter((r) => !isAsset(r.path));
    const assets = rs.filter((r) => isAsset(r.path));
    const cached = rs.filter((r) => r.status === '304');
    const offsite = rs.map((r) => r.referer).find((x) => x && !x.includes(rs[0].host));

    console.log('');
    rule('=');
    console.log(`${ip}    edge ${rs[0].edge}    ${rs[0].proto}`);
    console.log(`  ${rs[0].ua.replace(/^Mozilla\/5\.0 /, '').slice(0, 88)}`);
    console.log(`  ${rs.length} requests over ${span}s — ${pages.length} page, ${assets.length} asset` +
                (cached.length ? `, ${cached.length} already in the browser cache` : ''));
    if (offsite) console.log(`  arrived from off-site: ${offsite.slice(0, 70)}`);
    console.log('');
    console.log(`  ${pad('TIME', 10)}${pad('CODE', 6)}${pad('WHAT THE EDGE DID', 30)}${num('SIZE', 6)}${num('ms', 6)}  URL`);
    rule();
    for (const r of rs) {
      const url = (r.host ? 'https://' + r.host : '') + r.path + (r.query ? '?' + r.query : '');
      console.log(`  ${pad(r.time, 10)}${pad(r.status, 6)}${pad(EDGE_RESULT[r.result] || r.result, 30)}` +
                  `${num(size(r.bytes), 6)}${num(Math.round(r.took * 1000), 6)}  ${url}`);
      if (r.referer) console.log(`  ${' '.repeat(10)}came from  ${r.referer.slice(0, 76)}`);
    }
  }

  console.log('');
  rule('=');
  console.log(`FILTERED OUT — ${why.size} addresses, ${rows.filter((r) => why.has(r.ip)).length} requests`);
  rule();
  const grouped = new Map();
  for (const [ip, reason] of why) {
    const k = reason.split(':')[0];
    if (!grouped.has(k)) grouped.set(k, []);
    grouped.get(k).push(ip);
  }
  for (const [k, ips] of [...grouped].sort((a, b) => b[1].length - a[1].length)) {
    console.log(`  ${num(ips.length, 3)} addresses — ${k}`);
    console.log(`      ${ips.slice(0, 7).join('  ')}${ips.length > 7 ? '  ...' : ''}`);
  }

  console.log('');
  console.log('URLS REQUESTED — every path in the log, automated traffic included');
  rule();
  const paths = new Map();
  for (const r of rows) {
    if (isAsset(r.path)) continue;
    const u = r.path + (r.query ? '?' + r.query : '');
    if (!paths.has(u)) paths.set(u, new Set());
    paths.get(u).add(r.ip);
  }
  const shown = [...paths].sort((a, b) => b[1].size - a[1].size).slice(0, 18);
  console.log(`  ${num('ADDRS', 6)}  URL`);
  for (const [u, ips] of shown) {
    console.log(`  ${num(ips.size, 6)}  ${u.slice(0, 82)}${isKnownRoute(u.split('?')[0]) ? '' : '   <- not a route this app has'}`);
  }

  console.log('');
  console.log('HOW TO READ THIS');
  rule();
  console.log('CODE    200 served · 304 the browser already had it, so this is a return visit');
  console.log('        301 redirected · 403/404 never appear, see below');
  console.log('');
  console.log('"no such file -> index.html"  S3 holds no object at that path, so CloudFront');
  console.log('        returns the app shell with a 200. Every SPA route looks like this AND so');
  console.log('        does every probe. It is not an error, and it is why a 404 count is');
  console.log('        useless here.');
  console.log('"served from cache"  CloudFront had it. "fetched from S3" means it did not.');
  console.log('');
  console.log('Displaying a page costs one page request plus three assets. A page request with');
  console.log('no assets following it means the HTML was fetched and never rendered.');
  console.log('');
  console.log('HTTP/1.1 under a browser-shaped user agent is worth a second look — browsers');
  console.log('negotiate HTTP/2.');
  console.log('');
  console.log('The three rules that filter an address out, all provable from the log:');
  console.log('  1. it asked for a known exploit path');
  console.log('  2. it asked for a path this app does not have');
  console.log('  3. its user agent says it is a tool');
  console.log('Nothing else is filtered. No guess about datacenters, dwell time or navigation.');
  console.log('');
})();

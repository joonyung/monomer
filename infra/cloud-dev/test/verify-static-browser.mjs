import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile, readdir, mkdir, writeFile } from 'node:fs/promises';
import { resolve, extname } from 'node:path';
import { runInNewContext } from 'node:vm';
import { chromium } from '../../../web-app/node_modules/@playwright/test/index.mjs';
import routing from '../build/src/static-routing.js';

const root = resolve(import.meta.dirname, '../../..');
const output = resolve(root, 'screenshots/static-hosting');
await mkdir(output, {recursive: true});
const assets = await readdir(resolve(root, 'web-app/dist/assets'));
const workers = assets.filter(name => /^chart-widget-worker-[A-Za-z0-9_-]+\.js$/.test(name));
assert.equal(workers.length, 1, 'Build must emit exactly one chart worker with the policy filename');
const workerUri = '/assets/' + workers[0];
assert.ok((await Promise.all(assets.filter(name => name.endsWith('.js') && name !== workers[0]).map(name => readFile(resolve(root, 'web-app/dist/assets', name), 'utf8')))).some(text => text.includes(workerUri)), 'Actual app bundle must reference the policy worker');
const servers = [];
const mime = {'.html':'text/html', '.js':'text/javascript', '.css':'text/css', '.svg':'image/svg+xml', '.md':'text/markdown', '.txt':'text/plain', '.json':'application/json', '.woff2':'font/woff2'};
async function serve(site) {
  const server = createServer(async (req, res) => {
    try {
      const url = new URL(req.url, 'http://localhost');
      let body;
      const chart = site === 'app' && /^\/assets\/chart-widget-worker-[A-Za-z0-9_-]+\.js$/.test(url.pathname);
      if (site === 'app' && (chart || !url.pathname.startsWith('/assets/'))) res.setHeader('Content-Security-Policy', chart ? routing.WORKER_CSP : routing.APP_CSP);
      if (chart) res.setHeader('X-Monomer-Csp-Evidence', 'chart-widget-worker');
      res.setHeader('X-Robots-Tag', 'noindex, nofollow');
      if (url.pathname === '/__hosting_probe' && site === 'app') {
        body = '<!doctype html><html><title>Static hosting worker verification</title><body><canvas></canvas></body></html>';
        res.setHeader('Content-Type', 'text/html');
      } else {
        const host = site === 'app' ? 'app.dev.monomer.cc' : 'dev.monomer.cc';
        const request = {uri:url.pathname, method:'GET', querystring:{}, headers:{host:{value:host}}};
        const routed = runInNewContext(`${routing.routingCode(site, host)}; handler(event)`, {event:{request}});
        if (routed.statusCode) {
          res.writeHead(routed.statusCode, Object.fromEntries(Object.entries(routed.headers).map(([key, item])=>[key,item.value]))); res.end(); return;
        }
        try { body = await readFile(resolve(root, site === 'app' ? 'web-app/dist':'public-site/dist', '.' + routed.uri)); }
        catch { res.statusCode = 404; body = 'Not found'; }
        res.setHeader('Content-Type', res.statusCode === 404 ? 'text/plain' : (mime[extname(routed.uri)] ?? 'application/octet-stream'));
      }
      res.end(body);
    } catch(error) { res.writeHead(500); res.end(String(error)); }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  servers.push(server);
  return `http://127.0.0.1:${server.address().port}`;
}
let browser;
try {
  const app = await serve('app'), publicSite = await serve('public');
  browser = await chromium.launch({headless:true});
  const page = await browser.newPage();
  const response = await page.goto(app + '/__hosting_probe');
  assert.equal(response.headers()['content-security-policy'], routing.APP_CSP);
  const workerResponse = await page.request.get(app + workerUri);
  assert.equal(workerResponse.headers()['content-security-policy'], routing.WORKER_CSP);
  assert.equal(workerResponse.headers()['x-monomer-csp-evidence'], 'chart-widget-worker');
  const result = await page.evaluate(async workerUri => {
    const worker = new Worker(workerUri, {type:'module'});
    const canvas = document.querySelector('canvas').transferControlToOffscreen();
    return await new Promise((resolve, reject) => {
      const timer = setTimeout(()=>{worker.terminate();reject(Error('Worker timed out'));}, 15000);
      worker.onerror = event => { clearTimeout(timer); worker.terminate();reject(Error(event.message)); };
      worker.onmessage = event => {
        if (event.data.type === 'failed') {clearTimeout(timer);worker.terminate();reject(Error(event.data.message));}
        if (event.data.type === 'rendered') {clearTimeout(timer);worker.terminate();resolve(event.data);}
      };
      worker.postMessage({type:'initialize',generation:1,canvas,moduleSource:'export default defineChart({ render() { return { animation:false, xAxis: {type:"category",data:["A"]}, yAxis:{}, series:[{type:"bar", data:[3]}] }; } });',queries:{},parameters:{},variables:{},locale:'en',viewport:{width:320,height:240},density:1},[canvas]);
    });
  }, workerUri);
  assert.equal(result.type, 'rendered');
  for (const uri of ['/auth/callback','/oauth/consent','/workspaces/example']) {
    const r = await page.request.get(app+uri); assert.equal(r.status(),200);assert.ok((await r.text()).includes('type="module"'));
  }
  assert.equal((await page.request.get(app+'/assets/missing.js')).status(),404);
  for (const name of assets.filter(name=>name.endsWith('.js') && name !== workers[0])) {
    const r = await page.request.get(app+'/assets/'+name);
    assert.ok(!(r.headers()['content-security-policy'] ?? '').includes('unsafe-eval'));
    assert.equal(r.headers()['x-monomer-csp-evidence'],undefined);
  }
  const docs = await page.request.get(publicSite+'/en/docs/');assert.equal(docs.status(),200);
  assert.equal(docs.headers()['x-robots-tag'],'noindex, nofollow');
  const md = await page.request.get(publicSite+'/en/analyze/run-sql/index.md');assert.equal(md.status(),200);assert.equal(md.headers()['content-type'],'text/markdown');
  assert.equal((await page.request.get(publicSite+'/missing-page/')).status(),404);
  await page.screenshot({path:resolve(output,'chart-worker.png')});
  await writeFile(resolve(output,'report.json'), JSON.stringify({scope:'Local actual build, CloudFront routing function and CSP browser regression; not AWS edge verification or authenticated OAuth',workerUri,workerResult:result,otherJsAssets:assets.filter(name=>name.endsWith('.js')&&name!==workers[0]).length,passed:true},null,2)+'\n');
  const doc = await browser.newPage();
  for (const [width,height] of [[1440,1000],[390,844]]) {
    await doc.setViewportSize({width,height});
    await doc.goto('file://' + resolve(root,'docs/research/2026-09-11-static-hosting.html'));
    assert.ok(await doc.evaluate(()=>document.documentElement.scrollWidth <= window.innerWidth), 'Design document overflows');
    await doc.screenshot({path:resolve(output,`design-${width}.png`),fullPage:true});
  }
  console.log('PASS: actual chart worker renders under dedicated CSP; other JS excludes unsafe-eval; SPA/public/MIME/missing paths verified locally');
} finally {
  await browser?.close();
  for (const server of servers) await new Promise(resolve=>server.close(resolve));
}

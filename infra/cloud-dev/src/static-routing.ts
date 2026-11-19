export const APP_CSP = "default-src 'self'; script-src 'self'; worker-src 'self'; connect-src 'self' http: https: ws: wss:; img-src 'self' data: blob:; font-src 'self' data:; style-src 'self' 'unsafe-inline'; object-src 'none'; frame-src 'none'; base-uri 'self'";
export const WORKER_CSP = "default-src 'none'; script-src 'self' 'unsafe-eval'; connect-src 'none'; worker-src 'none'; img-src 'none'; font-src 'none'; media-src 'none'; object-src 'none'";

export function routingCode(site: 'app' | 'public', host: string): string {
  return `function handler(event) {
    var request = event.request;
    var headers = {'content-type': {value: 'text/plain; charset=utf-8'}, 'cache-control': {value: 'no-store'}, 'x-robots-tag': {value: 'noindex, nofollow'}};
    function missing() { return {statusCode: 404, statusDescription: 'Not Found', headers: headers}; }
    if (!request.headers.host || request.headers.host.value !== '${host}') return missing();
    var uri = request.uri;
    if (/%(?:2f|5c|2e|00|25)/i.test(uri) || /[\\\\\\u0000-\\u0020]/.test(uri) || uri.indexOf('//') !== -1 || /(?:^|\\/)\\.\\.?(?:\\/|$)/.test(uri)) return missing();
    if (/^\\/(?:internal|actuator)(?:\\/|$)/.test(uri)) return missing();
    ${site === 'app' ? `
    if (/^\\/(?:v2|oauth2|login\\/oauth2|\\.well-known|monitor|mcp)(?:\\/|$)/.test(uri)) return missing();
    if (/^\\/(?:auth|oauth)(?:\\/|$)/.test(uri) && uri !== '/auth/callback' && uri !== '/oauth/consent') return missing();
    if (uri === '/assets' || uri === '/assets/') return missing();
    if (uri.indexOf('/assets/') === 0) return request;
    if (uri === '/' || !/\\.[^/]+$/.test(uri)) request.uri = '/index.html';
    ` : `
    if (uri === '/_astro' || uri === '/_astro/') return missing();
    if (uri.indexOf('/_astro/') === 0) return request;
    var redirectUri = uri === '/en' || uri === '/en/' || uri === '/en/index.html' ? '/'
      : (!uri.endsWith('/') && !/\\.[^/]+$/.test(uri) ? uri + '/' : null);
    if (redirectUri) {
      var query = [];
      for (var key in request.querystring) {
        var item = request.querystring[key];
        var values = item.multiValue || [item];
        for (var i = 0; i < values.length; i++) query.push(key + '=' + values[i].value);
      }
      headers.location = {value: redirectUri + (query.length ? '?' + query.join('&') : '')};
      return {statusCode: 301, statusDescription: 'Moved Permanently', headers: headers};
    }
    if (uri.endsWith('/')) request.uri += 'index.html';
    `}
    return request;
  }`;
}

export function pageHtmlRoutingCode(host: string): string {
  return `function handler(event) {
    var request = event.request;
    function missing() { return {statusCode: 404, statusDescription: 'Not Found', headers: {
      'content-type': {value: 'text/plain; charset=utf-8'}, 'cache-control': {value: 'no-store'},
      'x-content-type-options': {value: 'nosniff'}, 'x-robots-tag': {value: 'noindex, nofollow'}
    }}; }
    if (!request.headers.host || request.headers.host.value !== '${host}') return missing();
    if (request.method !== 'GET' && request.method !== 'HEAD') return missing();
    var match = /^\\/workspaces\\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\/pages\\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/.exec(request.uri);
    if (!match) return missing();
    request.uri = '/v2/public/workspaces/' + match[1] + '/pages/' + match[2] + '/html';
    request.querystring = {};
    delete request.headers.authorization;
    delete request.headers.cookie;
    return request;
  }`;
}

/**
 * Mehitha Gmail link: lets the Mehitha app search and read your Gmail, read-only.
 * It runs in your own Google account; nothing else can use it without the key.
 *
 * Setup (once):
 * 1. script.google.com → New project. Paste this file into Code.gs.
 * 2. Project Settings (gear) → tick "Show appsscript.json manifest file", then paste appsscript.json
 *    (it turns on the Gmail service, read-only).
 * 3. Run setup (choose it at the top, then Run) and allow access. Copy the key from the log.
 * 4. Deploy → New deployment → type Web app, Execute as: Me, Who has access: Anyone → Deploy.
 *    Copy the Web app URL.
 * 5. In Mehitha: Settings → Phone data → Gmail → paste the URL and the key → Link.
 */

function setup() {
  const props = PropertiesService.getScriptProperties();
  let key = props.getProperty('KEY');
  if (!key) {
    key = (Utilities.getUuid() + Utilities.getUuid()).replace(/-/g, '');
    props.setProperty('KEY', key);
  }
  Gmail.Users.getProfile('me'); // asks for the read-only Gmail permission
  Logger.log('Mehitha key: ' + key);
}

/** Makes a new key; the old one stops working. Enter the new one in Mehitha. */
function newKey() {
  PropertiesService.getScriptProperties().deleteProperty('KEY');
  setup();
}

function doGet(e) {
  const p = (e && e.parameter) || {};
  const key = PropertiesService.getScriptProperties().getProperty('KEY');
  if (!key || p.key !== key) return json_({ error: 'wrong key' });
  try {
    if (p.action === 'ping') return json_({ ok: true, email: Gmail.Users.getProfile('me').emailAddress });
    if (p.action === 'search') return json_({ messages: search_(p.q || '', Math.min(Number(p.max) || 10, 25)) });
    if (p.action === 'read') return json_(read_(p.id || ''));
    return json_({ error: 'unknown action' });
  } catch (err) {
    return json_({ error: String((err && err.message) || err) });
  }
}

function search_(q, max) {
  const list = Gmail.Users.Messages.list('me', { q: q, maxResults: max }).messages || [];
  return list.map(function (m) {
    const msg = Gmail.Users.Messages.get('me', m.id, { format: 'metadata', metadataHeaders: ['From', 'Subject', 'Date'] });
    const h = headers_(msg);
    return { id: m.id, from: h.from || '', subject: h.subject || '', date: h.date || '', snippet: msg.snippet || '' };
  });
}

function read_(id) {
  const msg = Gmail.Users.Messages.get('me', id, { format: 'full' });
  const h = headers_(msg);
  const texts = [], htmls = [], files = [];
  walk_(msg.payload, texts, htmls, files);
  let body = texts.join('\n');
  if (!body.trim()) {
    body = htmls.join('\n')
      .replace(/<style[\s\S]*?<\/style>/gi, ' ').replace(/<script[\s\S]*?<\/script>/gi, ' ')
      .replace(/<br\s*\/?>|<\/p>|<\/tr>|<\/div>|<\/li>|<\/h\d>/gi, '\n').replace(/<\/td>/gi, ' | ')
      .replace(/<[^>]+>/g, ' ')
      .replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/&#39;/g, "'").replace(/&quot;/g, '"')
      .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&#8377;|&#x20b9;/gi, '₹');
  }
  body = body.replace(/[ \t ]+/g, ' ').replace(/\n\s*\n+/g, '\n').trim().slice(0, 15000);
  return { id: id, from: h.from || '', subject: h.subject || '', date: h.date || '', body: body, attachments: files };
}

function walk_(part, texts, htmls, files) {
  if (!part) return;
  if (part.filename) files.push(part.filename);
  else if (part.mimeType === 'text/plain' && part.body && part.body.data) texts.push(decode_(part.body.data));
  else if (part.mimeType === 'text/html' && part.body && part.body.data) htmls.push(decode_(part.body.data));
  (part.parts || []).forEach(function (p) { walk_(p, texts, htmls, files); });
}

function decode_(data) {
  // The Gmail service gives the body as bytes; the REST API as web-safe base64.
  if (Array.isArray(data)) return Utilities.newBlob(data).getDataAsString('UTF-8');
  data = String(data);
  while (data.length % 4) data += '=';
  return Utilities.newBlob(Utilities.base64DecodeWebSafe(data)).getDataAsString('UTF-8');
}

function headers_(msg) {
  const h = {};
  ((msg.payload || {}).headers || []).forEach(function (x) { h[x.name.toLowerCase()] = x.value; });
  return h;
}

function json_(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}

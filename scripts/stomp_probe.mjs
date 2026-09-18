// 라이브러리 없이 원시 STOMP 프레임으로 CONNECT → SUBSCRIBE → SEND → MESSAGE 왕복을 확인한다.
//   node scripts/stomp_probe.mjs <token> <roomId> [content]
//   node scripts/stomp_probe.mjs <token> <roomId> --expect-error <CODE>   ERROR 프레임을 기대
// 종료 코드: 0 성공, 1 기대와 다른 응답, 2 타임아웃
const [token, roomId, ...rest] = process.argv.slice(2);
const expectErrorIdx = rest.indexOf('--expect-error');
const expectError = expectErrorIdx >= 0 ? rest[expectErrorIdx + 1] : null;
const content = expectErrorIdx >= 0 ? rest.slice(0, expectErrorIdx).join(' ') : rest.join(' ') || 'hello from probe';
const url = process.env.CHAT_WS_URL ?? 'ws://localhost:8092/ws';

const frame = (cmd, headers, body = '') =>
  cmd + '\n' + Object.entries(headers).map(([k, v]) => `${k}:${v}`).join('\n') + '\n\n' + body + '\0';
const parse = (text) => {
  const [head, body = ''] = text.split('\n\n');
  const [cmd, ...headerLines] = head.split('\n');
  const headers = Object.fromEntries(headerLines.map((l) => l.split(/:(.*)/s).slice(0, 2)));
  return { cmd, headers, body: body.replace(/\0$/, '') };
};

const ws = new WebSocket(url);
const timer = setTimeout(() => { console.log('TIMEOUT'); process.exit(2); }, 8000);
const done = (code) => { clearTimeout(timer); try { ws.close(); } catch { /* ignore */ } process.exit(code); };

ws.onopen = () => {
  const headers = { 'accept-version': '1.2' };
  if (token) headers.Authorization = 'Bearer ' + token;
  ws.send(frame('CONNECT', headers));
};
ws.onmessage = (ev) => {
  const { cmd, headers, body } = parse(String(ev.data));
  if (cmd === 'CONNECTED') {
    console.log('CONNECTED heart-beat=' + headers['heart-beat']);
    ws.send(frame('SUBSCRIBE', { id: 'sub-0', destination: '/topic/rooms/' + roomId }));
    ws.send(frame('SUBSCRIBE', { id: 'sub-1', destination: '/user/queue/errors' }));
    setTimeout(() => ws.send(frame('SEND',
      { destination: '/app/rooms/' + roomId + '/messages', 'content-type': 'application/json' },
      JSON.stringify({ content }))), 400);
  } else if (cmd === 'MESSAGE') {
    console.log('MESSAGE ' + headers.destination + ' ' + body);
    const isUserError = headers.destination === '/user/queue/errors';
    if (isUserError && expectError) done(body.includes(`"code":"${expectError}"`) ? 0 : 1);
    if (!expectError && body.includes('"type":"TALK"')) done(0);
  } else if (cmd === 'ERROR') {
    console.log('ERROR code=' + headers.code + ' message=' + headers.message);
    done(expectError && headers.code === expectError ? 0 : 1);
  }
};
ws.onerror = () => { console.log('WS error'); done(1); };

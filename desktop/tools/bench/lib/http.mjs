// HTTPS requests to the OpenAI API with per-phase timings.
//
// `cold` uses a fresh agent (new DNS + TCP + TLS per request, like
// tauri-plugin-http today, which builds a new reqwest client per fetch).
// `warm` reuses one keep-alive agent that callers can pre-connect.
import https from 'node:https';
import { performance } from 'node:perf_hooks';

const HOST = 'api.openai.com';

export const warmAgent = new https.Agent({ keepAlive: true, maxSockets: 4 });

export function freshAgent() {
	return new https.Agent({ keepAlive: false });
}

/** Builds a multipart/form-data body. Fields: [name, value] or [name, {filename, type, data}]. */
export function multipart(fields) {
	const boundary = `----vfbench${Math.random().toString(16).slice(2)}`;
	const chunks = [];
	for (const [name, value] of fields) {
		if (value && typeof value === 'object' && 'data' in value) {
			chunks.push(
				Buffer.from(
					`--${boundary}\r\nContent-Disposition: form-data; name="${name}"; filename="${value.filename}"\r\nContent-Type: ${value.type}\r\n\r\n`,
				),
				value.data,
				Buffer.from('\r\n'),
			);
		} else {
			chunks.push(
				Buffer.from(
					`--${boundary}\r\nContent-Disposition: form-data; name="${name}"\r\n\r\n${value}\r\n`,
				),
			);
		}
	}
	chunks.push(Buffer.from(`--${boundary}--\r\n`));
	return { body: Buffer.concat(chunks), contentType: `multipart/form-data; boundary=${boundary}` };
}

/**
 * One request. Returns {status, json, text, t} where t holds milliseconds from
 * the start: dns, connect, tls (null when a socket was reused), sent (body
 * handed to the OS), ttfb (response headers), total.
 */
export function request({ method = 'POST', path, key, body, contentType, agent }) {
	return new Promise((resolve) => {
		const t0 = performance.now();
		const t = { dns: null, connect: null, tls: null, sent: null, ttfb: null, total: null, reused: false };
		const req = https.request(
			{
				host: HOST,
				path,
				method,
				agent,
				headers: {
					Authorization: `Bearer ${key}`,
					...(contentType ? { 'Content-Type': contentType } : {}),
					...(body ? { 'Content-Length': body.length } : {}),
				},
			},
			(res) => {
				t.ttfb = performance.now() - t0;
				const parts = [];
				res.on('data', (d) => parts.push(d));
				res.on('end', () => {
					t.total = performance.now() - t0;
					const text = Buffer.concat(parts).toString('utf8');
					let json = null;
					try {
						json = JSON.parse(text);
					} catch {}
					resolve({ status: res.statusCode, json, text, t });
				});
			},
		);
		req.on('socket', (socket) => {
			if (!socket.connecting) {
				t.reused = true;
				return;
			}
			socket.once('lookup', () => (t.dns = performance.now() - t0));
			socket.once('connect', () => (t.connect = performance.now() - t0));
			socket.once('secureConnect', () => (t.tls = performance.now() - t0));
		});
		req.on('finish', () => (t.sent = performance.now() - t0));
		req.on('error', (e) => {
			t.total = performance.now() - t0;
			resolve({ status: -1, json: null, text: e.message, t });
		});
		if (body) req.write(body);
		req.end();
	});
}

/** Opens (or refreshes) a keep-alive connection: the "pre-connect on hotkey" idea. */
export async function preconnect(key, agent = warmAgent) {
	return request({ method: 'GET', path: '/v1/models/gpt-5.4-mini', key, agent });
}

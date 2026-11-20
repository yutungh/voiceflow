/**
 * VoiceFlow (speed, PLAN-v2 addendum): OpenAI requests go over one pooled
 * connection kept by the Rust side (`src-tauri/src/openai_http.rs`), warmed
 * at the hotkey, instead of `tauri-plugin-http`, which opens a new
 * connection for every request and sends bodies as JSON number arrays.
 *
 * Only the default OpenAI endpoint uses the pool; a custom base URL and the
 * web build keep their previous fetch.
 */
import { invoke } from '@tauri-apps/api/core';
import { fetch as tauriFetch } from '@tauri-apps/plugin-http';

export const OPENAI_API_ORIGIN = 'https://api.openai.com';

/** The fetch shape the OpenAI SDK accepts. */
export type FetchLike = (input: string | URL | Request, init?: RequestInit) => Promise<Response>;

/** True for the default OpenAI endpoint (unset, or on api.openai.com). */
export function usesOpenAIOrigin(baseURL: string | null | undefined): boolean {
	if (!baseURL) return true;
	try {
		return new URL(baseURL).origin === OPENAI_API_ORIGIN;
	} catch {
		return false;
	}
}

/** Statuses whose responses carry no body (the Response constructor refuses one). */
const NULL_BODY_STATUS = new Set([101, 103, 204, 205, 304]);

/** Unpacks `openai_fetch`'s reply: u16 status, u32 header-JSON length, headers, body. */
export function responseFromFrame(buffer: ArrayBuffer): Response {
	if (buffer.byteLength < 6) throw new TypeError('Empty reply from the network layer');
	const view = new DataView(buffer);
	const status = view.getUint16(0, true);
	const headerLength = view.getUint32(2, true);
	if (6 + headerLength > buffer.byteLength) {
		throw new TypeError('Truncated reply from the network layer');
	}
	const headerJson = new TextDecoder().decode(new Uint8Array(buffer, 6, headerLength));
	const pairs = JSON.parse(headerJson) as [string, string][];
	const headers = new Headers();
	for (const [name, value] of pairs) {
		// The body is already complete and decoded.
		if (/^(content-length|content-encoding|transfer-encoding)$/i.test(name)) continue;
		headers.append(name, value);
	}
	const body = NULL_BODY_STATUS.has(status)
		? null
		: new Uint8Array(buffer, 6 + headerLength);
	return new Response(body, { status, headers });
}

function abortError(signal: AbortSignal): unknown {
	return signal.reason ?? new DOMException('The operation was aborted.', 'AbortError');
}

/**
 * A `fetch` for the OpenAI SDK that sends the request over the pooled Rust
 * connection. Network failures reject with a TypeError, as fetch does, so
 * the SDK's connection-error handling and retries are unchanged.
 */
export const pooledOpenAIFetch: FetchLike = async (input, init) => {
	const request = new Request(input, init);
	if (new URL(request.url).origin !== OPENAI_API_ORIGIN) {
		return tauriFetch(input, init);
	}
	const signal = request.signal;
	if (signal.aborted) throw abortError(signal);
	const body =
		request.method === 'GET' || request.method === 'HEAD'
			? new Uint8Array()
			: new Uint8Array(await request.arrayBuffer());
	const call = invoke<ArrayBuffer>('openai_fetch', body, {
		headers: {
			'x-vf-url': request.url,
			'x-vf-method': request.method,
			'x-vf-headers': JSON.stringify([...request.headers.entries()]),
		},
	});
	const reply = await new Promise<ArrayBuffer>((resolve, reject) => {
		const onAbort = () => reject(abortError(signal));
		signal.addEventListener('abort', onAbort, { once: true });
		call.then(
			(value) => {
				signal.removeEventListener('abort', onAbort);
				resolve(value);
			},
			(error) => {
				signal.removeEventListener('abort', onAbort);
				reject(new TypeError(`Network request failed: ${String(error)}`));
			},
		);
	});
	return responseFromFrame(reply);
};

/**
 * The fetch the OpenAI SDK should use: the pooled connection on desktop for
 * the default endpoint, `tauri-plugin-http` for a custom desktop endpoint,
 * and the browser's own fetch on the web.
 */
export function openAIFetchFor(
	baseURL: string | null | undefined,
): FetchLike | undefined {
	if (!window.__TAURI_INTERNALS__) return undefined;
	return usesOpenAIOrigin(baseURL) ? pooledOpenAIFetch : tauriFetch;
}

let lastWarm = 0;

/**
 * Opens (or keeps open) the pooled connection while the person is talking.
 * Sends no key and no audio. Fire and forget; at most once every 2 s.
 */
export function warmOpenAIConnection(now = Date.now()): boolean {
	if (!window.__TAURI_INTERNALS__) return false;
	if (now - lastWarm < 2000) return false;
	lastWarm = now;
	void invoke<boolean>('openai_warm').catch(() => {});
	return true;
}

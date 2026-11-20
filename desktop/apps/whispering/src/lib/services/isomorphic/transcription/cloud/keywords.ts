/**
 * VoiceFlow 0.9.11: the vocabulary words sent as `keywords[]` with an OpenAI
 * transcription request, as the Android app sends them. Pure, so it is unit
 * tested with bun.
 */

/**
 * Models that take `keywords[]`. OpenAI documents the parameter for
 * gpt-transcribe; the Android app uses the same test.
 */
export function supportsKeywords(model: string): boolean {
	const lower = model.trim().toLowerCase();
	return lower === 'gpt-transcribe' || lower.startsWith('gpt-transcribe-');
}

/**
 * The `keywords[]` for a request: the words of the vocabulary prompt
 * (`buildPrompt(...).sent`, the same merged list), each once, without hints.
 * OpenAI's reference documents no limit on the count (checked 2026-11-19);
 * the prompt budget already bounds the list.
 */
export function transcriptionKeywords(model: string, words: readonly string[]): string[] {
	if (!supportsKeywords(model)) return [];
	const seen = new Set<string>();
	const out: string[] = [];
	for (const raw of words) {
		const word = raw.trim();
		const key = word.toLowerCase();
		if (!word || seen.has(key) || /[<>\r\n]/.test(word)) continue;
		seen.add(key);
		out.push(word);
	}
	return out;
}

/** The request field, or nothing when there are no keywords. */
export function keywordsParam(model: string, words: readonly string[]): { keywords?: string[] } {
	const keywords = transcriptionKeywords(model, words);
	return keywords.length > 0 ? { keywords } : {};
}

/**
 * VoiceFlow 0.9.15: the language field for a request. gpt-transcribe models
 * take `languages[]`, as OpenAI documents for them (the SDK sends an array as
 * `languages[]`); other models keep the singular `language`. Never both:
 * gpt-transcribe refuses a request with the two together. Nothing for Auto.
 */
export function languageParam(
	model: string,
	outputLanguage: string,
): { language?: string; languages?: string[] } {
	const code = outputLanguage.trim();
	if (!code || code === 'auto') return {};
	return supportsKeywords(model) ? { languages: [code] } : { language: code };
}

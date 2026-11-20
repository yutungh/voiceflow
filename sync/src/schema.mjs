// A small JSON Schema (2020-12) validator for the subset the pack schemas use.
// It fails closed: a schema that uses a keyword it does not implement is refused
// when it is compiled, so a later schema change can never be silently under-checked.

const ANNOTATIONS = new Set(["$schema", "$id", "$comment", "title", "description", "default", "examples"]);
const KEYWORDS = new Set([
	"type", "const", "enum", "required", "properties", "items",
	"minItems", "maxItems", "minLength", "maxLength", "pattern",
	"minimum", "maximum", "allOf", "anyOf", "oneOf", "if", "then", "else",
	"additionalProperties",
]);

function typeOf(v) {
	if (v === null) return "null";
	if (Array.isArray(v)) return "array";
	if (typeof v === "number") return Number.isInteger(v) ? "integer" : "number";
	return typeof v; // string, boolean, object
}

function typeMatches(v, t) {
	const actual = typeOf(v);
	if (t === "number") return actual === "number" || actual === "integer";
	return actual === t;
}

function deepEqual(a, b) {
	if (a === b) return true;
	if (typeOf(a) !== typeOf(b)) return false;
	if (Array.isArray(a)) return a.length === b.length && a.every((x, i) => deepEqual(x, b[i]));
	if (a && typeof a === "object") {
		const ka = Object.keys(a), kb = Object.keys(b);
		return ka.length === kb.length && ka.every((k) => Object.hasOwn(b, k) && deepEqual(a[k], b[k]));
	}
	return false;
}

const codePoints = (s) => [...s].length;

/** Compile a schema object into a validator `(value) => string[]` (empty when valid). */
export function compileSchema(schema) {
	const check = compileNode(schema, "#");
	return (value) => {
		const errors = [];
		check(value, "$", errors);
		return errors;
	};
}

function compileNode(node, at) {
	if (node === true) return () => {};
	if (node === false) return (_v, p, e) => e.push(`${p}: not allowed`);
	if (!node || typeof node !== "object" || Array.isArray(node)) throw new Error(`schema ${at}: not an object`);
	for (const k of Object.keys(node)) {
		if (!KEYWORDS.has(k) && !ANNOTATIONS.has(k)) throw new Error(`schema ${at}: unsupported keyword "${k}"`);
	}
	const checks = [];
	if ("type" in node) {
		const types = Array.isArray(node.type) ? node.type : [node.type];
		checks.push((v, p, e) => { if (!types.some((t) => typeMatches(v, t))) e.push(`${p}: expected ${types.join(" or ")}`); });
	}
	if ("const" in node) checks.push((v, p, e) => { if (!deepEqual(v, node.const)) e.push(`${p}: must be ${JSON.stringify(node.const)}`); });
	if ("enum" in node) checks.push((v, p, e) => { if (!node.enum.some((x) => deepEqual(v, x))) e.push(`${p}: not an allowed value`); });
	if ("pattern" in node) {
		const re = new RegExp(node.pattern, "u");
		checks.push((v, p, e) => { if (typeof v === "string" && !re.test(v)) e.push(`${p}: does not match the pattern`); });
	}
	if ("minLength" in node) checks.push((v, p, e) => { if (typeof v === "string" && codePoints(v) < node.minLength) e.push(`${p}: too short`); });
	if ("maxLength" in node) checks.push((v, p, e) => { if (typeof v === "string" && codePoints(v) > node.maxLength) e.push(`${p}: too long`); });
	if ("minimum" in node) checks.push((v, p, e) => { if (typeof v === "number" && v < node.minimum) e.push(`${p}: below the minimum`); });
	if ("maximum" in node) checks.push((v, p, e) => { if (typeof v === "number" && v > node.maximum) e.push(`${p}: above the maximum`); });
	if ("minItems" in node) checks.push((v, p, e) => { if (Array.isArray(v) && v.length < node.minItems) e.push(`${p}: too few items`); });
	if ("maxItems" in node) checks.push((v, p, e) => { if (Array.isArray(v) && v.length > node.maxItems) e.push(`${p}: too many items`); });
	if ("required" in node) {
		checks.push((v, p, e) => {
			if (typeOf(v) !== "object") return;
			for (const r of node.required) if (!Object.hasOwn(v, r)) e.push(`${p}: missing "${r}"`);
		});
	}
	if ("properties" in node) {
		const props = Object.fromEntries(Object.entries(node.properties).map(([k, s]) => [k, compileNode(s, `${at}/properties/${k}`)]));
		checks.push((v, p, e) => {
			if (typeOf(v) !== "object") return;
			for (const [k, c] of Object.entries(props)) if (Object.hasOwn(v, k)) c(v[k], `${p}.${k}`, e);
		});
	}
	if ("additionalProperties" in node) {
		const known = new Set(Object.keys(node.properties || {}));
		const extra = compileNode(node.additionalProperties, `${at}/additionalProperties`);
		checks.push((v, p, e) => {
			if (typeOf(v) !== "object") return;
			for (const k of Object.keys(v)) if (!known.has(k)) extra(v[k], `${p}.${k}`, e);
		});
	}
	if ("items" in node) {
		const item = compileNode(node.items, `${at}/items`);
		checks.push((v, p, e) => { if (Array.isArray(v)) v.forEach((x, i) => item(x, `${p}[${i}]`, e)); });
	}
	if ("allOf" in node) {
		const subs = node.allOf.map((s, i) => compileNode(s, `${at}/allOf/${i}`));
		checks.push((v, p, e) => subs.forEach((s) => s(v, p, e)));
	}
	if ("anyOf" in node) {
		const subs = node.anyOf.map((s, i) => compileNode(s, `${at}/anyOf/${i}`));
		checks.push((v, p, e) => { if (!subs.some((s) => passes(s, v))) e.push(`${p}: matches none of the allowed shapes`); });
	}
	if ("oneOf" in node) {
		const subs = node.oneOf.map((s, i) => compileNode(s, `${at}/oneOf/${i}`));
		checks.push((v, p, e) => { if (subs.filter((s) => passes(s, v)).length !== 1) e.push(`${p}: must match exactly one shape`); });
	}
	if ("if" in node) {
		const cond = compileNode(node.if, `${at}/if`);
		const then = "then" in node ? compileNode(node.then, `${at}/then`) : null;
		const otherwise = "else" in node ? compileNode(node.else, `${at}/else`) : null;
		checks.push((v, p, e) => {
			if (passes(cond, v)) { if (then) then(v, p, e); } else if (otherwise) otherwise(v, p, e);
		});
	} else if ("then" in node || "else" in node) {
		throw new Error(`schema ${at}: "then"/"else" without "if"`);
	}
	return (v, p, e) => { for (const c of checks) c(v, p, e); };
}

function passes(check, v) {
	const e = [];
	check(v, "$", e);
	return e.length === 0;
}

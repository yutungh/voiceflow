// Loads an OpenAI API key into memory for the benchmarks.
//
// Order: OPENAI_API_KEY, then (Windows only) a generic credential in the
// Windows Credential Manager named by --cred <target>. The key is never
// printed, logged or written anywhere; callers get it as a string.
import { spawnSync } from 'node:child_process';

const READ_CREDENTIAL_PS = String.raw`
$ErrorActionPreference = 'Stop'
Add-Type -Namespace VfBench -Name Cred -MemberDefinition @'
[System.Runtime.InteropServices.DllImport("advapi32.dll", CharSet = System.Runtime.InteropServices.CharSet.Unicode, SetLastError = true)]
public static extern bool CredRead(string target, int type, int flags, out System.IntPtr credential);
[System.Runtime.InteropServices.DllImport("advapi32.dll")]
public static extern void CredFree(System.IntPtr credential);
'@
$p = [System.IntPtr]::Zero
if (-not [VfBench.Cred]::CredRead($env:VF_BENCH_CRED_TARGET, 1, 0, [ref]$p)) { exit 3 }
try {
  # CREDENTIALW: Flags(4) Type(4) TargetName(ptr) Comment(ptr) LastWritten(8) BlobSize(4) [pad to ptr] Blob(ptr)
  $ptr = [System.IntPtr]::Size
  $sizeOffset = 8 + 2 * $ptr + 8
  $size = [System.Runtime.InteropServices.Marshal]::ReadInt32($p, $sizeOffset)
  $blobOffset = $sizeOffset + $ptr
  $blob = [System.Runtime.InteropServices.Marshal]::ReadIntPtr($p, $blobOffset)
  $bytes = New-Object byte[] $size
  [System.Runtime.InteropServices.Marshal]::Copy($blob, $bytes, 0, $size)
  [Console]::Out.Write([Convert]::ToBase64String($bytes))
} finally { [VfBench.Cred]::CredFree($p) }
`;

function decodeBlob(bytes) {
	// keyring stores UTF-16LE on Windows; accept UTF-8 too.
	const looksUtf16 =
		bytes.length >= 2 &&
		bytes.length % 2 === 0 &&
		bytes.every((b, i) => i % 2 === 0 || b === 0);
	return (looksUtf16 ? bytes.toString('utf16le') : bytes.toString('utf8')).trim();
}

export function loadKey(credTarget) {
	if (process.env.OPENAI_API_KEY) return process.env.OPENAI_API_KEY.trim();
	if (!credTarget) {
		throw new Error('Set OPENAI_API_KEY or pass --cred <credential target>.');
	}
	if (process.platform !== 'win32') {
		throw new Error('--cred works on Windows only; set OPENAI_API_KEY.');
	}
	const result = spawnSync(
		'powershell.exe',
		['-NoProfile', '-NonInteractive', '-Command', READ_CREDENTIAL_PS],
		{
			env: { ...process.env, VF_BENCH_CRED_TARGET: credTarget },
			windowsHide: true,
			encoding: 'utf8',
		},
	);
	if (result.status !== 0) {
		throw new Error(`Could not read credential (exit ${result.status}).`);
	}
	const key = decodeBlob(Buffer.from(result.stdout.trim(), 'base64'));
	if (!key.startsWith('sk-')) throw new Error('Credential is not an OpenAI key.');
	return key;
}

/** Removes the key (and anything key-shaped) from a string before it is shown. */
export function redact(text, key) {
	let s = String(text).replace(/sk-[A-Za-z0-9_\-*.]{8,}/g, '[REDACTED]');
	if (key) s = s.split(key).join('[REDACTED]');
	return s;
}

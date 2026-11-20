/**
 * VoiceFlow: the apps VoiceFlow never records in or pastes into (spec §4.3,
 * Tier B), and the browsers whose window titles are checked against the
 * sensitive-page pattern. Read-only in M1; editing comes with the vocabulary
 * pack (M2).
 */

export type BlockedApp = {
	/** What the user sees. */
	name: string;
	/** Windows: lower-case executable file names. */
	exe: string[];
	/** macOS: bundle identifiers (compared case-insensitively). */
	bundleIds: string[];
};

export const BLOCKED_APPS: readonly BlockedApp[] = [
	// Password managers
	{
		name: '1Password',
		exe: ['1password.exe', '1password'],
		bundleIds: ['com.1password.1password', 'com.agilebits.onepassword7'],
	},
	{
		name: 'Bitwarden',
		exe: ['bitwarden.exe', 'bitwarden'],
		bundleIds: ['com.bitwarden.desktop'],
	},
	{ name: 'KeePass', exe: ['keepass.exe'], bundleIds: [] },
	{
		name: 'KeePassXC',
		exe: ['keepassxc.exe', 'keepassxc'],
		bundleIds: ['org.keepassxc.keepassxc'],
	},
	{
		name: 'LastPass',
		exe: ['lastpass.exe', 'lastpass'],
		bundleIds: ['com.lastpass.lastpass', 'com.lastpass.lastpassmacdesktop'],
	},
	{
		name: 'Dashlane',
		exe: ['dashlane.exe', 'dashlane'],
		bundleIds: ['com.dashlane.dashlane', 'com.dashlane.dashlanephonefinal'],
	},
	{
		name: 'Keeper',
		exe: [
			'keeper password manager.exe',
			'keeperpasswordmanager.exe',
			'keeper.exe',
			'keeper password manager',
		],
		bundleIds: ['com.callpod.keepermac', 'com.keepersecurity.passwordmanager'],
	},
	{
		name: 'Proton Pass',
		exe: ['proton pass.exe', 'protonpass.exe', 'proton pass'],
		bundleIds: ['me.proton.pass.electron'],
	},
	{
		name: 'Enpass',
		exe: ['enpass.exe', 'enpass'],
		bundleIds: ['in.sinew.enpass-desktop', 'in.sinew.enpass-desktop.app'],
	},
	// Authenticators (Microsoft and Google Authenticator have no desktop
	// apps; Windows Hello and Microsoft account prompts are covered below).
	{
		name: 'Authy',
		exe: ['authy desktop.exe', 'authy.exe', 'authy desktop'],
		bundleIds: ['com.authy.authy-mac'],
	},
	// Windows sign-in, credential and elevation prompts
	{
		name: 'Windows Security (Windows Hello and credential prompts)',
		exe: ['credentialuibroker.exe'],
		bundleIds: [],
	},
	{ name: 'User Account Control prompt', exe: ['consent.exe'], bundleIds: [] },
	{ name: 'Windows sign-in screen', exe: ['logonui.exe'], bundleIds: [] },
	// macOS system password prompts and stores
	{
		name: 'macOS password prompt (SecurityAgent)',
		exe: ['securityagent'],
		bundleIds: ['com.apple.securityagent'],
	},
	{
		name: 'Keychain Access',
		exe: ['keychain access'],
		bundleIds: ['com.apple.keychainaccess'],
	},
	{
		name: 'Passwords',
		exe: [],
		bundleIds: ['com.apple.passwords'],
	},
];

/** Browsers: their window title names the page, so it is checked. */
export const BROWSERS = {
	exe: [
		'chrome.exe',
		'msedge.exe',
		'firefox.exe',
		'brave.exe',
		'opera.exe',
		'opera_gx.exe',
		'vivaldi.exe',
		'arc.exe',
		'librewolf.exe',
		'waterfox.exe',
		'zen.exe',
		'iexplore.exe',
		'chromium.exe',
		'thorium.exe',
	],
	bundleIds: [
		'com.google.chrome',
		'com.microsoft.edgemac',
		'org.mozilla.firefox',
		'com.apple.safari',
		'com.brave.browser',
		'company.thebrowser.browser',
		'com.operasoftware.opera',
		'com.vivaldi.vivaldi',
		'org.chromium.chromium',
		'app.zen-browser.zen',
	],
} as const;

/**
 * A browser page whose title matches this is treated as sensitive: audio is
 * allowed (the user chose to speak) but no context about it is used.
 * Title matching has limits: a bank page with a generic title is missed.
 */
export const SENSITIVE_TITLE_PATTERN = new RegExp(
	[
		'password',
		'passcode',
		'\\bpin\\b',
		'sign[\\s-]?in',
		'sign[\\s-]?on',
		'log[\\s-]?in',
		'verification code',
		'verify',
		'one[\\s-]?time (?:code|password|passcode|pin)',
		'\\botp\\b',
		'\\b2fa\\b',
		'\\bmfa\\b',
		'two[\\s-]?(?:factor|step)',
		'authenticat',
		'security code',
		'\\bbank',
		'online banking',
		'credit union',
		'\\bchase\\b',
		'bank of america',
		'wells fargo',
		'\\bciti(?:bank)?\\b',
		'capital one',
		'\\bu\\.?s\\.? bank\\b',
		'\\bpnc\\b',
		'\\btruist\\b',
		'\\btd bank\\b',
		'\\bally\\b',
		'\\bamerican express\\b',
		'\\bamex\\b',
		'\\bdiscover\\b',
		'\\bschwab\\b',
		'\\bfidelity\\b',
		'\\bvanguard\\b',
		'\\bpaypal\\b',
		'\\bvenmo\\b',
		'\\bzelle\\b',
		'\\brobinhood\\b',
		'navy federal',
		'\\busaa\\b',
		'\\bhsbc\\b',
		'\\bbarclays\\b',
		'\\bsantander\\b',
		'\\bcoinbase\\b',
	].join('|'),
	'i',
);

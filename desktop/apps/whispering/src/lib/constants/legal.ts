/**
 * VoiceFlow: the licence and notice texts shipped inside the app, for the
 * About screen (AGPL section 5(d)). They are read from desktop/LICENSE and
 * desktop/NOTICE at build time, so the app always carries the current text.
 */
import licenseText from '../../../../../LICENSE?raw';
import noticeText from '../../../../../NOTICE?raw';

export { licenseText, noticeText };

/** Where the corresponding source is published (AGPL section 6). */
export const SOURCE_URL = 'https://github.com/yutungh/voicelnq';

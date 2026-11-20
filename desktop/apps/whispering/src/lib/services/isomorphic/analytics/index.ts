import { Ok } from 'wellcrafted/result';
import type { AnalyticsService } from './types';

export type { AnalyticsService, AnalyticsServiceError, Event } from './types';

/**
 * VoiceFlow removed upstream's Aptabase analytics. The service keeps its
 * interface so existing call sites compile, but it sends nothing anywhere.
 */
export const AnalyticsServiceLive: AnalyticsService = {
	logEvent: async () => Ok(undefined),
};

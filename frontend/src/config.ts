/**
 * Build-time configuration.
 *
 * Cosmetic fallbacks only, for the first paint before the public settings
 * endpoint answers. Nothing here is a business fact.
 *
 * <p>Contact details are deliberately absent. They are administrator-editable,
 * so anything compiled into the bundle goes stale the moment it is changed in
 * the admin - and a stale number sends customers to a stranger, which is worse
 * than showing no channel at all. Those come from useSiteSettings and nowhere
 * else.
 */
export const config = {
  storeName: import.meta.env.VITE_STORE_NAME || 'Rainbow Books',
  tagline: import.meta.env.VITE_STORE_TAGLINE || 'Handwoven coir craft',

  currency: import.meta.env.VITE_CURRENCY || 'INR',
  locale: import.meta.env.VITE_LOCALE || 'en-IN',
} as const;

export const siteUrl = (): string =>
  typeof window === 'undefined' ? '' : window.location.origin;

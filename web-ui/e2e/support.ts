import AxeBuilder from '@axe-core/playwright';
import type { Page } from '@playwright/test';
import { expect } from '@playwright/test';

export type DemoRole = 'customer' | 'agent' | 'admin';

/** WCAG 2.1 A + AA, the level SkyBook commits to (docs/nfr.md). */
const WCAG_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'];

export async function expectNoAccessibilityViolations(page: Page, context: string): Promise<void> {
  const results = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze();
  const summary = results.violations.map((v) => ({
    id: v.id,
    impact: v.impact,
    help: v.help,
    targets: v.nodes.slice(0, 3).map((n) => n.target.join(' ')),
  }));
  expect(summary, `axe violations on ${context}`).toEqual([]);
}

/** Mock-mode sign-in through the role picker; the mock API state lives in this page, so never reload. */
export async function signIn(page: Page, role: DemoRole): Promise<void> {
  await page.getByRole('button', { name: 'Sign in' }).click();
  await page.getByRole('button', { name: `Continue as ${role}` }).click();
}

export function tomorrowIso(): string {
  const d = new Date();
  d.setDate(d.getDate() + 1);
  const pad = (n: number) => n.toString().padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

export function inDaysIso(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() + days);
  const pad = (n: number) => n.toString().padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/** Types into the city/airport combobox and picks the suggestion for that IATA code. */
export async function pickAirport(page: Page, label: RegExp, code: string): Promise<void> {
  await page.getByRole('combobox', { name: label }).fill(code);
  await page.getByRole('option', { name: new RegExp(`^${code} `) }).click();
}

/** Searches from the home page with the given route and date. */
export async function search(page: Page, origin: string, destination: string, date: string): Promise<void> {
  await pickAirport(page, /From/, origin);
  await pickAirport(page, /To/, destination);
  await page.getByLabel(/Departure date/).fill(date);
  await page.getByRole('button', { name: 'Search' }).click();
}

import { expect, test } from '@playwright/test';

import { expectNoAccessibilityViolations, inDaysIso, signIn } from './support';

/** One screen per test, each in a fresh page, so a violation points at exactly one screen. */
test.describe('WCAG 2.1 AA (axe)', () => {
  test('search page', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    await expectNoAccessibilityViolations(page, 'search page');
  });

  test('search results', async ({ page }) => {
    await page.goto(`/?origin=DXB&destination=LHR&date=${inDaysIso(4)}&passengers=1`);
    await expect(page.getByRole('article').first()).toBeVisible();
    await expectNoAccessibilityViolations(page, 'search results');
  });

  test('booking form with validation errors', async ({ page }) => {
    await page.goto('/login?returnTo=%2Fflights%2F3%2Fbook%3Fpassengers%3D2');
    await page.getByRole('button', { name: 'Continue as customer' }).click();
    await expect(page.getByRole('group', { name: 'Seat map' })).toBeVisible();
    await page.getByRole('button', { name: 'Continue to payment' }).click();
    await expect(page.getByText('First name is required').first()).toBeVisible();
    await expectNoAccessibilityViolations(page, 'booking form with validation errors');
  });

  test('sign-in and not-found pages', async ({ page }) => {
    await page.goto('/login');
    await expectNoAccessibilityViolations(page, 'sign-in page');
    await page.goto('/does-not-exist');
    await expect(page.getByRole('heading', { name: 'Page not found' })).toBeVisible();
    await expectNoAccessibilityViolations(page, 'not-found page');
  });

  test('flight management and new flight dialog', async ({ page }) => {
    await page.goto('/');
    await signIn(page, 'admin');
    await page.getByRole('link', { name: 'Flights', exact: true }).click();
    await expect(page.getByRole('table', { name: 'Flights' })).toBeVisible();
    await expectNoAccessibilityViolations(page, 'flight management');
    await page.getByRole('button', { name: 'New flight' }).click();
    await expect(page.getByRole('dialog', { name: 'New flight' })).toBeVisible();
    await expectNoAccessibilityViolations(page, 'new flight dialog');
  });

  test('booking servicing', async ({ page }) => {
    await page.goto('/');
    await signIn(page, 'agent');
    await page.getByRole('link', { name: 'Servicing' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Booking servicing' })).toBeVisible();
    await expectNoAccessibilityViolations(page, 'booking servicing');
  });
});

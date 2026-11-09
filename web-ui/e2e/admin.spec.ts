import { expect, test } from '@playwright/test';

import { inDaysIso, signIn } from './support';

test.describe('staff', () => {
  test('admin schedules a flight that customers can then find', async ({ page }) => {
    await page.goto('/');
    await signIn(page, 'admin');
    await page.getByRole('link', { name: 'Flights', exact: true }).click();
    await page.getByRole('button', { name: 'New flight' }).click();

    const dialog = page.getByRole('dialog', { name: 'New flight' });
    await dialog.getByRole('textbox', { name: /Flight number/ }).fill('SB777');
    await dialog.getByRole('textbox', { name: /^Origin/ }).fill('BOM');
    await dialog.getByRole('textbox', { name: /^Destination/ }).fill('LHR');
    await dialog.getByLabel(/Departure/).fill(`${inDaysIso(6)}T09:00`);
    await dialog.getByLabel(/Arrival/).fill(`${inDaysIso(6)}T18:30`);
    await dialog.getByRole('button', { name: 'Create flight' }).click();
    await expect(dialog).toBeHidden();

    await page.getByRole('link', { name: 'Search flights' }).click();
    await page.getByRole('textbox', { name: /From/ }).fill('BOM');
    await page.getByRole('textbox', { name: /To/ }).fill('LHR');
    await page.getByLabel(/Departure date/).fill(inDaysIso(6));
    await page.getByRole('button', { name: 'Search' }).click();
    await expect(page.getByRole('article', { name: /SB777: BOM to LHR/ })).toBeVisible();
  });

  test('agents service bookings but cannot manage flights', async ({ page }) => {
    await page.goto('/');
    await signIn(page, 'agent');
    await expect(page.getByRole('link', { name: 'Servicing' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Flights', exact: true })).toHaveCount(0);
    await page.getByRole('link', { name: 'Servicing' }).click();
    await expect(page.getByText('No bookings match these filters')).toBeVisible();
  });
});

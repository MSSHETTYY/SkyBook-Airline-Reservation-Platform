import { expect, test } from '@playwright/test';

import { inDaysIso, search, signIn } from './support';

test.describe('customer journey', () => {
  test('search, pick seats with the keyboard, pay, get tickets, then cancel with a refund', async ({ page }) => {
    await page.goto('/');
    await signIn(page, 'customer');
    await expect(page.getByRole('heading', { level: 1, name: 'Where would you like to go?' })).toBeVisible();

    // 10 days out: the cancellation quote is in the 100% tier.
    await search(page, 'DXB', 'LHR', inDaysIso(10));
    await page.getByRole('combobox', { name: 'Passengers' }).click();
    await page.getByRole('option', { name: '2' }).click();
    await page.getByRole('button', { name: 'Search' }).click();
    const flight = page.getByRole('article', { name: /SB101: DXB to LHR/ });
    await flight.getByRole('link', { name: /Select flight SB101/ }).click();

    await expect(page.getByRole('heading', { level: 1, name: /SB101: DXB to LHR/ })).toBeVisible();
    const free = page.getByRole('group', { name: 'Seat map' }).getByRole('button', { name: /economy.*, available$/ });
    // Keyboard only: seats are real buttons, so Space and Enter both toggle them.
    await free.nth(1).focus();
    await page.keyboard.press('Space');
    await free.nth(1).focus();
    await page.keyboard.press('Enter');
    await expect(page.getByText(/2 of 2 seats selected/)).toBeVisible();

    for (const [index, [first, last]] of [['Ada', 'Lovelace'], ['Alan', 'Turing']].entries()) {
      const passenger = page.getByRole('group', { name: new RegExp(`^Passenger ${index + 1}`) });
      await passenger.getByRole('textbox', { name: /First name/ }).fill(first);
      await passenger.getByRole('textbox', { name: /Last name/ }).fill(last);
      await passenger.getByLabel(/Date of birth/).fill('1990-02-03');
    }
    await expect(page.getByText('Total:')).toContainText('$840.00');
    await page.getByRole('button', { name: 'Continue to payment' }).click();

    await expect(page.getByRole('heading', { level: 1, name: /^Booking [A-Z2-9]{6}$/ })).toBeVisible();
    await expect(page.getByText(/Seats held for/)).toBeVisible();
    await page.getByRole('button', { name: 'Pay $840.00 (test)' }).click();
    await expect(page.getByText('Your booking is confirmed. Your e-ticket has been sent by email.')).toBeVisible();
    await expect(page.getByRole('cell', { name: /^176\d{10}$/ })).toHaveCount(2);

    await page.getByRole('button', { name: 'Cancel booking' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByText(/100% of the fare/)).toBeVisible();
    await dialog.getByRole('button', { name: 'Cancel booking' }).click();
    await expect(page.getByText('This booking has been cancelled.')).toBeVisible();

    await page.getByRole('link', { name: 'My bookings' }).click();
    await expect(page.getByRole('table', { name: 'My bookings' }).getByText('Cancelled')).toBeVisible();
  });

  test('a seat taken meanwhile is reported and can be replaced', async ({ page }) => {
    await page.goto('/');
    await signIn(page, 'customer');
    await search(page, 'DXB', 'BOM', inDaysIso(3));
    await page.getByRole('article', { name: /SB201/ }).getByRole('link', { name: /Select flight/ }).click();

    await page.getByRole('button', { name: /^Seat 3A, .*available$/ }).click();
    const passenger = page.getByRole('group', { name: /^Passenger 1/ });
    await passenger.getByRole('textbox', { name: /First name/ }).fill('Grace');
    await passenger.getByRole('textbox', { name: /Last name/ }).fill('Hopper');
    await passenger.getByLabel(/Date of birth/).fill('1980-12-09');
    await page.getByRole('button', { name: 'Continue to payment' }).click();

    await expect(page.getByRole('alert').filter({ hasText: 'just taken' })).toBeVisible();
    await expect(page.getByRole('button', { name: /^Seat 3A, .*unavailable$/ })).toBeVisible();
    await page.getByRole('button', { name: /economy.*, available$/ }).first().click();
    await page.getByRole('button', { name: 'Continue to payment' }).click();
    await expect(page.getByText('Awaiting payment', { exact: true })).toBeVisible();
  });
});

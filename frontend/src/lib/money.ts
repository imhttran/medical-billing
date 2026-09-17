// Amounts are USD throughout: the practice bills U.S. payers on U.S. coverages.
const usd = new Intl.NumberFormat("en-US", {
  style: "currency",
  currency: "USD",
});

/** An amount as it should read on screen, or an em dash when there is none. */
export const money = (value: number | null | undefined) =>
  value === null || value === undefined ? "—" : usd.format(value);

/** The same amount as a bare decimal, for a form field that posts it back. */
export const decimal = (value: number) => value.toFixed(2);

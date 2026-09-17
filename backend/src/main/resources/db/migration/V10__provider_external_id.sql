-- The identifier the source system used for a provider, kept so an imported
-- Practitioner can be reconciled rather than duplicated — the same reason
-- `patients.external_id` exists. The NPI stays where it is: it is a provider's
-- billing identity, not the identifier a hospital's export happens to use.
ALTER TABLE providers
  ADD COLUMN IF NOT EXISTS external_id TEXT;

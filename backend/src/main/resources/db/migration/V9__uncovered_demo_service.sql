-- A service the synthetic payer does not cover.
--
-- The fee schedule prices every procedure the catalog seeds, which left "we do not
-- cover this" unreachable from the screens: the only way to produce a denial was a
-- code no catalog holds, and validation refuses those before a claim is submitted —
-- correctly, since a biller cannot bill a code that does not exist. One plausible
-- primary-care code with no rate for the synthetic payer is what makes a denial,
-- and so the denial queue, reachable with the data that is already here.

INSERT INTO procedure_codes (code, code_system, description, default_charge) VALUES
  ('17110', 'CPT', 'Destruction of benign lesion, up to 14 lesions', 95.00)
ON CONFLICT (code) DO NOTHING;

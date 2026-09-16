-- Claims: the billable encounter, its diagnoses and service lines, the
-- adjudication record, and the fee schedule the simulated payer prices against.

-- Claim numbers are human-facing (CLM-000123) and come from one sequence rather
-- than from the id, so a number is never reused and is not guessable from a
-- count of rows.
CREATE SEQUENCE IF NOT EXISTS claim_numbers;

CREATE TABLE IF NOT EXISTS claims (
  id                 SERIAL PRIMARY KEY,
  organization_id    INTEGER NOT NULL REFERENCES organizations(id),
  claim_number       TEXT NOT NULL UNIQUE,
  patient_id         INTEGER NOT NULL REFERENCES patients(id),
  provider_id        INTEGER NOT NULL REFERENCES providers(id),
  coverage_id        INTEGER NOT NULL REFERENCES coverages(id),
  payer_id           INTEGER NOT NULL REFERENCES payers(id),
  -- Nullable: a draft can be entered without it and validation requires it
  -- before the claim is marked READY. The references above are not nullable
  -- because they are what identifies the claim.
  service_date       DATE,
  status             TEXT NOT NULL DEFAULT 'DRAFT',
  -- Which submission this row describes. A resubmission increments it; nothing
  -- in this slice does.
  submission_version INTEGER NOT NULL DEFAULT 1,
  submitted_at       TIMESTAMPTZ,
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- `status` has no CHECK constraint on purpose: the value is only ever written by
-- the state machine, from an enum, so a constraint here would be a second list of
-- statuses to keep in step with ClaimStatus.
CREATE INDEX IF NOT EXISTS claims_organization_status_idx ON claims (organization_id, status);
CREATE INDEX IF NOT EXISTS claims_patient_idx ON claims (patient_id);

CREATE TABLE IF NOT EXISTS claim_diagnoses (
  id             SERIAL PRIMARY KEY,
  claim_id       INTEGER NOT NULL REFERENCES claims(id) ON DELETE CASCADE,
  diagnosis_code TEXT NOT NULL,
  sequence       INTEGER NOT NULL,
  UNIQUE (claim_id, sequence)
);

-- The latest line results live here. Adjudications are the immutable record of
-- each submission; a second adjudication for the same claim (a resubmission)
-- would need its own line table to keep the history, which arrives with the
-- correction workflow rather than now.
CREATE TABLE IF NOT EXISTS claim_lines (
  id                   SERIAL PRIMARY KEY,
  claim_id             INTEGER NOT NULL REFERENCES claims(id) ON DELETE CASCADE,
  line_number          INTEGER NOT NULL,
  procedure_code       TEXT NOT NULL,
  quantity             INTEGER NOT NULL DEFAULT 1,
  charge_amount        NUMERIC(10, 2) NOT NULL,
  -- NULL until the payer prices the line.
  allowed_amount       NUMERIC(10, 2),
  adjustment_amount    NUMERIC(10, 2),
  payer_amount         NUMERIC(10, 2),
  patient_responsibility NUMERIC(10, 2),
  status               TEXT NOT NULL DEFAULT 'PENDING',
  UNIQUE (claim_id, line_number)
);

CREATE TABLE IF NOT EXISTS adjudications (
  id                     SERIAL PRIMARY KEY,
  claim_id               INTEGER NOT NULL REFERENCES claims(id) ON DELETE CASCADE,
  organization_id        INTEGER NOT NULL REFERENCES organizations(id),
  adjudicated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  outcome                TEXT NOT NULL,
  total_charge           NUMERIC(10, 2) NOT NULL,
  total_allowed          NUMERIC(10, 2) NOT NULL,
  total_adjustment       NUMERIC(10, 2) NOT NULL,
  payer_responsibility   NUMERIC(10, 2) NOT NULL,
  patient_responsibility NUMERIC(10, 2) NOT NULL
);

CREATE INDEX IF NOT EXISTS adjudications_claim_idx ON adjudications (claim_id);

-- What a payer allows for a procedure, and the patient's fixed share of it. The
-- golden path's numbers live here rather than in code: allowed 110.00 with a
-- 30.00 copay turns a 150.00 charge into 40.00 contractual adjustment, 80.00
-- payer responsibility and 30.00 patient responsibility.
--
-- A copay rather than coinsurance, because the plan's own worked example is not
-- a percentage: 20% of 110.00 would be 22.00, not 30.00.
CREATE TABLE IF NOT EXISTS payer_fee_schedule (
  payer_id       INTEGER NOT NULL REFERENCES payers(id),
  procedure_code TEXT NOT NULL REFERENCES procedure_codes(code),
  allowed_amount NUMERIC(10, 2) NOT NULL,
  patient_copay  NUMERIC(10, 2) NOT NULL DEFAULT 0,
  PRIMARY KEY (payer_id, procedure_code)
);

-- The seeded payer covers every seeded procedure, so the simulator has a rate
-- for anything a claim can name. A procedure with no rate is treated as not
-- covered, which is a real outcome rather than a wrong number.
INSERT INTO payer_fee_schedule (payer_id, procedure_code, allowed_amount, patient_copay)
SELECT p.id, s.code, s.allowed, s.copay
FROM (VALUES
  ('99213', 110.00, 30.00),
  ('99212',  85.00, 25.00),
  ('99214', 150.00, 40.00),
  ('99203', 130.00, 35.00),
  ('99396', 165.00,  0.00),
  ('87880',  20.00,  0.00),
  ('81002',  12.00,  0.00),
  ('36415',  10.00,  0.00),
  ('96127',  25.00,  0.00),
  ('90686',  35.00,  0.00),
  ('90471',  18.00,  0.00),
  ('G0439', 175.00,  0.00)
) AS s(code, allowed, copay)
JOIN payers p ON p.payer_code = 'SYN001'
ON CONFLICT (payer_id, procedure_code) DO NOTHING;

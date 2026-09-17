-- Money against a claim: what the payer remitted, and what the patient paid.
--
-- The payer's share is recorded when it adjudicates a claim, because the
-- simulated payer answers immediately and its answer is also its remittance. The
-- patient's share is recorded by hand, one payment at a time, against the claim
-- whose balance it reduces.
--
-- Neither table has a running total: a stored balance is a second copy of the
-- payment rows that a bug can desync, exactly like a stored claim total. Balances
-- are summed on read.

CREATE TABLE IF NOT EXISTS insurance_payments (
  id               SERIAL PRIMARY KEY,
  organization_id  INTEGER NOT NULL REFERENCES organizations(id),
  claim_id         INTEGER NOT NULL REFERENCES claims(id) ON DELETE CASCADE,
  amount           NUMERIC(10, 2) NOT NULL CHECK (amount > 0),
  payment_date     DATE NOT NULL,
  -- The payer's trace number. In the simulator it is derived from the claim
  -- number and the submission, so a remittance can be traced back to its answer.
  reference_number TEXT NOT NULL,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS patient_payments (
  id               SERIAL PRIMARY KEY,
  organization_id  INTEGER NOT NULL REFERENCES organizations(id),
  -- Both ids: the claim the payment settles, and the patient whose balance it
  -- reduces. A payment is always taken against a claim, so claim_id is required.
  patient_id       INTEGER NOT NULL REFERENCES patients(id) ON DELETE CASCADE,
  claim_id         INTEGER NOT NULL REFERENCES claims(id) ON DELETE CASCADE,
  amount           NUMERIC(10, 2) NOT NULL CHECK (amount > 0),
  payment_method   TEXT NOT NULL
                     CHECK (payment_method IN ('CASH', 'CHECK', 'CARD', 'TRANSFER', 'OTHER')),
  payment_date     DATE NOT NULL,
  reference_number TEXT,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Every balance is per claim, so both tables are read by claim.
CREATE INDEX IF NOT EXISTS insurance_payments_claim_idx ON insurance_payments (claim_id);
CREATE INDEX IF NOT EXISTS patient_payments_claim_idx ON patient_payments (claim_id);
CREATE INDEX IF NOT EXISTS patient_payments_patient_idx ON patient_payments (patient_id);

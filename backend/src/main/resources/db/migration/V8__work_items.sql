-- The work queue: what a biller has to follow up, and who is doing it.
--
-- An item is opened when the payer's answer creates follow-up work — it refused
-- the claim, or it would not cover a line — and it is closed when the work is
-- done: the claim goes back out, or someone decides nothing is to be done. It is
-- a record of work rather than a view of the claims, which is why it is a table:
-- an item can be assigned, and it stays on the queue until somebody closes it.

CREATE TABLE IF NOT EXISTS work_items (
  id                  SERIAL PRIMARY KEY,
  organization_id     INTEGER NOT NULL REFERENCES organizations(id),
  claim_id            INTEGER NOT NULL REFERENCES claims(id) ON DELETE CASCADE,
  -- Only the kinds something actually opens. CORRECTION and FOLLOW_UP are in the
  -- plan's model but nothing produces them yet.
  type                TEXT NOT NULL CHECK (type IN ('REJECTION', 'DENIAL')),
  reason_code         TEXT NOT NULL,
  reason_text         TEXT NOT NULL,
  status              TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RESOLVED')),
  assigned_user_id    INTEGER REFERENCES users(id),
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  resolved_at         TIMESTAMPTZ,
  resolved_by_user_id INTEGER REFERENCES users(id)
);

-- A claim has at most one open item of each kind. Without this a claim rejected
-- twice, or denied on two submissions, would stack up duplicates on the queue.
CREATE UNIQUE INDEX IF NOT EXISTS work_items_one_open_per_claim_type
  ON work_items (claim_id, type) WHERE status = 'OPEN';

CREATE INDEX IF NOT EXISTS work_items_organization_idx ON work_items (organization_id, status);

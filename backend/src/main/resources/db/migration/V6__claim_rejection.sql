-- What the payer said when it refused to process a claim.
--
-- A rejection is not an adjudication: nothing was priced, so there are no allowed
-- amounts and no money, and no row goes into `adjudications`. The reason sits on
-- the claim because it explains the claim's current status, and only the latest
-- one means anything — resubmitting a corrected claim replaces it.
--
-- ponytail: latest rejection only. The audit trail plus `submission_version` are
-- where a full history of payer messages would be built, if one is ever wanted.
ALTER TABLE claims
  ADD COLUMN IF NOT EXISTS rejection_code    TEXT,
  ADD COLUMN IF NOT EXISTS rejection_message TEXT,
  ADD COLUMN IF NOT EXISTS rejected_at       TIMESTAMPTZ;

-- The practice administrator bills too.
--
-- V2 seeded PRACTICE_ADMIN with CLAIM_VIEW only, so a practice admin could see
-- claims and supervise them but not work one. In a small practice the same
-- person manages users and does the billing, and the plan's role description
-- ("runs one practice") does not survive contact with a two-person front desk.
-- The claim operations now belong to that role as well.
--
-- Void stays with BILLING_MANAGER, and PAYMENT_RECORD stays with the billing
-- roles, so this widens what an admin can do to a claim without handing them the
-- destructive or money-moving permission.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM (VALUES
  ('PRACTICE_ADMIN', 'CLAIM_CREATE'),
  ('PRACTICE_ADMIN', 'CLAIM_EDIT'),
  ('PRACTICE_ADMIN', 'CLAIM_SUBMIT'),
  ('PRACTICE_ADMIN', 'CLAIM_RESUBMIT')
) AS granted(role_code, permission_code)
JOIN roles r ON r.code = granted.role_code
JOIN permissions p ON p.code = granted.permission_code
ON CONFLICT DO NOTHING;

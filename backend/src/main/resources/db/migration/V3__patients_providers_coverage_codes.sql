-- Patients, providers, payers, coverage, and the two terminology tables.
--
-- Every table that belongs to a practice carries organization_id. Payers and the
-- two code tables do not: they are shared reference data, not tenant-owned.
--
-- Every statement is IF NOT EXISTS / ON CONFLICT, so it is also a no-op against
-- a database that already has these tables.

CREATE TABLE IF NOT EXISTS providers (
  id              SERIAL PRIMARY KEY,
  organization_id INTEGER NOT NULL REFERENCES organizations(id),
  -- Set when the provider is also a login; most are practitioners without one.
  user_id         INTEGER REFERENCES users(id),
  first_name      TEXT NOT NULL,
  last_name       TEXT NOT NULL,
  npi             TEXT,
  taxonomy_code   TEXT,
  active          BOOLEAN NOT NULL DEFAULT true,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS providers_organization_idx ON providers (organization_id);

CREATE TABLE IF NOT EXISTS patients (
  id              SERIAL PRIMARY KEY,
  organization_id INTEGER NOT NULL REFERENCES organizations(id),
  -- The identifier the source system used, kept so an imported record can be
  -- reconciled on re-import instead of duplicated.
  external_id     TEXT,
  first_name      TEXT NOT NULL,
  last_name       TEXT NOT NULL,
  date_of_birth   DATE NOT NULL,
  sex             TEXT,
  address_line1   TEXT,
  address_line2   TEXT,
  city            TEXT,
  state           TEXT,
  postal_code     TEXT,
  phone           TEXT,
  active          BOOLEAN NOT NULL DEFAULT true,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Serves both the practice-scoped list and the name search.
CREATE INDEX IF NOT EXISTS patients_organization_name_idx
  ON patients (organization_id, last_name, first_name);

CREATE TABLE IF NOT EXISTS payers (
  id         SERIAL PRIMARY KEY,
  name       TEXT NOT NULL UNIQUE,
  payer_code TEXT NOT NULL UNIQUE,
  active     BOOLEAN NOT NULL DEFAULT true
);

-- A patient may hold several coverages over time. V1 processes the primary one
-- per claim, which is what `priority` orders.
--
-- member_id is NOT NULL on purpose. A coverage with no member id cannot be
-- billed, so it is rejected at the boundary rather than stored half-formed and
-- caught later by claim validation.
CREATE TABLE IF NOT EXISTS coverages (
  id                         SERIAL PRIMARY KEY,
  organization_id            INTEGER NOT NULL REFERENCES organizations(id),
  patient_id                 INTEGER NOT NULL REFERENCES patients(id) ON DELETE CASCADE,
  payer_id                   INTEGER NOT NULL REFERENCES payers(id),
  member_id                  TEXT NOT NULL,
  group_number               TEXT,
  subscriber_name            TEXT,
  relationship_to_subscriber TEXT,
  effective_date             DATE,
  termination_date           DATE,
  priority                   INTEGER NOT NULL DEFAULT 1,
  active                     BOOLEAN NOT NULL DEFAULT true,
  created_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at                 TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS coverages_patient_idx ON coverages (patient_id, priority);

-- Terminology. Not tenant-owned: the codes are the same for every practice.
-- `code` is the natural key, so it is the primary key rather than a surrogate id.
CREATE TABLE IF NOT EXISTS diagnosis_codes (
  code        TEXT PRIMARY KEY,
  description TEXT NOT NULL,
  active      BOOLEAN NOT NULL DEFAULT true
);

CREATE TABLE IF NOT EXISTS procedure_codes (
  code           TEXT PRIMARY KEY,
  -- CPT or HCPCS. Kept explicit so the FHIR export can emit the right system.
  code_system    TEXT NOT NULL CHECK (code_system IN ('CPT', 'HCPCS')),
  description    TEXT NOT NULL,
  -- Development convenience so the claim screen can pre-fill a charge.
  default_charge NUMERIC(10, 2),
  active         BOOLEAN NOT NULL DEFAULT true
);

-- ---------------------------------------------------------------------------
-- Reference data.

INSERT INTO payers (name, payer_code) VALUES
  ('Synthetic Health Plan',  'SYN001'),
  ('Demo Medicare Part B',   'DEM001'),
  ('Example Commercial PPO', 'EXC001')
ON CONFLICT (payer_code) DO NOTHING;

-- A small sample so the claim screen and the payer simulator have something to
-- search and price against. These short descriptions are development
-- placeholders, not licensed ICD-10-CM or CPT content. Real code sets have their
-- own licensing and distribution terms and must not be assumed to come with
-- FHIR or to be free to redistribute.
INSERT INTO diagnosis_codes (code, description) VALUES
  ('J06.9',  'Acute upper respiratory infection, unspecified'),
  ('J02.9',  'Acute pharyngitis, unspecified'),
  ('E11.9',  'Type 2 diabetes mellitus without complications'),
  ('I10',    'Essential (primary) hypertension'),
  ('M54.5',  'Low back pain'),
  ('K21.9',  'GERD without esophagitis'),
  ('N39.0',  'Urinary tract infection, site not specified'),
  ('R05',    'Cough'),
  ('R51',    'Headache'),
  ('F41.1',  'Generalized anxiety disorder'),
  ('L03.90', 'Cellulitis, unspecified'),
  ('Z00.00', 'General adult medical examination without abnormal findings')
ON CONFLICT (code) DO NOTHING;

INSERT INTO procedure_codes (code, code_system, description, default_charge) VALUES
  ('99213', 'CPT',   'Office visit, established patient, low complexity',      150.00),
  ('99212', 'CPT',   'Office visit, established patient, straightforward',     110.00),
  ('99214', 'CPT',   'Office visit, established patient, moderate complexity', 210.00),
  ('99203', 'CPT',   'Office visit, new patient, low complexity',              180.00),
  ('99396', 'CPT',   'Periodic preventive visit, established patient, 40-64',  220.00),
  ('87880', 'CPT',   'Rapid strep test',                                        25.00),
  ('81002', 'CPT',   'Urinalysis, non-automated',                               15.00),
  ('36415', 'CPT',   'Venipuncture',                                            12.00),
  ('96127', 'CPT',   'Brief emotional or behavioral assessment',                 30.00),
  ('90686', 'CPT',   'Influenza vaccine, quadrivalent',                          40.00),
  ('90471', 'CPT',   'Immunization administration',                              20.00),
  ('G0439', 'HCPCS', 'Annual wellness visit, subsequent',                       200.00)
ON CONFLICT (code) DO NOTHING;

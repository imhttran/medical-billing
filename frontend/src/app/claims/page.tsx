"use client";

import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type FormEvent,
  type MouseEvent,
} from "react";
import { useRouter } from "next/navigation";
import { submitJson } from "@/lib/api";
import { useSession } from "@/lib/session";
import { PageHeader } from "@/components/PageHeader";
import { PageFooter } from "@/components/PageFooter";
import { PageTitle } from "@/components/PageTitle";

type ClaimSummary = {
  claim: {
    id: number;
    claimNumber: string;
    patientId: number;
    serviceDate: string | null;
    status: string;
  };
  totalCharge: number;
};

type Patient = { id: number; firstName: string; lastName: string };
type Provider = { id: number; firstName: string; lastName: string };
type Coverage = {
  id: number;
  memberId: string;
  priority: number;
  active: boolean;
};
type Code = { code: string; description: string };

/**
 * The claim list, and the one-screen way to start a claim: pick the patient,
 * their coverage, the provider and the date, name one diagnosis and one service,
 * and the claim exists. Everything after that happens on the claim's own page.
 *
 * The code pickers are datalists loaded once with the first hundred rows of each
 * terminology table. That covers the seeded set; a real code list wants
 * search-as-you-type against the existing /api/codes endpoints instead.
 */
export default function ClaimsPage() {
  const router = useRouter();
  const { me, withToken } = useSession();
  const [claims, setClaims] = useState<ClaimSummary[] | null>(null);
  const [patients, setPatients] = useState<Patient[]>([]);
  const [providers, setProviders] = useState<Provider[]>([]);
  const [coverages, setCoverages] = useState<Coverage[]>([]);
  const [diagnoses, setDiagnoses] = useState<Code[]>([]);
  const [procedures, setProcedures] = useState<Code[]>([]);
  const [failed, setFailed] = useState(false);
  const [error, setError] = useState("");
  const addFormRef = useRef<HTMLFormElement>(null);
  const addSectionRef = useRef<HTMLDetailsElement>(null);

  const load = useCallback(async (authToken: string) => {
    const [
      claimResult,
      patientResult,
      providerResult,
      diagnosisResult,
      procedureResult,
    ] = await Promise.all([
      submitJson<{ claims: ClaimSummary[] }>(authToken, "/api/claims", "GET"),
      submitJson<{ patients: Patient[] }>(authToken, "/api/patients", "GET"),
      submitJson<{ providers: Provider[] }>(authToken, "/api/providers", "GET"),
      submitJson<{ diagnoses: Code[] }>(
        authToken,
        "/api/codes/diagnoses?limit=100",
        "GET",
      ),
      submitJson<{ procedures: Code[] }>(
        authToken,
        "/api/codes/procedures?limit=100",
        "GET",
      ),
    ]);

    if (!claimResult.ok) {
      setFailed(true);
      return;
    }
    setClaims(claimResult.data.claims);

    // The rest are only needed by the form; an empty picker is better than a
    // failed page if one of them is refused.
    setPatients(patientResult.ok ? patientResult.data.patients : []);
    setProviders(providerResult.ok ? providerResult.data.providers : []);
    setDiagnoses(diagnosisResult.ok ? diagnosisResult.data.diagnoses : []);
    setProcedures(procedureResult.ok ? procedureResult.data.procedures : []);
    setFailed(false);
  }, []);

  useEffect(() => {
    if (!me) return;
    withToken(load);
  }, [me, load, withToken]);

  /** A claim's practice comes from its coverage, so the coverages must load first. */
  const loadCoverages = (patientId: string) => {
    setCoverages([]);
    if (!patientId) return;
    withToken(async (authToken) => {
      const { ok, data } = await submitJson<{ coverages: Coverage[] }>(
        authToken,
        `/api/patients/${patientId}/coverages`,
        "GET",
      );
      setCoverages(ok ? data.coverages.filter((c) => c.active) : []);
    });
  };

  const handleAdd = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    setError("");
    withToken(async (authToken) => {
      // No organization and no payer: the server reads the practice and the payer
      // from the coverage.
      const { ok, data: body } = await submitJson(
        authToken,
        "/api/claims",
        "POST",
        {
          patientId: Number(data.get("patientId")),
          providerId: Number(data.get("providerId")),
          coverageId: Number(data.get("coverageId")),
          serviceDate: data.get("serviceDate"),
          diagnoses: [data.get("diagnosisCode")],
          lines: [
            {
              procedureCode: data.get("procedureCode"),
              quantity: 1,
              chargeAmount: data.get("chargeAmount"),
            },
          ],
        },
      );
      if (!ok) {
        setError(body.message ?? "Could not create the claim.");
        return;
      }
      const created = body.claim as { id: number };
      addFormRef.current?.reset();
      if (addSectionRef.current) addSectionRef.current.open = false;
      router.push(`/claims/${created.id}`);
    });
  };

  const patientName = (patientId: number) => {
    const patient = patients.find((candidate) => candidate.id === patientId);
    return patient
      ? `${patient.lastName}, ${patient.firstName}`
      : `Patient ${patientId}`;
  };

  const logout = (event: MouseEvent<HTMLAnchorElement>) => {
    event.preventDefault();
    localStorage.removeItem("auth_token");
    window.location.href = "/";
  };

  return (
    <div className="dashboard-container wide">
      <PageTitle title="Claims | Medical Billing" />
      <PageHeader
        title="Claims"
        subtitle={
          me ? (
            <>
              Signed in as <span className="highlight">{me.email}</span>
            </>
          ) : (
            "Loading…"
          )
        }
      >
        <a
          className="logout-link"
          href="/patients"
          style={{ marginRight: "1rem" }}
        >
          Patients
        </a>
        <a
          className="logout-link"
          href="/work-queue"
          style={{ marginRight: "1rem" }}
        >
          Work queue
        </a>
        <a
          className="logout-link"
          href="/dashboard"
          style={{ marginRight: "1rem" }}
        >
          Dashboard
        </a>
        <a
          className="logout-link"
          href="/audit"
          style={{ marginRight: "1rem" }}
        >
          Audit trail
        </a>
        <a className="logout-link" href="/" onClick={logout}>
          Logout
        </a>
      </PageHeader>

      <div className="dashboard-card">
        <div className="user-list-section">
          <h2>Claims</h2>

          <details ref={addSectionRef}>
            <summary className="add-user-toggle">New Claim</summary>
            <form ref={addFormRef} onSubmit={handleAdd}>
              <div className="input-group">
                <label htmlFor="patientId">Patient</label>
                <select
                  id="patientId"
                  name="patientId"
                  required
                  defaultValue=""
                  onChange={(event) => loadCoverages(event.target.value)}
                >
                  <option value="" disabled>
                    Choose a patient
                  </option>
                  {patients.map((patient) => (
                    <option key={patient.id} value={patient.id}>
                      {patient.lastName}, {patient.firstName}
                    </option>
                  ))}
                </select>
              </div>
              <div className="input-group">
                <label htmlFor="coverageId">Primary insurance</label>
                <select
                  id="coverageId"
                  name="coverageId"
                  required
                  defaultValue=""
                >
                  <option value="" disabled>
                    {coverages.length
                      ? "Choose the coverage"
                      : "Choose a patient first"}
                  </option>
                  {coverages.map((coverage) => (
                    <option key={coverage.id} value={coverage.id}>
                      Member {coverage.memberId} (priority {coverage.priority})
                    </option>
                  ))}
                </select>
              </div>
              <div className="input-group">
                <label htmlFor="providerId">Provider</label>
                <select
                  id="providerId"
                  name="providerId"
                  required
                  defaultValue=""
                >
                  <option value="" disabled>
                    Choose a provider
                  </option>
                  {providers.map((provider) => (
                    <option key={provider.id} value={provider.id}>
                      {provider.lastName}, {provider.firstName}
                    </option>
                  ))}
                </select>
              </div>
              <div className="input-group">
                <label htmlFor="serviceDate">Service date</label>
                <input
                  id="serviceDate"
                  name="serviceDate"
                  type="date"
                  required
                />
              </div>
              <div className="input-group">
                <label htmlFor="diagnosisCode">Diagnosis (ICD-10-CM)</label>
                <input
                  id="diagnosisCode"
                  name="diagnosisCode"
                  list="diagnosis-codes"
                  placeholder="J06.9"
                  required
                />
                <datalist id="diagnosis-codes">
                  {diagnoses.map((code) => (
                    <option key={code.code} value={code.code}>
                      {code.description}
                    </option>
                  ))}
                </datalist>
              </div>
              <div className="input-group">
                <label htmlFor="procedureCode">Service (CPT/HCPCS)</label>
                <input
                  id="procedureCode"
                  name="procedureCode"
                  list="procedure-codes"
                  placeholder="99213"
                  required
                />
                <datalist id="procedure-codes">
                  {procedures.map((code) => (
                    <option key={code.code} value={code.code}>
                      {code.description}
                    </option>
                  ))}
                </datalist>
              </div>
              <div className="input-group">
                <label htmlFor="chargeAmount">Charge</label>
                <input
                  id="chargeAmount"
                  name="chargeAmount"
                  inputMode="decimal"
                  placeholder="150.00"
                  required
                />
              </div>
              <button type="submit" className="login-button">
                Create Claim
              </button>
            </form>
          </details>

          {error ? <p role="alert">{error}</p> : null}

          <div className="table-scroll">
            <table className="user-table">
              <thead>
                <tr>
                  <th>Claim</th>
                  <th>Patient</th>
                  <th>Service date</th>
                  <th>Status</th>
                  <th>Charge</th>
                </tr>
              </thead>
              <tbody>
                {failed ? (
                  <tr>
                    <td colSpan={5}>Failed to load claims.</td>
                  </tr>
                ) : claims === null ? (
                  <tr>
                    <td colSpan={5}>Loading…</td>
                  </tr>
                ) : claims.length === 0 ? (
                  <tr>
                    <td colSpan={5}>No claims yet.</td>
                  </tr>
                ) : (
                  claims.map((summary) => (
                    <tr key={summary.claim.id}>
                      <td>
                        <a href={`/claims/${summary.claim.id}`}>
                          {summary.claim.claimNumber}
                        </a>
                      </td>
                      <td>{patientName(summary.claim.patientId)}</td>
                      <td>{summary.claim.serviceDate ?? "—"}</td>
                      <td>{summary.claim.status}</td>
                      <td>{summary.totalCharge}</td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        </div>
      </div>

      <PageFooter />
    </div>
  );
}

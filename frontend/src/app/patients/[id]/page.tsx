"use client";

import {
  useCallback,
  useEffect,
  useState,
  type FormEvent,
  type MouseEvent,
} from "react";
import { useParams } from "next/navigation";
import { API_BASE, renewSessionFrom, submitJson } from "@/lib/api";
import { useSession } from "@/lib/session";
import { PageHeader } from "@/components/PageHeader";
import { PageFooter } from "@/components/PageFooter";
import { PageTitle } from "@/components/PageTitle";

type Patient = {
  id: number;
  firstName: string;
  lastName: string;
  dateOfBirth: string;
  sex: string | null;
  addressLine1: string | null;
  city: string | null;
  state: string | null;
  postalCode: string | null;
  phone: string | null;
};

type Coverage = {
  id: number;
  payerId: number;
  memberId: string;
  groupNumber: string | null;
  subscriberName: string | null;
  relationshipToSubscriber: string | null;
  effectiveDate: string | null;
  terminationDate: string | null;
  priority: number;
  active: boolean;
};

type Payer = { id: number; name: string; payerCode: string };

const SEXES = ["", "MALE", "FEMALE", "OTHER", "UNKNOWN"];

// A PUT replaces the whole coverage, so every field goes back rather than just
// the one being changed — otherwise retiring a coverage would blank out its
// member id.
const coverageBody = (coverage: Coverage, active: boolean) => ({
  payerId: coverage.payerId,
  memberId: coverage.memberId,
  groupNumber: coverage.groupNumber ?? "",
  subscriberName: coverage.subscriberName ?? "",
  relationshipToSubscriber: coverage.relationshipToSubscriber ?? "",
  effectiveDate: coverage.effectiveDate ?? "",
  terminationDate: coverage.terminationDate ?? "",
  priority: coverage.priority,
  active,
});

export default function PatientDetailPage() {
  const params = useParams<{ id: string }>();
  const patientId = String(params.id);
  const { me, withToken } = useSession();

  const [patient, setPatient] = useState<Patient | null>(null);
  const [coverages, setCoverages] = useState<Coverage[] | null>(null);
  const [payers, setPayers] = useState<Payer[]>([]);
  const [failed, setFailed] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  // Bumped after a save so the uncontrolled edit form re-mounts with the values
  // the server actually stored.
  const [formVersion, setFormVersion] = useState(0);

  const load = useCallback(
    async (authToken: string) => {
      try {
        const headers = { Authorization: `Bearer ${authToken}` };
        const [patientResponse, coverageResponse, payerResponse] =
          await Promise.all([
            fetch(`${API_BASE}/api/patients/${patientId}`, { headers }),
            fetch(`${API_BASE}/api/patients/${patientId}/coverages`, {
              headers,
            }),
            fetch(`${API_BASE}/api/payers`, { headers }),
          ]);
        [patientResponse, coverageResponse, payerResponse].forEach(
          renewSessionFrom,
        );

        const patientData = await patientResponse.json();
        if (!patientResponse.ok) throw new Error(patientData.message);
        setPatient(patientData.patient);

        // Coverage and payers are secondary: a user who may read the patient but
        // not their coverage should still see the details.
        const coverageData = await coverageResponse.json();
        setCoverages(coverageResponse.ok ? coverageData.coverages : []);
        const payerData = await payerResponse.json();
        setPayers(payerResponse.ok ? payerData.payers : []);
        setFailed(false);
      } catch {
        setFailed(true);
      }
    },
    [patientId],
  );

  useEffect(() => {
    if (!me) return;
    withToken(load);
  }, [me, load, withToken]);

  const handleSavePatient = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    setError("");
    setNotice("");
    withToken(async (authToken) => {
      const { ok, data: body } = await submitJson(
        authToken,
        `/api/patients/${patientId}`,
        "PUT",
        {
          firstName: data.get("firstName"),
          lastName: data.get("lastName"),
          dateOfBirth: data.get("dateOfBirth"),
          sex: data.get("sex"),
          addressLine1: data.get("addressLine1"),
          addressLine2: data.get("addressLine2"),
          city: data.get("city"),
          state: data.get("state"),
          postalCode: data.get("postalCode"),
          phone: data.get("phone"),
        },
      );
      if (!ok) {
        setError(body.message ?? "Could not save the patient.");
        return;
      }
      setPatient(body.patient as Patient);
      setFormVersion((version) => version + 1);
      setNotice("Patient saved.");
    });
  };

  const handleAddCoverage = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    setError("");
    setNotice("");
    withToken(async (authToken) => {
      const { ok, data: body } = await submitJson(
        authToken,
        `/api/patients/${patientId}/coverages`,
        "POST",
        {
          payerId: Number(data.get("payerId")),
          memberId: data.get("memberId"),
          groupNumber: data.get("groupNumber"),
          subscriberName: data.get("subscriberName"),
          relationshipToSubscriber: data.get("relationshipToSubscriber"),
          effectiveDate: data.get("effectiveDate"),
          priority: Number(data.get("priority")),
        },
      );
      if (!ok) {
        setError(body.message ?? "Could not add the coverage.");
        return;
      }
      form.reset();
      await load(authToken);
      setNotice("Coverage added.");
    });
  };

  const setCoverageActive = (coverage: Coverage, active: boolean) => {
    setError("");
    setNotice("");
    withToken(async (authToken) => {
      const { ok, data: body } = await submitJson(
        authToken,
        `/api/coverages/${coverage.id}`,
        "PUT",
        coverageBody(coverage, active),
      );
      if (!ok) {
        setError(body.message ?? "Could not update the coverage.");
        return;
      }
      await load(authToken);
      setNotice(active ? "Coverage reactivated." : "Coverage retired.");
    });
  };

  const payerName = (payerId: number) =>
    payers.find((payer) => payer.id === payerId)?.name ?? `Payer ${payerId}`;

  const logout = (event: MouseEvent<HTMLAnchorElement>) => {
    event.preventDefault();
    localStorage.removeItem("auth_token");
    window.location.href = "/";
  };

  return (
    <div className="dashboard-container wide">
      <PageTitle title="Patient | Medical Billing" />
      <PageHeader
        title={
          patient ? `${patient.lastName}, ${patient.firstName}` : "Patient"
        }
        subtitle={patient ? `Born ${patient.dateOfBirth}` : "Loading…"}
      >
        <a className="logout-link" href="/patients">
          Patients
        </a>
        <a className="logout-link" href="/" onClick={logout}>
          Logout
        </a>
      </PageHeader>

      <div className="dashboard-card">
        <div className="user-list-section">
          {failed ? <p role="alert">Failed to load this patient.</p> : null}
          {error ? <p role="alert">{error}</p> : null}
          {notice ? <p className="highlight">{notice}</p> : null}

          {patient ? (
            <>
              <h2>Details</h2>
              <form key={formVersion} onSubmit={handleSavePatient}>
                <div className="input-group">
                  <label htmlFor="firstName">First name</label>
                  <input
                    id="firstName"
                    name="firstName"
                    defaultValue={patient.firstName}
                    required
                  />
                </div>
                <div className="input-group">
                  <label htmlFor="lastName">Last name</label>
                  <input
                    id="lastName"
                    name="lastName"
                    defaultValue={patient.lastName}
                    required
                  />
                </div>
                <div className="input-group">
                  <label htmlFor="dateOfBirth">Date of birth</label>
                  <input
                    id="dateOfBirth"
                    name="dateOfBirth"
                    type="date"
                    defaultValue={patient.dateOfBirth}
                    required
                  />
                </div>
                <div className="input-group">
                  <label htmlFor="sex">Sex</label>
                  <select id="sex" name="sex" defaultValue={patient.sex ?? ""}>
                    {SEXES.map((sex) => (
                      <option key={sex} value={sex}>
                        {sex || "Not recorded"}
                      </option>
                    ))}
                  </select>
                </div>
                <div className="input-group">
                  <label htmlFor="addressLine1">Address</label>
                  <input
                    id="addressLine1"
                    name="addressLine1"
                    defaultValue={patient.addressLine1 ?? ""}
                  />
                </div>
                <div className="input-group">
                  <label htmlFor="city">City</label>
                  <input
                    id="city"
                    name="city"
                    defaultValue={patient.city ?? ""}
                  />
                </div>
                <div className="input-group">
                  <label htmlFor="state">State</label>
                  <input
                    id="state"
                    name="state"
                    defaultValue={patient.state ?? ""}
                  />
                </div>
                <div className="input-group">
                  <label htmlFor="postalCode">ZIP</label>
                  <input
                    id="postalCode"
                    name="postalCode"
                    defaultValue={patient.postalCode ?? ""}
                  />
                </div>
                <div className="input-group">
                  <label htmlFor="phone">Phone</label>
                  <input
                    id="phone"
                    name="phone"
                    defaultValue={patient.phone ?? ""}
                  />
                </div>
                <button type="submit" className="login-button">
                  Save
                </button>
              </form>

              <h2>Coverage</h2>
              <div className="table-scroll">
                <table className="user-table">
                  <thead>
                    <tr>
                      <th>Payer</th>
                      <th>Member ID</th>
                      <th>Group</th>
                      <th>Effective</th>
                      <th>Priority</th>
                      <th>Status</th>
                      <th>Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    {coverages === null ? (
                      <tr>
                        <td colSpan={7}>Loading…</td>
                      </tr>
                    ) : coverages.length === 0 ? (
                      <tr>
                        <td colSpan={7}>No coverage on file.</td>
                      </tr>
                    ) : (
                      coverages.map((coverage) => (
                        <tr key={coverage.id}>
                          <td>{payerName(coverage.payerId)}</td>
                          <td>{coverage.memberId}</td>
                          <td>{coverage.groupNumber ?? "—"}</td>
                          <td>{coverage.effectiveDate ?? "—"}</td>
                          <td>{coverage.priority}</td>
                          <td>{coverage.active ? "Active" : "Retired"}</td>
                          <td>
                            <button
                              type="button"
                              className={
                                coverage.active
                                  ? "button-danger"
                                  : "link-button"
                              }
                              onClick={() =>
                                setCoverageActive(coverage, !coverage.active)
                              }
                            >
                              {coverage.active ? "Retire" : "Reactivate"}
                            </button>
                          </td>
                        </tr>
                      ))
                    )}
                  </tbody>
                </table>
              </div>

              <details>
                <summary className="add-user-toggle">Add Coverage</summary>
                <form onSubmit={handleAddCoverage}>
                  <div className="input-group">
                    <label htmlFor="payerId">Payer</label>
                    <select
                      id="payerId"
                      name="payerId"
                      required
                      defaultValue=""
                    >
                      <option value="" disabled>
                        Choose a payer
                      </option>
                      {payers.map((payer) => (
                        <option key={payer.id} value={payer.id}>
                          {payer.name} ({payer.payerCode})
                        </option>
                      ))}
                    </select>
                  </div>
                  <div className="input-group">
                    <label htmlFor="memberId">Member ID</label>
                    <input id="memberId" name="memberId" required />
                  </div>
                  <div className="input-group">
                    <label htmlFor="groupNumber">Group number</label>
                    <input id="groupNumber" name="groupNumber" />
                  </div>
                  <div className="input-group">
                    <label htmlFor="subscriberName">Subscriber name</label>
                    <input id="subscriberName" name="subscriberName" />
                  </div>
                  <div className="input-group">
                    <label htmlFor="relationshipToSubscriber">
                      Relationship to subscriber
                    </label>
                    <input
                      id="relationshipToSubscriber"
                      name="relationshipToSubscriber"
                      placeholder="SELF"
                    />
                  </div>
                  <div className="input-group">
                    <label htmlFor="effectiveDate">Effective date</label>
                    <input
                      id="effectiveDate"
                      name="effectiveDate"
                      type="date"
                    />
                  </div>
                  <div className="input-group">
                    <label htmlFor="priority">Priority</label>
                    <input
                      id="priority"
                      name="priority"
                      type="number"
                      min="1"
                      defaultValue="1"
                    />
                  </div>
                  <button type="submit" className="login-button">
                    Add Coverage
                  </button>
                </form>
              </details>
            </>
          ) : null}
        </div>
      </div>

      <PageFooter />
    </div>
  );
}

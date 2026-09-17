"use client";

import {
  useCallback,
  useEffect,
  useState,
  type FormEvent,
  type MouseEvent,
} from "react";
import { useParams } from "next/navigation";
import { getJson, submitJson } from "@/lib/api";
import { money } from "@/lib/money";
import { allows, useSession } from "@/lib/session";
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

/** What this patient still owes, per claim. The server computes all of it. */
type Balance = {
  balance: number;
  claims: { claimId: number; claimNumber: string; balance: number }[];
};

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
  const [balance, setBalance] = useState<Balance | null>(null);
  const [failed, setFailed] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  // Bumped after a save so the uncontrolled edit form re-mounts with the values
  // the server actually stored.
  const [formVersion, setFormVersion] = useState(0);

  // A provider reads patients and coverage without editing either, so the
  // controls that would only come back 403 are not offered.
  const canEditPatient = allows(me, "PATIENT_EDIT");
  const canEditCoverage = allows(me, "COVERAGE_EDIT");

  const load = useCallback(
    async (authToken: string) => {
      const [patientResult, coverageResult, payerResult, balanceResult] =
        await Promise.all([
          getJson<{ patient: Patient }>(
            authToken,
            `/api/patients/${patientId}`,
          ),
          getJson<{ coverages: Coverage[] }>(
            authToken,
            `/api/patients/${patientId}/coverages`,
          ),
          getJson<{ payers: Payer[] }>(authToken, "/api/payers"),
          getJson<{ balance: Balance }>(
            authToken,
            `/api/patients/${patientId}/balance`,
          ),
        ]);

      if (!patientResult.ok) {
        setFailed(true);
        return;
      }
      setPatient(patientResult.data.patient);

      // Coverage, payers and the balance are secondary: a user who may read the
      // patient but not their coverage should still see the details.
      setCoverages(coverageResult.ok ? coverageResult.data.coverages : []);
      setPayers(payerResult.ok ? payerResult.data.payers : []);
      setBalance(balanceResult.ok ? balanceResult.data.balance : null);
      setFailed(false);
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
        current="/patients"
        permissions={me?.permissions}
      >
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
              {/* The fields are the only place a patient's details are shown, so
                  a caller who may read but not edit keeps seeing them and loses
                  only the ability to change them. A disabled fieldset switches
                  off everything inside it, the submit included, so there's
                  nothing left to press. */}
              <form key={formVersion} onSubmit={handleSavePatient}>
                <fieldset className="field-group" disabled={!canEditPatient}>
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
                    <select
                      id="sex"
                      name="sex"
                      defaultValue={patient.sex ?? ""}
                    >
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
                  <button type="submit" className="primary-button">
                    Save
                  </button>
                </fieldset>
              </form>

              <h2>Balance</h2>
              {balance === null ? (
                <p>Not available.</p>
              ) : balance.claims.length === 0 ? (
                <p>Nothing owed.</p>
              ) : (
                <>
                  <p>
                    <strong>{money(balance.balance)}</strong> owed across{" "}
                    {balance.claims.length} claim
                    {balance.claims.length === 1 ? "" : "s"}.
                  </p>
                  <div className="table-scroll">
                    <table className="user-table">
                      <thead>
                        <tr>
                          <th>Claim</th>
                          <th>Balance</th>
                        </tr>
                      </thead>
                      <tbody>
                        {balance.claims.map((claim) => (
                          <tr key={claim.claimId}>
                            <td>
                              <a href={`/claims/${claim.claimId}`}>
                                {claim.claimNumber}
                              </a>
                            </td>
                            <td>{money(claim.balance)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </>
              )}

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
                      <th
                        style={
                          canEditCoverage ? undefined : { display: "none" }
                        }
                      >
                        Actions
                      </th>
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
                            {canEditCoverage ? (
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
                            ) : null}
                          </td>
                        </tr>
                      ))
                    )}
                  </tbody>
                </table>
              </div>

              {canEditCoverage ? (
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
                    <button type="submit" className="primary-button">
                      Add Coverage
                    </button>
                  </form>
                </details>
              ) : null}
            </>
          ) : null}
        </div>
      </div>

      <PageFooter />
    </div>
  );
}

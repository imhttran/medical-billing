"use client";

import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type FormEvent,
  type MouseEvent,
} from "react";
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
  city: string | null;
  state: string | null;
};

const SEXES = ["", "MALE", "FEMALE", "OTHER", "UNKNOWN"];

export default function PatientsPage() {
  const { me, withToken } = useSession();
  const [patients, setPatients] = useState<Patient[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [error, setError] = useState("");
  // The applied search, not the field: typing does not refetch, submitting does.
  const [search, setSearch] = useState("");
  const addFormRef = useRef<HTMLFormElement>(null);
  const addSectionRef = useRef<HTMLDetailsElement>(null);

  const load = useCallback(async (authToken: string, forSearch: string) => {
    try {
      const path = forSearch
        ? `/api/patients?query=${encodeURIComponent(forSearch)}`
        : "/api/patients";
      const response = await fetch(`${API_BASE}${path}`, {
        headers: { Authorization: `Bearer ${authToken}` },
      });
      renewSessionFrom(response);
      const data = await response.json();
      if (!response.ok) throw new Error(data.message);
      setPatients(data.patients);
      setFailed(false);
    } catch {
      setFailed(true);
    }
  }, []);

  useEffect(() => {
    if (!me) return;
    withToken((authToken) => load(authToken, search));
  }, [me, search, load, withToken]);

  const handleSearch = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const submitted = new FormData(event.currentTarget).get("query");
    setSearch(typeof submitted === "string" ? submitted.trim() : "");
  };

  const handleAdd = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    setError("");
    withToken(async (authToken) => {
      // No organizationId: the server derives the practice from the caller's own
      // grants, so the browser never names the tenant it writes to.
      const { ok, data: result } = await submitJson(
        authToken,
        "/api/patients",
        "POST",
        {
          firstName: data.get("firstName"),
          lastName: data.get("lastName"),
          dateOfBirth: data.get("dateOfBirth"),
          sex: data.get("sex"),
          addressLine1: data.get("addressLine1"),
          city: data.get("city"),
          state: data.get("state"),
          postalCode: data.get("postalCode"),
          phone: data.get("phone"),
        },
      );
      if (!ok) {
        setError(result.message ?? "Could not create the patient.");
        return;
      }
      addFormRef.current?.reset();
      if (addSectionRef.current) addSectionRef.current.open = false;
      await load(authToken, search);
    });
  };

  const logout = (event: MouseEvent<HTMLAnchorElement>) => {
    event.preventDefault();
    localStorage.removeItem("auth_token");
    window.location.href = "/";
  };

  return (
    <div className="dashboard-container wide">
      <PageTitle title="Patients | Medical Billing" />
      <PageHeader
        title="Patients"
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
          href="/claims"
          style={{ marginRight: "1rem" }}
        >
          Claims
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
          <h2>Patients</h2>

          <form className="add-user-form" onSubmit={handleSearch}>
            <input
              type="search"
              name="query"
              placeholder="Search by name"
              defaultValue={search}
            />
            <button type="submit" className="login-button">
              Search
            </button>
            {search ? (
              <button
                type="button"
                className="link-button"
                onClick={() => setSearch("")}
              >
                Clear
              </button>
            ) : null}
          </form>

          <details ref={addSectionRef}>
            <summary className="add-user-toggle">Add Patient</summary>
            <form ref={addFormRef} onSubmit={handleAdd}>
              <div className="input-group">
                <label htmlFor="firstName">First name</label>
                <input id="firstName" name="firstName" required />
              </div>
              <div className="input-group">
                <label htmlFor="lastName">Last name</label>
                <input id="lastName" name="lastName" required />
              </div>
              <div className="input-group">
                <label htmlFor="dateOfBirth">Date of birth</label>
                <input
                  id="dateOfBirth"
                  name="dateOfBirth"
                  type="date"
                  required
                />
              </div>
              <div className="input-group">
                <label htmlFor="sex">Sex</label>
                <select id="sex" name="sex" defaultValue="">
                  {SEXES.map((sex) => (
                    <option key={sex} value={sex}>
                      {sex || "Not recorded"}
                    </option>
                  ))}
                </select>
              </div>
              <div className="input-group">
                <label htmlFor="addressLine1">Address</label>
                <input id="addressLine1" name="addressLine1" />
              </div>
              <div className="input-group">
                <label htmlFor="city">City</label>
                <input id="city" name="city" />
              </div>
              <div className="input-group">
                <label htmlFor="state">State</label>
                <input id="state" name="state" />
              </div>
              <div className="input-group">
                <label htmlFor="postalCode">ZIP</label>
                <input id="postalCode" name="postalCode" />
              </div>
              <div className="input-group">
                <label htmlFor="phone">Phone</label>
                <input id="phone" name="phone" />
              </div>
              <button type="submit" className="login-button">
                Add Patient
              </button>
            </form>
          </details>

          {error ? <p role="alert">{error}</p> : null}

          <div className="table-scroll">
            <table className="user-table">
              <thead>
                <tr>
                  <th>Name</th>
                  <th>Date of birth</th>
                  <th>Sex</th>
                  <th>City</th>
                </tr>
              </thead>
              <tbody>
                {failed ? (
                  <tr>
                    <td colSpan={4}>Failed to load patients.</td>
                  </tr>
                ) : patients === null ? (
                  <tr>
                    <td colSpan={4}>Loading…</td>
                  </tr>
                ) : patients.length === 0 ? (
                  <tr>
                    <td colSpan={4}>
                      {search
                        ? `No patients match “${search}”.`
                        : "No patients yet."}
                    </td>
                  </tr>
                ) : (
                  patients.map((patient) => (
                    <tr key={patient.id}>
                      <td>
                        <a href={`/patients/${patient.id}`}>
                          {patient.lastName}, {patient.firstName}
                        </a>
                      </td>
                      <td>{patient.dateOfBirth}</td>
                      <td>{patient.sex ?? "—"}</td>
                      <td>{patient.city ?? "—"}</td>
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

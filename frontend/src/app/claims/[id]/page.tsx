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

type Line = {
  lineNumber: number;
  procedureCode: string;
  quantity: number;
  chargeAmount: number | null;
  allowedAmount: number | null;
  adjustmentAmount: number | null;
  payerAmount: number | null;
  patientResponsibility: number | null;
  status: string;
};

type Adjudication = {
  outcome: string;
  totalCharge: number;
  totalAllowed: number;
  totalAdjustment: number;
  payerResponsibility: number;
  patientResponsibility: number;
};

type ClaimDetail = {
  claim: {
    id: number;
    claimNumber: string;
    status: string;
    serviceDate: string | null;
    patientId: number;
    providerId: number;
    coverageId: number;
    submissionVersion: number;
    rejectionCode: string | null;
    rejectionMessage: string | null;
    rejectedAt: string | null;
  };
  diagnoses: { diagnosisCode: string; sequence: number }[];
  lines: Line[];
  adjudication: Adjudication | null;
};

type Coverage = { id: number; memberId: string; active: boolean };

type InsurancePayment = {
  id: number;
  amount: number;
  paymentDate: string;
  referenceNumber: string;
};

type PatientPayment = {
  id: number;
  amount: number;
  paymentMethod: string;
  paymentDate: string;
  referenceNumber: string | null;
};

/**
 * The claim's money. The three figures are computed on the server from the
 * adjudication and the payment rows, so nothing here subtracts anything.
 */
type Payments = {
  insurancePayments: InsurancePayment[];
  patientPayments: PatientPayment[];
  patientResponsibility: number;
  patientPaid: number;
  balance: number;
};

type Issue = { code: string; message: string };

const money = (value: number | null | undefined) =>
  value === null || value === undefined ? "—" : value.toFixed(2);

const PAYMENT_METHODS = ["CASH", "CHECK", "CARD", "TRANSFER", "OTHER"];

/** Today, as the date input wants it. */
const today = () => new Date().toISOString().slice(0, 10);

/**
 * A GET with the session token, or null when the answer is not a success. The
 * payments are the reason: a role without PAYMENT_VIEW may see the claim and not
 * its money, and that leaves the section off the page rather than erroring.
 */
async function getJson<T>(authToken: string, path: string): Promise<T | null> {
  try {
    const response = await fetch(`${API_BASE}${path}`, {
      headers: { Authorization: `Bearer ${authToken}` },
    });
    renewSessionFrom(response);
    if (!response.ok) return null;
    return (await response.json()) as T;
  } catch {
    return null;
  }
}

export default function ClaimDetailPage() {
  const params = useParams<{ id: string }>();
  const claimId = String(params.id);
  const { me, withToken } = useSession();

  const [detail, setDetail] = useState<ClaimDetail | null>(null);
  const [coverages, setCoverages] = useState<Coverage[]>([]);
  const [payments, setPayments] = useState<Payments | null>(null);
  const [issues, setIssues] = useState<Issue[]>([]);
  const [failed, setFailed] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  /**
   * The claim's money, which changes whenever the payer answers or a payment is
   * recorded — so it is refreshed after both, not just when the page loads.
   */
  const loadPayments = useCallback(
    async (authToken: string) => {
      const money = await getJson<{ payments: Payments }>(
        authToken,
        `/api/claims/${claimId}/payments`,
      );
      setPayments(money?.payments ?? null);
    },
    [claimId],
  );

  const load = useCallback(
    async (authToken: string) => {
      const data = await getJson<ClaimDetail>(
        authToken,
        `/api/claims/${claimId}`,
      );
      if (!data) {
        setFailed(true);
        return;
      }
      setDetail(data);
      setFailed(false);

      // The patient's coverages, so a claim can be corrected onto the right one.
      const listed = await getJson<{ coverages: Coverage[] }>(
        authToken,
        `/api/patients/${data.claim.patientId}/coverages`,
      );
      setCoverages(listed?.coverages ?? []);

      await loadPayments(authToken);
    },
    [claimId, loadPayments],
  );

  useEffect(() => {
    if (!me) return;
    withToken(load);
  }, [me, load, withToken]);

  /**
   * Validate, Mark ready and Submit are the same call shape: the transition
   * either happens or the response carries the reasons it did not. Submitting a
   * rejected or corrected claim is a resubmission on the same route.
   */
  const act = (action: "validate" | "ready" | "submit") => {
    setError("");
    setNotice("");
    setIssues([]);
    withToken(async (authToken) => {
      const { ok, data: body } = await submitJson(
        authToken,
        `/api/claims/${claimId}/${action}`,
        "POST",
      );
      if (!ok) {
        const refused = body.issues as Issue[] | undefined;
        if (refused?.length) setIssues(refused);
        else setError(body.message ?? "The claim could not be updated.");
        return;
      }
      if (action === "validate") {
        const reported = (body.issues as Issue[]) ?? [];
        setIssues(reported);
        setNotice(
          reported.length
            ? "Validation found problems."
            : "The claim is valid.",
        );
        return;
      }
      const answer = body as unknown as ClaimDetail;
      setDetail(answer);
      setNotice(
        answer.claim.status === "REJECTED"
          ? "The payer rejected this claim."
          : action === "ready"
            ? "Claim marked ready."
            : "Claim submitted.",
      );
      // Submitting is when the payer's remittance appears, and a rejection clears
      // whatever the last one was, so the money is re-read rather than assumed.
      await loadPayments(authToken);
    });
  };

  /**
   * Correcting a claim is a PUT of the whole claim: the form only carries the two
   * fields a rejection is usually about — the service date and which coverage the
   * claim is billed to — and everything else goes back as it came, because a PUT
   * replaces all of it. Editing a rejected claim is also the move the state
   * machine calls CORRECTED.
   */
  const correct = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!detail) return;
    const data = new FormData(event.currentTarget);
    setError("");
    setNotice("");
    setIssues([]);
    withToken(async (authToken) => {
      const { ok, data: body } = await submitJson(
        authToken,
        `/api/claims/${claimId}`,
        "PUT",
        {
          patientId: detail.claim.patientId,
          providerId: detail.claim.providerId,
          coverageId: Number(data.get("coverageId") ?? detail.claim.coverageId),
          serviceDate: data.get("serviceDate"),
          diagnoses: detail.diagnoses.map((row) => row.diagnosisCode),
          lines: detail.lines.map((line) => ({
            procedureCode: line.procedureCode,
            quantity: line.quantity,
            chargeAmount: String(line.chargeAmount),
          })),
        },
      );
      if (!ok) {
        const refused = body.issues as Issue[] | undefined;
        if (refused?.length) setIssues(refused);
        else setError(body.message ?? "The claim could not be saved.");
        return;
      }
      setDetail(body as unknown as ClaimDetail);
      setNotice("Claim saved. It can go back to the payer now.");
      await loadPayments(authToken);
    });
  };

  /** A hand-entered patient payment. It can settle the claim, so the answer's claim comes back too. */
  const pay = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    setError("");
    setNotice("");
    withToken(async (authToken) => {
      const { ok, data: body } = await submitJson(
        authToken,
        `/api/claims/${claimId}/patient-payments`,
        "POST",
        {
          amount: data.get("amount"),
          paymentMethod: data.get("paymentMethod"),
          paymentDate: data.get("paymentDate"),
          referenceNumber: data.get("referenceNumber"),
        },
      );
      if (!ok) {
        setError(body.message ?? "The payment could not be recorded.");
        return;
      }
      form.reset();
      setDetail(body as unknown as ClaimDetail);
      setPayments((body.payments ?? null) as Payments | null);
      setNotice("Payment recorded.");
    });
  };

  const logout = (event: MouseEvent<HTMLAnchorElement>) => {
    event.preventDefault();
    localStorage.removeItem("auth_token");
    window.location.href = "/";
  };

  const status = detail?.claim.status;
  const editable = status === "DRAFT" || status === "READY";
  // A rejected claim goes back out once whatever the rejection named is fixed,
  // which is usually the service date or the coverage it was billed to.
  const resubmittable = status === "REJECTED" || status === "CORRECTED";
  const correctable = editable || resubmittable;

  return (
    <div className="dashboard-container wide">
      <PageTitle title="Claim | Medical Billing" />
      <PageHeader
        title={detail ? detail.claim.claimNumber : "Claim"}
        subtitle={
          detail
            ? `${status} · service date ${detail.claim.serviceDate ?? "not set"}`
            : "Loading…"
        }
      >
        <a className="logout-link" href="/claims">
          Claims
        </a>
        <a className="logout-link" href="/work-queue">
          Work queue
        </a>
        <a className="logout-link" href="/" onClick={logout}>
          Logout
        </a>
      </PageHeader>

      <div className="dashboard-card">
        <div className="user-list-section">
          {failed ? <p role="alert">Failed to load this claim.</p> : null}
          {error ? <p role="alert">{error}</p> : null}
          {notice ? <p className="highlight">{notice}</p> : null}

          {issues.length ? (
            <div role="alert">
              <h2>Problems</h2>
              <ul>
                {issues.map((issue) => (
                  <li key={issue.code + issue.message}>
                    <code>{issue.code}</code> — {issue.message}
                  </li>
                ))}
              </ul>
            </div>
          ) : null}

          {detail ? (
            <>
              {detail.claim.rejectionMessage ? (
                <div>
                  <h2>Rejected by the payer</h2>
                  <p>
                    <code>{detail.claim.rejectionCode}</code> —{" "}
                    {detail.claim.rejectionMessage}
                  </p>
                </div>
              ) : null}

              {editable ? (
                <div className="form-footer">
                  <div>
                    <button
                      type="button"
                      className="link-button"
                      onClick={() => act("validate")}
                    >
                      Validate
                    </button>{" "}
                    <button
                      type="button"
                      className="login-button"
                      onClick={() => act("ready")}
                      disabled={status === "READY"}
                    >
                      Mark ready
                    </button>{" "}
                    <button
                      type="button"
                      className="login-button"
                      onClick={() => act("submit")}
                      disabled={status !== "READY"}
                    >
                      Submit
                    </button>
                  </div>
                </div>
              ) : null}

              {resubmittable ? (
                <div className="form-footer">
                  <div>
                    <button
                      type="button"
                      className="login-button"
                      onClick={() => act("submit")}
                    >
                      Resubmit
                    </button>
                  </div>
                </div>
              ) : null}

              {correctable ? (
                <details>
                  <summary>Edit this claim</summary>
                  <form onSubmit={correct}>
                    <div className="input-group">
                      <label htmlFor="serviceDate">Service date</label>
                      <input
                        id="serviceDate"
                        name="serviceDate"
                        type="date"
                        defaultValue={detail.claim.serviceDate ?? ""}
                        required
                      />
                    </div>
                    {coverages.length ? (
                      <div className="input-group">
                        <label htmlFor="coverageId">Primary insurance</label>
                        <select
                          id="coverageId"
                          name="coverageId"
                          defaultValue={String(detail.claim.coverageId)}
                        >
                          {coverages.map((coverage) => (
                            <option key={coverage.id} value={coverage.id}>
                              {coverage.memberId}
                              {coverage.active ? "" : " (retired)"}
                            </option>
                          ))}
                        </select>
                      </div>
                    ) : null}
                    <button type="submit" className="login-button">
                      Save claim
                    </button>
                  </form>
                </details>
              ) : null}

              <h2>Diagnoses</h2>
              <div className="table-scroll">
                <table className="user-table">
                  <thead>
                    <tr>
                      <th>#</th>
                      <th>Code</th>
                    </tr>
                  </thead>
                  <tbody>
                    {detail.diagnoses.length === 0 ? (
                      <tr>
                        <td colSpan={2}>None.</td>
                      </tr>
                    ) : (
                      detail.diagnoses.map((diagnosis) => (
                        <tr key={diagnosis.sequence}>
                          <td>{diagnosis.sequence}</td>
                          <td>{diagnosis.diagnosisCode}</td>
                        </tr>
                      ))
                    )}
                  </tbody>
                </table>
              </div>

              <h2>Services</h2>
              <div className="table-scroll">
                <table className="user-table">
                  <thead>
                    <tr>
                      <th>#</th>
                      <th>Procedure</th>
                      <th>Qty</th>
                      <th>Charge</th>
                      <th>Allowed</th>
                      <th>Adjustment</th>
                      <th>Payer</th>
                      <th>Patient</th>
                      <th>Status</th>
                    </tr>
                  </thead>
                  <tbody>
                    {detail.lines.length === 0 ? (
                      <tr>
                        <td colSpan={9}>No service lines.</td>
                      </tr>
                    ) : (
                      detail.lines.map((line) => (
                        <tr key={line.lineNumber}>
                          <td>{line.lineNumber}</td>
                          <td>{line.procedureCode}</td>
                          <td>{line.quantity}</td>
                          <td>{money(line.chargeAmount)}</td>
                          <td>{money(line.allowedAmount)}</td>
                          <td>{money(line.adjustmentAmount)}</td>
                          <td>{money(line.payerAmount)}</td>
                          <td>{money(line.patientResponsibility)}</td>
                          <td>{line.status}</td>
                        </tr>
                      ))
                    )}
                  </tbody>
                </table>
              </div>

              <h2>Adjudication</h2>
              {detail.adjudication ? (
                <div className="table-scroll">
                  <table className="user-table">
                    <thead>
                      <tr>
                        <th>Outcome</th>
                        <th>Charge</th>
                        <th>Allowed</th>
                        <th>Adjustment</th>
                        <th>Insurance</th>
                        <th>Patient</th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr>
                        <td>{detail.adjudication.outcome}</td>
                        <td>{money(detail.adjudication.totalCharge)}</td>
                        <td>{money(detail.adjudication.totalAllowed)}</td>
                        <td>{money(detail.adjudication.totalAdjustment)}</td>
                        <td>
                          {money(detail.adjudication.payerResponsibility)}
                        </td>
                        <td>
                          {money(detail.adjudication.patientResponsibility)}
                        </td>
                      </tr>
                    </tbody>
                  </table>
                </div>
              ) : (
                <p>
                  {status === "REJECTED"
                    ? "The payer refused this claim and priced nothing. Fix what the rejection names, then resubmit it."
                    : status === "CORRECTED"
                      ? "The claim has been corrected. Resubmit it for a fresh answer."
                      : "Not adjudicated yet. Mark the claim ready, then submit it — the simulated payer answers immediately."}
                </p>
              )}

              {payments ? (
                <>
                  <h2>Payments</h2>
                  <p>
                    Patient responsibility{" "}
                    {money(payments.patientResponsibility)}
                    {" · "}paid {money(payments.patientPaid)}
                    {" · "}
                    <strong>balance {money(payments.balance)}</strong>
                  </p>

                  <div className="table-scroll">
                    <table className="user-table">
                      <thead>
                        <tr>
                          <th>Paid by</th>
                          <th>Amount</th>
                          <th>Date</th>
                          <th>Reference</th>
                        </tr>
                      </thead>
                      <tbody>
                        {payments.insurancePayments.length === 0 &&
                        payments.patientPayments.length === 0 ? (
                          <tr>
                            <td colSpan={4}>Nothing has been paid yet.</td>
                          </tr>
                        ) : (
                          <>
                            {payments.insurancePayments.map((payment) => (
                              <tr key={`insurance-${payment.id}`}>
                                <td>Insurance</td>
                                <td>{money(payment.amount)}</td>
                                <td>{payment.paymentDate}</td>
                                <td>{payment.referenceNumber}</td>
                              </tr>
                            ))}
                            {payments.patientPayments.map((payment) => (
                              <tr key={`patient-${payment.id}`}>
                                <td>Patient ({payment.paymentMethod})</td>
                                <td>{money(payment.amount)}</td>
                                <td>{payment.paymentDate}</td>
                                <td>{payment.referenceNumber ?? "—"}</td>
                              </tr>
                            ))}
                          </>
                        )}
                      </tbody>
                    </table>
                  </div>

                  {payments.balance > 0 ? (
                    <details>
                      <summary>Record a patient payment</summary>
                      <form onSubmit={pay}>
                        <div className="input-group">
                          <label htmlFor="amount">Amount</label>
                          <input
                            id="amount"
                            name="amount"
                            defaultValue={money(payments.balance)}
                            required
                          />
                        </div>
                        <div className="input-group">
                          <label htmlFor="paymentMethod">Method</label>
                          <select
                            id="paymentMethod"
                            name="paymentMethod"
                            defaultValue="CASH"
                          >
                            {PAYMENT_METHODS.map((method) => (
                              <option key={method} value={method}>
                                {method}
                              </option>
                            ))}
                          </select>
                        </div>
                        <div className="input-group">
                          <label htmlFor="paymentDate">Payment date</label>
                          <input
                            id="paymentDate"
                            name="paymentDate"
                            type="date"
                            defaultValue={today()}
                            required
                          />
                        </div>
                        <div className="input-group">
                          <label htmlFor="referenceNumber">Reference</label>
                          <input
                            id="referenceNumber"
                            name="referenceNumber"
                            placeholder="Optional"
                          />
                        </div>
                        <button type="submit" className="login-button">
                          Record payment
                        </button>
                      </form>
                    </details>
                  ) : null}
                </>
              ) : null}
            </>
          ) : null}
        </div>
      </div>

      <PageFooter />
    </div>
  );
}

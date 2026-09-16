"use client";

import { useCallback, useEffect, useState, type MouseEvent } from "react";
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
  };
  diagnoses: { diagnosisCode: string; sequence: number }[];
  lines: Line[];
  adjudication: Adjudication | null;
};

type Issue = { code: string; message: string };

const money = (value: number | null | undefined) =>
  value === null || value === undefined ? "—" : value.toFixed(2);

export default function ClaimDetailPage() {
  const params = useParams<{ id: string }>();
  const claimId = String(params.id);
  const { me, withToken } = useSession();

  const [detail, setDetail] = useState<ClaimDetail | null>(null);
  const [issues, setIssues] = useState<Issue[]>([]);
  const [failed, setFailed] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  const load = useCallback(
    async (authToken: string) => {
      try {
        const response = await fetch(`${API_BASE}/api/claims/${claimId}`, {
          headers: { Authorization: `Bearer ${authToken}` },
        });
        renewSessionFrom(response);
        const data = await response.json();
        if (!response.ok) throw new Error(data.message);
        setDetail(data as ClaimDetail);
        setFailed(false);
      } catch {
        setFailed(true);
      }
    },
    [claimId],
  );

  useEffect(() => {
    if (!me) return;
    withToken(load);
  }, [me, load, withToken]);

  /**
   * Validate, Mark ready and Submit are the same call shape: the transition
   * either happens or the response carries the reasons it did not.
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
      setDetail(body as unknown as ClaimDetail);
      setNotice(
        action === "ready" ? "Claim marked ready." : "Claim submitted.",
      );
    });
  };

  const logout = (event: MouseEvent<HTMLAnchorElement>) => {
    event.preventDefault();
    localStorage.removeItem("auth_token");
    window.location.href = "/";
  };

  const status = detail?.claim.status;
  const editable = status === "DRAFT" || status === "READY";

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
                  Not adjudicated yet. Mark the claim ready, then submit it —
                  the simulated payer answers immediately.
                </p>
              )}
            </>
          ) : null}
        </div>
      </div>

      <PageFooter />
    </div>
  );
}

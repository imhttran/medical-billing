"use client";

import { useCallback, useEffect, useState, type MouseEvent } from "react";
import { getJson, submitJson } from "@/lib/api";
import { useSession } from "@/lib/session";
import { PageHeader } from "@/components/PageHeader";
import { PageFooter } from "@/components/PageFooter";
import { PageTitle } from "@/components/PageTitle";

type WorkItem = {
  id: number;
  claimId: number;
  claimNumber: string;
  claimStatus: string;
  patientId: number;
  patientName: string;
  type: string;
  status: string;
  reasonCode: string;
  reasonText: string;
  assignedUserId: number | null;
  assignedUserEmail: string | null;
  createdAt: string;
  resolvedAt: string | null;
};

const STATUSES = [
  { value: "OPEN", label: "Open" },
  { value: "RESOLVED", label: "Resolved" },
  { value: "ALL", label: "All" },
];

/** The date part of a timestamp, which is all a resolved row needs. */
const day = (timestamp: string) => timestamp.slice(0, 10);

export default function WorkQueuePage() {
  const { me, withToken } = useSession();

  const [items, setItems] = useState<WorkItem[] | null>(null);
  const [status, setStatus] = useState("OPEN");
  const [failed, setFailed] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  const load = useCallback(
    async (authToken: string) => {
      const { ok, data } = await getJson<{ workItems: WorkItem[] }>(
        authToken,
        `/api/work-items?status=${status}`,
      );
      if (ok) setItems(data.workItems);
      setFailed(!ok);
    },
    [status],
  );

  useEffect(() => {
    if (!me) return;
    withToken(load);
  }, [me, load, withToken]);

  /** Both actions are one call that answers with the item it changed. */
  const act = (action: "assign" | "resolve", itemId: number) => {
    setError("");
    setNotice("");
    withToken(async (authToken) => {
      const body = action === "assign" ? { userId: me?.id } : undefined;
      const { ok, data } = await submitJson(
        authToken,
        `/api/work-items/${itemId}/${action}`,
        "POST",
        body,
      );
      if (!ok) {
        setError(data.message ?? "The work item could not be updated.");
        return;
      }
      setNotice(
        action === "assign" ? "Assigned to you." : "Work item resolved.",
      );
      await load(authToken);
    });
  };

  const logout = (event: MouseEvent<HTMLAnchorElement>) => {
    event.preventDefault();
    localStorage.removeItem("auth_token");
    window.location.href = "/";
  };

  return (
    <div className="dashboard-container wide">
      <PageTitle title="Work queue | Medical Billing" />
      <PageHeader
        title="Work queue"
        subtitle="What the payers' answers left for someone to do"
        current="/work-queue"
        permissions={me?.permissions}
      >
        <a className="logout-link" href="/" onClick={logout}>
          Logout
        </a>
      </PageHeader>

      <div className="dashboard-card">
        <div className="user-list-section">
          {failed ? <p role="alert">Failed to load the work queue.</p> : null}
          {error ? <p role="alert">{error}</p> : null}
          {notice ? <p className="highlight">{notice}</p> : null}

          <div className="input-group">
            <label htmlFor="status">Show</label>
            <select
              id="status"
              name="status"
              value={status}
              onChange={(event) => setStatus(event.target.value)}
            >
              {STATUSES.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </div>

          <div className="table-scroll">
            <table className="user-table">
              <thead>
                <tr>
                  <th>Claim</th>
                  <th>Patient</th>
                  <th>Claim status</th>
                  <th>Kind</th>
                  <th>Problem</th>
                  <th>Assigned</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {items === null ? (
                  <tr>
                    <td colSpan={7}>Loading…</td>
                  </tr>
                ) : items.length === 0 ? (
                  <tr>
                    <td colSpan={7}>
                      {status === "OPEN"
                        ? "Nothing needs attention."
                        : "Nothing here."}
                    </td>
                  </tr>
                ) : (
                  items.map((item) => (
                    <tr key={item.id}>
                      <td>
                        <a href={`/claims/${item.claimId}`}>
                          {item.claimNumber}
                        </a>
                      </td>
                      <td>
                        <a href={`/patients/${item.patientId}`}>
                          {item.patientName}
                        </a>
                      </td>
                      <td>{item.claimStatus}</td>
                      <td>{item.type}</td>
                      <td>
                        <code>{item.reasonCode}</code> — {item.reasonText}
                      </td>
                      <td>{item.assignedUserEmail ?? "—"}</td>
                      <td>
                        {item.status !== "OPEN" ? (
                          <span>
                            resolved{" "}
                            {item.resolvedAt ? day(item.resolvedAt) : ""}
                          </span>
                        ) : (
                          <>
                            {item.assignedUserId === me?.id ? null : (
                              <button
                                type="button"
                                className="link-button"
                                onClick={() => act("assign", item.id)}
                              >
                                Assign to me
                              </button>
                            )}{" "}
                            <button
                              type="button"
                              className="primary-button"
                              onClick={() => act("resolve", item.id)}
                            >
                              Resolve
                            </button>
                          </>
                        )}
                      </td>
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
